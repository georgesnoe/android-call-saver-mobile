package com.callsaver.app

import android.content.Context
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UploadResult(
    val success: Boolean,
    val retryable: Boolean,
    val message: String,
)

object Uploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .callTimeout(45, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    fun upload(
        context: Context,
        meta: RecordingMeta,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): UploadResult {
        if (!Config.isUploadConfigured) {
            return UploadResult(false, retryable = false, message = "Upload configuration is missing")
        }
        val url = Config.uploadUrl
            ?: return UploadResult(false, retryable = false, message = "Invalid upload URL")

        val file = File(PendingStore.recordingsDir(context), meta.fileName)
        if (!file.isFile) {
            return UploadResult(false, retryable = false, message = "Audio file is missing")
        }
        if (file.length() > Config.maxUploadBytes) {
            return UploadResult(
                false,
                retryable = false,
                message = "File is larger than the 50 MB server limit",
            )
        }

        val sha = meta.sha256 ?: sha256(file)
        if (sha != null && meta.sha256 != sha) {
            PendingStore.setSha256(context, meta, sha)
        }

        val mime = when (file.extension.lowercase()) {
            "aac" -> "audio/aac"
            "m4a", "mp4" -> "audio/mp4"
            "3gp" -> "audio/3gpp"
            else -> "application/octet-stream"
        }.toMediaType()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("type", PhoneNumbers.sanitizeType(meta.type))
            .addFormDataPart(
                "phoneNumber",
                PhoneNumbers.normalize(meta.phoneNumber, Config.defaultCountryCode),
            )
            .addFormDataPart("name", PhoneNumbers.sanitizeName(meta.name))
            .addFormDataPart(
                "file",
                file.name,
                ProgressRequestBody(file, mime, onProgress),
            )
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", Config.userAgent)
            .header("Idempotency-Key", meta.id)
            .header("X-Recording-Id", meta.id)
            .header(Config.accessHeader, Config.accessCode)

        if (!sha.isNullOrBlank()) {
            requestBuilder.header("X-Content-SHA256", sha)
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                // Touch only a small prefix; the status code is the contract.
                try {
                    response.peekBody(16_384).string()
                } catch (_: Throwable) {
                }
                if (response.isSuccessful) {
                    UploadResult(true, retryable = true, message = "HTTP ${response.code}")
                } else {
                    UploadResult(
                        false,
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                        message = "HTTP ${response.code}",
                    )
                }
            }
        } catch (t: Throwable) {
            UploadResult(
                false,
                retryable = true,
                message = "${t.javaClass.simpleName}: ${t.message.orEmpty()}".take(300),
            )
        }
    }

    fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (t: Throwable) {
        Logx.w("Upload", "Could not hash ${file.name}", t)
        null
    }

    private class ProgressRequestBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onProgress: (sent: Long, total: Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var sent = 0L
            file.source().use { source ->
                val buffer = Buffer()
                while (true) {
                    val read = source.read(buffer, 64 * 1024)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    sent += read
                    onProgress(sent, total)
                }
            }
        }
    }
}

object UploadBackoff {
    private const val BASE_MS = 30_000L
    private const val MAX_MS = 6L * 60L * 60L * 1_000L

    fun nextAttemptAt(attempts: Int, retryable: Boolean): Long {
        val now = System.currentTimeMillis()
        val jitter = (0..30_000L).random()
        if (!retryable) {
            // Permanent client errors (400/401/403/413) keep the file but are
            // retried slowly so a fixed server or credential is picked up.
            return now + 24L * 60L * 60L * 1_000L + jitter
        }
        val shift = attempts.coerceIn(0, 10)
        val delay = (BASE_MS shl shift).coerceAtMost(MAX_MS)
        return now + delay + jitter
    }
}
