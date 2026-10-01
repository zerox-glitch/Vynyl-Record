package com.vynylrecord.app.core.audio.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The compressor: attack, release, soft knee and make-up gain.
 *
 * The chain uses it twice — gently on the voice, firmly across the mix — so the settings differ but the
 * behaviour under test does not. A compressor that pumps, or one that never lets go, is what makes a
 * three-minute recording tiring to listen to.
 */
class CompressorTest {

    private val sampleRate = 44_100

    private fun compressor(
        thresholdDb: Float = -18f,
        ratio: Float = 4f,
        attackSeconds: Float = 0.004f,
        releaseSeconds: Float = 0.12f,
        kneeDb: Float = 6f,
        makeupGain: Float = 1f,
    ) = Compressor(
        thresholdDb = thresholdDb,
        ratio = ratio,
        attackSeconds = attackSeconds,
        releaseSeconds = releaseSeconds,
        sampleRate = sampleRate,
        kneeDb = kneeDb,
        makeupGain = makeupGain,
    )

    @Test
    fun `a loud signal is reduced and a quiet one is not`() {
        val unit = compressor()
        // Loud for long enough that the envelope settles.
        repeat(20_000) { unit.process((0.9f * sin(it / 3.0)).toFloat()) }
        val loudReduction = unit.currentReductionDb()
        assertTrue("a loud passage was reduced by only $loudReduction dB", loudReduction > 1f)

        unit.reset()
        repeat(20_000) { unit.process((0.01f * sin(it / 3.0)).toFloat()) }
        val quietReduction = unit.currentReductionDb()
        assertTrue("a quiet passage was still compressed by $quietReduction dB", quietReduction < 0.5f)
    }

    @Test
    fun `the reduction follows the ratio on a steady tone`() {
        val unit = compressor(thresholdDb = -20f, ratio = 4f, kneeDb = 0f)
        // 0 dBFS tone: 20 dB over the threshold, so a 4:1 ratio lands about 15 dB down.
        var last = 0f
        repeat(40_000) { last = unit.process(0.999f) }
        val reduction = -DspMath.linearToDb(abs(last) / 0.999f)
        assertTrue("measured reduction was $reduction dB", reduction in 13f..17f)
    }

    @Test
    fun `a soft knee makes the transition gradual`() {
        val hard = compressor(thresholdDb = -20f, ratio = 4f, kneeDb = 0f)
        val soft = compressor(thresholdDb = -20f, ratio = 4f, kneeDb = 12f)

        val hardReduction = reductionAt(hard, 0.1f)
        val softReduction = reductionAt(soft, 0.1f)
        // At -20 dBFS, deep inside a 12 dB knee, the soft compressor is barely touching the signal yet.
        assertTrue("soft knee reduced by $softReduction dB at the threshold", softReduction < hardReduction)
        assertTrue("soft knee reduced by $softReduction dB", softReduction >= 0f)
    }

    @Test
    fun `attack is faster than release`() {
        val unit = compressor(attackSeconds = 0.002f, releaseSeconds = 0.4f)
        unit.reset()
        repeat(4_000) { unit.process(1f) }
        val settled = unit.currentReductionDb()

        unit.reset()
        repeat(20) { unit.process(1f) }
        val early = unit.currentReductionDb()
        assertTrue("the compressor clamped instantly: $early vs $settled", early < settled)

        // Now let it recover, then check that it does so slowly rather than snapping back.
        unit.reset()
        repeat(30_000) { unit.process(1f) }
        val engaged = unit.currentReductionDb()
        repeat(200) { unit.process(0f) }
        val justAfter = unit.currentReductionDb()
        assertTrue("release was instant", justAfter > engaged * 0.5f)
    }

    @Test
    fun `make-up gain restores the level`() {
        val plain = compressor(makeupGain = 1f)
        val lifted = compressor(makeupGain = 2f)
        var plainPeak = 0f
        var liftedPeak = 0f
        repeat(20_000) { i ->
            val input = (0.8f * sin(2.0 * Math.PI * 300.0 * i / sampleRate)).toFloat()
            plainPeak = maxOf(plainPeak, abs(plain.process(input)))
            liftedPeak = maxOf(liftedPeak, abs(lifted.process(input)))
        }
        assertTrue("make-up gain did nothing", liftedPeak > plainPeak * 1.5f)
        assertTrue(liftedPeak <= 4f)
    }

    @Test
    fun `the output never becomes non-finite`() {
        val unit = compressor(ratio = 20f)
        var value = 0f
        listOf(0f, 1f, -1f, 0.5f, 0f, 1f, 1f, 1f).forEach { _ -> repeat(1_000) { value = unit.process(1f) } }
        assertTrue(value.isFinite())
        assertTrue(abs(value) < 10f)
    }

    @Test
    fun `reset clears the envelope`() {
        val unit = compressor()
        repeat(20_000) { unit.process(1f) }
        assertTrue(unit.currentReductionDb() > 1f)
        unit.reset()
        assertTrue(unit.currentReductionDb() < 1e-3f)
    }

    private fun reductionAt(unit: Compressor, amplitude: Float): Float {
        repeat(20_000) { unit.process(amplitude) }
        return unit.currentReductionDb()
    }
}
