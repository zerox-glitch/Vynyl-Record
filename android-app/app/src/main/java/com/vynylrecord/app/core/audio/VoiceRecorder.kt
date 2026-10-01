package com.vynylrecord.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.vynylrecord.app.core.audio.dsp.DspMath
import com.vynylrecord.app.core.audio.dsp.WavCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Capturing a voice.
 *
 * A memory is spoken once. The recorder's job is to lose as little of that as possible: capture mono from
 * the device microphone, write it to the record's own source file as 44.1 kHz stereo PCM, and never put
 * the whole thing in memory — a ten-minute memory is 100 MB as float, and a phone will kill an app that
 * asks for that.
 *
 * ## Pause means pause
 *
 * Pausing stops the file from growing *and* discards whatever the microphone captured while paused, so
 * resuming produces one continuous take with no silence in the middle and no drift. A recorder that kept
 * reading and then trimmed the pause out would leave the seam audible in the finished record, because the
 * vinyl bed is rendered over the seam as though nothing happened.
 *
 * ## Failure is a state, not an exception
 *
 * Microphones are borrowed: a phone call, another app, a device with no microphone at all. Every one of
 * those ends in a [State.Failed] with a sentence a person can act on, never in a crash and never in a
 * silent empty file.
 */
class VoiceRecorder(
    private val scope: CoroutineScope,
    private val maxDurationMs: Long = MAX_DURATION_MS,
) {

    sealed interface State {
        data object Idle : State
        data object Preparing : State
        data class Recording(val elapsedMs: Long, val level: Float) : State
        data class Paused(val elapsedMs: Long) : State
        data class Finished(val file: File, val durationMs: Long, val peak: Float) : State
        data class Failed(val message: String) : State
    }

    /** A live meter so the Studio can draw a level and a moving waveform while someone speaks. */
    data class Meter(
        val level: Float = 0f,
        val peakDb: Float = -60f,
        val elapsedMs: Long = 0L,
        val pauseCount: Int = 0,
    ) {
        /** What the level means, in the one word a person needs while speaking. */
        val clippingLabel: String
            get() = when {
                peakDb >= -0.5f -> "too loud"
                peakDb >= -3f -> "close to the limit"
                peakDb <= -45f -> "very quiet"
                peakDb <= -24f -> "quiet"
                else -> "good"
            }
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _meter = MutableStateFlow(Meter())
    val meter: StateFlow<Meter> = _meter.asStateFlow()

    /** The last ~8 seconds of level history, for the live waveform strip. */
    private val _levels = MutableStateFlow<List<Float>>(emptyList())
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    private var job: Job? = null
    private var record: AudioRecord? = null

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = false

    private var target: File? = null

    /**
     * Starts capturing into [target].
     *
     * Returns false when the microphone could not be opened at all, which is the one failure worth
     * reporting synchronously: the caller shows the permission rationale or the "this device has no
     * microphone" message instead of starting a screen that has nothing to record with.
     */
    @SuppressLint("MissingPermission")
    fun start(target: File): Boolean {
        if (job?.isActive == true) return false
        stopRequested = false
        paused = false
        this.target = target
        _levels.value = emptyList()
        _meter.value = Meter()

        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minimum <= 0) {
            _state.value = State.Failed("This device cannot capture audio at 44.1 kHz.")
            return false
        }
        val bufferBytes = max(minimum * 2, SAMPLE_RATE / 5 * BYTES_PER_SAMPLE)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferBytes,
            )
        } catch (error: Exception) {
            Log.w(TAG, "microphone could not be opened", error)
            _state.value = State.Failed("The microphone is not available.")
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            _state.value = State.Failed("The microphone is not available.")
            return false
        }

        record = recorder
        _state.value = State.Preparing
        job = scope.launch(Dispatchers.Default) { captureLoop(recorder, target) }
        return true
    }

    /** Holds the capture open and stops appending. */
    fun pause() {
        if (job?.isActive != true) return
        paused = true
        val elapsed = currentElapsed()
        _state.value = State.Paused(elapsed)
        _meter.value = _meter.value.copy(pauseCount = _meter.value.pauseCount + 1)
    }

    fun resume() {
        if (job?.isActive != true) return
        paused = false
    }

    val isPaused: Boolean get() = paused

    /** Ends the capture; the coroutine finalises the WAV header and publishes [State.Finished]. */
    fun stop() {
        stopRequested = true
        paused = false
    }

    /** Ends the capture and deletes what was written, for "start over". */
    fun cancel() {
        stopRequested = true
        paused = false
        job?.cancel()
        job = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        target?.delete()
        _state.value = State.Idle
    }

    fun release() {
        job?.cancel()
        job = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    private fun currentElapsed(): Long = _meter.value.elapsedMs

    private fun captureLoop(recorder: AudioRecord, target: File) {
        val writer = try {
            target.parentFile?.mkdirs()
            WavCodec.Writer(target, SAMPLE_RATE, 2, 16)
        } catch (error: Exception) {
            Log.w(TAG, "could not open the capture file", error)
            runCatching { recorder.release() }
            _state.value = State.Failed("There is no space to record into.")
            return
        }

        // Mono in, stereo out: the microphone is one channel and the record is two, so each captured
        // frame is written twice. Doing it here means nothing downstream has to know the source was mono.
        val mono = ShortArray(FRAMES_PER_READ)
        val stereo = FloatArray(FRAMES_PER_READ * 2)
        var framesWritten = 0L
        var peak = 0f
        var lastUiUpdate = 0L
        val levels = ArrayList<Float>(512)
        var lastLevelPush = 0L
        var levelSum = 0.0
        var levelCount = 0
        var levelPeak = 0f

        try {
            recorder.startRecording()
            _state.value = State.Recording(0L, 0f)

            while (scope.isActive && !stopRequested) {
                val read = recorder.read(mono, 0, mono.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    Log.w(TAG, "capture read returned $read")
                    _state.value = State.Failed("The microphone stopped responding.")
                    break
                }
                if (read == 0) continue

                if (paused) {
                    // Discarded, not buffered: a pause must not appear in the finished take.
                    continue
                }

                for (frame in 0 until read) {
                    val value = mono[frame] / 32_768f
                    stereo[frame * 2] = value
                    stereo[frame * 2 + 1] = value
                    val magnitude = abs(value)
                    if (magnitude > peak) peak = magnitude
                    if (magnitude > levelPeak) levelPeak = magnitude
                    levelSum += value.toDouble() * value
                }
                levelCount += read
                writer.write(stereo, 0, read)
                framesWritten += read

                val now = System.currentTimeMillis()
                if (now - lastLevelPush >= LEVEL_INTERVAL_MS) {
                    val rms = if (levelCount == 0) 0f else sqrt(levelSum / levelCount).toFloat()
                    levels += rms.coerceIn(0f, 1f)
                    if (levels.size > MAX_LEVEL_POINTS) levels.removeAt(0)
                    _levels.value = levels.toList()
                    _meter.value = _meter.value.copy(
                        level = rms,
                        peakDb = DspMath.linearToDb(levelPeak.coerceAtLeast(1e-6f)),
                        elapsedMs = framesWritten * 1000L / SAMPLE_RATE,
                        pauseCount = _meter.value.pauseCount,
                    )
                    lastLevelPush = now
                    levelSum = 0.0
                    levelCount = 0
                    levelPeak = 0f
                }
                if (now - lastUiUpdate >= UI_INTERVAL_MS) {
                    val elapsed = framesWritten * 1000L / SAMPLE_RATE
                    if (!paused) _state.value = State.Recording(elapsed, peak)
                    lastUiUpdate = now
                }

                if (framesWritten * 1000L / SAMPLE_RATE >= maxDurationMs) {
                    // The record's own limit. Stopping here is friendlier than failing at press time.
                    Log.i(TAG, "capture reached the maximum length")
                    break
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "capture failed", error)
            _state.value = State.Failed("Recording stopped unexpectedly: ${error.message}")
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { writer.finish() }
            record = null

            val durationMs = framesWritten * 1000L / SAMPLE_RATE
            val failed = _state.value is State.Failed
            if (failed || framesWritten <= 0L) {
                target.delete()
                if (!failed) _state.value = State.Failed("Nothing was recorded.")
            } else if (durationMs < MIN_DURATION_MS) {
                // A tap on the button is not a memory. Say so rather than pressing a half-second record.
                target.delete()
                _state.value = State.Failed("That was too short. Hold on for at least a second.")
            } else {
                _state.value = State.Finished(target, durationMs, peak)
            }
        }
    }

    private companion object {
        const val TAG = "VynylRecorder"

        /** The studio's rate; capturing at anything else would mean resampling every memory. */
        const val SAMPLE_RATE = 44_100
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
        const val FRAMES_PER_READ = 4_096

        /** The same ten minutes a record holds; see [com.vynylrecord.app.core.audio.render.RenderLimits]. */
        const val MAX_DURATION_MS = 10L * 60L * 1000L
        const val MIN_DURATION_MS = 700L

        const val LEVEL_INTERVAL_MS = 60L
        const val UI_INTERVAL_MS = 120L
        const val MAX_LEVEL_POINTS = 160
    }
}
