package com.vynylrecord.turntable.studio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import com.vynylrecord.turntable.press.WavIO
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Records a voice into a WAV file, straight to disk.
 *
 * `MediaRecorder` would be less code, but it only writes compressed containers and it cannot be
 * changed mid-flight. `AudioRecord` hands over raw PCM, which means the Studio can show a live level
 * meter, the capture goes into the press chain with no decode step and no loss, and the file that
 * lands on disk is a plain 44.1 kHz mono WAV that anything can open.
 *
 * The header is written up front with placeholder lengths and patched when recording stops, so an
 * hour-long voice note never has to be held in memory, and a capture that is interrupted by the
 * process being killed is still a valid WAV up to the last flushed frame.
 *
 * Nothing here touches the network, and the file is written into app-private storage: the recording
 * is the user's, on their device, from the moment it exists.
 */
class VoiceRecorder(private val context: Context) {

    private var record: AudioRecord? = null
    private var output: RandomAccessFile? = null
    private var worker: Thread? = null
    private var effects = mutableListOf<AutoCloseable>()

    @Volatile
    private var recording = false

    @Volatile
    private var framesWritten = 0L

    @Volatile
    private var peakLevel = 0f

    @Volatile
    private var lastErrorText: String? = null

    private var target: File? = null
    private var startedAtMs = 0L

    /** A short description of the last failure, for the Studio to show instead of a silent nothing. */
    val lastError: String? get() = lastErrorText

    val isRecording: Boolean get() = recording

    /** Live input level, 0..1, for the meter under the record button. */
    val level: Float get() = peakLevel

    fun elapsedMs(): Long = if (!recording) 0L else SystemClock.elapsedRealtime() - startedAtMs

    /**
     * Starts capturing to [file].
     *
     * Returns false when the microphone could not be opened — most often because the permission was
     * refused, occasionally because another app holds the input. The caller shows [lastError] rather
     * than pretending to record.
     */
    @SuppressLint("MissingPermission") // checked by the caller before it gets here; see RecorderPermission
    fun start(file: File): Boolean {
        stop()
        lastErrorText = null
        peakLevel = 0f
        framesWritten = 0L

        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        if (minimum <= 0) {
            lastErrorText = "This device cannot capture at 44.1 kHz."
            return false
        }
        val bufferBytes = maxOf(minimum * 2, SAMPLE_RATE / 4 * BYTES_PER_FRAME)

        try {
            val created = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_MASK,
                ENCODING,
                bufferBytes,
            )
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                created.release()
                lastErrorText = "The microphone is unavailable."
                return false
            }

            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            raf.write(WavIO.wavHeader(CHANNELS, SAMPLE_RATE, BITS_PER_SAMPLE, 0))

            // Clean-up effects, where the device offers them. A voice note pressed onto vinyl does not
            // want a fan in the background, and these are all local to the input path.
            effects = mutableListOf<AutoCloseable>().apply {
                runCatching { NoiseSuppressor.create(created.audioSessionId)?.let { add(AutoCloseableRef(it)) } }
                runCatching { AutomaticGainControl.create(created.audioSessionId)?.let { add(AutoCloseableRef(it)) } }
                runCatching { AcousticEchoCanceler.create(created.audioSessionId)?.let { add(AutoCloseableRef(it)) } }
            }

            record = created
            output = raf
            target = file
            startedAtMs = SystemClock.elapsedRealtime()
            recording = true
            created.startRecording()

            worker = thread(name = "vynyl-recorder", isDaemon = true) { pump(created, raf, bufferBytes) }
            return true
        } catch (error: Exception) {
            lastErrorText = when (error) {
                is SecurityException -> "Microphone permission was not granted."
                is IOException -> "The recording could not be written to storage."
                else -> "Recording could not start on this device."
            }
            releaseAll()
            file.delete()
            return false
        }
    }

    /** Reads from the microphone into the file until [stop] is called. */
    private fun pump(source: AudioRecord, sink: RandomAccessFile, bufferBytes: Int) {
        val buffer = ByteArray(bufferBytes)
        try {
            while (recording) {
                val read = source.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                sink.write(buffer, 0, read)
                framesWritten += read / BYTES_PER_FRAME
                peakLevel = peakOf(buffer, read)
            }
        } catch (error: IOException) {
            lastErrorText = "Storage filled up while recording."
            recording = false
        } catch (error: IllegalStateException) {
            recording = false
        }
    }

    /** The loudest sample in a chunk, converted back from 16-bit PCM for the meter. */
    private fun peakOf(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var index = 0
        while (index + 1 < length) {
            val value = (buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)
            val magnitude = abs(if (value >= 0x8000) value - 0x10000 else value)
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    /**
     * Stops capturing and returns the finished file, or null when nothing usable was recorded.
     *
     * The header's two length fields are patched here, which is what makes the file immediately
     * playable by anything that opens it afterwards.
     */
    fun stop(): File? {
        val source = record ?: return null
        recording = false
        releaseEffects()
        runCatching { source.stop() }
        worker?.join(WORKER_JOIN_MS)
        worker = null
        runCatching { source.release() }
        record = null

        var result: File? = null
        try {
            output?.let { sink ->
                val dataBytes = (framesWritten * BYTES_PER_FRAME).toInt()
                sink.seek(0L)
                sink.write(WavIO.wavHeader(CHANNELS, SAMPLE_RATE, BITS_PER_SAMPLE, dataBytes))
            }
            result = target?.takeIf { framesWritten > 0L && it.length() > WAV_HEADER_BYTES }
            if (result == null) {
                lastErrorText = lastErrorText ?: "Nothing was captured."
                target?.delete()
            }
        } catch (error: IOException) {
            lastErrorText = "The recording could not be finished."
        } finally {
            output?.let { runCatching { it.close() } }
            output = null
            target = null
            startedAtMs = 0L
        }
        return result
    }

    /** Abandons a capture and deletes whatever was written. */
    fun discard() {
        val file = target
        stop()
        file?.delete()
        lastErrorText = null
    }

    private fun releaseEffects() {
        for (effect in effects) runCatching { effect.close() }
        effects.clear()
    }

    private fun releaseAll() {
        recording = false
        releaseEffects()
        output?.let { runCatching { it.close() } }
        output = null
        record?.let { runCatching { it.release() } }
        record = null
        worker = null
        target = null
        startedAtMs = 0L
    }

    /** `AudioEffect` is a `Closeable` in spirit but not in signature, so it is wrapped. */
    private class AutoCloseableRef(private val effect: android.media.audiofx.AudioEffect) : AutoCloseable {
        override fun close() {
            runCatching { effect.enabled = false }
            runCatching { effect.release() }
        }
    }

    companion object {
        /** 44.1 kHz mono: the studio rate, so nothing is resampled on the way in. */
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 1

        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_FRAME = 2
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_BYTES = 44L
        private const val WORKER_JOIN_MS = 1_500L

        /** Longest side this app will press: eight minutes of mono 44.1 kHz. */
        const val MAX_RECORDING_MS = 8L * 60L * 1000L
    }
}
