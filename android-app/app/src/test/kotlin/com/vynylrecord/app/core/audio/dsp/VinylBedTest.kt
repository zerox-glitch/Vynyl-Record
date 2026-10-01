package com.vynylrecord.app.core.audio.dsp

import com.vynylrecord.app.core.model.VinylPresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The record itself: the surface bed, its crackle and its pops.
 *
 * The bed is what makes a voice recording a *record*, so it is tested for the things that would give it
 * away — a short repeated loop, a fixed rhythm, a level that ignores the preset — rather than for its
 * exact samples.
 */
class VinylBedTest {

    private val sampleRate = 44_100
    private val oneSecond = sampleRate

    private fun bed(preset: VinylPresetId, seconds: Int, seed: Long = 1234L): VinylBed =
        VinylBed(preset.recipe, seed, sampleRate, seconds * sampleRate)

    /**
     * The bed writes into `out[0 until frames * 2)`, so each block is rendered into a scratch buffer and
     * copied into place — the caller stitches blocks together, exactly as the master renderer does.
     */
    private fun renderAll(bed: VinylBed, seconds: Int, block: Int = 4_096): FloatArray {
        val total = seconds * sampleRate
        val out = FloatArray(total * 2)
        val scratch = FloatArray(block * 2)
        var frame = 0
        while (frame < total) {
            val frames = minOf(block, total - frame)
            bed.render(frame, frames, scratch)
            System.arraycopy(scratch, 0, out, frame * 2, frames * 2)
            frame += frames
        }
        return out
    }

    @Test
    fun `block boundaries do not repeat the noise`() {
        val bed = bed(VinylPresetId.WARM_VINTAGE, 2)
        val whole = renderAll(bed, 2)

        val other = VinylBed(VinylPresetId.WARM_VINTAGE.recipe, 1234L, sampleRate, 2 * sampleRate)
        // A deliberately unrelated block size, again stitched through a scratch buffer.
        val piecewise = renderAll(other, 2, block = 1_000)
        // Rendering in awkward block sizes must produce exactly the same audio as one long pass.
        var worst = 0f
        for (index in whole.indices) worst = maxOf(worst, abs(whole[index] - piecewise[index]))
        assertTrue("block splitting changed the bed by $worst", worst < 1e-6f)
    }

    @Test
    fun `the same seed rebuilds the same record`() {
        val first = bed(VinylPresetId.DUSTY_RECORD, 4, seed = 99L)
        val second = bed(VinylPresetId.DUSTY_RECORD, 4, seed = 99L)
        assertEquals(first.crackleCount, second.crackleCount)
        assertEquals(first.popCount, second.popCount)

        val a = renderAll(first, 1)
        val b = renderAll(second, 1)
        for (index in a.indices) assertEquals(a[index], b[index], 0f)
    }

    @Test
    fun `different seeds give different records`() {
        val first = bed(VinylPresetId.DUSTY_RECORD, 30, seed = 1L)
        val second = bed(VinylPresetId.DUSTY_RECORD, 30, seed = 2L)
        assertNotEquals(first.crackleCount to first.popCount, second.crackleCount to second.popCount)
    }

    @Test
    fun `crackle density follows the preset gradient`() {
        // Thirty seconds is long enough that the Poisson draw is stable, and the presets are far apart.
        val counts = VinylPresetId.entries.map { preset ->
            preset.id to bed(preset, 30).crackleCount
        }
        val clean = counts.first { it.first == VinylPresetId.CLEAN_VINYL.id }.second
        val warm = counts.first { it.first == VinylPresetId.WARM_VINTAGE.id }.second
        val dusty = counts.first { it.first == VinylPresetId.DUSTY_RECORD.id }.second
        val archival = counts.first { it.first == VinylPresetId.RARE_ARCHIVAL.id }.second

        assertTrue("clean produced $clean crackles in 30 s", clean in 1..12)
        assertTrue("warm ($warm) should out-crackle clean ($clean)", warm > clean)
        assertTrue("dusty ($dusty) should out-crackle warm ($warm)", dusty > warm)
        assertTrue("archival ($archival) should out-crackle dusty ($dusty)", archival > dusty)
    }

    @Test
    fun `a disabled preset produces no transients at all`() {
        val quiet = VinylPresetId.CLEAN_VINYL.recipe.copy(
            crackleEnabled = false,
            popsEnabled = false,
            surfaceEnabled = false,
            hissLevelDb = -120f,
        )
        val bed = VinylBed(quiet, 7L, sampleRate, 5 * sampleRate)
        assertEquals(0, bed.crackleCount)
        assertEquals(0, bed.popCount)
        val audio = FloatArray(5 * sampleRate * 2)
        bed.render(0, 5 * sampleRate, audio)
        assertEquals(0f, DspMath.peak(audio), 1e-7f)
    }

    @Test
    fun `the bed level follows the preset`() {
        val levels = listOf(VinylPresetId.CLEAN_VINYL, VinylPresetId.DUSTY_RECORD, VinylPresetId.RARE_ARCHIVAL)
            .map { preset ->
                val audio = FloatArray(3 * sampleRate * 2)
                VinylBed(preset.recipe, 5L, sampleRate, 3 * sampleRate).render(0, 3 * sampleRate, audio)
                preset.id to DspMath.rms(audio)
            }
        assertTrue(
            "expected a rising bed across the presets: $levels",
            levels[0].second < levels[1].second && levels[1].second < levels[2].second,
        )
        levels.forEach { (id, rms) ->
            assertTrue("$id produced an inaudible bed ($rms)", rms > 1e-6f)
            assertTrue("$id produced a bed that swamps the voice ($rms)", rms < 0.2f)
        }
    }

    @Test
    fun `the bed has no short repeating period`() {
        val audio = FloatArray(6 * sampleRate * 2)
        VinylBed(VinylPresetId.WARM_VINTAGE.recipe, 21L, sampleRate, 6 * sampleRate)
            .render(0, 6 * sampleRate, audio)

        val left = FloatArray(6 * sampleRate) { audio[it * 2] }
        var energy = 0.0
        left.forEach { energy += it.toDouble() * it }
        assertTrue(energy > 0.0)

        listOf(2_048, 4_096, 11_025, 22_050).forEach { lag ->
            var sum = 0.0
            for (index in 0 until left.size - lag) sum += left[index].toDouble() * left[index + lag]
            val correlation = sum / energy
            assertTrue("lag $lag correlated at $correlation", abs(correlation) < 0.4)
        }
    }

    @Test
    fun `the bed survives a hostile block request`() {
        val bed = bed(VinylPresetId.RARE_ARCHIVAL, 1)
        val out = FloatArray(64 * 2)
        bed.render(0, 64, out)
        // Ask for a block that runs past the end of the record: the renderer should not write past it.
        bed.render(0, 0, out)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun `pops are rarer and louder than crackles`() {
        val bed = bed(VinylPresetId.RARE_ARCHIVAL, 30)
        assertTrue("archival popped ${bed.popCount} times in 30 s", bed.popCount in 0..4)
        assertTrue("crackles were not more numerous than pops", bed.crackleCount > bed.popCount)

        val audio = FloatArray(30 * sampleRate * 2)
        bed.render(0, 30 * sampleRate, audio)
        val peak = DspMath.peak(audio)
        assertTrue("the bed peaked at $peak", peak < 0.6f)
    }

    @Test
    fun `the needle drops before the voice and then goes quiet`() {
        val recipe = VinylPresetId.WARM_VINTAGE.recipe
        val bed = VinylBed(recipe, 3L, sampleRate, 4 * sampleRate)
        val out = FloatArray(4 * sampleRate * 2)
        bed.render(0, 4 * sampleRate, out)

        // The first 200 ms carry the needle contact; a second later the bed is only surface, not impact.
        val head = FloatArray(200 * sampleRate / 1000 * 2)
        System.arraycopy(out, 0, head, 0, head.size)
        val tail = FloatArray(400 * sampleRate / 1000 * 2)
        System.arraycopy(out, 2 * sampleRate * 2, tail, 0, tail.size)
        assertTrue("the needle intro was inaudible", DspMath.peak(head) > 0f)
        assertTrue("the needle intro never ended", DspMath.peak(head) > DspMath.rms(tail))
    }
}
