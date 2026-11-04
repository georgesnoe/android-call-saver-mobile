package com.callsaver.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class RecordingMeta(
    val id: String,
    val fileName: String,
    val sessionId: String,
    val part: Int,
    val type: String,
    val phoneNumber: String,
    val name: String,
    val createdAt: Long,
    val durationMs: Long,
    val source: String,
    val sizeBytes: Long,
    val ready: Boolean,
    val attempts: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
    val sha256: String?,
)

/**
 * Durable, file-based upload queue. The audio file is the source of truth; a
 * sidecar JSON holds metadata and retry state. Nothing is deleted until the
 * server has returned a successful response.
 */
object PendingStore {
    private val LOCK = Any()
    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val audioExtensions = setOf("aac", "m4a", "mp4", "3gp")

    fun recordingsDir(context: Context): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun newAudioFile(
        context: Context,
        sessionId: String,
        part: Int,
        extension: String,
    ): File {
        val safeExt = extension.trimStart('.').ifBlank { "aac" }
        val stamp = stampFormat.format(Date())
        val shortSession = sessionId.replace("-", "").take(8)
        val name = "call_${stamp}_${shortSession}_p$part.$safeExt"
        return File(recordingsDir(context), name)
    }

    fun sidecarFor(audio: File): File = File(audio.parentFile, audio.name + ".json")

    fun read(context: Context, sidecar: File): RecordingMeta? {
        if (!sidecar.isFile) return null
        return try {
            val json = JSONObject(sidecar.readText(Charsets.UTF_8))
            val fileName = json.getString("fileName")
            val audio = File(recordingsDir(context), fileName)
            if (!audio.isFile) {
                sidecar.delete()
                return null
            }
            RecordingMeta(
                id = json.optString("id", fileName),
                fileName = fileName,
                sessionId = json.optString("sessionId", "unknown"),
                part = json.optInt("part", 1),
                type = PhoneNumbers.sanitizeType(json.optString("type", "incoming")),
                phoneNumber = PhoneNumbers.normalize(
                    json.optString("phoneNumber", ""),
                    Config.defaultCountryCode,
                ),
                name = PhoneNumbers.sanitizeName(json.optString("name", "unknown")),
                createdAt = json.optLong("createdAt", audio.lastModified()),
                durationMs = json.optLong("durationMs", 0L),
                source = json.optString("source", "unknown"),
                sizeBytes = audio.length(),
                ready = json.optBoolean("ready", false),
                attempts = json.optInt("attempts", 0),
                nextAttemptAt = json.optLong("nextAttemptAt", 0L),
                lastError = json.optString("lastError", "").ifBlank { null },
                sha256 = json.optString("sha256", "").ifBlank { null },
            )
        } catch (t: Throwable) {
            Logx.w("Store", "Cannot read sidecar ${sidecar.name}", t)
            null
        }
    }

    fun write(context: Context, meta: RecordingMeta) {
        val audio = File(recordingsDir(context), meta.fileName)
        val sidecar = sidecarFor(audio)
        val json = JSONObject().apply {
            put("version", 1)
            put("id", meta.id)
            put("fileName", meta.fileName)
            put("sessionId", meta.sessionId)
            put("part", meta.part)
            put("type", meta.type)
            put("phoneNumber", meta.phoneNumber)
            put("name", meta.name)
            put("createdAt", meta.createdAt)
            put("durationMs", meta.durationMs)
            put("source", meta.source)
            put("sizeBytes", meta.sizeBytes)
            put("ready", meta.ready)
            put("attempts", meta.attempts)
            put("nextAttemptAt", meta.nextAttemptAt)
            meta.lastError?.let { put("lastError", it) }
            meta.sha256?.let { put("sha256", it) }
        }
        atomicWrite(sidecar, json.toString())
    }

    fun list(context: Context, readyOnly: Boolean = false, now: Long = System.currentTimeMillis()): List<RecordingMeta> {
        val dir = recordingsDir(context)
        val sidecars = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        } ?: return emptyList()

        val result = ArrayList<RecordingMeta>(sidecars.size)
        for (sidecar in sidecars) {
            // A .done marker means the server already accepted this file. Never
            // upload it again while crash recovery is finishing the cleanup.
            val audioName = sidecar.name.removeSuffix(".json")
            if (File(dir, audioName + ".done").exists()) continue
            val meta = synchronized(LOCK) { read(context, sidecar) } ?: continue
            if (readyOnly && (!meta.ready || meta.nextAttemptAt > now)) continue
            result.add(meta)
        }
        return result.sortedBy { it.createdAt }
    }

    fun pendingCount(context: Context): Int =
        list(context, readyOnly = true).size

    fun find(context: Context, fileName: String): RecordingMeta? = synchronized(LOCK) {
        val audio = File(recordingsDir(context), fileName)
        read(context, sidecarFor(audio))
    }

    fun markAttempt(context: Context, meta: RecordingMeta, error: String, nextAttemptAt: Long) {
        synchronized(LOCK) {
            write(
                context,
                meta.copy(
                    attempts = meta.attempts + 1,
                    nextAttemptAt = nextAttemptAt,
                    lastError = error.take(500),
                ),
            )
        }
    }

    fun setSha256(context: Context, meta: RecordingMeta, sha256: String) {
        synchronized(LOCK) {
            write(context, meta.copy(sha256 = sha256, sizeBytes = meta.sizeBytes))
        }
    }

    /**
     * Marks every part of a session ready for upload. Called once the call has
     * ended and caller details have been resolved from the call log.
     */
    fun finishSession(
        context: Context,
        sessionId: String,
        type: String,
        phoneNumber: String,
        name: String,
    ): List<RecordingMeta> = synchronized(LOCK) {
        val updated = ArrayList<RecordingMeta>()
        for (meta in list(context)) {
            if (meta.sessionId != sessionId) continue
            val newMeta = meta.copy(
                type = PhoneNumbers.sanitizeType(type),
                phoneNumber = PhoneNumbers.normalize(phoneNumber, Config.defaultCountryCode),
                name = PhoneNumbers.sanitizeName(name),
                ready = true,
                nextAttemptAt = 0L,
            )
            write(context, newMeta)
            updated.add(newMeta)
        }
        updated
    }

    /**
     * Successful upload path:
     * 1. create a durable .done marker,
     * 2. delete the audio and sidecar,
     * 3. delete the marker.
     * A crash at any point causes recovery to finish the cleanup without ever
     * deleting an audio file that was not acknowledged by the server.
     */
    fun deleteAfterUpload(context: Context, meta: RecordingMeta): Boolean = synchronized(LOCK) {
        val dir = recordingsDir(context)
        val audio = File(dir, meta.fileName)
        val sidecar = sidecarFor(audio)
        val marker = File(dir, audio.name + ".done")
        return try {
            if (!marker.exists()) {
                FileOutputStream(marker).use { out ->
                    out.write("uploaded\n".toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
            }
            if (audio.exists() && !audio.delete()) {
                Logx.w("Store", "Could not delete uploaded audio ${audio.name}")
                return false
            }
            sidecar.delete()
            marker.delete()
            true
        } catch (t: Throwable) {
            Logx.w("Store", "Could not finalize upload for ${audio.name}", t)
            false
        }
    }

    /**
     * Repairs state after a crash, reboot or app update:
     * - completes deletions marked .done,
     * - adopts audio files whose sidecar was never written,
     * - releases sessions that were interrupted mid-call,
     * - removes stale temporary files.
     */
    fun recover(context: Context, activeSessionId: String? = null) = synchronized(LOCK) {
        val dir = recordingsDir(context)
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.name.endsWith(".tmp")) file.delete()
        }

        for (marker in files.filter { it.isFile && it.name.endsWith(".done") }) {
            val audioName = marker.name.removeSuffix(".done")
            val audio = File(dir, audioName)
            if (audio.exists() && !audio.delete()) {
                // Keep the marker so a later recovery retries instead of
                // re-uploading an already accepted file.
                Logx.w("Store", "Could not delete uploaded audio $audioName; will retry")
                continue
            }
            sidecarFor(audio).delete()
            marker.delete()
            Logx.i("Store", "Cleaned completed upload $audioName")
        }

        val now = System.currentTimeMillis()
        val staleSessionMs = 10 * 60_000L

        for (file in dir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val ext = file.extension.lowercase()
            if (ext !in audioExtensions) continue
            // Leave files whose successful upload is still being cleaned up.
            if (File(dir, file.name + ".done").exists()) continue
            if (file.length() == 0L) {
                file.delete()
                continue
            }

            val sidecar = sidecarFor(file)
            if (!sidecar.exists()) {
                if (now - file.lastModified() < 5_000L) continue
                val meta = RecordingMeta(
                    id = UUID.randomUUID().toString(),
                    fileName = file.name,
                    sessionId = "orphan-${file.name}",
                    part = 1,
                    type = "incoming",
                    phoneNumber = Config.defaultCountryCode,
                    name = "unknown",
                    createdAt = file.lastModified(),
                    durationMs = 0L,
                    source = "recovered",
                    sizeBytes = file.length(),
                    ready = true,
                    attempts = 0,
                    nextAttemptAt = 0L,
                    lastError = "Recovered audio file with no metadata",
                    sha256 = null,
                )
                write(context, meta)
                Logx.w("Store", "Recovered orphan audio ${file.name}")
                continue
            }

            val meta = read(context, sidecar) ?: continue
            if (meta.sessionId == activeSessionId) continue
            if (!meta.ready && now - meta.createdAt > staleSessionMs) {
                write(
                    context,
                    meta.copy(
                        type = PhoneNumbers.sanitizeType(meta.type),
                        phoneNumber = PhoneNumbers.normalize(meta.phoneNumber, Config.defaultCountryCode),
                        name = PhoneNumbers.sanitizeName(meta.name),
                        ready = true,
                        nextAttemptAt = 0L,
                        lastError = "Recovered interrupted recording session",
                    ),
                )
                Logx.w("Store", "Released interrupted session for ${file.name}")
            }
        }

        // Sidecars whose audio disappeared are useless.
        for (sidecar in dir.listFiles().orEmpty()) {
            if (!sidecar.isFile || !sidecar.name.endsWith(".json")) continue
            val audioName = sidecar.name.removeSuffix(".json")
            if (!File(dir, audioName).exists()) sidecar.delete()
        }
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                Logx.w("Store", "Could not replace ${target.name}")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Logx.e("Store", "Could not write ${target.name}", t)
            tmp.delete()
        }
    }
}
