package com.vynylrecord.app.core.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The arithmetic the whole DSP layer shares.
 *
 * Every filter here is a second-order section built from the Audio EQ Cookbook formulas, which is what
 * the web renderer's FFmpeg chain used too: `highpass=f=…:t=q:w=0.5` is a cookbook high-pass at Q=0.5,
 * `equalizer=f=…:t=q:w=2:g=…` is a cookbook peaking filter at Q=2. Reproducing the same formulas rather
 * than approximating them is what makes a record pressed on the phone sound like the same record
 * pressed in the web studio.
 */
object DspMath {

    /** -42 dB -> 0.0079. Silence floors clamp instead of returning zero, which would mute a layer. */
    fun dbToLinear(db: Float): Float = Math.pow(10.0, (db / 20.0)).toFloat()

    fun linearToDb(linear: Float): Float =
        if (linear <= 1e-9f) -120f else (20.0 * kotlin.math.log10(linear.toDouble())).toFloat()

    fun centsToRatio(cents: Float): Float = Math.pow(2.0, (cents / 1200.0)).toFloat()

    fun clamp(value: Float, min: Float, max: Float): Float =
        if (value < min) min else if (value > max) max else value

    /** The soft clipper used for saturation and for the bed's own safety ceiling. */
    fun softClip(value: Float, ceiling: Float): Float {
        if (ceiling <= 0f) return 0f
        return ceiling * tanh(value / ceiling)
    }

    /**
     * A second-order section in transposed direct form II.
     *
     * Transposed form is used because it keeps the state small and well-conditioned when the cutoff is
     * near Nyquist, which matters here: several presets roll off as high as 16.5 kHz at 44.1 kHz.
     */
    class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1 = 0f
        private var z2 = 0f

        fun reset() {
            z1 = 0f
            z2 = 0f
        }

        fun setCoefficients(nb0: Float, nb1: Float, nb2: Float, na0: Float, na1: Float, na2: Float) {
            val inv = if (abs(na0) < 1e-12f) 0f else 1f / na0
            b0 = nb0 * inv
            b1 = nb1 * inv
            b2 = nb2 * inv
            a1 = na1 * inv
            a2 = na2 * inv
        }

        fun process(input: Float): Float {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        fun lowPass(cutoffHz: Float, q: Float, sampleRate: Int) = design(cutoffHz, q, sampleRate, Type.LOW_PASS)

        fun highPass(cutoffHz: Float, q: Float, sampleRate: Int) = design(cutoffHz, q, sampleRate, Type.HIGH_PASS)

        fun peaking(cutoffHz: Float, q: Float, gainDb: Float, sampleRate: Int) =
            design(cutoffHz, q, sampleRate, Type.PEAK, gainDb)

        private enum class Type { LOW_PASS, HIGH_PASS, PEAK }

        private fun design(cutoffHz: Float, q: Float, sampleRate: Int, type: Type, gainDb: Float = 0f) {
            // Above Nyquist a cookbook filter folds back and rings; clamping to 0.45·fs is what FFmpeg
            // does internally and keeps a 16.5 kHz setting sane on a 44.1 kHz stream.
            val frequency = cutoffHz.coerceIn(1f, sampleRate * 0.45f)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val safeQ = q.coerceAtLeast(0.05f)
            val alpha = sinW0 / (2.0 * safeQ)
            val a = Math.pow(10.0, (gainDb / 40.0))

            when (type) {
                Type.LOW_PASS -> setCoefficients(
                    nb0 = ((1.0 - cosW0) / 2.0).toFloat(),
                    nb1 = (1.0 - cosW0).toFloat(),
                    nb2 = ((1.0 - cosW0) / 2.0).toFloat(),
                    na0 = (1.0 + alpha).toFloat(),
                    na1 = (-2.0 * cosW0).toFloat(),
                    na2 = (1.0 - alpha).toFloat(),
                )

                Type.HIGH_PASS -> setCoefficients(
                    nb0 = ((1.0 + cosW0) / 2.0).toFloat(),
                    nb1 = (-(1.0 + cosW0)).toFloat(),
                    nb2 = ((1.0 + cosW0) / 2.0).toFloat(),
                    na0 = (1.0 + alpha).toFloat(),
                    na1 = (-2.0 * cosW0).toFloat(),
                    na2 = (1.0 - alpha).toFloat(),
                )

                Type.PEAK -> setCoefficients(
                    nb0 = (1.0 + alpha * a).toFloat(),
                    nb1 = (-2.0 * cosW0).toFloat(),
                    nb2 = (1.0 - alpha * a).toFloat(),
                    na0 = (1.0 + alpha / a).toFloat(),
                    na1 = (-2.0 * cosW0).toFloat(),
                    na2 = (1.0 - alpha / a).toFloat(),
                )
            }
        }
    }

    /** A one-pole low-pass, used where a gentle slope is wanted rather than a resonant one. */
    class OnePole {
        private var coefficient = 0f
        private var state = 0f

        fun lowPass(cutoffHz: Float, sampleRate: Int) {
            coefficient = 1f - kotlin.math.exp(-2.0 * PI * cutoffHz / sampleRate).toFloat()
        }

        fun highPass(cutoffHz: Float, sampleRate: Int) {
            lowPass(cutoffHz, sampleRate)
            highPassMode = true
        }

        private var highPassMode = false

        fun reset() {
            state = 0f
        }

        fun process(input: Float): Float {
            state += coefficient * (input - state)
            return if (highPassMode) input - state else state
        }
    }

    /** First-order DC blocker. Every analogue stage drifts; this is what stops a rumble becoming DC. */
    class DcBlocker(private val pole: Float = 0.995f) {
        private var lastInput = 0f
        private var lastOutput = 0f

        fun process(input: Float): Float {
            val output = input - lastInput + pole * lastOutput
            lastInput = input
            lastOutput = output
            return output
        }
    }

    /** A Hann window, used by the waveform extractor. */
    fun hann(size: Int, index: Int): Float =
        (0.5 - 0.5 * cos(2.0 * PI * index / (size - 1).coerceAtLeast(1))).toFloat()

    /** Guard against a NaN or an infinity ever reaching the encoder. Returns silence instead. */
    fun sanitize(value: Float): Float = when {
        value.isNaN() -> 0f
        value == Float.POSITIVE_INFINITY -> 1f
        value == Float.NEGATIVE_INFINITY -> -1f
        else -> value
    }

    fun sanitize(buffer: FloatArray, count: Int = buffer.size) {
        for (index in 0 until count) {
            val value = buffer[index]
            if (!value.isFinite()) buffer[index] = sanitize(value)
        }
    }

    fun peak(buffer: FloatArray, count: Int = buffer.size): Float {
        var peak = 0f
        for (index in 0 until count) {
            val magnitude = abs(buffer[index])
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    fun rms(buffer: FloatArray, count: Int = buffer.size): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (index in 0 until count) {
            val value = buffer[index].toDouble()
            sum += value * value
        }
        return sqrt(sum / count).toFloat()
    }
}
