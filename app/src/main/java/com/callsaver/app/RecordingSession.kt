package com.callsaver.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * One call session can produce multiple files when it grows beyond the
 * per-file rotation threshold. Every part shares a sessionId and the same
 * caller metadata.
 */
class RecordingSession(
    val id: String = UUID.randomUUID().toString(),
    val startedAtWallMs: Long = System.currentTimeMillis(),
    val startedAtElapsedMs: Long,
    val sawRingingAtStart: Boolean,
    val initialIncomingNumber: String?,
    val callLogMaxIdAtStart: Long = -1L,
) {
    val parts = ArrayList<PartRecord>()

    fun addPart(record: PartRecord) {
        synchronized(parts) { parts.add(record) }
    }

    fun snapshotParts(): List<PartRecord> = synchronized(parts) { parts.toList() }
}

data class PartRecord(
    val file: java.io.File,
    val partNumber: Int,
    val startedAtWallMs: Long,
    val durationMs: Long,
    val source: String,
    val sizeBytes: Long,
)

fun formatFileStamp(ms: Long): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))
