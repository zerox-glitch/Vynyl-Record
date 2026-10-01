package com.vynylrecord.app.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Two small stages with big consequences: the duck that keeps a background bed out of the way of a voice,
 * and the width control that makes an archival transfer sound like one microphone in a room.
 */
class DuckingAndStereoTest {

    private val sampleRate = 44_100

    @Test
    fun `silence does not duck the bed at all`() {
        val ducker = Ducker(maxDuck = 0.8f, sampleRate = sampleRate)
        var gain = 1f
        repeat(20_000) { gain = ducker.gainFor(0f) }
        assertEquals(1f, gain, 1e-3f)
    }

    @Test
    fun `a loud voice pushes the bed down without muting it`() {
        val ducker = Ducker(maxDuck = 0.75f, sampleRate = sampleRate)
        var gain = 1f
        repeat(30_000) { gain = ducker.gainFor(0.7f) }
        assertTrue("bed was not ducked: " + gain, gain < 0.5f)
        assertTrue("ducking buried the bed: " + gain, gain > 0.2f)
    }

    @Test
    fun `ducking follows the voice down again`() {
        val ducker = Ducker(maxDuck = 0.8f, sampleRate = sampleRate)
        var gain = 1f
        repeat(20_000) { gain = ducker.gainFor(0.6f) }
        val ducked = gain
        // The voice stops; the bed must come back within a sentence's pause.
        repeat((0.6 * sampleRate).toInt()) { gain = ducker.gainFor(0f) }
        assertTrue("bed stayed at $gain after the voice stopped (was $ducked)", gain > ducked + 0.2f)
    }

    @Test
    fun `a quiet voice ducks less than a loud one`() {
        val quiet = Ducker(maxDuck = 0.8f, sampleRate = sampleRate)
        val loud = Ducker(maxDuck = 0.8f, sampleRate = sampleRate)
        var quietGain = 1f
        var loudGain = 1f
        repeat(30_000) {
            quietGain = quiet.gainFor(0.003f)
            loudGain = loud.gainFor(0.4f)
        }
        assertTrue("quiet voice ducked as much as a shout: $quietGain vs $loudGain", quietGain > loudGain)
    }

    @Test
    fun `the duck never goes below its floor`() {
        val ducker = Ducker(maxDuck = 0.6f, sampleRate = sampleRate)
        var lowest = 1f
        repeat(60_000) { lowest = minOf(lowest, ducker.gainFor(1f)) }
        assertTrue("gain fell to $lowest", lowest >= 0.4f - 1e-3f)
    }

    @Test
    fun `the duck resets`() {
        val ducker = Ducker(maxDuck = 0.7f, sampleRate = sampleRate)
        repeat(20_000) { ducker.gainFor(0.5f) }
        ducker.reset()
        assertEquals(1f, ducker.gainFor(0f), 1e-3f)
    }

    @Test
    fun `a full width passes the stereo image through unchanged`() {
        val width = StereoWidth(1f)
        val out = FloatArray(2)
        width.process(0.8f, 0.2f, out, 0)
        assertEquals(0.8f, out[0], 1e-6f)
        assertEquals(0.2f, out[1], 1e-6f)
    }

    @Test
    fun `zero width collapses the image to mono`() {
        val width = StereoWidth(0f)
        val out = FloatArray(2)
        width.process(0.8f, 0.2f, out, 0)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(0f, out[0] - out[1], 0f)
    }

    @Test
    fun `narrowing an archival side keeps the mid intact`() {
        val wide = StereoWidth(1f)
        val narrow = StereoWidth(0.85f)
        val wideOut = FloatArray(2)
        val narrowOut = FloatArray(2)
        wide.process(0.6f, 0.4f, wideOut, 0)
        narrow.process(0.6f, 0.4f, narrowOut, 0)

        val wideMid = (wideOut[0] + wideOut[1]) * 0.5f
        val narrowMid = (narrowOut[0] + narrowOut[1]) * 0.5f
        assertEquals(wideMid, narrowMid, 1e-6f)
        assertTrue(abs(narrowOut[0] - narrowOut[1]) < abs(wideOut[0] - wideOut[1]))
    }

    @Test
    fun `width writes into the given offset`() {
        val width = StereoWidth(1f)
        val out = FloatArray(8)
        width.process(0.25f, -0.25f, out, 4)
        assertEquals(0.25f, out[4], 1e-6f)
        assertEquals(-0.25f, out[5], 1e-6f)
        assertEquals(0f, out[0], 0f)
    }
}
