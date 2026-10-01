package com.vynylrecord.app.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** The shared arithmetic: levels, filters, and the sanitisers that keep NaN out of a master. */
class DspMathTest {

    private val sampleRate = 44_100

    @Test
    fun `decibels and linear gain round-trip`() {
        listOf(0f, -6f, -12f, -24f, -42f, -60f).forEach { db ->
            val linear = DspMath.dbToLinear(db)
            assertEquals(db.toDouble(), DspMath.linearToDb(linear).toDouble(), 1e-4)
        }
        assertEquals(1f, DspMath.dbToLinear(0f), 1e-6f)
        assertEquals(0.5f, DspMath.dbToLinear(-6.0206f), 1e-3f)
    }

    @Test
    fun `cents map to the ratio a turntable would`() {
        assertEquals(1f, DspMath.centsToRatio(0f), 1e-6f)
        assertEquals(1.0293f, DspMath.centsToRatio(50f), 1e-3f)
        assertEquals(2f, DspMath.centsToRatio(1200f), 1e-4f)
        assertEquals(0.5f, DspMath.centsToRatio(-1200f), 1e-4f)
    }

    @Test
    fun `clamp and soft clip keep their promises`() {
        assertEquals(0.5f, DspMath.clamp(0.5f, -1f, 1f), 0f)
        assertEquals(1f, DspMath.clamp(9f, -1f, 1f), 0f)
        assertEquals(-1f, DspMath.clamp(-9f, -1f, 1f), 0f)

        val ceiling = 0.96f
        listOf(-4f, -1f, 0f, 0.5f, 1f, 3.7f).forEach { input ->
            val output = DspMath.softClip(input, ceiling)
            assertTrue("softClip($input) = $output", abs(output) <= ceiling + 1e-6f)
            assertTrue(output.isFinite())
        }
        assertEquals(0f, DspMath.softClip(0f, ceiling), 1e-7f)
        // Below the ceiling the curve must be transparent, or the bed would be distorted for no reason.
        assertEquals(0.4f, DspMath.softClip(0.4f, ceiling), 1e-4f)
    }

    @Test
    fun `sanitize removes the values that would poison a render`() {
        assertEquals(0f, DspMath.sanitize(Float.NaN), 0f)
        assertEquals(1f, DspMath.sanitize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(-1f, DspMath.sanitize(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0.25f, DspMath.sanitize(0.25f), 0f)

        val buffer = floatArrayOf(Float.NaN, 0.5f, Float.POSITIVE_INFINITY, -0.5f, Float.NEGATIVE_INFINITY)
        DspMath.sanitize(buffer)
        assertTrue(buffer.none { it.isNaN() || it.isInfinite() })
    }

    @Test
    fun `peak and rms measure a known signal`() {
        val sine = FloatArray(4_410) { i -> (0.5f * sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toFloat() }
        assertEquals(0.5f, DspMath.peak(sine), 0.01f)
        assertEquals(0.3535f, DspMath.rms(sine), 0.01f)
        // The count argument is what the renderer uses to measure a partly filled block.
        assertEquals(0.5f, DspMath.peak(sine, 1_000), 0.05f)
        assertEquals(0f, DspMath.rms(FloatArray(64)), 0f)
    }

    @Test
    fun `hann is a window that starts and ends at zero`() {
        assertEquals(0f, DspMath.hann(16, 0), 1e-6f)
        assertEquals(0f, DspMath.hann(16, 15), 1e-6f)
        assertEquals(1f, DspMath.hann(16, 8), 1e-3f)
        assertEquals(1f, DspMath.hann(1, 0), 1e-6f)
    }

    @Test
    fun `a low-pass passes DC and removes a high tone`() {
        val biquad = DspMath.Biquad()
        biquad.lowPass(1_000f, 0.707f, sampleRate)

        var average = 0f
        repeat(4_000) { average += biquad.process(1f) }
        average /= 4_000f
        assertEquals(1f, average, 0.01f)

        biquad.reset()
        var energy = 0.0
        repeat(4_410) { i ->
            val input = sin(2.0 * Math.PI * 8_000.0 * i / sampleRate).toFloat()
            val output = biquad.process(input)
            energy += output.toDouble() * output
        }
        assertTrue("8 kHz leaked through a 1 kHz low-pass", energy < 1.0)
    }

    @Test
    fun `a high-pass passes a high tone and blocks DC`() {
        val biquad = DspMath.Biquad()
        biquad.highPass(200f, 0.707f, sampleRate)
        var average = 0f
        repeat(4_000) { average += biquad.process(1f) }
        average /= 4_000f
        assertEquals(0f, average, 0.01f)

        biquad.reset()
        var energy = 0.0
        repeat(4_410) { i ->
            val input = sin(2.0 * Math.PI * 2_000.0 * i / sampleRate).toFloat()
            val output = biquad.process(input)
            energy += output.toDouble() * output
        }
        assertTrue("2 kHz was killed by a 200 Hz high-pass", energy > 100.0)
    }

    @Test
    fun `a peaking band adds gain only around its centre`() {
        val biquad = DspMath.Biquad()
        biquad.peaking(3_000f, 2f, 6f, sampleRate)
        val atCentre = boostOf(biquad, 3_000f)
        val away = boostOf(biquad, 120f)
        assertTrue("centre boost was " + atCentre, atCentre > 3.5f)
        assertTrue("far band was boosted by " + away, away < 1.4f)
    }

    private fun boostOf(biquad: DspMath.Biquad, frequency: Double): Double {
        biquad.reset()
        var energy = 0.0
        repeat(20_000) { i ->
            val input = sin(2.0 * Math.PI * frequency * (i + 10_000) / sampleRate).toFloat()
            val output = biquad.process(input)
            energy += output.toDouble() * output
        }
        return kotlin.math.sqrt(energy / 20_000.0) * kotlin.math.sqrt(2.0)
    }

    @Test
    fun `filters survive a hostile input`() {
        val biquad = DspMath.Biquad()
        biquad.lowPass(4_000f, 0.9f, sampleRate)
        var last = 0f
        listOf(1f, -1f, 1f, -1f, 0f, 0.5f, -0.5f).forEach { last = biquad.process(it) }
        assertTrue(last.isFinite())

        val onePole = DspMath.OnePole()
        onePole.lowPass(800f, sampleRate)
        repeat(500) { onePole.process(if (it % 2 == 0) 1f else -1f) }
        assertTrue(onePole.process(1f).isFinite())
        onePole.highPass(800f, sampleRate)
        onePole.reset()
        assertTrue(onePole.process(1f).isFinite())
    }

    @Test
    fun `the dc blocker removes an offset and keeps the signal`() {
        val blocker = DspMath.DcBlocker()
        var last = 0f
        repeat(10_000) { last = blocker.process(0.5f) }
        assertTrue("a constant offset survived as " + last, abs(last) < 1e-3f)

        blocker.reset()
        var amplitude = 0f
        repeat(4_410) { i ->
            val output = blocker.process(sin(2.0 * Math.PI * 300.0 * i / sampleRate).toFloat())
            amplitude = maxOf(amplitude, abs(output))
        }
        assertTrue("the dc blocker ate the signal: " + amplitude, amplitude > 0.8f)
        assertFalse(amplitude > 1.01f)
    }
}
