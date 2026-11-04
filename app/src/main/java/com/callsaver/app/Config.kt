package com.callsaver.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object Config {
    private const val MIB = 1024L * 1024L

    val apiBaseUrl: String = BuildConfig.API_BASE_URL.trim()
    val uploadPath: String = BuildConfig.API_UPLOAD_PATH.trim().ifBlank { "/api/upload" }
    val accessCode: String = BuildConfig.API_ACCESS_CODE
    val accessHeader: String = BuildConfig.API_ACCESS_HEADER.trim().ifBlank { "x-access-code" }
    val defaultCountryCode: String =
        PhoneNumbers.normalizeCountryCode(BuildConfig.DEFAULT_COUNTRY_CODE)

    val recordingAudioSource: String =
        BuildConfig.RECORDING_AUDIO_SOURCE.trim().lowercase().ifBlank { "auto" }
    val recordingBitrate: Int = BuildConfig.RECORDING_BITRATE.coerceIn(12_000, 128_000)

    /** The server rejects files above 50 MB. Stay well below that. */
    val maxUploadBytes: Long = 48_000_000L

    /** Rotate to a new file before the 50 MB server limit is reached. */
    val maxPartBytes: Long = 45L * MIB

    /** Refuse to start a recording when free storage is this low. */
    val minFreeBytesToRecord: Long = 200L * MIB

    val uploadUrl: HttpUrl? by lazy { buildUploadUrl() }

    val isUploadConfigured: Boolean
        get() = apiBaseUrl.isNotBlank() && accessCode.isNotBlank() && uploadUrl != null

    val userAgent: String
        get() = "CallSaver/${BuildConfig.VERSION_NAME} (Android)"

    private fun buildUploadUrl(): HttpUrl? {
        if (apiBaseUrl.isBlank()) return null
        val base = apiBaseUrl.trim().trimEnd('/')
        val path = uploadPath.trim()
        val full = when {
            path.startsWith("http://", ignoreCase = true) ||
                path.startsWith("https://", ignoreCase = true) -> path
            path.isEmpty() -> base
            else -> "$base/${path.trimStart('/')}"
        }
        return full.toHttpUrlOrNull()
    }
}
