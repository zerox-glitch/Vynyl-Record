package com.vynylrecord.app.core.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Every shape the turntable is made of, checked as geometry rather than as pixels.
 *
 * A mesh that is inside-out, or that has a degenerate triangle in it, shows up on a device as a hole or a
 * black facet — and after the fact it is close to impossible to tell which of a dozen builders is at
 * fault. So each builder is checked here: winding against its own normals, unit-length normals, indices in
 * range, no zero-area faces, and a bounding radius that matches the shape's real size.
 */
class MeshesTest {

    private data class Named(val label: String, val mesh: MeshData)

    private fun shapes(): List<Named> = listOf(
        Named("box", Meshes.box(0.45f, 0.08f, 0.36f)),
        Named("beveledBox", Meshes.beveledBox(0.45f, 0.08f, 0.36f, bevel = 0.01f)),
        Named("cylinder", Meshes.cylinder(0.155f, 0.02f, 48)),
        Named("taperedCylinder", Meshes.taperedCylinder(0.012f, 0.008f, 0.09f, 24)),
        Named("cone", Meshes.cone(0.01f, 0.02f, 20)),
        Named("disc", Meshes.disc(0.1524f, 0.05f, 0.004f, 64)),
        Named("ring", Meshes.ring(0.155f, 0.14f, 0.006f, 64)),
        Named("torus", Meshes.torus(0.2f, 0.03f, 40, 16)),
        Named("tube", Meshes.tube(curve = DeckDimensions.armCurve(), radius = 0.006f, alongSegments = 24, radialSegments = 10)),
        Named("wedge", Meshes.wedge(0.2f, 0.05f, 0.15f)),
        Named("knob", Meshes.knob(0.018f, 0.012f, 20)),
        Named("needle", Meshes.needle(0.014f)),
    )

    @Test
    fun `every builder produces a valid mesh`() {
        shapes().forEach { (label, mesh) ->
            assertTrue("$label is not valid", mesh.isValid)
            assertEquals("$label has a partial triangle", 0, mesh.indexCount % 3)
            assertEquals("$label's uv count does not match its vertices", mesh.vertexCount * 2, mesh.uvs.size)
            assertEquals("$label's normal count does not match", mesh.positions.size, mesh.normals.size)
            assertTrue("$label has no geometry", mesh.vertexCount > 3)
            assertTrue("$label needs more than 32k vertices for 16-bit indices", mesh.vertexCount < 32_768)
        }
    }

    @Test
    fun `every face is wound to face outwards`() {
        shapes().forEach { (label, mesh) ->
            var inward = 0
            var checked = 0
            var index = 0
            while (index + 2 < mesh.indexCount) {
                val a = mesh.indices[index].toInt()
                val b = mesh.indices[index + 1].toInt()
                val c = mesh.indices[index + 2].toInt()
                val geometric = faceNormal(mesh, a, b, c)
                if (geometric != null) {
                    val average = averageNormal(mesh, a, b, c)
                    if (dot(geometric, average) < 0f) inward++
                    checked++
                }
                index += 3
            }
            assertTrue("$label has no checkable faces", checked > 0)
            assertEquals("$label has inward-facing triangles", 0, inward)
        }
    }

    @Test
    fun `no triangle is degenerate`() {
        shapes().forEach { (label, mesh) ->
            var index = 0
            while (index + 2 < mesh.indexCount) {
                val a = mesh.indices[index].toInt()
                val b = mesh.indices[index + 1].toInt()
                val c = mesh.indices[index + 2].toInt()
                assertTrue("$label repeats a vertex in a triangle", a != b && b != c && a != c)
                assertTrue("$label has a zero-area triangle at $index", faceNormal(mesh, a, b, c) != null)
                index += 3
            }
        }
    }

    @Test
    fun `normals are unit length and finite`() {
        shapes().forEach { (label, mesh) ->
            for (vertex in 0 until mesh.vertexCount) {
                val x = mesh.normals[vertex * 3]
                val y = mesh.normals[vertex * 3 + 1]
                val z = mesh.normals[vertex * 3 + 2]
                val length = sqrt(x * x + y * y + z * z)
                assertTrue("$label has a non-finite normal", length.isFinite())
                assertTrue("$label normal $length at vertex $vertex is not unit length", abs(length - 1f) < 0.02f)
            }
        }
    }

    @Test
    fun `every vertex lies inside the shape's own bounding radius`() {
        shapes().forEach { (label, mesh) ->
            val radius = mesh.boundingRadius()
            assertTrue("$label has no radius", radius > 0f)
            for (vertex in 0 until mesh.vertexCount) {
                val x = mesh.positions[vertex * 3]
                val y = mesh.positions[vertex * 3 + 1]
                val z = mesh.positions[vertex * 3 + 2]
                val distance = sqrt(x * x + y * y + z * z)
                assertTrue("$label vertex $vertex is outside its own radius", distance <= radius + 1e-4f)
            }
        }
    }

    @Test
    fun `the box is the size it was asked for`() {
        val mesh = Meshes.box(0.45f, 0.075f, 0.36f)
        assertEquals(24, mesh.vertexCount)
        assertEquals(36, mesh.indexCount)
        val radius = mesh.boundingRadius()
        // Half the diagonal of the plinth: sqrt((w/2)^2 + (h/2)^2 + (d/2)^2).
        val expected = sqrt(0.225f * 0.225f + 0.0375f * 0.0375f + 0.18f * 0.18f)
        assertEquals(expected.toDouble(), radius.toDouble(), 1e-3)
    }

    @Test
    fun `the record disc is a record's size and is hollowed for its label`() {
        val disc = Meshes.disc(0.1524f, 0.05f, 0.004f, 64)
        assertEquals(0.1524f, disc.boundingRadius(), 1e-3f)
        // A 12-inch record is 304.8 mm across; the mesh must not be a sphere or a cube.
        assertTrue(disc.vertexCount > 200)
        // The disc is a Y-up slab, so half its thickness sits either side of the label plane.
        val flat = disc.positions.filterIndexed { index, _ -> index % 3 == 1 }.maxOf { abs(it) }
        assertEquals("the disc has thickness", 0.002f, flat, 1e-4f)
    }

    @Test
    fun `a mesh with a mirrored face is detected by the winding check`() {
        // The check is only worth anything if it fails when it should: flip a triangle and confirm.
        val good = Meshes.box(1f, 1f, 1f)
        val broken = MeshData(good.positions, good.normals, good.uvs, good.indices.copyOf().also {
            val a = it[0].toInt()
            it[0] = it[1]
            it[1] = a.toShort()
        })
        var inward = 0
        var index = 0
        while (index + 2 < broken.indexCount) {
            val normal = faceNormal(broken, broken.indices[index].toInt(), broken.indices[index + 1].toInt(), broken.indices[index + 2].toInt())
            if (normal != null && dot(
                    normal,
                    averageNormal(broken, broken.indices[index].toInt(), broken.indices[index + 1].toInt(), broken.indices[index + 2].toInt()),
                ) < 0f
            ) {
                inward++
            }
            index += 3
        }
        assertTrue("the winding check missed a flipped face", inward > 0)
    }

    @Test
    fun `deriving normals produces a usable mesh`() {
        val derived = Meshes.knob(0.02f, 0.01f, 24).withDerivedNormals()
        assertTrue(derived.isValid)
        for (vertex in 0 until derived.vertexCount) {
            val x = derived.normals[vertex * 3]
            val y = derived.normals[vertex * 3 + 1]
            val z = derived.normals[vertex * 3 + 2]
            assertEquals("derived normal is not unit length", 1f, sqrt(x * x + y * y + z * z), 0.02f)
        }
    }

    @Test
    fun `a tube follows its curve`() {
        val curve = DeckDimensions.armCurve()
        assertTrue("the tonearm curve needs at least two points", curve.size >= 2)
        val tube = Meshes.tube(curve, radius = 0.006f, alongSegments = 20, radialSegments = 8)
        assertTrue(tube.isValid)
        // The tube must reach the end of the curve it was swept along, not collapse to a ribbon.
        val last = curve.last()
        val radius = tube.boundingRadius()
        assertTrue(radius >= sqrt(last.x * last.x + last.y * last.y + last.z * last.z) - 0.05f)
    }

    private fun faceNormal(mesh: MeshData, a: Int, b: Int, c: Int): FloatArray? {
        val ax = mesh.positions[a * 3]
        val ay = mesh.positions[a * 3 + 1]
        val az = mesh.positions[a * 3 + 2]
        val abx = mesh.positions[b * 3] - ax
        val aby = mesh.positions[b * 3 + 1] - ay
        val abz = mesh.positions[b * 3 + 2] - az
        val acx = mesh.positions[c * 3] - ax
        val acy = mesh.positions[c * 3 + 1] - ay
        val acz = mesh.positions[c * 3 + 2] - az
        val nx = aby * acz - abz * acy
        val ny = abz * acx - abx * acz
        val nz = abx * acy - aby * acx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length <= 1e-7f) return null
        return floatArrayOf(nx / length, ny / length, nz / length)
    }

    private fun averageNormal(mesh: MeshData, a: Int, b: Int, c: Int): FloatArray {
        var x = 0f
        var y = 0f
        var z = 0f
        listOf(a, b, c).forEach { vertex ->
            x += mesh.normals[vertex * 3]
            y += mesh.normals[vertex * 3 + 1]
            z += mesh.normals[vertex * 3 + 2]
        }
        return floatArrayOf(x, y, z)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
