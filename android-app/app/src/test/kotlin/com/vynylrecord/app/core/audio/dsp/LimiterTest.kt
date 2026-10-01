package com.vynylrecord.app.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The last stage before the master leaves the engine.
 *
 * The rule the app promises is a final peak around -1 dBFS with no hard clipping, so the tests here are
 * deliberately about the ceiling and about what happens to a transient that arrives with no warning.
 */
class LimiterTest {

    private val sampleRate = 44_100

    private fun unit(ceiling: Float = 0.891f, lookAheadMs: Float = 5f) =
        Limiter(ceiling, sampleRate, lookAheadMs, releaseMs = 120f)

    @Test
    fun `a steady overload lands on the ceiling`() {
        val limiter = unit()
        val out = FloatArray(2)
        var peak = 0f
        repeat(20_000) { i ->
            val sample = (1.6f * sin(2.0 * Math.PI * 250.0 * i / sampleRate)).toFloat()
            limiter.process(sample, sample, out, 0)
            peak = maxOf(peak, abs(out[0]))
        }
        assertTrue("peaked at $peak", peak <= 0.892f)
        assertTrue("the limiter muted the signal: " + peak, peak > 0.8f)
    }

    @Test
    fun `the look-ahead catches a transient before it arrives`() {
        val limiter = unit()
        val out = FloatArray(2)
        // Silence first, so the gain is resting.
        repeat(4_410) { limiter.process(0f, 0f, out, 0) }
        // Three frames of full scale: without look-ahead the first of them would slip through at unity.
        val peaks = ArrayList<Float>()
        repeat(3) {
            limiter.process(1.4f, 1.4f, out, 0)
            peaks += out[0]
        }
        assertTrue("the transient escaped at ${peaks.max()}", peaks.max() <= 1.0f)
    }

    @Test
    fun `a quiet signal is left alone`() {
        val limiter = unit()
        val out = FloatArray(2)
        var worst = 0f
        repeat(8_000) { i ->
            val sample = (0.2f * sin(2.0 * Math.PI * 400.0 * i / sampleRate)).toFloat()
            limiter.process(sample, sample, out, 0)
            // The delay is unconditional, so compare levels rather than sample-for-sample.
            worst = maxOf(worst, abs(out[0]))
        }
        assertTrue("a quiet signal came out at $worst", worst in 0.19f..0.21f)
    }

    @Test
    fun `both channels are limited by the same gain`() {
        val limiter = unit()
        val out = FloatArray(2)
        // Left is loud, right is quiet: the image must not wobble when only one side is hot.
        repeat(4_000) { limiter.process(2f, 0.1f, out, 0) }
        val ratio = abs(out[1] / out[0])
        assertTrue("the quiet side was pulled by a different gain: ratio $ratio", abs(ratio - 0.05f) < 0.01f)
    }

    @Test
    fun `it releases back to unity`() {
        val limiter = unit()
        val out = FloatArray(2)
        repeat(4_000) { limiter.process(2f, 2f, out, 0) }
        val whilePressed = abs(out[0])
        repeat(40_000) { limiter.process(0.3f, 0.3f, out, 0) }
        val afterRelease = abs(out[0])
        assertTrue("still clamped at $afterRelease", afterRelease > whilePressed)
    }

    @Test
    fun `silence stays exactly silent`() {
        val limiter = unit()
        val out = FloatArray(2)
        repeat(2_000) { limiter.process(0f, 0f, out, 0) }
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    @Test
    fun `nothing non-finite can reach the master`() {
        val limiter = unit()
        val out = FloatArray(2)
        limiter.process(Float.NaN, Float.POSITIVE_INFINITY, out, 0)
        assertTrue(out[0].isFinite())
        assertTrue(out[1].isFinite())
        repeat(1_000) { limiter.process(4f, -4f, out, 0) }
        assertTrue(out[0].isFinite() && out[1].isFinite())
    }

    @Test
    fun `reset empties the delay line`() {
        val limiter = unit()
        val out = FloatArray(2)
        repeat(1_000) { limiter.process(1f, 1f, out, 0) }
        limiter.reset()
        limiter.process(0f, 0f, out, 0)
        assertEquals(0f, out[0], 0f)
    }
}
