package com.callsaver.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Records a call on the service's handler thread. Tries the best audio source
 * first and falls back when the OS denies it. Files are rotated before they
 * reach the server's 50 MB limit.
 */
class CallRecorder(
    private val context: Context,
    private val session: RecordingSession,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartClosed(record: PartRecord)
        fun onRecorderError(message: String)
        fun onSourceChanged(source: String)
    }

    private data class Container(val format: Int, val extension: String)
    private data class Source(val value: Int, val name: String)

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var currentPart = 0
    private var currentSource = Source(MediaRecorder.AudioSource.MIC, "mic")
    private var partStartedAtWall = 0L
    private var partStartedAtElapsed = 0L
    private var closed = false
    private var rotationInProgress = false
    private var probeDone = false
    private var sourceIndex = 0
    private var consecutiveStartFailures = 0

    private val sources: List<Source> = resolveSources()
    private val containers: List<Container> = listOf(
        Container(MediaRecorder.OutputFormat.AAC_ADTS, "aac"),
        Container(MediaRecorder.OutputFormat.MPEG_4, "m4a"),
    )

    private val sizeCheck = object : Runnable {
        override fun run() {
            if (closed) return
            val file = currentFile
            if (file != null && recorder != null && file.length() >= Config.maxPartBytes) {
                rotate(force = true)
            }
            if (!closed) handler.postDelayed(this, 2_000L)
        }
    }

    private val probe = Runnable {
        if (closed || probeDone || rotationInProgress) return@Runnable
        probeDone = true
        val file = currentFile ?: return@Runnable
        val source = currentSource
        if (Config.recordingAudioSource != "auto") return@Runnable
        if (source.value != MediaRecorder.AudioSource.VOICE_CALL) return@Runnable
        if (file.length() < 6_000L) {
            Logx.w("Recorder", "VOICE_CALL produced no usable audio; trying the next source")
            discardAndTryNextSource(MediaRecorder.AudioSource.VOICE_CALL)
        }
    }

    fun start(): Boolean {
        if (closed) return false
        val started = startPart(partNumber = 1)
        if (started) {
            handler.postDelayed(sizeCheck, 2_000L)
            handler.postDelayed(probe, 8_000L)
        }
        return started
    }

    /** Stops and finalizes the current part. Safe to call more than once. */
    fun stop() {
        if (closed) return
        closed = true
        handler.removeCallbacks(sizeCheck)
        handler.removeCallbacks(probe)
        closeCurrentPart()
    }

    val isActive: Boolean
        get() = recorder != null && !closed

    private fun startPart(partNumber: Int): Boolean {
        if (closed) return false
        var firstSource = sourceIndex.coerceIn(0, sources.lastIndex)
        var attempts = 0

        while (attempts < sources.size) {
            val source = sources[firstSource]
            for (container in containers) {
                if (closed) return false
                val file = PendingStore.newAudioFile(
                    context,
                    session.id,
                    partNumber,
                    container.extension,
                )
                val started = tryStart(source, container, file)
                if (started != null) {
                    recorder = started
                    currentFile = file
                    currentPart = partNumber
                    currentSource = source
                    sourceIndex = firstSource
                    partStartedAtWall = System.currentTimeMillis()
                    partStartedAtElapsed = SystemClock.elapsedRealtime()
                    consecutiveStartFailures = 0
                    writePlaceholder(file, partNumber, source)
                    listener.onSourceChanged(source.name)
                    Logx.i("Recorder", "Recording part $partNumber with ${source.name}/${container.extension}")
                    return true
                }
                file.delete()
            }

            firstSource = (firstSource + 1) % sources.size
            attempts++
        }

        consecutiveStartFailures++
        listener.onRecorderError("No usable audio source (all ${sources.size} sources failed)")
        return false
    }

    @Suppress("DEPRECATION")
    private fun tryStart(source: Source, container: Container, file: File): MediaRecorder? {
        val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        return try {
            candidate.setAudioSource(source.value)
            candidate.setOutputFormat(container.format)
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            candidate.setOutputFile(file.absolutePath)
            candidate.setAudioEncodingBitRate(Config.recordingBitrate)
            candidate.setAudioSamplingRate(16_000)
            candidate.setAudioChannels(1)
            try {
                candidate.setMaxFileSize(Config.maxPartBytes + 2L * 1024L * 1024L)
            } catch (_: Throwable) {
                // Not supported by every vendor recorder.
            }
            candidate.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    handler.post { rotate(force = true) }
                }
            }
            candidate.prepare()
            candidate.start()
            candidate
        } catch (t: Throwable) {
            try {
                candidate.release()
            } catch (_: Throwable) {
            }
            Logx.w(
                "Recorder",
                "Source ${source.name} / ${container.extension} rejected: " +
                    "${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}",
            )
            null
        }
    }

    private fun rotate(force: Boolean) {
        if (closed || rotationInProgress) return
        val file = currentFile ?: return
        if (!force && file.length() < Config.maxPartBytes) return

        rotationInProgress = true
        closeCurrentPart()

        val free = availableBytes()
        if (free in 0 until Config.minFreeBytesToRecord) {
            closed = true
            listener.onRecorderError("Storage is almost full; recording stopped")
            rotationInProgress = false
            return
        }

        handler.postDelayed({
            rotationInProgress = false
            if (closed) return@postDelayed
            val nextPart = currentPart + 1
            if (!startPart(nextPart)) {
                closed = true
            }
        }, 350L)
    }

    private fun discardAndTryNextSource(failedSource: Int) {
        if (closed || rotationInProgress) return
        rotationInProgress = true
        val failedFile = currentFile
        stopAndReleaseCurrent(deleteFile = true)
        if (failedFile != null) {
            Logx.i("Recorder", "Discarded silent probe file ${failedFile.name}")
        }

        val failedIndex = sources.indexOfFirst { it.value == failedSource }
        if (failedIndex >= 0) {
            sourceIndex = (failedIndex + 1) % sources.size
        }

        handler.postDelayed({
            rotationInProgress = false
            if (closed) return@postDelayed
            val partToReuse = currentPart.coerceAtLeast(1)
            if (!startPart(partToReuse)) {
                closed = true
            }
        }, 350L)
        // Restart the probe window for the new source.
        probeDone = false
        handler.postDelayed(probe, 8_000L)
    }

    private fun closeCurrentPart() {
        val file = currentFile
        val part = currentPart
        val source = currentSource
        val startedWall = partStartedAtWall
        val startedElapsed = partStartedAtElapsed
        if (file == null) {
            stopAndReleaseCurrent(deleteFile = false)
            return
        }

        var stopError: Throwable? = null
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            stopError = t
            Logx.w("Recorder", "MediaRecorder.stop failed; salvaging file", t)
        } finally {
            try {
                recorder?.release()
            } catch (_: Throwable) {
            }
            recorder = null
        }

        val size = file.length()
        if (size <= 0L || (stopError != null && size < 1_024L)) {
            file.delete()
            PendingStore.sidecarFor(file).delete()
            currentFile = null
            listener.onRecorderError("Recording part was empty and was discarded")
            return
        }

        try {
            FileOutputStream(file, true).use { it.fd.sync() }
        } catch (_: Throwable) {
        }

        val duration = (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L)
        val existing = PendingStore.find(context, file.name)
        val meta = (existing ?: buildMeta(file, part, source.name, startedWall)).copy(
            durationMs = duration,
            source = source.name,
            sizeBytes = size,
            lastError = stopError?.let { "Recorder stop error; file salvaged" },
        )
        PendingStore.write(context, meta)

        currentFile = null
        listener.onPartClosed(
            PartRecord(
                file = file,
                partNumber = part,
                startedAtWallMs = startedWall,
                durationMs = duration,
                source = source.name,
                sizeBytes = size,
            ),
        )
    }

    private fun stopAndReleaseCurrent(deleteFile: Boolean) {
        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null
        val file = currentFile
        currentFile = null
        if (deleteFile && file != null) {
            file.delete()
            PendingStore.sidecarFor(file).delete()
        }
    }

    private fun writePlaceholder(file: File, part: Int, source: Source) {
        PendingStore.write(
            context,
            buildMeta(file, part, source.name, System.currentTimeMillis()),
        )
    }

    private fun buildMeta(
        file: File,
        part: Int,
        source: String,
        createdAt: Long,
    ): RecordingMeta = RecordingMeta(
        id = UUID.randomUUID().toString(),
        fileName = file.name,
        sessionId = session.id,
        part = part,
        type = if (session.sawRingingAtStart) "incoming" else "outbound",
        phoneNumber = PhoneNumbers.normalize(
            session.initialIncomingNumber,
            Config.defaultCountryCode,
        ),
        name = "unknown",
        createdAt = createdAt,
        durationMs = 0L,
        source = source,
        sizeBytes = file.length(),
        ready = false,
        attempts = 0,
        nextAttemptAt = 0L,
        lastError = null,
        sha256 = null,
    )

    private fun availableBytes(): Long = try {
        android.os.StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Throwable) {
        -1L
    }

    private fun resolveSources(): List<Source> {
        val voiceCall = Source(MediaRecorder.AudioSource.VOICE_CALL, "voice_call")
        val voiceCommunication =
            Source(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "voice_communication")
        val voiceRecognition =
            Source(MediaRecorder.AudioSource.VOICE_RECOGNITION, "voice_recognition")
        val mic = Source(MediaRecorder.AudioSource.MIC, "mic")
        val default = Source(MediaRecorder.AudioSource.DEFAULT, "default")

        return when (Config.recordingAudioSource) {
            "voice_call" -> listOf(voiceCall)
            "voice_communication" -> listOf(voiceCommunication)
            "voice_recognition" -> listOf(voiceRecognition)
            "mic" -> listOf(mic)
            "default" -> listOf(default)
            else -> listOf(voiceCall, voiceCommunication, voiceRecognition, mic, default)
        }
    }
}
