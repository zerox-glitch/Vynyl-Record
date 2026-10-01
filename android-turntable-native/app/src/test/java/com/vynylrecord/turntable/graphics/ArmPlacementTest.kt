package com.vynylrecord.turntable.graphics

import com.vynylrecord.turntable.graphics.geometry.Mesh
import com.vynylrecord.turntable.graphics.geometry.MeshFactory.GeometryDetail
import com.vynylrecord.turntable.graphics.geometry.TurntableBuilder
import com.vynylrecord.turntable.graphics.geometry.VertexFormat
import com.vynylrecord.turntable.model.Mat4
import com.vynylrecord.turntable.model.TonearmGeometry
import com.vynylrecord.turntable.model.TurntableSpec
import com.vynylrecord.turntable.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Where the tonearm actually ends up once the renderer has transformed it.
 *
 * This is the regression test for the bug that made the arm render beside the platter with its
 * counterweight hanging off the front of the deck: [TurntableBuilder] leaves the arm meshes in
 * pivot-local space and bakes the bearing into them with `place(...)`, while the renderer applies
 * `translate(pivot) * yaw * lift * translate(-pivot)` to that node. If either half drifts, the arm
 * moves -- and the only thing that proves it has not is transforming a real vertex of the real mesh
 * by the real matrix and checking that the stylus lands in the groove.
 *
 * Everything here is pure Kotlin, so it runs on the JVM with no device.
 */
class ArmPlacementTest {

    /** The matrix the renderer builds for the arm node, reproduced exactly. */
    private fun armMatrix(angleDeg: Float, lift01: Float): FloatArray {
        val m = FloatArray(Mat4.SIZE)
        Mat4.setTranslation(
            TurntableSpec.TONEARM_PIVOT_X, TurntableSpec.TONEARM_PIVOT_Y, TurntableSpec.TONEARM_PIVOT_Z, m, 0,
        )
        Mat4.rotateY(m, 0, -angleDeg, m, 0)
        Mat4.rotateZ(m, 0, lift01 * TurntableSpec.TONEARM_LIFT_MAX_DEG, m, 0)
        Mat4.translate(
            m, 0,
            -TurntableSpec.TONEARM_PIVOT_X, -TurntableSpec.TONEARM_PIVOT_Y, -TurntableSpec.TONEARM_PIVOT_Z,
            m, 0,
        )
        return m
    }

    /** The stylus tip: the needle mesh's furthest vertex along the (un-yawed) arm axis. */
    private fun stylusTip(needle: Mesh): Vec3 {
        val stride = VertexFormat.FLOATS_PER_VERTEX
        var best = 0
        for (v in 0 until needle.vertexCount) {
            if (needle.vertices[v * stride] > needle.vertices[best * stride]) best = v
        }
        return Vec3(needle.vertices[best * stride], needle.vertices[best * stride + 1], needle.vertices[best * stride + 2])
    }

    private fun tipRadiusAt(angleDeg: Float, lift01: Float = 0f): Float {
        val meshes = TurntableBuilder.build(GeometryDetail.MEDIUM)
        val tip = stylusTip(meshes.needle)
        val matrix = armMatrix(angleDeg, lift01)
        val world = Vec3()
        Mat4.transformPoint(matrix, 0, tip.x, tip.y, tip.z, world)
        return hypot(world.x - TurntableSpec.PLATTER_CENTER_X, world.z - TurntableSpec.PLATTER_CENTER_Z)
    }

    @Test
    fun `the stylus lands on the audio band at both ends of the sweep`() {
        assertEquals(
            "lead-in groove",
            TurntableSpec.AUDIO_BAND_OUTER,
            tipRadiusAt(TonearmGeometry.LEAD_IN_ANGLE_DEG),
            0.004f,
        )
        assertEquals(
            "run-out groove",
            TurntableSpec.AUDIO_BAND_INNER,
            tipRadiusAt(TonearmGeometry.RUN_OUT_ANGLE_DEG),
            0.004f,
        )
    }

    @Test
    fun `the stylus tracks inwards monotonically as the arm sweeps`() {
        var previous = Float.MAX_VALUE
        var progress = 0f
        while (progress <= 1f) {
            val radius = tipRadiusAt(TonearmGeometry.angleForProgress(progress))
            assertTrue("radius must shrink as the arm tracks in ($radius after $previous)", radius < previous)
            assertTrue("the stylus left the pressing at progress=$progress", radius < TurntableSpec.RECORD_RADIUS)
            previous = radius
            progress += 0.05f
        }
    }

    @Test
    fun `the parked arm sits clear of the record on its rest`() {
        val meshes = TurntableBuilder.build(GeometryDetail.MEDIUM)
        val tip = stylusTip(meshes.needle)
        val world = Vec3()
        Mat4.transformPoint(armMatrix(TurntableSpec.TONEARM_REST_ANGLE_DEG, 0f), 0, tip.x, tip.y, tip.z, world)

        val radius = hypot(world.x - TurntableSpec.PLATTER_CENTER_X, world.z - TurntableSpec.PLATTER_CENTER_Z)
        assertTrue("the parked stylus must not hang over the pressing ($radius)", radius > TurntableSpec.PLATTER_RADIUS)
        assertTrue("the parked stylus must stay on the deck", abs(world.x) < TurntableSpec.PLINTH_WIDTH * 0.5f)
        assertTrue("the parked stylus must stay on the deck", abs(world.z) < TurntableSpec.PLINTH_DEPTH * 0.5f)
        assertEquals("the stylus rides the record surface", TurntableSpec.RECORD_TOP, world.y, 0.003f)

        // The arm rest is built from the same parked angle, so the transformed mesh and the
        // geometric prediction have to agree.
        val predicted = Vec3()
        TonearmGeometry.tipPosition(TurntableSpec.TONEARM_REST_ANGLE_DEG, predicted)
        assertEquals("the stylus should sit over its rest", predicted.x, world.x, 0.012f)
        assertEquals("the stylus should sit over its rest", predicted.z, world.z, 0.012f)
    }

    @Test
    fun `the whole arm stays over the deck at every point in the sweep`() {
        val meshes = TurntableBuilder.build(GeometryDetail.MEDIUM)
        val world = Vec3()
        val stride = VertexFormat.FLOATS_PER_VERTEX

        var progress = 0f
        while (progress <= 1f) {
            val matrix = armMatrix(TonearmGeometry.angleForProgress(progress), 0f)
            for (v in 0 until meshes.tonearm.vertexCount) {
                Mat4.transformPoint(
                    matrix, 0,
                    meshes.tonearm.vertices[v * stride],
                    meshes.tonearm.vertices[v * stride + 1],
                    meshes.tonearm.vertices[v * stride + 2],
                    world,
                )
                // Half a plinth of slack allows for the counterweight, which overhangs the moulded
                // edge very slightly at the extreme inner groove; the bug this guards against threw
                // the arm a quarter of a metre past the corner.
                assertTrue(
                    "the arm left the deck at progress=$progress: x=${world.x} z=${world.z}",
                    abs(world.x) < TurntableSpec.PLINTH_WIDTH * 0.5f + 0.01f &&
                        abs(world.z) < TurntableSpec.PLINTH_DEPTH * 0.5f + 0.01f,
                )
                assertTrue("the arm sank below the plinth", world.y > TurntableSpec.PLINTH_BOTTOM)
            }
            progress += 0.05f
        }
    }

    @Test
    fun `cueing lifts the stylus clear of the groove`() {
        val lowered = tipRadiusAt(TonearmGeometry.LEAD_IN_ANGLE_DEG, 0f)
        val meshes = TurntableBuilder.build(GeometryDetail.MEDIUM)
        val tip = stylusTip(meshes.needle)

        val down = Vec3()
        val up = Vec3()
        Mat4.transformPoint(armMatrix(TonearmGeometry.LEAD_IN_ANGLE_DEG, 0f), 0, tip.x, tip.y, tip.z, down)
        Mat4.transformPoint(armMatrix(TonearmGeometry.LEAD_IN_ANGLE_DEG, 1f), 0, tip.x, tip.y, tip.z, up)

        assertTrue("the cueing lever must raise the stylus", up.y > down.y)
        assertTrue(
            "the lifted stylus must clear the record surface (${(up.y - down.y) * 1000} mm)",
            up.y - down.y > TurntableSpec.GROOVE_DEPTH,
        )
        assertEquals("lifting must not swing the arm along the groove", lowered, tipRadiusAt(TonearmGeometry.LEAD_IN_ANGLE_DEG, 1f), 0.004f)
    }
}
