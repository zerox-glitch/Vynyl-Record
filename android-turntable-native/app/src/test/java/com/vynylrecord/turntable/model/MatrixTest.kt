package com.vynylrecord.turntable.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The whole renderer trusts [Mat4]: a wrong sign or a transposed multiply would show up as geometry
 * in the wrong place, and a wrong inverse as black lighting. These tests pin the conventions.
 */
class MatrixTest {

    private val identity = FloatArray(16)

    private fun matrix(): FloatArray = FloatArray(16)

    @Test
    fun `multiply with identity is a no-op`() {
        val m = matrix()
        Mat4.setRotationY(37f, m)
        Mat4.translate(m, 0, 0.1f, 0.2f, 0.3f, m, 0)
        val out = matrix()
        Mat4.identity(identity)
        Mat4.multiply(m, 0, identity, 0, out, 0)
        for (i in 0 until 16) assertEquals(m[i], out[i], 1e-6f)
    }

    @Test
    fun `rotation about Y maps X onto cos theta and minus sin theta`() {
        val m = matrix()
        Mat4.setRotationY(90f, m)
        val point = Vec3()
        Mat4.transformPoint(m, 0, 1f, 0f, 0f, point)
        assertEquals(0f, point.x, 1e-5f)
        assertEquals(0f, point.y, 1e-5f)
        assertEquals(-1f, point.z, 1e-5f)
    }

    @Test
    fun `rotation about Z lifts the X axis`() {
        val m = matrix()
        Mat4.setRotationZ(90f, m)
        val point = Vec3()
        Mat4.transformPoint(m, 0, 1f, 0f, 0f, point)
        assertEquals(0f, point.x, 1e-5f)
        assertEquals(1f, point.y, 1e-5f)
    }

    @Test
    fun `inverse undoes a composed transform`() {
        val m = matrix()
        Mat4.setRotationY(23f, m)
        Mat4.scale(m, 0, 1.7f, 0.6f, 3.1f, m, 0)
        Mat4.rotateY(m, 0, -48f, m, 0)
        Mat4.translate(m, 0, -0.12f, 0.07f, 0.4f, m, 0)

        val inverse = matrix()
        assertTrue(Mat4.invert(m, 0, inverse, 0))

        val product = matrix()
        Mat4.multiply(m, 0, inverse, 0, product, 0)
        Mat4.identity(identity)
        for (i in 0 until 16) {
            assertEquals("element $i of M * M^-1", identity[i], product[i], 1e-4f)
        }
    }

    @Test
    fun `look-at puts the target straight down the view axis`() {
        val view = matrix()
        Mat4.setLookAt(0.4f, 0.9f, 1.6f, 0f, 0.1f, 0f, 0f, 1f, 0f, view, 0)
        val target = Vec3()
        Mat4.transformPoint(view, 0, 0f, 0.1f, 0f, target)
        assertEquals(0f, target.x, 1e-4f)
        assertEquals(0f, target.y, 1e-4f)
        val expected = -sqrt(0.4f * 0.4f + 0.8f * 0.8f + 1.6f * 1.6f)
        assertEquals(expected, target.z, 1e-3f)
    }

    @Test
    fun `perspective maps the near and far planes onto the NDC cube`() {
        val projection = matrix()
        Mat4.setPerspective(34f, 1.5f, 0.05f, 12f, projection, 0)
        val near = Vec3()
        val far = Vec3()
        Mat4.transformPoint(projection, 0, 0f, 0f, -0.05f, near)
        Mat4.transformPoint(projection, 0, 0f, 0f, -12f, far)
        assertEquals(-1f, near.z, 1e-3f)
        assertEquals(1f, far.z, 1e-3f)
    }

    @Test
    fun `normal matrix keeps normals perpendicular under non-uniform scale`() {
        // Squash a surface along Y: the normal of a 45 degree facet must tilt the other way.
        val model = matrix()
        Mat4.setScale(1f, 0.25f, 1f, model, 0)
        val normals = FloatArray(9)
        Mat4.normalMatrix3(model, 0, normals, 0)
        val source = Vec3(0f, 1f, -1f)
        val transformed = Vec3(
            normals[0] * source.x + normals[3] * source.y + normals[6] * source.z,
            normals[1] * source.x + normals[4] * source.y + normals[7] * source.z,
            normals[2] * source.x + normals[5] * source.y + normals[8] * source.z,
        )
        // inverse-transpose of diag(1, 1/4, 1) applied to (0, 1, -1) is (0, 4, -1).
        assertEquals(0f, transformed.x, 1e-5f)
        assertEquals(4f, transformed.y, 1e-4f)
        assertEquals(-1f, transformed.z, 1e-4f)
    }

    @Test
    fun `damp converges without overshooting`() {
        var value = 0f
        repeat(120) { value = MathUtils.damp(value, 1f, 6f, 1f / 60f) }
        assertEquals(1f, value, 1e-3f)
        assertTrue(value <= 1.0001f)
    }

    @Test
    fun `shortest angle delta wraps the short way`() {
        assertEquals(-30f, MathUtils.shortestAngleDelta(10f, 340f), 1e-4f)
        assertEquals(20f, MathUtils.shortestAngleDelta(350f, 10f), 1e-4f)
    }

    @Test
    fun `easing curves stay in range and agree at the ends`() {
        val curves = listOf(
            Easing.linear(0f), Easing.smoothStep(0f), Easing.easeInQuad(0f), Easing.easeOutCubic(0f),
        )
        for (value in curves) assertEquals(0f, value, 1e-5f)
        assertEquals(1f, Easing.easeOutCubic(1f), 1e-5f)
        assertEquals(1f, Easing.easeInOutCubic(1f), 1e-5f)
        assertEquals(1f, Easing.easeOutQuad(1f), 1e-5f)
        var t = 0f
        while (t <= 1f) {
            val value = Easing.easeInOutCubic(t)
            assertTrue("easing must stay inside the unit range", value >= -1e-4f && value <= 1.0001f)
            t += 0.05f
        }
    }
}
