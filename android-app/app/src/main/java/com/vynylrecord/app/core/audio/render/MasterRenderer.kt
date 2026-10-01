package com.vynylrecord.app.core.audio.render

import com.vynylrecord.app.core.audio.dsp.DspMath
import com.vynylrecord.app.core.audio.dsp.PcmSink
import com.vynylrecord.app.core.audio.dsp.PcmSource
import com.vynylrecord.app.core.audio.dsp.VinylMasterChain
import com.vynylrecord.app.core.model.VinylRecipe
import java.io.Closeable

/**
 * Runs the press chain over a recording and writes the result to a sink.
 *
 * This class is the whole of "render a record", and it has no Android dependency at all: it takes
 * sources, a sink and a recipe. That is what lets the DSP be tested on the JVM at full length — a
 * three-second fixture through the real chain, asserting the real numbers — instead of being tested only
 * through a device, which is where audio bugs normally survive to production.
 *
 * ## What it promises
 *
 *  * **A known length.** The output is exactly the input plus the needle lead-in and the tail, so the
 *    duration is correct by construction rather than trimmed afterwards.
 *  * **No clipping.** The limiter is the last stage before the fades, and [Result.peak] is measured on
 *    the samples that were actually written.
 *  * **No NaN or infinity.** Every sample passes through [DspMath.sanitize] before it reaches the sink,
 *    so a corrupted import cannot poison the encoder.
 *  * **Cancellation.** [render] checks the cancellation flag between blocks and returns
 *    [Result.Cancelled] without finalising the sink, which is what leaves a partial file that the
 *    cleanup step can delete rather than a half-valid record.
 */
class MasterRenderer(
    private val sampleRate: Int,
    private val blockFrames: Int = 8_192,
) {

    /** What a render produced, or why it stopped. */
    sealed interface Result {
        data class Completed(
            val framesWritten: Int,
            val durationMs: Long,
            val peak: Float,
            val crackleCount: Int,
            val popCount: Int,
            val limiterReductionDb: Float,
            val diagnostics: String,
        ) : Result

        data class Cancelled(val framesWritten: Int) : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Renders [voice] (optionally mixed with [music]) into [sink].
     *
     * @param leadInFrames frames of needle lead-in before the voice starts
     * @param onProgress called with 0..1 after every block
     * @param isCancelled polled between blocks; a render that is cancelled leaves the sink unfinished
     */
    fun render(
        voice: PcmSource,
        music: PcmSource?,
        recipe: VinylRecipe,
        seed: Long,
        sink: PcmSink,
        leadInFrames: Int = 0,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        if (voice.sampleRate != sampleRate) {
            return Result.Failed("source is ${voice.sampleRate} Hz but the studio renders at $sampleRate Hz")
        }

        val voiceFrames = voice.frameCount
        if (voiceFrames <= 0) return Result.Failed("the source has no audio in it")

        val totalFrames = voiceFrames + leadInFrames
        val chain = VinylMasterChain(recipe, seed, sampleRate, totalFrames)

        val voiceBlock = FloatArray(blockFrames * 2)
        val musicBlock = FloatArray(blockFrames * 2)
        val outBlock = FloatArray(blockFrames * 2)

        var written = 0
        var frame = 0
        return try {
            while (written < totalFrames) {
                if (isCancelled()) return Result.Cancelled(written)
                val frames = minOf(blockFrames, totalFrames - written)

                // The lead-in is silence with the record already turning, so the voice simply starts
                // later rather than being padded by the mixer.
                if (frame < leadInFrames && voice != null) {
                    val silent = minOf(frames, leadInFrames - frame)
                    java.util.Arrays.fill(voiceBlock, 0, silent * 2, 0f)
                    val fromVoice = frames - silent
                    if (fromVoice > 0) readVoiced(voice, voiceBlock, silent, fromVoice)
                } else {
                    readVoiced(voice, voiceBlock, 0, frames)
                }
                frame += frames

                if (music != null) {
                    val read = readFully(music, musicBlock, frames)
                    // A music source that ran short is treated as silence rather than as an error: the
                    // record is the voice, and a bed that ends early must not end the record.
                    if (read < frames) {
                        java.util.Arrays.fill(musicBlock, read * 2, frames * 2, 0f)
                    }
                }

                chain.process(voiceBlock, music, frames, outBlock)
                sink.write(outBlock, 0, frames)
                written += frames
                onProgress(written.toFloat() / totalFrames)
            }
            sink.finish()
            val diagnostics = chain.diagnostics()
            Result.Completed(
                framesWritten = written,
                durationMs = written * 1000L / sampleRate,
                peak = diagnostics.outputPeak,
                crackleCount = diagnostics.crackleCount,
                popCount = diagnostics.popCount,
                limiterReductionDb = diagnostics.limiterReductionDb,
                diagnostics = "${diagnostics.crackleCount} crackles, ${diagnostics.popCount} pops, " +
                    "surface ${diagnostics.surfaceShape}, peak ${DspMath.linearToDb(diagnostics.outputPeak)} dBFS",
            )
        } catch (error: Exception) {
            Result.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun readVoiced(voice: PcmSource, target: FloatArray, offset: Int, frames: Int) {
        val read = readFully(voice, target, frames, offset)
        if (read < frames) {
            // A short source is padded rather than left holding the previous block's audio.
            java.util.Arrays.fill(target, (offset + read) * 2, (offset + frames) * 2, 0f)
        }
    }

    private fun readFully(source: PcmSource, target: FloatArray, frames: Int, offset: Int = 0): Int {
        var total = 0
        while (total < frames) {
            val read = source.read(target, (offset + total) * 2, frames - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    /** Closes sources and a sink together, ignoring the failures that closing a closed file produces. */
    fun closeAll(vararg closeables: Closeable?) {
        for (closeable in closeables) {
            runCatching { closeable?.close() }
        }
    }
}

/**
 * How long a record is allowed to be.
 *
 * The limit is not technical: a `.vynyl` bundle is a ZIP that has to be shareable, a WAV has a 4 GB RIFF
 * ceiling, and a render is a foreground job the user is waiting on. Ten minutes covers a long letter and
 * still finishes in a few seconds on a mid-range phone.
 */
object RenderLimits {
    const val MAX_RECORD_MS: Long = 10L * 60L * 1000L
    const val MIN_RECORD_MS: Long = 400L

    /** Below this the app warns before starting rather than failing afterwards. */
    const val LOW_STORAGE_BYTES: Long = 64L * 1024L * 1024L

    /** 16-bit stereo at 44.1 kHz, plus the artwork and the index. */
    fun estimateMasterBytes(durationMs: Long, sampleRate: Int = 44_100): Long =
        durationMs * sampleRate / 1000L * 4L + 44L
}

/**
 * Checks a finished render before it is allowed to be called finished.
 *
 * A record that appears in the library with a broken file behind it is worse than a record that failed
 * loudly, because the user only finds out when they try to play it — possibly months later, possibly in
 * front of the person it was for. Every check here is one that has actually prevented a bad record.
 */
object RenderValidator {

    enum class Failure { MISSING, TOO_SMALL, DURATION_MISMATCH, SILENT, CLIPPED, UNDECODABLE }

    data class Report(
        val ok: Boolean,
        val failure: Failure? = null,
        val message: String = "",
        val measuredDurationMs: Long = 0L,
        val measuredPeak: Float = 0f,
        val sizeBytes: Long = 0L,
    )

    /**
     * @param file the master file that was just written
     * @param expectedDurationMs what the render intended to produce
     * @param measuredDurationMs the duration the decoder reports, or 0 when it could not be measured
     * @param peak the highest sample magnitude written, or -1 when the encoder cannot report one
     */
    fun validate(
        file: java.io.File,
        sizeBytes: Long,
        expectedDurationMs: Long,
        measuredDurationMs: Long,
        peak: Float,
        decodable: Boolean,
    ): Report {
        if (!file.isFile || sizeBytes <= 44L) {
            return Report(false, Failure.MISSING, "the master file was not written")
        }
        // 400 ms of 16-bit stereo is about 70 KB; anything under that is a header and no audio.
        if (sizeBytes < 8_000L) {
            return Report(false, Failure.TOO_SMALL, "the master is only $sizeBytes bytes", sizeBytes = sizeBytes)
        }
        if (!decodable || measuredDurationMs <= 0L) {
            return Report(false, Failure.UNDECODABLE, "the master could not be read back", sizeBytes = sizeBytes)
        }
        // A decoder that reports a wildly different duration means the container and the audio disagree.
        val drift = kotlin.math.abs(measuredDurationMs - expectedDurationMs)
        if (drift > maxOf(1_500L, expectedDurationMs / 20L)) {
            return Report(
                false,
                Failure.DURATION_MISMATCH,
                "the master is $measuredDurationMs ms but should be about $expectedDurationMs ms",
                measuredDurationMs = measuredDurationMs,
                sizeBytes = sizeBytes,
            )
        }
        if (peak >= 0f && peak < 1e-4f) {
            return Report(
                false,
                Failure.SILENT,
                "the master is silent",
                measuredDurationMs = measuredDurationMs,
                measuredPeak = peak,
                sizeBytes = sizeBytes,
            )
        }
        if (peak > 1.0001f) {
            return Report(
                false,
                Failure.CLIPPED,
                "the master clips at $peak",
                measuredDurationMs = measuredDurationMs,
                measuredPeak = peak,
                sizeBytes = sizeBytes,
            )
        }
        return Report(true, measuredDurationMs = measuredDurationMs, measuredPeak = peak, sizeBytes = sizeBytes)
    }
}
