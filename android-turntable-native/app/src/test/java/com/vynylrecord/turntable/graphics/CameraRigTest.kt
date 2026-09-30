package com.vynylrecord.turntable.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Camera limits are a correctness requirement, not a polish item: the camera must never invert, dip
 * under the floor, or push the turntable out of frame, and gesture deltas must survive the trip from
 * the UI thread to the render thread.
 */
class CameraRigTest {

    private val snapshot = CameraRig.Snapshot()

    private fun CameraRig.settle(seconds: Float = 4f) {
        var elapsed = 0f
        while (elapsed < seconds) {
            update(1f / 60f)
            elapsed += 1f / 60f
        }
    }

    @Test
    fun `starts on the hero framing`() {
        val rig = CameraRig()
        rig.writeTo(snapshot)
        assertEquals(CameraPreset.HERO.azimuth, snapshot.azimuthDeg, 1e-4f)
        assertEquals(CameraPreset.HERO.elevation, snapshot.elevationDeg, 1e-4f)
        assertEquals(CameraPreset.HERO.distance, snapshot.distance, 1e-4f)
    }

    @Test
    fun `dragging changes the azimuth and elevation`() {
        val rig = CameraRig()
        rig.beginDrag()
        rig.drag(-120f, 40f, viewportHeight = 1200)
        rig.endDrag()
        rig.writeTo(snapshot)
        assertTrue(abs(snapshot.azimuthDeg - CameraPreset.HERO.azimuth) > 1e-3f)
        assertTrue(abs(snapshot.elevationDeg - CameraPreset.HERO.elevation) > 1e-3f)
    }

    @Test
    fun `elevation can never look under the floor`() {
        val rig = CameraRig()
        rig.beginDrag()
        // Drag far past any sane limit in both directions.
        rig.drag(0f, 100_000f, viewportHeight = 1000)
        rig.writeTo(snapshot)
        assertEquals(CameraRig.MIN_ELEVATION_DEG, snapshot.elevationDeg, 1e-3f)

        rig.drag(0f, -200_000f, viewportHeight = 1000)
        rig.writeTo(snapshot)
        assertEquals(CameraRig.MAX_ELEVATION_DEG, snapshot.elevationDeg, 1e-3f)
        assertTrue("the camera must stay above the deck", snapshot.elevationDeg > 0f)
        rig.endDrag()
    }

    @Test
    fun `zoom is clamped at both ends`() {
        val rig = CameraRig()
        rig.zoomBy(0.0001f)
        rig.writeTo(snapshot)
        assertEquals(CameraRig.MIN_DISTANCE, snapshot.distance, 1e-4f)
        assertTrue("the near plane must not enter the platter", snapshot.distance > 0.15f)

        rig.zoomBy(10_000f)
        rig.writeTo(snapshot)
        assertEquals(CameraRig.MAX_DISTANCE, snapshot.distance, 1e-4f)
    }

    @Test
    fun `panning keeps the deck in frame`() {
        val rig = CameraRig()
        rig.pan(100_000f, 100_000f, viewportHeight = 1000)
        rig.writeTo(snapshot)
        assertTrue("pan must be limited in X", abs(snapshot.targetX) <= 0.55f)
        assertTrue("pan must be limited in Z", abs(snapshot.targetZ) <= 0.55f)
        assertTrue("the target must stay near the deck", snapshot.targetY in 0f..0.4f)
    }

    @Test
    fun `reset returns to the hero framing`() {
        val rig = CameraRig()
        rig.applyPreset(CameraPreset.NEEDLE, instant = true)
        rig.writeTo(snapshot)
        assertTrue(abs(snapshot.distance - CameraPreset.HERO.distance) > 1e-3f)

        rig.reset(instant = true)
        rig.writeTo(snapshot)
        assertEquals(CameraPreset.HERO.azimuth, snapshot.azimuthDeg, 1e-4f)
        assertEquals(CameraPreset.HERO.distance, snapshot.distance, 1e-4f)
        assertEquals(CameraPreset.HERO.targetY, snapshot.targetY, 1e-4f)
    }

    @Test
    fun `every preset is inside the safety limits`() {
        for (preset in CameraPreset.entries) {
            assertTrue(
                "${preset.name} elevation ${preset.elevation} is outside the limits",
                preset.elevation in CameraRig.MIN_ELEVATION_DEG..CameraRig.MAX_ELEVATION_DEG,
            )
            assertTrue(
                "${preset.name} distance ${preset.distance} is outside the limits",
                preset.distance in CameraRig.MIN_DISTANCE..CameraRig.MAX_DISTANCE,
            )
            assertTrue("targets stay within the deck", abs(preset.targetX) < 0.4f && abs(preset.targetZ) < 0.4f)
        }
    }

    @Test
    fun `a slow idle orbit moves the camera when motion is allowed`() {
        val rig = CameraRig()
        rig.setReducedMotion(false)
        rig.writeTo(snapshot)
        val before = snapshot.azimuthDeg
        rig.settle(20f)
        rig.writeTo(snapshot)
        assertTrue(
            "the idle orbit should have moved by now (${before} -> ${snapshot.azimuthDeg})",
            abs(snapshot.azimuthDeg - before) > 0.5f,
        )
    }

    @Test
    fun `reduced motion disables the idle orbit`() {
        val rig = CameraRig()
        rig.setReducedMotion(true)
        rig.writeTo(snapshot)
        val before = snapshot.azimuthDeg
        rig.settle(30f)
        rig.writeTo(snapshot)
        assertEquals(before, snapshot.azimuthDeg, 1e-3f)
    }

    @Test
    fun `dragging pauses the idle orbit`() {
        val rig = CameraRig()
        rig.beginDrag()
        rig.writeTo(snapshot)
        val before = snapshot.azimuthDeg
        rig.settle(20f)
        rig.writeTo(snapshot)
        assertEquals("a finger on the glass wins", before, snapshot.azimuthDeg, 1e-3f)
        rig.endDrag()
    }

    @Test
    fun `inertia decays instead of running forever`() {
        val rig = CameraRig()
        rig.setAutoOrbitEnabled(false)
        rig.beginDrag()
        repeat(12) { rig.drag(-60f, 0f, viewportHeight = 1200) }
        rig.endDrag()

        rig.settle(0.15f)
        rig.writeTo(snapshot)
        val early = snapshot.azimuthDeg
        rig.settle(0.15f)
        rig.writeTo(snapshot)
        val firstDelta = abs(snapshot.azimuthDeg - early)

        rig.settle(6f)
        rig.writeTo(snapshot)
        val settled = snapshot.azimuthDeg
        rig.settle(0.5f)
        rig.writeTo(snapshot)
        val lastDelta = abs(snapshot.azimuthDeg - settled)

        assertTrue("inertia should be dying out, $firstDelta -> $lastDelta", lastDelta < firstDelta)
        assertTrue("inertia must stop eventually, last delta $lastDelta", lastDelta < 0.05f)
    }
}
