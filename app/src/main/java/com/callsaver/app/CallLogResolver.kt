package com.callsaver.app

import android.content.Context
import android.provider.CallLog
import java.util.concurrent.TimeUnit

object CallTracker {
    @Volatile
    var lastIncomingNumber: String? = null

    @Volatile
    var sawRinging: Boolean = false

    @Volatile
    var telephonyCallbacksRegistered: Boolean = false

    fun onRinging(number: String?) {
        sawRinging = true
        if (!number.isNullOrBlank()) lastIncomingNumber = number
    }

    fun resetAfterSession() {
        lastIncomingNumber = null
        sawRinging = false
    }
}

object RuntimeState {
    @Volatile
    var callSaverService: Any? = null
}

data class ResolvedCall(
    val phoneNumber: String,
    val contactName: String,
    val type: String,
    val foundInCallLog: Boolean,
)

object CallLogResolver {
    /**
     * Highest call-log row id at the moment a session starts. Comparing ids
     * after the call is much safer than looking at recent dates: it cannot
     * pick a different call that happened shortly before this one.
     */
    fun maxCallLogId(context: Context): Long = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),
            null,
            null,
            "${CallLog.Calls._ID} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: -1L
    } catch (t: Throwable) {
        Logx.w("CallLog", "Could not snapshot the call log", t)
        -1L
    }

    fun resolve(
        context: Context,
        startedAtWallMs: Long,
        callLogMaxIdAtStart: Long,
        sessionDurationMs: Long,
        heuristicIncoming: Boolean,
        fallbackNumber: String?,
    ): ResolvedCall {
        val rawNumber = queryLatestCall(
            context,
            startedAtWallMs,
            callLogMaxIdAtStart,
            sessionDurationMs,
            heuristicIncoming,
        )
        val resolvedRaw = rawNumber?.number

        val incoming = when {
            rawNumber?.type == CallLog.Calls.OUTGOING_TYPE -> false
            rawNumber?.type == CallLog.Calls.INCOMING_TYPE -> true
            rawNumber?.type == CallLog.Calls.MISSED_TYPE -> true
            rawNumber?.type == CallLog.Calls.REJECTED_TYPE -> true
            rawNumber != null -> !rawNumber.type.let { it == CallLog.Calls.OUTGOING_TYPE }
            else -> heuristicIncoming
        }

        val sourceNumber = resolvedRaw
            ?: fallbackNumber
            ?: ""

        val phoneNumber = PhoneNumbers.normalize(sourceNumber, Config.defaultCountryCode)
        val contactName = when {
            !rawNumber?.cachedName.isNullOrBlank() -> rawNumber!!.cachedName!!.trim()
            else -> PhoneNumbers.lookupContactName(context, phoneNumber, sourceNumber)
        }

        return ResolvedCall(
            phoneNumber = phoneNumber,
            contactName = PhoneNumbers.sanitizeName(contactName),
            type = if (incoming) "incoming" else "outbound",
            foundInCallLog = rawNumber != null,
        )
    }

    private data class CallRow(
        val number: String?,
        val type: Int,
        val cachedName: String?,
        val durationMs: Long,
    )

    private val projection = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.TYPE,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
    )

    private fun queryLatestCall(
        context: Context,
        startedAtWallMs: Long,
        maxIdAtStart: Long,
        sessionDurationMs: Long,
        heuristicIncoming: Boolean,
    ): CallRow? {
        // Prefer a row created after the session started. Call log writes can
        // lag a little behind the call-state change, so retry briefly.
        if (maxIdAtStart >= 0) {
            repeat(3) { attempt ->
                queryAfterId(context, maxIdAtStart)?.let { return it }
                if (attempt < 2) sleepBeforeRetry()
            }
        }

        // Some devices insert the outgoing-call row before the state change we
        // snapshot from. Fall back to a tight time window around this call.
        repeat(3) { attempt ->
            queryNearStart(
                context,
                startedAtWallMs,
                sessionDurationMs,
                heuristicIncoming,
            )?.let { return it }
            if (attempt < 2) sleepBeforeRetry()
        }
        return null
    }

    private fun queryAfterId(context: Context, maxIdAtStart: Long): CallRow? = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.TYPE} IN (?, ?)",
            arrayOf(
                maxIdAtStart.toString(),
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.OUTGOING_TYPE.toString(),
            ),
            "${CallLog.Calls._ID} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.toCallRow() else null
        }
    } catch (t: SecurityException) {
        Logx.w("CallLog", "READ_CALL_LOG permission is missing", t)
        null
    } catch (t: Throwable) {
        Logx.w("CallLog", "Could not read call log", t)
        null
    }

    private fun queryNearStart(
        context: Context,
        startedAtWallMs: Long,
        sessionDurationMs: Long,
        heuristicIncoming: Boolean,
    ): CallRow? = try {
        // Incoming calls ring before the answered/off-hook session begins.
        val windowMinutes = if (heuristicIncoming) 15L else 5L
        val since = (startedAtWallMs - TimeUnit.MINUTES.toMillis(windowMinutes)).coerceAtLeast(0L)
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} IN (?, ?)",
            arrayOf(
                since.toString(),
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.OUTGOING_TYPE.toString(),
            ),
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            var scanned = 0
            while (cursor.moveToNext() && scanned < 20) {
                scanned++
                val row = cursor.toCallRow()
                if (durationMatches(row.durationMs, sessionDurationMs)) return@use row
            }
            null
        }
    } catch (t: SecurityException) {
        Logx.w("CallLog", "READ_CALL_LOG permission is missing", t)
        null
    } catch (t: Throwable) {
        Logx.w("CallLog", "Could not read call log", t)
        null
    }

    private fun durationMatches(callLogDurationMs: Long, sessionDurationMs: Long): Boolean {
        if (sessionDurationMs <= 0L || callLogDurationMs <= 0L) return true
        if (sessionDurationMs < 5_000L) return true
        // Outgoing sessions start before ringing, so the log duration can be
        // shorter than the session. The generous tolerance still rejects a
        // clearly different previous call.
        val tolerance = maxOf(3L * 60L * 1_000L, sessionDurationMs / 2)
        return kotlin.math.abs(callLogDurationMs - sessionDurationMs) <= tolerance
    }

    private fun android.database.Cursor.toCallRow(): CallRow = CallRow(
        number = getString(1),
        type = getInt(2),
        cachedName = getString(3),
        durationMs = getLong(5) * 1000L,
    )

    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(800L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

object NetworkMonitor {
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            false
        }
    }
}
