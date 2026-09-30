package com.vynylrecord.turntable.graphics.animation

import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.TonearmGeometry
import com.vynylrecord.turntable.model.TurntableSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The mechanism state machine, driven frame by frame like the renderer drives it.
 *
 * Every assertion here is about observable behaviour a reviewer can check on screen: the platter
 * ramps instead of jumping, the arm does not move before the platter is up to speed, the needle
 * lands once, pausing lifts the stylus but leaves the platter turning, and the motion is identical
 * at 30 Hz and 60 Hz.
 */
class TurntableAnimatorTest {

    private val frame = 1f / 60f

    private fun audioSnapshot(progress: Float = 0f, playing: Boolean = true): AudioSnapshot =
        AudioSnapshot().apply {
            isPrepared = true
            isPlaying = playing
            durationMs = 60_000L
            positionMs = (progress * durationMs).toLong()
        }

    private fun TurntableAnimator.advance(
        seconds: Float,
        audio: AudioSnapshot,
        step: Float = frame,
    ) {
        var elapsed = 0f
        while (elapsed < seconds) {
            update(step, audio)
            elapsed += step
        }
    }

    /** Runs until [condition] holds or [limitSeconds] elapse; returns the elapsed time. */
    private fun TurntableAnimator.advanceUntil(
        audio: AudioSnapshot,
        limitSeconds: Float = 8f,
        step: Float = frame,
        condition: (TurntableAnimator) -> Boolean,
    ): Float {
        var elapsed = 0f
        while (elapsed < limitSeconds && !condition(this)) {
            update(step, audio)
            elapsed += step
        }
        return elapsed
    }

    /** An animator with a record already seated on the platter, ready for a play request. */
    private fun seatedAnimator(): TurntableAnimator = TurntableAnimator().apply {
        val audio = audioSnapshot()
        setRecordAvailable(true)
        advance(2.5f, audio)
    }

    @Test
    fun `parks on the rest when nothing is loaded`() {
        val animator = TurntableAnimator()
        animator.advance(1f, AudioSnapshot())
        assertEquals(VisualPhase.IDLE, animator.phase)
        assertEquals(TurntableSpec.TONEARM_REST_ANGLE_DEG, animator.tonearmAngleDeg, 0.01f)
        assertEquals(1f, animator.tonearmLift01, 1e-4f)
        assertEquals(0f, animator.platterRpm, 1e-4f)
        assertFalse(animator.needleContact)
    }

    @Test
    fun `placing a record lowers it onto the mat`() {
        val animator = TurntableAnimator()
        val audio = audioSnapshot()
        animator.update(frame, audio)

        animator.setRecordAvailable(true)
        assertEquals(VisualPhase.LOADING_RECORD, animator.phase)
        assertEquals(TurntableAnimator.RECORD_INSERTION_HEIGHT, animator.recordOffsetY, 1e-5f)

        val elapsed = animator.advanceUntil(audio) { it.phase == VisualPhase.IDLE }
        assertTrue("settling must finish", elapsed < 3f)
        assertEquals(0f, animator.recordOffsetY, 1e-5f)
        assertEquals(0f, animator.recordInsertion01, 1e-5f)
        assertTrue("the record is on the deck", animator.hasRecord)
    }

    @Test
    fun `a play request during placement is honoured once the record lands`() {
        val animator = TurntableAnimator()
        val audio = audioSnapshot()
        animator.update(frame, audio)
        animator.setRecordAvailable(true)
        animator.requestPlay()
        assertEquals(VisualPhase.LOADING_RECORD, animator.phase)

        animator.advanceUntil(audio) { it.phase == VisualPhase.PLATTER_STARTING || it.platterRpm > 1f }
        assertTrue("the deferred play must start the platter", animator.platterRpm > 1f)
    }

    @Test
    fun `the platter accelerates instead of jumping to speed`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.requestPlay()
        assertEquals(VisualPhase.PLATTER_STARTING, animator.phase)

        val nominal = PlatterSpeed.THIRTY_THREE.rpm
        var previous = 0f
        var elapsed = 0f
        while (elapsed < 1.6f && animator.platterRpm < nominal * 0.985f) {
            animator.update(frame, audio)
            elapsed += frame
            val step = animator.platterRpm - previous
            assertTrue("speed must ramp, not jump (step $step)", step <= nominal * frame * 2f + 1e-3f)
            assertTrue("the platter only ever speeds up while starting", step >= -1e-4f)
            previous = animator.platterRpm
        }
        assertEquals(nominal, animator.platterRpm, nominal * 0.02f)
        assertTrue("spin-up should take about a second, took $elapsed", elapsed in 0.5f..1.6f)
    }

    @Test
    fun `rotation follows elapsed time at the selected speed`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.setSpeed(PlatterSpeed.THIRTY_THREE)
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }

        val before = animator.platterAngleDeg
        animator.advance(1f, audio)
        var travelled = animator.platterAngleDeg - before
        if (travelled < 0f) travelled += 360f
        // 33 1/3 rpm is 200 degrees per second.
        assertEquals(200f, travelled, 6f)
    }

    @Test
    fun `the arm waits for the platter before leaving its rest`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.requestPlay()

        animator.update(frame, audio)
        assertEquals(VisualPhase.PLATTER_STARTING, animator.phase)
        assertEquals(TurntableSpec.TONEARM_REST_ANGLE_DEG, animator.tonearmAngleDeg, 0.01f)

        animator.advanceUntil(audio) { it.phase != VisualPhase.PLATTER_STARTING }
        assertEquals(VisualPhase.TONEARM_MOVING, animator.phase)
        // The arm travels inward: the angle grows from the rest angle towards the lead-in.
        assertTrue(animator.tonearmAngleDeg > TurntableSpec.TONEARM_REST_ANGLE_DEG)
    }

    @Test
    fun `needle contact fires exactly once when the stylus lands`() {
        val animator = seatedAnimator()
        var contacts = 0
        animator.onNeedleContact = { contacts++ }
        val audio = audioSnapshot()
        animator.requestPlay()
        val elapsed = animator.advanceUntil(audio, limitSeconds = 8f) { it.phase == VisualPhase.PLAYING }

        assertEquals("audio must start on the landing frame", 1, contacts)
        assertTrue("the whole start-up should take a few seconds, took $elapsed", elapsed < 6f)
        assertTrue(animator.needleContact)
        assertEquals(0f, animator.tonearmLift01, 1e-4f)
    }

    @Test
    fun `the arm tracks audio progress while playing`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot(progress = 0.0f)
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }

        val later = audioSnapshot(progress = 0.75f)
        animator.advance(2f, later)
        assertEquals(TonearmGeometry.angleForProgress(0.75f), animator.tonearmAngleDeg, 0.4f)
    }

    @Test
    fun `pausing lifts the stylus but keeps the platter turning`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }

        animator.requestPause()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PAUSED }
        assertEquals(VisualPhase.PAUSED, animator.phase)
        assertEquals(1f, animator.tonearmLift01, 1e-3f)
        assertFalse(animator.needleContact)
        assertTrue(
            "the motor is never switched off for a pause",
            animator.platterRpm > PlatterSpeed.THIRTY_THREE.rpm * 0.9f,
        )
    }

    @Test
    fun `resuming drops the needle without restarting the platter`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }
        animator.requestPause()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PAUSED }

        val rpmBefore = animator.platterRpm
        var minimumRpm = Float.MAX_VALUE
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }
        var elapsed = 0f
        while (elapsed < 0.5f) {
            minimumRpm = minOf(minimumRpm, animator.platterRpm)
            animator.update(frame, audio)
            elapsed += frame
        }
        assertEquals("the stylus is back in the groove", 0f, animator.tonearmLift01, 1e-3f)
        assertEquals("the platter never restarted", rpmBefore, minimumRpm, PlatterSpeed.THIRTY_THREE.rpm * 0.02f)
    }

    @Test
    fun `seeking glides the arm to the new position`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot(progress = 0.1f)
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }

        animator.requestSeek(0.9f)
        assertEquals(VisualPhase.SEEKING, animator.phase)
        val before = animator.tonearmAngleDeg
        animator.update(frame, audio)
        assertTrue("the arm must not teleport", abs(animator.tonearmAngleDeg - before) < 2f)

        // The transport really did jump, so progress follows the arm.
        val jumped = audioSnapshot(progress = 0.9f)
        animator.advanceUntil(jumped) { it.phase == VisualPhase.PLAYING }
        assertEquals(TonearmGeometry.angleForProgress(0.9f), animator.tonearmAngleDeg, 0.3f)
    }

    @Test
    fun `a finished side lifts the needle, returns the arm and stops the platter`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }

        animator.requestCompleted()
        animator.advanceUntil(audio, limitSeconds = 12f) { it.phase == VisualPhase.COMPLETED }
        assertEquals(VisualPhase.COMPLETED, animator.phase)
        assertEquals(TurntableSpec.TONEARM_REST_ANGLE_DEG, animator.tonearmAngleDeg, 0.2f)
        assertEquals(0f, animator.platterRpm, 1e-3f)
        assertFalse(animator.needleContact)
    }

    @Test
    fun `an error parks the mechanism the same way`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }

        animator.requestError()
        animator.advanceUntil(audio, limitSeconds = 12f) { it.phase == VisualPhase.ERROR }
        assertEquals(VisualPhase.ERROR, animator.phase)
        assertEquals(TurntableSpec.TONEARM_REST_ANGLE_DEG, animator.tonearmAngleDeg, 0.2f)
        assertEquals(0f, animator.platterRpm, 1e-3f)
    }

    @Test
    fun `audio stopping on its own lifts the needle`() {
        val animator = seatedAnimator()
        val playing = audioSnapshot()
        animator.requestPlay()
        animator.advanceUntil(playing) { it.phase == VisualPhase.PLAYING }

        val stopped = audioSnapshot(playing = false)
        animator.advanceUntil(stopped, limitSeconds = 6f) { it.phase == VisualPhase.PAUSED }
        assertEquals(VisualPhase.PAUSED, animator.phase)
    }

    @Test
    fun `the same wall time produces the same motion at 30 Hz and 60 Hz`() {
        val fast = seatedAnimator()
        val slow = seatedAnimator()
        val audio = audioSnapshot()
        fast.requestPlay()
        slow.requestPlay()

        fast.advance(2.4f, audio, step = 1f / 60f)
        slow.advance(2.4f, audio, step = 1f / 30f)

        assertEquals(fast.phase, slow.phase)
        assertEquals(fast.platterRpm, slow.platterRpm, 1f)
        val angleDelta = abs(fast.platterAngleDeg - slow.platterAngleDeg)
        assertTrue("platter angles must agree, delta $angleDelta", angleDelta < 3f || angleDelta > 357f)
        assertEquals(fast.tonearmAngleDeg, slow.tonearmAngleDeg, 0.6f)
    }

    @Test
    fun `reduced motion shortens the start-up sequence`() {
        val normal = seatedAnimator()
        val reduced = seatedAnimator().apply { setReducedMotion(true) }
        val audio = audioSnapshot()

        normal.requestPlay()
        reduced.requestPlay()
        val normalTime = normal.advanceUntil(audio, limitSeconds = 10f) { it.phase == VisualPhase.PLAYING }
        val reducedTime = reduced.advanceUntil(audio, limitSeconds = 10f) { it.phase == VisualPhase.PLAYING }

        assertTrue("reduced motion must reach playback sooner", reducedTime < normalTime)
        assertTrue("but the sequence still happens", reducedTime > 0.2f)
    }

    @Test
    fun `changing speed changes the platter target`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }

        animator.setSpeed(PlatterSpeed.FORTY_FIVE)
        animator.advance(2f, audio)
        assertEquals(PlatterSpeed.FORTY_FIVE.rpm, animator.platterRpm, 1f)
        assertEquals(22f, animator.speedSelectorAngleDeg, 0.5f)
    }

    @Test
    fun `the indicator lamp is brightest while the deck runs`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.advance(0.5f, audio)
        val parked = animator.lampLevel

        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }
        animator.advance(0.4f, audio)
        assertTrue("lamp should brighten: $parked -> ${animator.lampLevel}", animator.lampLevel > parked + 0.2f)
    }

    @Test
    fun `the stylus micro vibration stays tiny`() {
        val animator = seatedAnimator()
        val audio = audioSnapshot()
        animator.requestPlay()
        animator.advanceUntil(audio) { it.phase == VisualPhase.PLAYING }

        var peak = 0f
        var elapsed = 0f
        while (elapsed < 1f) {
            animator.update(frame, audio)
            elapsed += frame
            peak = maxOf(peak, abs(animator.needleMicroOffsetY))
        }
        assertTrue("micro vibration must stay sub-millimetre, peak was $peak", peak < 0.0006f)
        assertTrue("but it must be visible at all", peak > 1e-6f)
    }
}
