package com.vynylrecord.app.core.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Saturation: the analogue stage, and the first thing that makes a voice sound like it was cut rather
 * than recorded.
 *
 * A `tanh` waveshaper with a dry/wet blend and an output compensation, so a preset can turn the drive up
 * without also turning the record down — the classic mistake being to compensate with the input gain,
 * which changes the character instead of the level.
 */
class Saturator(
    private val drive: Float,
    private val mix: Float,
    private val sampleRate: Int,
) {
    private val compensation = if (drive > 0f) 1f / tanh(drive) else 1f
    private val dc = DspMath.DcBlocker()
    // Saturation generates harmonics above the band; the low-pass keeps them from aliasing back down
    // as a metallic edge, which is the audible difference between "warm" and "gritty" here.
    private val antiAlias = DspMath.Biquad().apply {
        lowPass(16_000f, 0.707f, sampleRate)
    }

    val isActive: Boolean get() = drive > 0.001f && mix > 0.001f

    fun process(input: Float): Float {
        if (!isActive) return input
        val wet = tanh(input * (1f + drive * 3.2f)) * compensation
        return antiAlias.process(dc.process(input + (wet - input) * mix.coerceIn(0f, 1f)))
    }

    fun reset() {
        dc.reset()
        antiAlias.reset()
    }
}

/**
 * A feed-forward compressor with a soft knee.
 *
 * Used twice in the chain and for two different jobs: gently on the voice before the wow and flutter,
 * and more firmly across the mix as the preset's compander. The implementation is the same; only the
 * settings differ, which is why there is one class rather than two.
 */
class Compressor(
    private val thresholdDb: Float,
    private val ratio: Float,
    private val attackSeconds: Float,
    private val releaseSeconds: Float,
    private val sampleRate: Int,
    /** Wider knee = more gradual onset. 6 dB is musical; 0 dB is a hard knee. */
    private val kneeDb: Float = 6f,
    private val makeupGain: Float = 1f,
) {
    private val attackCoefficient = exp(-1.0 / (attackSeconds.coerceAtLeast(1e-4f) * sampleRate))
    private val releaseCoefficient = exp(-1.0 / (releaseSeconds.coerceAtLeast(1e-4f) * sampleRate))
    private var envelope = 0f

    private val isActive: Boolean get() = ratio > 1.001f

    fun process(input: Float): Float {
        if (!isActive) return input
        val level = abs(input)
        // Peak-following envelope: instant up, exponential down.
        val coefficient = if (level > envelope) attackCoefficient else releaseCoefficient
        envelope = level + coefficient * (envelope - level)

        val levelDb = DspMath.linearToDb(envelope)
        val over = levelDb - thresholdDb
        val gainReductionDb = when {
            over <= -kneeDb / 2f -> 0f
            over >= kneeDb / 2f -> over - over / ratio
            else -> {
                // Quadratic interpolation across the knee, the standard soft-knee form.
                val x = over + kneeDb / 2f
                val compressed = x - (x * x) / (2f * kneeDb) * (1f - 1f / ratio) * 2f
                compressed - over + over / ratio
            }
        }
        val gain = DspMath.dbToLinear(-gainReductionDb) * makeupGain
        return input * gain
    }

    /** The gain reduction currently applied, in dB (positive). Used by the tests and the diagnostics. */
    fun currentReductionDb(): Float {
        val levelDb = DspMath.linearToDb(envelope)
        val over = levelDb - thresholdDb
        return if (over <= 0f) 0f else over - over / ratio
    }

    fun reset() {
        envelope = 0f
    }
}

/**
 * Wow and flutter: the speed instability of a belt-driven platter and a slightly off-centre pressing.
 *
 * Both are the same mechanism — a delay line whose length is modulated by a sine — and they differ only
 * in rate and depth. Wow is around 0.05–0.3 Hz over tens of milliseconds of delay; flutter is 30–60 Hz
 * over microseconds. Doing them in one pass matters: two independent resamplers would compound, and a
 * compounding pitch modulation is exactly the seasick artefact both are supposed to avoid.
 *
 * The depth is derived from cents rather than set as a delay range, so a preset that says "6 cents"
 * really does detune by six cents, whichever sample rate the record is rendered at.
 */
class WowFlutter(
    private val wowDepthCents: Float,
    private val wowRatePerMin: Float,
    private val flutterDepthCents: Float,
    private val flutterRatePerMin: Float,
    private val sampleRate: Int,
) {
    private val wowRate = (wowRatePerMin / 60f).coerceIn(0.02f, 4f)
    private val flutterRate = (flutterRatePerMin / 60f).coerceIn(4f, 120f)

    /**
     * The peak delay deviation each oscillator needs.
     *
     * A read pointer moving at `1 + m(t)` where `m(t) = d·sin(2πft)` produces a delay of
     * `-(d/(2πf))·cos(2πft)`, so the swing is `d/(2πf)` seconds. Both terms are computed in seconds and
     * then in samples, which is why the numbers below are so different for wow and flutter even though
     * the code path is identical.
     */
    private val wowDeviationSamples: Float
    private val flutterDeviationSamples: Float
    private val baseDelaySamples: Float

    init {
        val wowRatio = DspMath.centsToRatio(wowDepthCents) - 1f
        val flutterRatio = DspMath.centsToRatio(flutterDepthCents) - 1f
        wowDeviationSamples = (wowRatio / (2.0 * Math.PI * wowRate)).toFloat() * sampleRate
        flutterDeviationSamples = (flutterRatio / (2.0 * Math.PI * flutterRate)).toFloat() * sampleRate
        // Enough line to hold the deepest swing plus a few samples of interpolation margin, sampled at
        // the top of the wow cycle so the buffer is big enough at every phase.
        val maxDeviation = abs(wowDeviationSamples) + abs(flutterDeviationSamples)
        baseDelaySamples = maxDeviation + 4f
    }

    private val lineSize = ((baseDelaySamples + abs(wowDeviationSamples) + abs(flutterDeviationSamples)) * 2f)
        .toInt() + 8
    private val line = FloatArray(lineSize * 2)
    private var writeIndex = 0
    private var phase = 0.0
    private var flutterPhase = 0.0

    val isActive: Boolean get() = wowDeviationSamples > 0.01f || flutterDeviationSamples > 0.01f

    fun process(left: Float, right: Float, out: FloatArray, outOffset: Int) {
        if (!isActive) {
            out[outOffset] = left
            out[outOffset + 1] = right
            writeIndex = (writeIndex + 1) % lineSize
            return
        }
        line[writeIndex * 2] = left
        line[writeIndex * 2 + 1] = right

        val wow = sin(phase) * wowDeviationSamples
        val flutter = sin(flutterPhase) * flutterDeviationSamples
        var delay = baseDelaySamples + wow + flutter
        if (delay < 1f) delay = 1f
        if (delay > lineSize - 3f) delay = lineSize - 3f

        val readPosition = writeIndex - delay + lineSize
        val index = readPosition.toInt()
        val fraction = readPosition - index
        val i0 = ((index % lineSize) + lineSize) % lineSize
        val i1 = (i0 + 1) % lineSize
        val i2 = (i0 + 2) % lineSize

        // Cubic (Catmull-Rom) interpolation: with a 40 Hz flutter term, linear interpolation adds a
        // faint buzz at the modulation rate that is easy to hear on a quiet vocal.
        out[outOffset] = cubic(
            line[((i0 - 1 + lineSize) % lineSize) * 2],
            line[i0 * 2], line[i1 * 2], line[i2 * 2], fraction,
        )
        out[outOffset + 1] = cubic(
            line[((i0 - 1 + lineSize) % lineSize) * 2 + 1],
            line[i0 * 2 + 1], line[i1 * 2 + 1], line[i2 * 2 + 1], fraction,
        )

        writeIndex = (writeIndex + 1) % lineSize
        // Deterministic phase advance: derived from the sample index, never from wall-clock time, so a
        // render is reproducible even if it is paused and resumed.
        phase += 2.0 * Math.PI * wowRate / sampleRate
        if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
        flutterPhase += 2.0 * Math.PI * flutterRate / sampleRate
        if (flutterPhase > 2.0 * Math.PI) flutterPhase -= 2.0 * Math.PI
    }

    private fun cubic(y0: Float, y1: Float, y2: Float, y3: Float, t: Float): Float {
        val a = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
        val b = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c = -0.5f * y0 + 0.5f * y2
        return ((a * t + b) * t + c) * t + y1
    }

    fun reset() {
        line.fill(0f)
        writeIndex = 0
        phase = 0.0
        flutterPhase = 0.0
    }
}

/**
 * A look-ahead brick-wall-ish limiter.
 *
 * The ceiling is a hard promise — no sample ever leaves above it — which needs look-ahead: the gain has
 * to already be down when a transient arrives, not react after it. The detector runs [lookAheadSamples]
 * ahead of the output, its gain is smoothed by a fast attack and a slow release, and the signal is
 * delayed by the same amount to line the two up.
 *
 * The stage after this one does nothing but fade the ends, so this is the last chance to catch a peak
 * that the character stages introduced.
 */
class Limiter(
    private val ceiling: Float,
    private val sampleRate: Int,
    lookAheadMs: Float = 5f,
    releaseMs: Float = 120f,
) {
    private val lookAhead = ((lookAheadMs / 1000f) * sampleRate).toInt().coerceAtLeast(1)
    private val delayLeft = FloatArray(lookAhead)
    private val delayRight = FloatArray(lookAhead)
    private var writeIndex = 0

    private val attackCoefficient = exp(-1.0 / (0.0005f * sampleRate))
    private val releaseCoefficient = exp(-1.0 / ((releaseMs / 1000f) * sampleRate))
    private var gain = 1f

    /**
     * Processes one stereo frame.
     *
     * The gain is computed from the *peak of both channels* so the image does not wobble when one side
     * has a transient: a limiter that ducks a single channel pulls the vocal to one side, which is far
     * more audible than the transient it was trying to catch.
     */
    fun process(left: Float, right: Float, out: FloatArray, outOffset: Int) {
        val peak = maxOf(abs(left), abs(right))
        val target = if (peak > ceiling) ceiling / peak else 1f

        // Look ahead: the detector sees the incoming sample, the audio leaves delayed by the same
        // amount, so the gain is already in place when the transient reaches the output.
        val coefficient = if (target < gain) attackCoefficient else releaseCoefficient
        gain = target + coefficient * (gain - target)
        if (gain > 1f) gain = 1f

        val delayedLeft = delayLeft[writeIndex]
        val delayedRight = delayRight[writeIndex]
        delayLeft[writeIndex] = left
        delayRight[writeIndex] = right
        writeIndex = (writeIndex + 1) % lookAhead

        out[outOffset] = DspMath.sanitize(delayedLeft * gain)
        out[outOffset + 1] = DspMath.sanitize(delayedRight * gain)
    }

    fun reset() {
        delayLeft.fill(0f)
        delayRight.fill(0f)
        writeIndex = 0
        gain = 1f
    }
}

/**
 * Ducking: the background gives way to the voice.
 *
 * The gain is driven by the voice's own envelope rather than by a fixed curve, so a quiet sentence ducks
 * less than a shout and the music comes back the moment the sentence ends. The alternative — a fixed
 * low music level — is what makes a background bed sound like a separate recording playing behind the
 * record instead of part of it.
 */
class Ducker(
    private val maxDuck: Float,
    private val sampleRate: Int,
    attackMs: Float = 60f,
    releaseMs: Float = 420f,
    private val thresholdDb: Float = -34f,
) {
    private val attackCoefficient = exp(-1.0 / ((attackMs / 1000f) * sampleRate))
    private val releaseCoefficient = exp(-1.0 / ((releaseMs / 1000f) * sampleRate))
    private var envelope = 0f
    private var ducked = 0f

    /**
     * @param voice the voice sample for this frame
     * @return a gain between [1 - maxDuck] and 1 for the background
     */
    fun gainFor(voice: Float): Float {
        val level = abs(voice)
        val coefficient = if (level > envelope) attackCoefficient else releaseCoefficient
        envelope = level + coefficient * (envelope - level)

        val levelDb = DspMath.linearToDb(envelope)
        // Fully open above -18 dBFS, fully ducked at the threshold; linear in between so the music
        // slides rather than steps.
        val target = ((levelDb - thresholdDb) / 16f).coerceIn(0f, 1f)
        val coefficient2 = if (target > ducked) attackCoefficient else releaseCoefficient
        ducked = target + coefficient2 * (ducked - target)
        return 1f - maxDuck * ducked
    }

    fun reset() {
        envelope = 0f
        ducked = 0f
    }
}

/**
 * Stereo width, matching FFmpeg's `stereotools=mlev=1:slev=w`.
 *
 * Old records are narrower than a modern mix, and the presets use that: an archival transfer at 0.85
 * sounds like a single microphone in a room, while a clean pressing at 1.0 is untouched.
 */
class StereoWidth(private val width: Float) {
    fun process(left: Float, right: Float, out: FloatArray, outOffset: Int) {
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f * width
        out[outOffset] = mid + side
        out[outOffset + 1] = mid - side
    }
}
