package com.vynylrecord.app.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The tape stage.
 *
 * What matters here is that the shaper stays polite: it must add harmonics without adding clipping, it
 * must be transparent when the preset asks for no drive, and it must not introduce a DC offset, because
 * the compressor after it would read that offset as signal.
 */
class SaturationTest {

    private val sampleRate = 44_100

    private fun sine(amplitude: Float, frequency: Double = 220.0, frames: Int = 8_192): FloatArray =
        FloatArray(frames) { i -> (amplitude * sin(2.0 * Math.PI * frequency * i / sampleRate)).toFloat() }

    @Test
    fun `drive adds harmonics that were not in the input`() {
        val clean = sine(0.4f)
        val saturator = Saturator(drive = 0.6f, mix = 1f, sampleRate = sampleRate)
        val shaped = FloatArray(clean.size) { saturator.process(clean[it]) }

        // A pure sine has no third harmonic; the shaper is measured by how much it creates.
        val cleanThird = harmonicLevel(clean, 660.0)
        val shapedThird = harmonicLevel(shaped, 660.0)
        assertTrue("clean third was $cleanThird, shaped $shapedThird", shapedThird > cleanThird * 8f)
    }

    @Test
    fun `a low drive setting is nearly transparent`() {
        val input = sine(0.5f)
        val saturator = Saturator(drive = 0.02f, mix = 0.5f, sampleRate = sampleRate)
        var worst = 0f
        input.forEach { sample ->
            val output = saturator.process(sample)
            worst = maxOf(worst, abs(output - sample))
        }
        assertTrue("a gentle preset moved the signal by $worst", worst < 0.05f)
    }

    @Test
    fun `every drive setting stays inside full scale`() {
        listOf(0f, 0.08f, 0.28f, 0.45f, 0.6f, 0.85f).forEach { drive ->
            val saturator = Saturator(drive = drive, mix = 1f, sampleRate = sampleRate)
            var peak = 0f
            sine(0.98f, frames = 20_000).forEach { sample ->
                val output = saturator.process(sample)
                assertTrue("drive $drive produced a non-finite sample", output.isFinite())
                peak = maxOf(peak, abs(output))
            }
            assertTrue("drive $drive peaked at $peak", peak <= 1.001f)
            assertTrue("drive $drive was silent", peak > 0.5f)
        }
    }

    @Test
    fun `the shaper is odd and memoryless`() {
        val saturator = Saturator(drive = 0.5f, mix = 1f, sampleRate = sampleRate)
        val positive = saturator.process(0.3f)
        saturator.reset()
        val negative = saturator.process(-0.3f)
        assertEquals(positive, -negative, 1e-5f)
    }

    @Test
    fun `no dc offset is left behind`() {
        val saturator = Saturator(drive = 0.7f, mix = 1f, sampleRate = sampleRate)
        var sum = 0.0
        val frames = 40_000
        repeat(frames) { i ->
            val input = (0.6f * sin(2.0 * Math.PI * 180.0 * i / sampleRate)).toFloat()
            sum += saturator.process(input).toDouble()
        }
        val mean = sum / frames
        assertTrue("the saturated output had a mean of $mean", abs(mean) < 1e-3)
    }

    @Test
    fun `silence stays silent and does not ring`() {
        val saturator = Saturator(drive = 0.9f, mix = 1f, sampleRate = sampleRate)
        // Poke it with a transient, then feed silence: a filter with a long tail would keep whispering.
        saturator.process(1f)
        var tail = 0f
        repeat(4_410) { tail = maxOf(tail, abs(saturator.process(0f))) }
        assertTrue("the saturation rang for $tail after the input stopped", tail < 1e-3f)
    }

    @Test
    fun `gain compensation keeps the level roughly where it started`() {
        val input = sine(0.5f)
        val saturator = Saturator(drive = 0.4f, mix = 1f, sampleRate = sampleRate)
        var peak = 0f
        input.forEach { peak = maxOf(peak, abs(saturator.process(it))) }
        assertTrue("compensation overshot: " + peak, peak in 0.35f..0.85f)
        assertFalse(saturator.isActive.not())
    }

    private fun harmonicLevel(buffer: FloatArray, frequency: Double): Double {
        var real = 0.0
        var imaginary = 0.0
        buffer.forEachIndexed { index, sample ->
            val angle = 2.0 * Math.PI * frequency * index / sampleRate
            real += sample * kotlin.math.cos(angle)
            imaginary += sample * kotlin.math.sin(angle)
        }
        return 2.0 * kotlin.math.sqrt(real * real + imaginary * imaginary) / buffer.size
    }
}
