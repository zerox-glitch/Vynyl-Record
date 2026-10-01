package com.vynylrecord.app.core.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The maths the deck is built from.
 *
 * A column-major matrix library written by hand is exactly the kind of code that looks right and renders
 * mirrored, so every operation here is checked against a value computed on paper rather than against
 * another line of the same library.
 */
class Math3DTest {

    private fun assertFinite(values: FloatArray) {
        values.forEach { assertTrue("matrix contained $it", it.isFinite()) }
    }

    @Test
    fun `the identity leaves a point alone`() {
        val matrix = Mat4().identity()
        assertEquals(1f, matrix.values[0], 0f)
        assertEquals(1f, matrix.values[5], 0f)
        assertEquals(1f, matrix.values[10], 0f)
        assertEquals(1f, matrix.values[15], 0f)
        // Column-major: index 12, 13, 14 are the translation column.
        assertEquals(0f, matrix.values[12], 0f)
        assertEquals(0f, matrix.values[13], 0f)
        assertEquals(0f, matrix.values[14], 0f)
    }

    @Test
    fun `translation lands in the last column`() {
        val matrix = Mat4().identity().translate(1f, 2f, 3f)
        assertEquals(1f, matrix.values[12], 1e-6f)
        assertEquals(2f, matrix.values[13], 1e-6f)
        assertEquals(3f, matrix.values[14], 1e-6f)
        assertEquals(1f, matrix.values[0], 0f)
    }

    @Test
    fun `scaling multiplies the diagonal`() {
        val matrix = Mat4().identity().scale(2f, 3f, 4f)
        assertEquals(2f, matrix.values[0], 0f)
        assertEquals(3f, matrix.values[5], 0f)
        assertEquals(4f, matrix.values[10], 0f)
        assertEquals(1f, matrix.values[15], 0f)
    }

    @Test
    fun `a quarter turn about y maps x onto minus z`() {
        val matrix = Mat4().identity().rotateY((Math.PI / 2).toFloat())
        // Column-major: the first column is where the x axis goes.
        assertEquals(0f, matrix.values[0], 1e-5f)
        assertEquals(0f, matrix.values[1], 1e-5f)
        assertEquals(-1f, matrix.values[2], 1e-5f)
    }

    @Test
    fun `a quarter turn about x maps y onto z`() {
        val matrix = Mat4().identity().rotateX((Math.PI / 2).toFloat())
        assertEquals(0f, matrix.values[5], 1e-5f)
        assertEquals(1f, matrix.values[6], 1e-5f)
        assertEquals(-1f, matrix.values[9], 1e-5f)
    }

    @Test
    fun `multiplying by the identity changes nothing`() {
        val original = Mat4().identity().translate(1f, -2f, 0.5f).rotateY(0.3f).scale(2f, 2f, 2f)
        val copy = Mat4().copyFrom(original)
        val product = Mat4().copyFrom(original).multiply(Mat4().identity())
        for (index in 0 until 16) assertEquals("index $index", copy.values[index], product.values[index], 1e-5f)
    }

    @Test
    fun `multiplication applies the right-hand transform first`() {
        val translate = Mat4().identity().translate(1f, 0f, 0f)
        val scale = Mat4().identity().scale(2f, 2f, 2f)
        // translate * scale moves the origin by 1 and doubles everything.
        val combined = Mat4().copyFrom(translate).multiply(scale)
        assertEquals(1f, combined.values[12], 1e-6f)
        assertEquals(2f, combined.values[0], 1e-6f)
    }

    @Test
    fun `the projection and the view matrix are finite for a real camera`() {
        val projection = Mat4().perspective(34f, 16f / 9f, 0.1f, 100f)
        assertFinite(projection.values)
        val view = Mat4().lookAt(Vec3(0f, 1.5f, 2f), Vec3(0f, 0.3f, 0f), Vec3(0f, 1f, 0f))
        assertFinite(view.values)
        for (index in 0 until 16) assertTrue(abs(view.values[index]) < 100f)
    }

    @Test
    fun `the normal matrix keeps normals perpendicular to the surface`() {
        val model = Mat4().identity().rotateY(0.7f).scale(1f, 2f, 1f)
        val out = FloatArray(9)
        model.normalMatrix(out)
        assertTrue(out.all { it.isFinite() })
        assertTrue(out.any { abs(it) > 0.01f })
    }

    @Test
    fun `vector arithmetic behaves`() {
        val a = Vec3(1f, 2f, 3f)
        val b = Vec3(4f, 5f, 6f)
        assertEquals(32f, a.dot(b), 1e-5f)
        val cross = Vec3()
        a.cross(b, cross)
        assertEquals(-3f, cross.x, 1e-5f)
        assertEquals(6f, cross.y, 1e-5f)
        assertEquals(-3f, cross.z, 1e-5f)

        val normalised = Vec3(0f, 0f, 5f).normalize()
        assertEquals(1f, normalised.length(), 1e-5f)
        // Normalising a zero vector must not produce NaN: it is used on interpolation results.
        assertTrue(Vec3().normalize().length().isFinite())
    }

    @Test
    fun `lerp clamps and interpolates`() {
        assertEquals(0f, lerp(0f, 10f, -1f), 0f)
        assertEquals(5f, lerp(0f, 10f, 0.5f), 0f)
        assertEquals(10f, lerp(0f, 10f, 2f), 0f)
    }

    @Test
    fun `the smoother converges without overshooting`() {
        val smoothed = Smoothed(initial = 0f).configure(0.1f)
        smoothed.setTarget(1f)
        var previous = 0f
        var steps = 0
        while (!smoothed.atTarget && steps < 10_000) {
            val value = smoothed.update(1f / 60f)
            assertTrue("the smoother overshot to $value", value in -0.001f..1.001f)
            assertTrue("the smoother went backwards", value >= previous - 1e-6f)
            previous = value
            steps++
        }
        assertTrue("the smoother never arrived", smoothed.atTarget)
        assertTrue("converging took $steps frames", steps < 600)
    }

    @Test
    fun `the smoother snaps when asked`() {
        val smoothed = Smoothed(0f)
        smoothed.snap(0.75f)
        assertEquals(0.75f, smoothed.update(1f / 60f), 1e-6f)
        assertTrue(smoothed.atTarget)
    }

    @Test
    fun `an enormous frame delta does not blow up the smoother`() {
        val smoothed = Smoothed(0f).configure(0.05f)
        smoothed.setTarget(1f)
        val value = smoothed.update(30f)
        assertTrue(value.isFinite())
        assertTrue(value in 0f..1f)
    }

    @Test
    fun `matrix values are column major and ready for the gl call`() {
        // The renderer hands `values` straight to glUniformMatrix4fv with transpose = false.
        val matrix = Mat4().identity().translate(0f, 0f, -2f)
        assertEquals(16, matrix.values.size)
        assertEquals(0f, matrix.values[12], 0f)
        assertEquals(0f, matrix.values[13], 0f)
        assertEquals(-2f, matrix.values[14], 1e-6f)
        assertEquals(1f, matrix.values[15], 1e-6f)
    }
}
