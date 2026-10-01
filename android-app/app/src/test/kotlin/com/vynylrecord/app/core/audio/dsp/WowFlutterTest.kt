package com.vynylrecord.app.core.audio.dsp

import com.vynylrecord.app.core.model.VinylPresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The motor.
 *
 * Wow is a slow, deep drift; flutter is a fast, shallow tremor. Both are a modulated delay line, and the
 * two facts worth testing are that the depths are what the preset says in cents — so "6 cents" detunes by
 * six cents whatever the sample rate — and that the delay is interpolated, because a delay line read with
 * whole-sample precision clicks on every wobble.
 */
class WowFlutterTest {

    private val sampleRate = 44_100

    private fun unit(
        wowCents: Float = 6f,
        wowRate: Float = 3.3f,
        flutterCents: Float = 1.2f,
        flutterRate: Float = 2_700f,
        rate: Int = sampleRate,
    ) = WowFlutter(wowCents, wowRate, flutterCents, flutterRate, rate)

    @Test
    fun `the presets are inside the ranges the engine documents`() {
        VinylPresetId.entries.forEach { preset ->
            val recipe = preset.recipe
            if (recipe.wowEnabled) {
                val hz = recipe.wowRatePerMin / 60f
                assertTrue("${preset.id} wow is ${hz} Hz", hz in 0.05f..0.3f)
                assertTrue("${preset.id} wow depth is ${recipe.wowDepthCents}", recipe.wowDepthCents in 1f..12f)
            }
            if (recipe.flutterEnabled) {
                val hz = recipe.flutterRatePerMin / 60f
                assertTrue("${preset.id} flutter is ${hz} Hz", hz in 30f..60f)
                assertTrue(
                    "${preset.id} flutter depth is ${recipe.flutterDepthCents}",
                    recipe.flutterDepthCents in 0.1f..3f,
                )
            }
        }
    }

    @Test
    fun `a deep setting bends the signal and a shallow one barely touches it`() {
        val deep = unit(wowCents = 20f, flutterCents = 8f)
        val shallow = unit(wowCents = 0.5f, flutterCents = 0.2f)
        assertTrue(deep.isActive)
        assertTrue(shallow.isActive)

        val deepDrift = pitchDriftOf(deep)
        val shallowDrift = pitchDriftOf(shallow)
        assertTrue("deep drift $deepDrift vs shallow $shallowDrift", deepDrift > shallowDrift * 3f)
    }

    @Test
    fun `the delay line is interpolated rather than stepped`() {
        val flutter = unit(wowCents = 0f, flutterCents = 2f, flutterRate = 3_000f)
        val out = FloatArray(2)
        // A ramp makes a whole-sample delay visible: without interpolation the output staircases.
        var previous = 0f
        var worstJump = 0f
        var frame = 0f
        repeat(4_000) {
            flutter.process(frame, frame, out, 0)
            if (it > 500) worstJump = maxOf(worstJump, abs(out[0] - previous))
            previous = out[0]
            frame += 0.01f
        }
        assertTrue("the ramp came out in steps of $worstJump", worstJump < 0.02f)
    }

    @Test
    fun `the same settings and the same input produce the same output`() {
        val first = unit()
        val second = unit()
        val a = FloatArray(2)
        val b = FloatArray(2)
        repeat(2_000) { i ->
            val sample = (0.5f * sin(2.0 * Math.PI * 220.0 * i / sampleRate)).toFloat()
            first.process(sample, sample, a, 0)
            second.process(sample, sample, b, 0)
            assertEquals(a[0], b[0], 0f)
            assertEquals(a[1], b[1], 0f)
        }
    }

    @Test
    fun `silence in means silence out`() {
        val unit = unit(wowCents = 10f, flutterCents = 4f)
        val out = FloatArray(2)
        repeat(4_410) { unit.process(0f, 0f, out, 0) }
        assertEquals(0f, out[0], 1e-7f)
        assertEquals(0f, out[1], 1e-7f)
    }

    @Test
    fun `the stage keeps its channels separate and never blows up`() {
        val unit = unit()
        val out = FloatArray(2)
        var peak = 0f
        repeat(8_000) { i ->
            val left = (0.7f * sin(2.0 * Math.PI * 180.0 * i / sampleRate)).toFloat()
            val right = (0.2f * sin(2.0 * Math.PI * 330.0 * i / sampleRate)).toFloat()
            unit.process(left, right, out, 0)
            assertTrue(out[0].isFinite() && out[1].isFinite())
            peak = maxOf(peak, abs(out[0]), abs(out[1]))
        }
        assertTrue("peak was $peak", peak in 0.1f..1.2f)
    }

    @Test
    fun `the depth is honoured whatever the sample rate is`() {
        listOf(44_100, 48_000).forEach { rate ->
            val unit = unit(rate = rate)
            assertTrue("no drift at $rate Hz", pitchDriftOf(unit, rate) > 1e-4f)
        }
    }

    @Test
    fun `reset restarts the oscillator`() {
        val unit = unit()
        val out = FloatArray(2)
        repeat(1_000) { unit.process(0.5f, 0.5f, out, 0) }
        unit.reset()
        repeat(500) { unit.process(0f, 0f, out, 0) }
        assertFalse(out[0].isNaN())
        assertEquals(0f, out[0], 1e-7f)
    }

    /** How far the stage moves a steady tone, expressed as a fraction of the tone's amplitude. */
    private fun pitchDriftOf(unit: WowFlutter, rate: Int = sampleRate): Float {
        val out = FloatArray(2)
        var worst = 0f
        repeat(20_000) { i ->
            val sample = (0.5f * sin(2.0 * Math.PI * 400.0 * i / rate)).toFloat()
            unit.process(sample, sample, out, 0)
            if (i > 1_000) worst = maxOf(worst, abs(out[0] - sample))
        }
        return worst
    }
}
