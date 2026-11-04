package com.callsaver.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.callsaver.app.CallLogResolver
import com.callsaver.app.CallRecorder
import com.callsaver.app.CallTracker
import com.callsaver.app.Config
import com.callsaver.app.Logx
import com.callsaver.app.PartRecord
import com.callsaver.app.PendingStore
import com.callsaver.app.PhoneNumbers
import com.callsaver.app.R
import com.callsaver.app.RecordingSession
import com.callsaver.app.RuntimeState
import com.callsaver.app.SetupActivity
import com.callsaver.app.UploadScheduler
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CallSaverService : Service() {
    companion object {
        const val CHANNEL_ID = "call_saver_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.callsaver.app.action.START"
        const val ACTION_PHONE_STATE = "com.callsaver.app.action.PHONE_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_NUMBER = "number"
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private val metadataExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "call-saver-metadata").apply { isDaemon = true }
    }

    private var recorder: CallRecorder? = null
    private var session: RecordingSession? = null
    private val subStates = HashMap<Int, Int>()
    private val telephonyCallbacks = ArrayList<Pair<TelephonyManager, TelephonyCallback>>()
    private val phoneStateListeners = ArrayList<Pair<TelephonyManager, PhoneStateListener>>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var recordingWakeLock: PowerManager.WakeLock? = null

    private var foregroundReady = false
    private var micTypeActive = false
    private var destroying = false
    private var lastPendingShown = -1
    private var lastRecoveryAt = 0L
    private var startRetryPending = false
    private var finalizingSessionId: String? = null

    private val stopRunnable = Runnable { stopSession("call ended") }

    private val tick = object : Runnable {
        override fun run() {
            if (destroying) return
            try {
                val now = System.currentTimeMillis()
                if (now - lastRecoveryAt > 10L * 60L * 1_000L) {
                    lastRecoveryAt = now
                    PendingStore.recover(
                        this@CallSaverService,
                        session?.id ?: finalizingSessionId,
                    )
                }
                maybeKickUploads()
            } catch (t: Throwable) {
                Logx.w("Service", "Periodic maintenance failed", t)
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    private val recorderListener = object : CallRecorder.Listener {
        override fun onPartClosed(record: PartRecord) {
            Logx.i(
                "Session",
                "Part ${record.partNumber} closed: ${record.sizeBytes} bytes, " +
                    "${record.durationMs} ms, source=${record.source}",
            )
            updateServiceNotification()
        }

        override fun onRecorderError(message: String) {
            Logx.e("Session", message)
            updateServiceNotification(text = message)
        }

        override fun onSourceChanged(source: String) {
            Logx.i("Session", "Audio source: $source")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ensureForeground()
        Logx.init(this)

        handlerThread = HandlerThread("call-saver-events").apply { start() }
        handler = Handler(handlerThread.looper)
        RuntimeState.callSaverService = this

        handler.post {
            PendingStore.recover(this)
            lastRecoveryAt = System.currentTimeMillis()
            registerTelephony()
            seedCallState()
            maybeKickUploads()
        }
        registerNetworkCallback()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) ensureForeground()
        if (intent?.action == ACTION_PHONE_STATE) {
            onPhoneStateBroadcast(
                intent.getStringExtra(EXTRA_STATE),
                intent.getStringExtra(EXTRA_NUMBER),
            )
        } else {
            handler.post { maybeKickUploads() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroying = true
        RuntimeState.callSaverService = null
        handler.removeCallbacksAndMessages(null)

        val activeRecorder = recorder
        val activeSession = session
        recorder = null
        session = null
        try {
            activeRecorder?.stop()
        } catch (t: Throwable) {
            Logx.w("Service", "Could not stop recorder during shutdown", t)
        }

        if (activeSession != null) {
            try {
                PendingStore.finishSession(
                    this,
                    activeSession.id,
                    if (activeSession.sawRingingAtStart) "incoming" else "outbound",
                    PhoneNumbers.normalize(
                        activeSession.initialIncomingNumber,
                        Config.defaultCountryCode,
                    ),
                    "unknown",
                )
            } catch (t: Throwable) {
                Logx.w("Service", "Could not release session during shutdown", t)
            }
        }

        CallTracker.resetAfterSession()
        unregisterTelephony()
        unregisterNetwork()
        releaseRecordingWakeLock()
        metadataExecutor.shutdown()
        handlerThread.quitSafely()

        if (foregroundReady) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }

    /** Called by PhoneStateReceiver in the same process. */
    fun onPhoneStateBroadcast(state: String?, number: String?) {
        if (destroying) return
        handler.post {
            val intState = when (state?.uppercase()) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                else -> return@post
            }
            if (intState == TelephonyManager.CALL_STATE_RINGING) {
                CallTracker.onRinging(number)
            }
            if (!CallTracker.telephonyCallbacksRegistered) {
                onCallState(-1, intState)
            }
        }
    }

    private fun ensureForeground() {
        if (foregroundReady) return
        val notification = buildNotification(
            getString(R.string.notification_service_title),
            getString(R.string.notification_service_text),
            permissionsMissing(),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundReady = true
            micTypeActive = true
        } catch (t: Throwable) {
            Logx.w("Service", "Microphone foreground type was rejected; using generic type", t)
            try {
                startForeground(NOTIFICATION_ID, notification)
                foregroundReady = true
                micTypeActive = false
            } catch (t2: Throwable) {
                Logx.e("Service", "Could not start foreground service", t2)
            }
        }
    }

    private fun upgradeMicrophoneForeground() {
        if (micTypeActive || !hasPermission(Manifest.permission.RECORD_AUDIO)) return
        try {
            val notification = buildNotification(
                getString(R.string.notification_recording_title),
                getString(R.string.notification_recording_text),
                permissionsMissing(),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            micTypeActive = true
        } catch (t: Throwable) {
            Logx.w("Service", "Could not upgrade to microphone foreground service", t)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_service_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        includePermissionAction: Boolean,
    ): Notification {
        val openSetup = PendingIntent.getActivity(
            this,
            20,
            Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openSetup)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (includePermissionAction) {
            val actionIntent = PendingIntent.getActivity(
                this,
                21,
                Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_call),
                    getString(R.string.notification_permission_action),
                    actionIntent,
                ).build(),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun updateServiceNotification(
        title: String? = null,
        text: String? = null,
    ) {
        if (!foregroundReady || destroying) return
        val resolvedTitle = title ?: if (session != null) {
            getString(R.string.notification_recording_title)
        } else {
            getString(R.string.notification_service_title)
        }
        val resolvedText = text ?: if (session != null) {
            getString(R.string.notification_recording_text)
        } else {
            getString(R.string.notification_service_text)
        }
        try {
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIFICATION_ID,
                buildNotification(resolvedTitle, resolvedText, permissionsMissing()),
            )
        } catch (t: Throwable) {
            Logx.w("Service", "Could not update notification", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerTelephony() {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            Logx.w("Telephony", "READ_PHONE_STATE is missing; call detection is disabled")
            CallTracker.telephonyCallbacksRegistered = false
            return
        }

        val subscriptionManager = getSystemService(SubscriptionManager::class.java)
        val subscriptions = try {
            subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        } catch (t: Throwable) {
            Logx.w("Telephony", "Could not list SIM subscriptions", t)
            emptyList()
        }

        val managers = if (subscriptions.isNotEmpty()) {
            subscriptions.mapNotNull { info ->
                try {
                    val manager = createForSubscription(info.subscriptionId)
                        ?: return@mapNotNull null
                    info.subscriptionId to manager
                } catch (t: Throwable) {
                    Logx.w("Telephony", "Could not open subscription ${info.subscriptionId}", t)
                    null
                }
            }
        } else {
            val default = getSystemService(TelephonyManager::class.java)
            if (default != null) listOf(-1 to default) else emptyList()
        }

        for ((subId, manager) in managers) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handler.post { onCallState(subId, state) }
                    }
                }
                try {
                    manager.registerTelephonyCallback({ command -> handler.post(command) }, callback)
                    telephonyCallbacks.add(manager to callback)
                } catch (t: Throwable) {
                    Logx.w("Telephony", "Could not register callback for sub $subId", t)
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handler.post {
                            if (state == TelephonyManager.CALL_STATE_RINGING &&
                                !phoneNumber.isNullOrBlank()
                            ) {
                                CallTracker.onRinging(phoneNumber)
                            }
                            onCallState(subId, state)
                        }
                    }
                }
                try {
                    @Suppress("DEPRECATION")
                    manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    phoneStateListeners.add(manager to listener)
                } catch (t: Throwable) {
                    Logx.w("Telephony", "Could not register phone state listener for sub $subId", t)
                }
            }
        }

        CallTracker.telephonyCallbacksRegistered =
            telephonyCallbacks.isNotEmpty() || phoneStateListeners.isNotEmpty()
        Logx.i(
            "Telephony",
            "Registered ${telephonyCallbacks.size + phoneStateListeners.size} call-state listener(s)",
        )
    }

    @SuppressLint("MissingPermission")
    private fun createForSubscription(subId: Int): TelephonyManager? = try {
        getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId)
    } catch (t: Throwable) {
        Logx.w("Telephony", "createForSubscriptionId($subId) failed", t)
        null
    }

    @SuppressLint("MissingPermission")
    private fun seedCallState() {
        val manager = getSystemService(TelephonyManager::class.java) ?: return
        try {
            @Suppress("DEPRECATION")
            val state = manager.callState
            if (state != TelephonyManager.CALL_STATE_IDLE) {
                onCallState(-1, state)
            }
        } catch (t: Throwable) {
            Logx.w("Telephony", "Could not read current call state", t)
        }
    }

    private fun unregisterTelephony() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for ((manager, callback) in telephonyCallbacks) {
                try {
                    manager.unregisterTelephonyCallback(callback)
                } catch (_: Throwable) {
                }
            }
        }
        telephonyCallbacks.clear()

        @Suppress("DEPRECATION")
        for ((manager, listener) in phoneStateListeners) {
            try {
                manager.listen(listener, PhoneStateListener.LISTEN_NONE)
            } catch (_: Throwable) {
            }
        }
        phoneStateListeners.clear()
        CallTracker.telephonyCallbacksRegistered = false
    }

    private fun onCallState(subId: Int, state: Int) {
        if (destroying) return
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            CallTracker.sawRinging = true
        }
        subStates[subId] = state

        val anyOffhook = subStates.values.any { it == TelephonyManager.CALL_STATE_OFFHOOK }
        if (anyOffhook) {
            handler.removeCallbacks(stopRunnable)
            if (session == null) startSession()
        } else if (session != null) {
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, 1_200L)
        }
    }

    private fun startSession() {
        if (destroying || session != null) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Logx.w("Session", "RECORD_AUDIO is missing; cannot record")
            updateServiceNotification(text = getString(R.string.notification_permissions_needed))
            return
        }

        val freeBytes = try {
            StatFs(filesDir.absolutePath).availableBytes
        } catch (_: Throwable) {
            -1L
        }
        if (freeBytes in 0 until Config.minFreeBytesToRecord) {
            Logx.e("Session", "Not enough free storage to start recording")
            return
        }

        val newSession = RecordingSession(
            startedAtElapsedMs = SystemClock.elapsedRealtime(),
            sawRingingAtStart = CallTracker.sawRinging,
            initialIncomingNumber = CallTracker.lastIncomingNumber,
            callLogMaxIdAtStart = CallLogResolver.maxCallLogId(this),
        )
        val newRecorder = CallRecorder(this, newSession, handler, recorderListener)
        if (!newRecorder.start()) {
            Logx.e("Session", "Could not start a recorder for this call")
            scheduleStartRetry()
            return
        }

        session = newSession
        recorder = newRecorder
        acquireRecordingWakeLock()
        upgradeMicrophoneForeground()
        updateServiceNotification(
            getString(R.string.notification_recording_title),
            getString(R.string.notification_recording_text),
        )
        Logx.i("Session", "Call recording started")
    }

    private fun scheduleStartRetry() {
        if (startRetryPending || destroying) return
        startRetryPending = true
        handler.postDelayed({
            startRetryPending = false
            val anyOffhook = subStates.values.any { it == TelephonyManager.CALL_STATE_OFFHOOK }
            if (anyOffhook && session == null) startSession()
        }, 5_000L)
    }

    private fun stopSession(reason: String) {
        handler.removeCallbacks(stopRunnable)
        val activeRecorder = recorder
        val activeSession = session
        recorder = null
        session = null

        try {
            activeRecorder?.stop()
        } catch (t: Throwable) {
            Logx.w("Session", "Recorder stop failed", t)
        }
        releaseRecordingWakeLock()
        updateServiceNotification()
        CallTracker.resetAfterSession()

        if (activeSession == null) return
        Logx.i("Session", "Call recording stopped ($reason)")

        val fallbackNumber = activeSession.initialIncomingNumber
        val heuristicIncoming = activeSession.sawRingingAtStart
        val sessionDurationMs =
            (SystemClock.elapsedRealtime() - activeSession.startedAtElapsedMs).coerceAtLeast(0L)
        finalizingSessionId = activeSession.id
        metadataExecutor.execute {
            try {
                val resolved = CallLogResolver.resolve(
                    this,
                    activeSession.startedAtWallMs,
                    activeSession.callLogMaxIdAtStart,
                    sessionDurationMs,
                    heuristicIncoming,
                    fallbackNumber,
                )
                val parts = PendingStore.finishSession(
                    this,
                    activeSession.id,
                    resolved.type,
                    resolved.phoneNumber,
                    resolved.contactName,
                )
                Logx.i(
                    "Session",
                    "Session finalized: parts=${parts.size}, type=${resolved.type}, " +
                        "callLog=${resolved.foundInCallLog}",
                )
            } catch (t: Throwable) {
                Logx.e("Session", "Could not resolve call details; using fallback", t)
                PendingStore.finishSession(
                    this,
                    activeSession.id,
                    if (heuristicIncoming) "incoming" else "outbound",
                    PhoneNumbers.normalize(fallbackNumber, Config.defaultCountryCode),
                    "unknown",
                )
            }
            handler.post {
                if (finalizingSessionId == activeSession.id) {
                    finalizingSessionId = null
                }
                maybeKickUploads()
            }
        }
    }

    private fun maybeKickUploads() {
        if (destroying) return
        val pending = try {
            PendingStore.pendingCount(this)
        } catch (t: Throwable) {
            Logx.w("Upload", "Could not count pending recordings", t)
            0
        }
        if (pending != lastPendingShown) {
            lastPendingShown = pending
            if (pending > 0) {
                updateServiceNotification(text = "$pending recording(s) waiting to upload")
            } else {
                updateServiceNotification()
            }
        }
        if (pending > 0) UploadScheduler.kick(this)
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handler.post { maybeKickUploads() }
                }
            }
            cm.registerDefaultNetworkCallback(callback, handler)
            networkCallback = callback
        } catch (t: Throwable) {
            Logx.w("Network", "Could not register network callback", t)
        }
    }

    private fun unregisterNetwork() {
        val callback = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
        networkCallback = null
    }

    private fun acquireRecordingWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallSaver:recording")
            lock.setReferenceCounted(false)
            lock.acquire(TimeUnit.HOURS.toMillis(6))
            recordingWakeLock = lock
        } catch (t: Throwable) {
            Logx.w("Session", "Could not acquire recording wake lock", t)
        }
    }

    private fun releaseRecordingWakeLock() {
        try {
            recordingWakeLock?.release()
        } catch (_: Throwable) {
        }
        recordingWakeLock = null
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun permissionsMissing(): Boolean =
        !hasPermission(Manifest.permission.RECORD_AUDIO) ||
            !hasPermission(Manifest.permission.READ_PHONE_STATE)
}
