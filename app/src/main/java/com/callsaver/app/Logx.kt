package com.callsaver.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs to logcat and to an app-specific file that can be pulled with adb:
 *   adb pull /sdcard/Android/data/com.callsaver.app/files/logs/callsaver.log
 *
 * Never pass phone numbers, contact names, access codes or response bodies here.
 */
object Logx {
    private const val TAG = "CallSaver"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        synchronized(lock) {
            if (logFile != null) return
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, "logs")
            if (!dir.exists() && !dir.mkdirs()) {
                logFile = null
                return
            }
            logFile = File(dir, "callsaver.log")
        }
    }

    fun d(tag: String, message: String) = write(Log.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write(Log.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) =
        write(Log.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        write(Log.ERROR, tag, message, error)

    private fun write(priority: Int, tag: String, message: String, error: Throwable?) {
        Log.println(priority, "$TAG/$tag", message)
        error?.let { Log.println(priority, "$TAG/$tag", Log.getStackTraceString(it)) }

        val file = logFile ?: return
        synchronized(lock) {
            try {
                if (file.length() > MAX_LOG_BYTES) {
                    val old = File(file.parentFile, "callsaver.log.1")
                    old.delete()
                    file.renameTo(old)
                }
                val line = buildString {
                    append(timeFormat.format(Date()))
                    append(" [").append(tag).append("] ").append(message)
                    if (error != null) {
                        append(" | ").append(error.javaClass.simpleName)
                        append(": ").append(error.message)
                    }
                    append('\n')
                }
                file.appendText(line, Charsets.UTF_8)
            } catch (_: Throwable) {
                // Logging must never break recording or uploading.
            }
        }
    }
}
