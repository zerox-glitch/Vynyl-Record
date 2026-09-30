package com.vynylrecord.turntable.graphics.geometry

import com.vynylrecord.turntable.model.TurntableSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Geometry invariants that the shader depends on.
 *
 * The important one is the record's top surface: the fragment shader rebuilds a *metric* radius from
 * the interpolated `v` coordinate and lays grooves at a fixed pitch in metres, so the mesh has to
 * emit exactly the mapping the material advertises through `uDiscRadialRange`.
 */
class MeshFactoryTest {

    private fun assertWellFormed(mesh: Mesh) {
        assertTrue("${mesh.name} has no vertices", mesh.vertexCount > 0)
        assertTrue("${mesh.name} has no triangles", mesh.indexCount % 3 == 0 && mesh.indexCount > 0)
        for (index in mesh.indices) {
            assertTrue("${mesh.name} index out of range", index >= 0 && index < mesh.vertexCount)
        }
        for (vertex in 0 until mesh.vertexCount) {
            val base = vertex * VertexFormat.FLOATS_PER_VERTEX
            val x = mesh.vertices[base]
            val y = mesh.vertices[base + 1]
            val z = mesh.vertices[base + 2]
            val nx = mesh.vertices[base + 3]
            val ny = mesh.vertices[base + 4]
            val nz = mesh.vertices[base + 5]
            val u = mesh.vertices[base + 6]
            val v = mesh.vertices[base + 7]
            val tx = mesh.vertices[base + 8]
            val ty = mesh.vertices[base + 9]
            val tz = mesh.vertices[base + 10]

            assertTrue("${mesh.name} position is not finite", x.isFinite() && y.isFinite() && z.isFinite())
            val normalLength = sqrt(nx * nx + ny * ny + nz * nz)
            assertEquals("${mesh.name} vertex $vertex normal is not unit length", 1f, normalLength, 1e-3f)
            assertTrue("${mesh.name} uv is not finite", u.isFinite() && v.isFinite())
            // The vertex shader re-normalises tangents, so the mesh only has to provide a sane
            // direction: check it is finite and not degenerate.
            val tangentLength = sqrt(tx * tx + ty * ty + tz * tz)
            assertTrue(
                "${mesh.name} vertex $vertex has a degenerate tangent ($tangentLength)",
                tangentLength.isFinite() && tangentLength in 0.5f..1.5f,
            )
        }
        assertTrue("${mesh.name} bounds are empty", mesh.bounds.radius > 0f)
    }

    @Test
    fun `every generator produces well formed meshes`() {
        assertWellFormed(MeshFactory.box("box", 0.3f, 0.1f, 0.2f))
        assertWellFormed(MeshFactory.cylinder("cylinder", 0.05f, 0.02f, 24, topChamfer = 0.002f))
        assertWellFormed(MeshFactory.disc("disc", 0.15f, 0.005f, 48, edgeBevelWidth = 0.002f, edgeBevelHeight = 0.001f))
        assertWellFormed(MeshFactory.annulus("annulus", 0.05f, 0.15f, 0.01f, 48, edgeBevel = 0.001f))
        assertWellFormed(MeshFactory.planarAnnulus("label", 0.004f, 0.05f, 0.0004f, 48))
        assertWellFormed(MeshFactory.radialAnnulusTop("recordTop", 0.004f, 0.1524f, 0f, 72, rings = 5))
        assertWellFormed(MeshFactory.torus("torus", 0.02f, 0.003f, 24, 12))
        assertWellFormed(
            MeshFactory.tubeBetween("tube", 0f, 0f, 0f, 0.1f, 0.02f, 0f, 0.005f, segments = 12),
        )
        assertWellFormed(
            MeshFactory.beam("beam", 0f, 0f, 0f, 0.05f, 0.01f, 0.02f, 0.02f, 0.008f),
        )
        assertWellFormed(
            MeshFactory.roundedPrism(
                name = "prism",
                width = 0.46f,
                depth = 0.36f,
                height = 0.085f,
                cornerRadius = 0.02f,
                cornerSegments = 6,
                topBevel = 0.003f,
                bottomBevel = 0.003f,
                edgeSegments = 4,
            ),
        )
        assertWellFormed(MeshFactory.groundPlane("ground", size = 8f, segments = 4))
        assertWellFormed(
            MeshFactory.revolve(
                name = "lathe",
                profile = floatArrayOf(0f, 0f, 0.05f, 0f, 0.05f, 0.02f, 0f, 0.02f),
                segments = 24,
                capStart = true,
                capEnd = true,
            ),
        )
    }

    @Test
    fun `the record top surface matches the shader's radius contract`() {
        val mesh = MeshFactory.radialAnnulusTop(
            name = "recordTop",
            innerRadius = TurntableSpec.SPINDLE_RADIUS + 0.0002f,
            outerRadius = TurntableSpec.RECORD_RADIUS,
            y = 0f,
            segments = 72,
            rings = 4,
        )
        val inner = TurntableSpec.SPINDLE_RADIUS + 0.0002f
        val outer = TurntableSpec.RECORD_RADIUS
        var checked = 0
        for (vertex in 0 until mesh.vertexCount) {
            val base = vertex * VertexFormat.FLOATS_PER_VERTEX
            val x = mesh.vertices[base]
            val z = mesh.vertices[base + 2]
            val u = mesh.vertices[base + 6]
            val v = mesh.vertices[base + 7]
            val actualRadius = sqrt(x * x + z * z)
            val recoveredRadius = inner + v * (outer - inner)
            assertEquals(
                "v must be the normalised radius so the shader can recover metres",
                actualRadius,
                recoveredRadius,
                1e-5f,
            )
            val angle = kotlin.math.atan2(z, x)
            val normalisedAngle = if (angle < 0f) (angle + (2 * Math.PI).toFloat()) / (2 * Math.PI).toFloat() else angle / (2 * Math.PI).toFloat()
            assertEquals("u must be the angle around the disc", normalisedAngle, u, 1e-4f)
            checked++
        }
        assertTrue(checked > 100)
    }

    @Test
    fun `the record top is flat and faces up`() {
        val mesh = MeshFactory.radialAnnulusTop("recordTop", 0.004f, 0.15f, y = 0.02f, segments = 24, rings = 3)
        for (vertex in 0 until mesh.vertexCount) {
            val base = vertex * VertexFormat.FLOATS_PER_VERTEX
            assertEquals("the playing surface must be flat", 0.02f, mesh.vertices[base + 1], 1e-6f)
            assertEquals(1f, mesh.vertices[base + 4], 1e-5f)
        }
    }

    @Test
    fun `the index ceiling survives a high detail build`() {
        val meshes = TurntableBuilder.build(MeshFactory.GeometryDetail.HIGH)
        val all = meshes.all()
        assertTrue("the scene must contain meshes", all.isNotEmpty())
        for (mesh in all) {
            assertTrue("${mesh.name} exceeds the 16 bit index ceiling", mesh.vertexCount <= VertexFormat.MAX_VERTICES)
            assertWellFormed(mesh)
        }
        assertTrue(meshes.totalTriangles > 5_000)
        assertEquals(meshes.totalVertices, all.sumOf { it.vertexCount })
    }

    @Test
    fun `the scene sits on the deck and inside its footprint`() {
        val meshes = TurntableBuilder.build(MeshFactory.GeometryDetail.MEDIUM)
        val plinth = meshes.plinthBody
        assertTrue("the plinth must be a real solid", plinth.bounds.radius > 0.1f)
        assertTrue("the plinth top must be above the floor", plinth.bounds.maxY > TurntableSpec.PLINTH_TOP - 0.01f)
        assertEquals("the plinth sits on its feet", TurntableSpec.PLINTH_BOTTOM, plinth.bounds.minY, 0.005f)

        val record = meshes.recordBody
        assertTrue("the record must be on the platter", record.bounds.maxY < TurntableSpec.LABEL_TOP + 0.01f)
        assertTrue("the record must be above the mat", record.bounds.minY > TurntableSpec.MAT_TOP - 0.002f)
    }

    @Test
    fun `mesh transforms move geometry and keep normals unit length`() {
        val box = MeshFactory.box("box", 0.1f, 0.1f, 0.1f)
        val builder = MeshBuilder("box", box.vertexCount)
        builder.append(box, MeshFactory.translationMatrix(0.5f, 0.25f, -0.1f))
        val moved = builder.build()
        assertTrue(abs(moved.bounds.centerX - (box.bounds.centerX + 0.5f)) < 1e-4f)
        assertTrue(abs(moved.bounds.centerY - (box.bounds.centerY + 0.25f)) < 1e-4f)
        assertWellFormed(moved)
    }
}
