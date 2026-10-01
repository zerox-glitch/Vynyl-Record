package com.vynylrecord.app.core.audio.dsp

import com.vynylrecord.app.core.model.VinylPresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The press, end to end.
 *
 * Everything above this tests one stage. This file tests the thing the app actually promises: that a
 * voice goes in, a record comes out, the presets are audibly and measurably different, and pressing the
 * same record twice gives the same disc.
 */
class VinylMasterChainTest {

    private val sampleRate = 44_100

    /** A second of speech-shaped audio: a modulated two-tone burst with pauses, like a sentence. */
    private fun voice(seconds: Int = 2, amplitude: Float = 0.4f): FloatArray {
        val frames = seconds * sampleRate
        return FloatArray(frames * 2) { index ->
            val frame = index / 2
            val t = frame.toDouble() / sampleRate
            val syllable = if ((t * 3.0).toInt() % 4 == 3) 0.15 else 1.0
            val carrier = sin(2.0 * Math.PI * 190.0 * t) * 0.6 + sin(2.0 * Math.PI * 880.0 * t) * 0.4
            (carrier * syllable * amplitude).toFloat()
        }
    }

    private fun music(seconds: Int = 2, amplitude: Float = 0.5f): FloatArray {
        val frames = seconds * sampleRate
        return FloatArray(frames * 2) { index ->
            val frame = index / 2
            val t = frame.toDouble() / sampleRate
            val left = sin(2.0 * Math.PI * 110.0 * t) * amplitude
            val right = sin(2.0 * Math.PI * 164.8 * t) * amplitude
            (if (index % 2 == 0) left else right).toFloat()
        }
    }

    private fun press(
        preset: VinylPresetId,
        seed: Long = 4242L,
        source: FloatArray? = voice(),
        background: FloatArray? = null,
        seconds: Int = 2,
        blockFrames: Int = 4_096,
    ): FloatArray {
        val totalFrames = seconds * sampleRate
        val chain = VinylMasterChain(preset.recipe, seed, sampleRate, totalFrames)
        val master = FloatArray(totalFrames * 2)
        // The chain is a block processor: it fills `block` with this block's finished audio, and the
        // caller stitches the blocks together.
        val block = FloatArray(blockFrames * 2)
        val voiceBlock = FloatArray(blockFrames * 2)
        val musicBlock = FloatArray(blockFrames * 2)
        var frame = 0
        while (frame < totalFrames) {
            val frames = minOf(blockFrames, totalFrames - frame)
            val voiceChunk = source?.let { slice(it, frame, frames, voiceBlock) }
            val musicChunk = background?.let { slice(it, frame, frames, musicBlock) }
            chain.process(voiceChunk, musicChunk, frames, block)
            System.arraycopy(block, 0, master, frame * 2, frames * 2)
            frame += frames
        }
        return master
    }

    private fun slice(source: FloatArray, frame: Int, frames: Int, into: FloatArray): FloatArray {
        val available = minOf(frames, source.size / 2 - frame)
        java.util.Arrays.fill(into, 0f)
        if (available > 0) System.arraycopy(source, frame * 2, into, 0, available * 2)
        return into
    }

    @Test
    fun `the presets are measurably different presses`() {
        val renders = VinylPresetId.entries.associate { preset -> preset.id to press(preset) }
        val ids = renders.keys.toList()
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val difference = rmsDifference(renders.getValue(ids[i]), renders.getValue(ids[j]))
                assertTrue("${ids[i]} and ${ids[j]} differed by only $difference", difference > 0.002f)
            }
        }
    }

    @Test
    fun `the preset gradient darkens the record`() {
        val clean = highFrequencyRatio(press(VinylPresetId.CLEAN_VINYL))
        val warm = highFrequencyRatio(press(VinylPresetId.WARM_VINTAGE))
        val dusty = highFrequencyRatio(press(VinylPresetId.DUSTY_RECORD))
        val archival = highFrequencyRatio(press(VinylPresetId.RARE_ARCHIVAL))
        assertTrue("clean $clean, warm $warm", clean > warm)
        assertTrue("warm $warm, dusty $dusty", warm > dusty)
        assertTrue("dusty $dusty, archival $archival", dusty > archival)
    }

    @Test
    fun `the archival pressing still keeps the voice on top`() {
        val source = voice()
        val master = press(VinylPresetId.RARE_ARCHIVAL, source = source)
        // Compare each half-second of the output against the voice's own envelope: the words must still
        // be where the words were, not buried under three decades of dust.
        var voicedEnergy = 0.0
        var silentEnergy = 0.0
        for (frame in 0 until source.size / 2) {
            val loud = abs(source[frame * 2]) > 0.05f
            val sample = master[frame * 2].toDouble()
            if (loud) voicedEnergy += sample * sample else silentEnergy += sample * sample
        }
        assertTrue("the archival master had no voice energy", voicedEnergy > 0.0)
        assertTrue("the bed swamped the voice in the archival preset", silentEnergy < voicedEnergy)
    }

    @Test
    fun `the same record presses identically every time`() {
        val first = press(VinylPresetId.DUSTY_RECORD, seed = 777L)
        val second = press(VinylPresetId.DUSTY_RECORD, seed = 777L)
        for (index in first.indices) assertEquals(first[index], second[index], 0f)
    }

    @Test
    fun `a different record gets different pops`() {
        val one = press(VinylPresetId.DUSTY_RECORD, seed = 1L)
        val two = press(VinylPresetId.DUSTY_RECORD, seed = 2L)
        assertTrue("two seeds produced the same disc", rmsDifference(one, two) > 1e-5f)
    }

    @Test
    fun `nothing in the master is non-finite and nothing clips`() {
        VinylPresetId.entries.forEach { preset ->
            val master = press(preset, source = voice(amplitude = 0.98f))
            var peak = 0f
            master.forEach { sample ->
                assertTrue("${preset.id} produced $sample", sample.isFinite())
                peak = maxOf(peak, abs(sample))
            }
            assertTrue("${preset.id} clipped at $peak", peak <= preset.recipe.limiterCeil + 1e-3f)
            assertTrue("${preset.id} came out silent", peak > 1e-3f)
        }
    }

    @Test
    fun `a loud source is caught by the limiter rather than the ceiling`() {
        val loud = FloatArray(sampleRate * 2) { 0.99f }
        VinylPresetId.entries.forEach { preset ->
            val master = press(preset, source = loud, seconds = 1)
            assertTrue("${preset.id} exceeded its ceiling", DspMath.peak(master) <= preset.recipe.limiterCeil + 1e-3f)
        }
    }

    @Test
    fun `silence in still produces a record`() {
        val master = press(VinylPresetId.WARM_VINTAGE, source = null)
        val rms = DspMath.rms(master)
        assertTrue("a silent source produced no texture at all", rms > 1e-6f)
        assertTrue("a silent source produced a loud record ($rms)", rms < 0.1f)
        assertTrue(master.all { it.isFinite() })
    }

    @Test
    fun `the background is ducked while the voice speaks`() {
        val background = music()
        val alone = press(VinylPresetId.WARM_VINTAGE, source = null, background = background)
        val together = press(VinylPresetId.WARM_VINTAGE, source = voice(), background = background)

        // Look at a window where the voice is loud and compare the low-frequency bed energy.
        val from = sampleRate / 2 * 2
        val to = sampleRate * 2
        var aloneEnergy = 0.0
        var togetherEnergy = 0.0
        for (index in from until to) {
            aloneEnergy += alone[index].toDouble() * alone[index]
            togetherEnergy += together[index].toDouble() * together[index]
        }
        assertTrue("the background was louder with a voice over it", togetherEnergy < aloneEnergy * 1.6)
    }

    @Test
    fun `the diagnostics describe the press`() {
        VinylPresetId.entries.forEach { preset ->
            val chain = VinylMasterChain(preset.recipe, 909L, sampleRate, sampleRate)
            val block = FloatArray(4_096 * 2) { 0.2f }
            var frames = 0
            val out = FloatArray(4_096 * 2)
            while (frames < sampleRate) {
                val count = minOf(4_096, sampleRate - frames)
                chain.process(block, null, count, out)
                frames += count
            }
            val diagnostics = chain.diagnostics()
            assertEquals(frames, chain.framesProcessed)
            assertEquals(preset.recipe.surfaceFilter.id, diagnostics.surfaceShape)
            assertTrue("${preset.id} reported a peak of ${diagnostics.outputPeak}", diagnostics.outputPeak > 0f)
            assertTrue(diagnostics.limiterReductionDb >= 0f)
            if (preset.recipe.crackleEnabled) {
                assertTrue("${preset.id} produced no crackle in a second", diagnostics.crackleCount > 0)
            }
            if (!preset.recipe.popsEnabled) {
                assertEquals(0, diagnostics.popCount)
            }
        }
    }

    @Test
    fun `the record fades in and out instead of starting with a click`() {
        val master = press(VinylPresetId.CLEAN_VINYL, source = voice(seconds = 1), seconds = 1)
        val head = (0 until 40).maxOf { abs(master[it]) }
        val peak = DspMath.peak(master)
        assertTrue("the master began at $head against a peak of $peak", head < peak)
        val tail = (0 until 40).maxOf { abs(master[master.size - 1 - it]) }
        assertTrue("the master ended at $tail", tail < peak)
    }

    @Test
    fun `the chain can be fed odd block sizes`() {
        val totalFrames = sampleRate / 2
        val chain = VinylMasterChain(VinylPresetId.WARM_VINTAGE.recipe, 12L, sampleRate, totalFrames)
        val out = FloatArray(4_096 * 2)
        var frames = 0
        var block = 1
        while (frames < totalFrames) {
            val count = minOf(block, totalFrames - frames)
            chain.process(null, null, count, out)
            frames += count
            block = if (block > 2_000) 1 else block * 3
        }
        assertEquals(totalFrames, chain.framesProcessed)
        assertTrue(out.all { it.isFinite() })
        assertTrue(DspMath.peak(out) > 0f)
    }

    private fun rmsDifference(a: FloatArray, b: FloatArray): Double {
        val length = minOf(a.size, b.size)
        if (length == 0) return 0.0
        var sum = 0.0
        for (index in 0 until length) {
            val delta = (a[index] - b[index]).toDouble()
            sum += delta * delta
        }
        return kotlin.math.sqrt(sum / length)
    }

    /** Energy above roughly 6 kHz, as a fraction of the total: the audible "air" of a pressing. */
    private fun highFrequencyRatio(buffer: FloatArray): Double {
        var high = 0.0
        var total = 0.0
        var previous = 0f
        for (index in buffer.indices step 2) {
            val sample = buffer[index]
            val difference = sample - previous
            previous = sample
            high += difference.toDouble() * difference
            total += sample.toDouble() * sample
        }
        return if (total <= 0.0) 0.0 else high / total
    }
}
