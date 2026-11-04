package com.callsaver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UploadService : Service() {
    companion object {
        const val ACTION_UPLOAD = "com.callsaver.app.action.UPLOAD"
        const val CHANNEL_ID = "call_saver_uploads"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "call-saver-upload").apply { isDaemon = true }
    }

    private val lock = Any()
    private var processing = false
    private var rerun = false
    private val wakeLockRef = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L
    private var foregroundOk = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        foregroundOk = startAsForeground(buildNotification(0, 0))
        if (foregroundOk) acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundOk || executor.isShutdown) {
            stopSelf()
            return START_NOT_STICKY
        }
        synchronized(lock) {
            if (processing) {
                rerun = true
                return START_NOT_STICKY
            }
            processing = true
        }

        executor.execute {
            try {
                do {
                    synchronized(lock) { rerun = false }
                    processQueue()
                } while (synchronized(lock) { rerun })
            } finally {
                synchronized(lock) { processing = false }
                cleanupAndStop()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        executor.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun processQueue() {
        if (!Config.isUploadConfigured) {
            Logx.w("Upload", "Upload configuration is missing; keeping files")
            return
        }
        if (!NetworkMonitor.isOnline(this)) {
            Logx.i("Upload", "Network is offline; keeping files")
            return
        }

        val candidates = PendingStore.list(this, readyOnly = true)
        if (candidates.isEmpty()) return
        Logx.i("Upload", "Upload run started with ${candidates.size} candidate(s)")

        var processed = 0
        for (meta in candidates) {
            if (processed >= 50) break
            processed++

            val file = File(PendingStore.recordingsDir(this), meta.fileName)
            if (!file.isFile) {
                PendingStore.deleteAfterUpload(this, meta)
                continue
            }
            if (file.length() > Config.maxUploadBytes) {
                PendingStore.markAttempt(
                    this,
                    meta,
                    "File exceeds the 50 MB server limit",
                    UploadBackoff.nextAttemptAt(meta.attempts, retryable = false),
                )
                Logx.e("Upload", "Refusing to upload ${file.name}: over 50 MB")
                continue
            }

            updateNotification(0L, file.length())
            val result = Uploader.upload(this, meta) { sent, total ->
                updateNotification(sent, total)
            }

            if (result.success) {
                val deleted = PendingStore.deleteAfterUpload(this, meta)
                Logx.i(
                    "Upload",
                    "Uploaded ${file.name} (${file.length()} bytes, ${result.message}); " +
                        if (deleted) "local copy removed" else "cleanup will retry",
                )
            } else {
                PendingStore.markAttempt(
                    this,
                    meta,
                    result.message,
                    UploadBackoff.nextAttemptAt(meta.attempts, result.retryable),
                )
                Logx.w("Upload", "Upload failed for ${file.name}: ${result.message}")
                if (result.message.startsWith("IOException") ||
                    result.message.contains("Unable to resolve host", ignoreCase = true) ||
                    result.message.contains("timeout", ignoreCase = true) ||
                    result.message.contains("Failed to connect", ignoreCase = true)
                ) {
                    // Network went away; leave the rest for the next run.
                    break
                }
            }
        }
    }

    private fun startAsForeground(notification: Notification): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (t: Throwable) {
            Logx.w("Upload", "Could not start dataSync foreground service; retrying without type", t)
            return try {
                startForeground(NOTIFICATION_ID, notification)
                true
            } catch (t2: Throwable) {
                Logx.e("Upload", "Could not start upload foreground service", t2)
                stopSelf()
                false
            }
        }
    }

    private fun updateNotification(sent: Long, total: Long) {
        val now = System.currentTimeMillis()
        val done = if (total > 0) (sent * 100 / total).toInt() else 0
        if (now - lastNotificationAt < 1_000L && done < 100) return
        lastNotificationAt = now
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(sent, total))
    }

    private fun buildNotification(sent: Long, total: Long): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(getString(R.string.notification_upload_title))
            .setContentText(getString(R.string.notification_upload_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (total > 0) {
            val pct = (sent * 100 / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_upload_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_upload_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallSaver:upload")
            wl.setReferenceCounted(false)
            wl.acquire(45L * 60L * 1_000L)
            wakeLock = wl
            wakeLockRef.set(true)
        } catch (t: Throwable) {
            Logx.w("Upload", "Could not acquire upload wake lock", t)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLockRef.compareAndSet(true, false)) {
            try {
                wakeLock?.release()
            } catch (_: Throwable) {
            }
            wakeLock = null
        }
    }

    private fun cleanupAndStop() {
        releaseWakeLock()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
        isRunning = false
        stopSelf()
    }
}

object UploadScheduler {
    fun kick(context: Context) {
        if (!Config.isUploadConfigured) return
        if (!NetworkMonitor.isOnline(context)) return
        if (PendingStore.pendingCount(context) <= 0) return

        val intent = Intent(context, UploadService::class.java).setAction(UploadService.ACTION_UPLOAD)
        try {
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            Logx.w("Upload", "Could not start upload service", t)
        }
    }
}
