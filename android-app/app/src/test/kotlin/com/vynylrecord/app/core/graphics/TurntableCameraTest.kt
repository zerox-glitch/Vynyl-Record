package com.vynylrecord.app.core.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The camera.
 *
 * Two things matter here. The limits are real — a player that lets you drag the camera under the floor
 * shows the inside of the plinth, which is the moment a 3D scene stops being convincing — and the camera
 * must never be *inside* the deck, which is why the minimum distance is checked against the model's own
 * radius rather than trusted.
 */
class TurntableCameraTest {

    @Test
    fun `the camera opens on the default framing`() {
        val camera = TurntableCamera()
        assertEquals(TurntableCamera.DEFAULT_YAW, camera.yawDegrees, 1e-4f)
        assertEquals(TurntableCamera.DEFAULT_PITCH, camera.pitchDegrees, 1e-4f)
        assertEquals(TurntableCamera.DEFAULT_DISTANCE, camera.distance, 1e-4f)
        assertEquals(TurntableCamera.Preset.THREE_QUARTER, camera.nearestPreset())
    }

    @Test
    fun `pitch is clamped above the floor and below the top`() {
        val camera = TurntableCamera()
        camera.orbitBy(0f, -10_000f)
        assertEquals(TurntableCamera.MIN_PITCH, camera.pitchDegrees, 1e-3f)
        camera.orbitBy(0f, 10_000f)
        assertEquals(TurntableCamera.MAX_PITCH, camera.pitchDegrees, 1e-3f)
        assertTrue("the camera can see under the deck", camera.pitchDegrees > 0f)
    }

    @Test
    fun `yaw wraps instead of running away`() {
        val camera = TurntableCamera()
        repeat(50) { camera.orbitBy(1_000f, 0f) }
        assertTrue("yaw reached ${camera.yawDegrees}", camera.yawDegrees in 0f..360f)
        camera.orbitBy(-1_000_000f, 0f)
        assertTrue("yaw reached ${camera.yawDegrees}", camera.yawDegrees in 0f..360f)
    }

    @Test
    fun `zoom is clamped at both ends`() {
        val camera = TurntableCamera()
        camera.zoomBy(50f)
        assertEquals(TurntableCamera.MIN_DISTANCE, camera.distance, 1e-4f)
        camera.zoomBy(0.001f)
        assertEquals(TurntableCamera.MAX_DISTANCE, camera.distance, 1e-4f)
        // A degenerate pinch must be ignored rather than dividing by zero.
        val before = camera.distance
        camera.zoomBy(0f)
        camera.zoomBy(-1f)
        assertEquals(before, camera.distance, 1e-6f)
    }

    @Test
    fun `the camera never gets inside the deck`() {
        val camera = TurntableCamera()
        camera.zoomBy(100f)
        camera.buildMatrices(1_080, 2_400)
        // The plinth is about 0.45 m across, so its far corner is ~0.31 m from the centre.
        val distance = sqrt(
            camera.position.x * camera.position.x +
                camera.position.y * camera.position.y +
                camera.position.z * camera.position.z,
        )
        assertTrue("the camera is inside the plinth at $distance m", distance > 0.5f)

        // The plinth's own corner is the thing the camera must stay outside of.
        val corner = sqrt(
            (DeckDimensions.PLINTH_WIDTH / 2f) * (DeckDimensions.PLINTH_WIDTH / 2f) +
                (DeckDimensions.PLINTH_DEPTH / 2f) * (DeckDimensions.PLINTH_DEPTH / 2f),
        )
        assertTrue("the plinth is bigger than the closest camera position", TurntableCamera.MIN_DISTANCE > corner)
    }

    @Test
    fun `the presets are the four framings the player offers`() {
        assertEquals(4, TurntableCamera.Preset.ordered.size)
        assertEquals(
            listOf("Three-quarter view", "Front view", "Overhead view", "Close on the record"),
            TurntableCamera.Preset.ordered.map { it.label },
        )
        TurntableCamera.Preset.ordered.forEach { preset ->
            val camera = TurntableCamera()
            camera.apply(preset)
            assertTrue(preset.pitch in TurntableCamera.MIN_PITCH..TurntableCamera.MAX_PITCH)
            assertTrue(preset.distance in TurntableCamera.MIN_DISTANCE..TurntableCamera.MAX_DISTANCE)
            assertEquals(preset, camera.nearestPreset())
        }
    }

    @Test
    fun `cycling goes round the presets in order`() {
        val camera = TurntableCamera()
        val visited = ArrayList<TurntableCamera.Preset>()
        repeat(4) { visited += camera.cyclePreset() }
        assertEquals(TurntableCamera.Preset.ordered, visited)
        assertEquals(TurntableCamera.Preset.THREE_QUARTER, visited.last())
    }

    @Test
    fun `cycling never spins the long way round`() {
        val camera = TurntableCamera()
        // Park just past the front view and cycle: the move to the next preset must be a short one.
        camera.orbitBy(350f / TurntableCamera.DEGREES_PER_PIXEL_X, 0f)
        val before = camera.yawDegrees
        camera.cyclePreset()
        val travelled = abs(camera.yawDegrees - before)
        assertTrue("the camera travelled $travelled°", travelled < 180f)
    }

    @Test
    fun `reset returns to the opening framing`() {
        val camera = TurntableCamera()
        camera.orbitBy(400f, -120f)
        camera.zoomBy(2f)
        camera.reset()
        assertEquals(TurntableCamera.Preset.default.yaw, camera.yawDegrees, 1e-4f)
        assertEquals(TurntableCamera.Preset.default.pitch, camera.pitchDegrees, 1e-4f)
        assertEquals(TurntableCamera.Preset.default.distance, camera.distance, 1e-4f)
    }

    @Test
    fun `the matrices are rebuilt for the viewport and stay finite`() {
        val camera = TurntableCamera()
        listOf(1080 to 2400, 2400 to 1080, 800 to 800, 0 to 0).forEach { (width, height) ->
            camera.buildMatrices(width, height)
            assertTrue("a matrix was not finite at ${width}x$height", camera.viewProjectionMatrix.values.all { it.isFinite() })
            assertTrue(camera.position.x.isFinite() && camera.position.y.isFinite() && camera.position.z.isFinite())
        }
    }

    @Test
    fun `a wider viewport does not move the camera`() {
        val camera = TurntableCamera()
        camera.buildMatrices(1_080, 2_400)
        val portrait = Vec3().copyFrom(camera.position)
        camera.buildMatrices(2_400, 1_080)
        assertEquals(portrait.x, camera.position.x, 1e-6f)
        assertEquals(portrait.y, camera.position.y, 1e-6f)
        assertEquals(portrait.z, camera.position.z, 1e-6f)
    }

    @Test
    fun `auto orbit waits for the idle delay and then drifts`() {
        val camera = TurntableCamera()
        val start = camera.yawDegrees
        camera.autoOrbit = true
        camera.update(1f)
        assertEquals("the deck started drifting immediately", start, camera.yawDegrees, 1e-4f)

        repeat(400) { camera.update(0.05f) }
        assertNotEquals(start, camera.yawDegrees)
    }

    @Test
    fun `a finger on the deck stops the drift`() {
        val camera = TurntableCamera()
        camera.autoOrbit = true
        repeat(400) { camera.update(0.05f) }
        camera.isBeingTouched = true
        val held = camera.yawDegrees
        repeat(200) { camera.update(0.05f) }
        assertEquals("the deck drifted under the user's finger", held, camera.yawDegrees, 1e-4f)
        camera.isBeingTouched = false
    }

    @Test
    fun `auto orbit is off unless the setting asks for it`() {
        val camera = TurntableCamera()
        val start = camera.yawDegrees
        repeat(1_000) { camera.update(0.05f) }
        assertEquals(start, camera.yawDegrees, 1e-6f)
        assertFalse(camera.autoOrbit)
    }

    @Test
    fun `the readout describes the camera`() {
        val text = TurntableCamera().describe()
        assertTrue(text.contains("°"))
        assertTrue(text.contains("m"))
    }

    @Test
    fun `the lamp never goes fully dark`() {
        assertTrue(TurntableCamera.MIN_LAMP_INTENSITY > 0f)
    }
}
