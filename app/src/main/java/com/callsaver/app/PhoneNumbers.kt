package com.callsaver.app

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.PhoneLookup

object PhoneNumbers {
    private val placeholderNames = setOf(
        "unknown", "private", "anonymous", "restricted", "unavailable", "null", "withheld",
    )

    fun normalizeCountryCode(raw: String?): String {
        val digits = raw.orEmpty().filter { it.isDigit() }
        return "+" + digits.ifBlank { "228" }
    }

    /**
     * Produces a +<country_code><number> value as required by the upload API.
     * Unavailable caller IDs fall back to the configured country code alone.
     */
    fun normalize(raw: String?, defaultCountryCode: String): String {
        val countryCode = normalizeCountryCode(defaultCountryCode)
        if (raw.isNullOrBlank()) return countryCode

        val trimmed = raw.trim()
        if (placeholderNames.contains(trimmed.lowercase())) return countryCode

        var digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return countryCode

        if (trimmed.startsWith("+")) return "+$digits"

        if (digits.startsWith("00")) {
            digits = digits.removePrefix("00")
            return "+$digits"
        }

        val ccDigits = countryCode.removePrefix("+")
        // A number that already contains the country code is normally at least
        // country code + 7 digits. Local numbers in Togo are 8 digits, so this
        // does not accidentally treat a local number as international.
        if (digits.length >= ccDigits.length + 7 && digits.startsWith(ccDigits)) {
            return "+$digits"
        }
        return "+$ccDigits$digits"
    }

    fun lookupContactName(
        context: Context,
        normalizedNumber: String,
        fallbackRawNumber: String? = null,
    ): String {
        val candidates = buildList {
            add(normalizedNumber)
            if (!fallbackRawNumber.isNullOrBlank() && fallbackRawNumber != normalizedNumber) {
                add(fallbackRawNumber)
            }
        }
        for (candidate in candidates) {
            try {
                val uri = android.net.Uri.withAppendedPath(
                    PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(candidate),
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name.trim()
                    }
                }
            } catch (_: SecurityException) {
                return "unknown"
            } catch (_: Throwable) {
                // Try the next representation.
            }
        }
        return "unknown"
    }

    fun sanitizeType(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "outbound", "outgoing" -> "outbound"
        else -> "incoming"
    }

    fun sanitizeName(raw: String?): String =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) } ?: "unknown"
}
