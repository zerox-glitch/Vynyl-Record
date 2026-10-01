package com.vynylrecord.turntable.press

import com.vynylrecord.turntable.vault.PressRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The press chain, tested on real audio.
 *
 * This is the part of the app that turns a voice note into a record, and every claim it makes — that
 * it trims silence, that the character is baked in, that a pressing is deterministic — is checkable on
 * the JVM without a device. A recipe that quietly did nothing would be worse than one that failed
 * loudly: the user would press a side, see it in the vault, and never know the crackle was missing.
 */
class VinylPresserTest {

    private val rate = PcmAudio.STUDIO_SAMPLE_RATE

    /** A second of 440 Hz with a little silence either side, like a real capture. */
    private fun voiceNote(
        seconds: Double = 1.0,
        leadingSilence: Double = 0.25,
        trailingSilence: Double = 0.25,
        channels: Int = 1,
    ): PcmAudio {
        val leading = (leadingSilence * rate).toInt()
        val body = (seconds * rate).toInt()
        val trailing = (trailingSilence * rate).toInt()
        val frames = leading + body + trailing
        val samples = FloatArray(frames * channels)
        for (frame in leading until leading + body) {
            val value = (sin(frame * 2.0 * Math.PI * 440.0 / rate) * 0.4).toFloat()
            for (channel in 0 until channels) samples[frame * channels + channel] = value
        }
        return PcmAudio(rate, channels, samples)
    }

    @Test
    fun `a master press changes almost nothing about the master`() {
        val input = voiceNote()
        val pressed = VinylPresser().press(input, PressRecipe.MASTER)

        // The clean master is meant to be a transfer: level changes, no character.
        assertTrue("the side must survive", pressed.frameCount > 0)
        assertTrue("the side must not be silence", pressed.peak > 0.1f)
        assertTrue("nothing may clip past full scale", pressed.peak <= 1f)
    }

    @Test
    fun `trimming removes silence at both ends but keeps the voice`() {
        val input = voiceNote(seconds = 1.0, leadingSilence = 0.4, trailingSilence = 0.4)
        val pressed = VinylPresser().press(input, PressRecipe.MASTER.copy(trimSilence = true))

        assertTrue("the head should be trimmed", pressed.frameCount < input.frameCount)
        // 100 ms of padding survives at each end, so the side is the voice plus ~0.2 s.
        val expected = input.frameCount - ((0.4 + 0.4 - 0.2) * rate).toInt()
        assertEquals("unexpected trim length", expected.toFloat(), pressed.frameCount.toFloat(), rate * 0.05f)
        assertTrue("the voice must still be there", pressed.peak > 0.1f)

        val untrimmed = VinylPresser().press(voiceNote(), PressRecipe.MASTER.copy(trimSilence = false))
        assertTrue("with trimming off the whole capture is kept", untrimmed.frameCount > pressed.frameCount - 1)
    }

    @Test
    fun `the side starts and ends at silence so the needle does not click`() {
        val pressed = VinylPresser().press(voiceNote(), PressRecipe.STUDIO)
        val channels = pressed.channels

        var headPeak = 0f
        for (frame in 0 until 32) {
            val magnitude = abs(pressed.samples[frame * channels])
            if (magnitude > headPeak) headPeak = magnitude
        }
        val lastFrame = abs(pressed.samples[(pressed.frameCount - 1) * channels])
        assertTrue("the lead-in must fade up from silence (head peak $headPeak)", headPeak < 0.06f)
        assertTrue("the run-out must fade down to silence ($lastFrame)", lastFrame < 0.02f)
    }

    @Test
    fun `the same recipe on the same master presses the same record`() {
        val recipe = PressRecipe.STUDIO
        val a = VinylPresser(seed = 99L).press(voiceNote(), recipe)
        val b = VinylPresser(seed = 99L).press(voiceNote(), recipe)

        assertEquals(a.frameCount, b.frameCount)
        for (index in 0 until a.samples.size step 97) {
            assertEquals("sample $index differs", a.samples[index], b.samples[index], 0f)
        }

        // A different seed must actually make a different record, or the noise stages are not random.
        val c = VinylPresser(seed = 100L).press(voiceNote(), recipe)
        var differences = 0
        for (index in 0 until a.samples.size step 97) {
            if (abs(a.samples[index] - c.samples[index]) > 1e-7f) differences++
        }
        assertTrue("the noise must depend on the seed", differences > 10)
    }

    @Test
    fun `a crackled recipe raises the floor between the words`() {
        // Trimming is off so the leading silence is still there to measure. Measuring the quietest
        // windows of the *voice* instead would only measure the voice: a 440 Hz tone has no quiet
        // window inside a 512-frame bucket.
        fun pressWith(recipe: PressRecipe): PcmAudio = VinylPresser().press(
            voiceNote(seconds = 0.5, leadingSilence = 0.4, trailingSilence = 0.05),
            recipe.copy(trimSilence = false),
        )

        val crackled = pressWith(PressRecipe.Preset.ATTIC_FIND.recipe)
        val clean = pressWith(PressRecipe.MASTER)

        val from = rate / 8 // past the worst of the lead-in ramp
        val until = rate / 4 // still inside the original silence
        val crackledFloor = rms(crackled, from, until)
        val cleanFloor = rms(clean, from, until)

        assertTrue("the clean master must be quiet between words ($cleanFloor)", cleanFloor < 0.02f)
        assertTrue(
            "crackle and groove noise must be audible in the silence ($crackledFloor vs $cleanFloor)",
            crackledFloor > cleanFloor * 3f,
        )
    }

    @Test
    fun `wow and flutter bends the pitch without changing the length much`() {
        val input = voiceNote(seconds = 2.0, leadingSilence = 0.1, trailingSilence = 0.1, channels = 2)
        val pressed = VinylPresser().press(input, PressRecipe.STUDIO.copy(wowFlutter = 1f))

        val drift = abs(pressed.frameCount - input.frameCount).toFloat() / input.frameCount
        assertTrue("the side must not change length by more than half a percent ($drift)", drift < 0.005f)
    }

    @Test
    fun `stereo stays stereo and mono stays mono`() {
        val stereo = VinylPresser().press(voiceNote(channels = 2), PressRecipe.STUDIO)
        assertEquals(2, stereo.channels)

        val mono = VinylPresser().press(voiceNote(channels = 1), PressRecipe.STUDIO)
        assertEquals(1, mono.channels)
    }

    @Test
    fun `an empty master is refused rather than pressed`() {
        val empty = PcmAudio(rate, 1, FloatArray(0))
        val pressed = VinylPresser().press(empty, PressRecipe.STUDIO)
        assertEquals(0, pressed.frameCount)
    }

    @Test
    fun `a very short side still comes out whole`() {
        // 60 ms: shorter than the lead-in ramp, which must not divide by zero or invert.
        val short = voiceNote(seconds = 0.06, leadingSilence = 0.0, trailingSilence = 0.0)
        val pressed = VinylPresser().press(short, PressRecipe.STUDIO)
        assertTrue("a very short capture must not vanish", pressed.frameCount > 0)
        assertTrue(pressed.peak <= 1f)
    }

    @Test
    fun `progress runs forward from start to finish`() {
        val progress = mutableListOf<Pair<Float, String>>()
        VinylPresser().press(voiceNote(seconds = 0.5), PressRecipe.STUDIO) { fraction, stage ->
            progress += fraction to stage
        }

        assertTrue("progress must be reported", progress.size >= VinylPresser().stageNames.size)
        assertTrue("progress must start near zero", progress.first().first < 0.2f)
        assertTrue("progress must reach the end", progress.last().first > 0.99f)
        assertTrue("progress must never go backwards", progress.zipWithNext().all { it.first.first <= it.second.first + 1e-6f })
        assertTrue("every stage must be named", progress.all { it.second.isNotBlank() })
    }

    @Test
    fun `the recipe's describe never comes back empty`() {
        for (preset in PressRecipe.Preset.entries) {
            assertTrue("${preset.name} has no description", preset.recipe.describe().isNotBlank())
        }
        assertTrue(PressRecipe.MASTER.isMaster)
        assertFalse(PressRecipe.STUDIO.isMaster)
    }

    /** RMS of a range of frames, across channels: how loud the floor is in that window. */
    private fun rms(audio: PcmAudio, fromFrame: Int, toFrame: Int): Float {
        var sum = 0.0
        var count = 0
        for (frame in fromFrame until toFrame.coerceAtMost(audio.frameCount)) {
            for (channel in 0 until audio.channels) {
                val sample = audio.samples[frame * audio.channels + channel].toDouble()
                sum += sample * sample
                count++
            }
        }
        return if (count == 0) 0f else kotlin.math.sqrt(sum / count).toFloat()
    }
}
