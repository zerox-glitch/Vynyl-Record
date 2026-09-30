package com.vynylrecord.turntable.graphics.geometry

import com.vynylrecord.turntable.graphics.geometry.MeshFactory.GeometryDetail
import com.vynylrecord.turntable.model.TonearmGeometry
import com.vynylrecord.turntable.model.TurntableSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The deck assembly, checked as a whole.
 *
 * The valuable assertion here is winding agreement: the renderer enables back-face culling, so a
 * triangle whose winding disagrees with its vertex normals is a hole you can see straight through
 * the plinth. [MeshBuilder] normalises that at build time and this test holds it to it.
 */
class TurntableBuilderTest {

    @Test
    fun `every mesh has all of its parts`() {
        val meshes = TurntableBuilder.build(GeometryDetail.MEDIUM)
        val parts = meshes.all()
        assertTrue("the deck should be assembled from many parts", parts.size >= 20)
        for (mesh in parts) {
            assertTrue("${mesh.name} is empty", mesh.triangleCount > 0)
            assertTrue("${mesh.name} has no name", mesh.name.isNotBlank())
        }
        val names = parts.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `triangles wind with their normals`() {
        for (detail in GeometryDetail.entries) {
            for (mesh in TurntableBuilder.build(detail).all()) {
                val stride = VertexFormat.FLOATS_PER_VERTEX
                var flipped = 0
                var checked = 0
                var triangle = 0
                while (triangle + 2 < mesh.indices.size) {
                    val ia = mesh.indices[triangle].toInt()
                    val ib = mesh.indices[triangle + 1].toInt()
                    val ic = mesh.indices[triangle + 2].toInt()
                    triangle += 3
                    val ax = mesh.vertices[ia * stride]
                    val ay = mesh.vertices[ia * stride + 1]
                    val az = mesh.vertices[ia * stride + 2]
                    val abx = mesh.vertices[ib * stride] - ax
                    val aby = mesh.vertices[ib * stride + 1] - ay
                    val abz = mesh.vertices[ib * stride + 2] - az
                    val acx = mesh.vertices[ic * stride] - ax
                    val acy = mesh.vertices[ic * stride + 1] - ay
                    val acz = mesh.vertices[ic * stride + 2] - az
                    val gx = aby * acz - abz * acy
                    val gy = abz * acx - abx * acz
                    val gz = abx * acy - aby * acx
                    if (sqrt(gx * gx + gy * gy + gz * gz) < 1e-12f) continue
                    val nx = mesh.vertices[ia * stride + 3] + mesh.vertices[ib * stride + 3] + mesh.vertices[ic * stride + 3]
                    val ny = mesh.vertices[ia * stride + 4] + mesh.vertices[ib * stride + 4] + mesh.vertices[ic * stride + 4]
                    val nz = mesh.vertices[ia * stride + 5] + mesh.vertices[ib * stride + 5] + mesh.vertices[ic * stride + 5]
                    checked++
                    if (gx * nx + gy * ny + gz * nz < 0f) flipped++
                }
                assertTrue("${mesh.name} has $checked triangles, none to check", checked > 0)
                assertEquals("${mesh.name} has $flipped triangles wound against their normals", 0, flipped)
            }
        }
    }

    @Test
    fun `the deck stays inside its own footprint`() {
        val meshes = TurntableBuilder.build(GeometryDetail.HIGH)
        for (mesh in meshes.all()) {
            if (mesh.name == "ground") continue
            assertTrue("${mesh.name} is off the deck in X", abs(mesh.bounds.centerX) < TurntableSpec.PLINTH_WIDTH)
            assertTrue("${mesh.name} is off the deck in Z", abs(mesh.bounds.centerZ) < TurntableSpec.PLINTH_DEPTH)
            assertTrue("${mesh.name} sinks below the floor", mesh.bounds.minY > TurntableSpec.FLOOR_Y - 0.002f)
        }
    }

    @Test
    fun `the record and label stack in the right order`() {
        val meshes = TurntableBuilder.build(GeometryDetail.MEDIUM)
        assertTrue(meshes.mat.bounds.maxY <= TurntableSpec.MAT_TOP + 1e-4f)
        assertTrue(meshes.recordBody.bounds.minY >= TurntableSpec.RECORD_BOTTOM - 1e-4f)
        assertTrue(meshes.recordBody.bounds.maxY <= TurntableSpec.RECORD_TOP + 1e-3f)
        assertTrue(meshes.label.bounds.maxY <= TurntableSpec.LABEL_TOP + 1e-3f)
        assertTrue("the label must sit above the vinyl", meshes.label.bounds.minY >= TurntableSpec.RECORD_TOP - 1e-4f)
        assertTrue("the spindle must clear the label", meshes.spindle.bounds.maxY >= TurntableSpec.SPINDLE_TOP - 1e-3f)
    }

    @Test
    fun `every mesh on the arm node is baked at the bearing`() {
        val meshes = TurntableBuilder.build(GeometryDetail.MEDIUM)

        // The renderer applies one matrix per node, so all three arm meshes have to agree on a
        // coordinate space: the arm, the stylus and the brass pivot hardware are built in
        // pivot-local space and then placed at the bearing. Mixing spaces here -- which is what put
        // the arm beside the platter with its counterweight off the front of the deck -- is caught
        // by ArmPlacementTest, which checks where the stylus actually ends up.
        val arm = meshes.tonearm
        assertTrue(
            "the counterweight must reach behind the pivot: minX=${arm.bounds.minX}",
            arm.bounds.minX < TurntableSpec.TONEARM_PIVOT_X - 0.03f,
        )
        assertTrue(
            "the arm must reach out towards the stylus: maxX=${arm.bounds.maxX}",
            arm.bounds.maxX >= TurntableSpec.TONEARM_PIVOT_X + TonearmGeometry.EFFECTIVE_LENGTH - 0.02f,
        )
        assertTrue(
            "the arm must hang at its own bearing height: centreY=${arm.bounds.centerY}",
            abs(arm.bounds.centerY - TurntableSpec.TONEARM_PIVOT_Y) < 0.02f,
        )
        assertTrue(
            "the pivot brass must be centred on the pivot: centreX=${meshes.pivotBrass.bounds.centerX}",
            abs(meshes.pivotBrass.bounds.centerX - TurntableSpec.TONEARM_PIVOT_X) < 0.01f,
        )
        assertTrue(
            "the stylus is built at the bearing too: centreX=${meshes.needle.bounds.centerX}",
            meshes.needle.bounds.centerX > TurntableSpec.TONEARM_PIVOT_X,
        )
        assertTrue("the arm rest is on the deck beside the pivot", abs(meshes.armRest.bounds.centerX) > 0.1f)
    }

    @Test
    fun `the controls sit on the front edge where a hand can reach them`() {
        val meshes = TurntableBuilder.build(GeometryDetail.MEDIUM)
        val controls = listOf(meshes.powerButton, meshes.speedSelector, meshes.volumeKnob, meshes.indicatorBezel)
        for (control in controls) {
            assertTrue("${control.name} must be on the deck", control.bounds.centerZ > 0.1f)
            assertTrue("${control.name} must not float", control.bounds.minY > TurntableSpec.PLINTH_TOP - 0.01f)
            assertTrue("${control.name} must stay on the plinth", abs(control.bounds.centerX) < TurntableSpec.PLINTH_WIDTH * 0.5f)
        }
    }

    @Test
    fun `quality levels actually change the triangle budget`() {
        val low = TurntableBuilder.build(GeometryDetail.LOW).totalTriangles
        val high = TurntableBuilder.build(GeometryDetail.HIGH).totalTriangles
        assertTrue("High must build more than Low ($low vs $high)", high > low)
        assertTrue("even Low must be a real deck", low > 4_000)
        assertTrue("High must stay inside a 16-bit index budget per mesh", high < 200_000)
    }
}
