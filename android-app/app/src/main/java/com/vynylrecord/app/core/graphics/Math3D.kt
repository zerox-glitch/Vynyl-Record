package com.vynylrecord.app.core.graphics

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The small amount of linear algebra the deck needs.
 *
 * Written here rather than pulled from a library because the app needs exactly four things from a matrix
 * stack — translation, rotation, perspective, look-at — and because a hot render loop wants mutable
 * matrices it can reuse. Nothing in this file allocates after construction: [Mat4.identity] and friends
 * mutate `this` and return it, so a scene of twenty parts costs zero garbage per frame.
 *
 * The matrices are column-major, which is what `glUniformMatrix4fv` with `transpose = false` expects.
 */
class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {

    fun set(x: Float, y: Float, z: Float): Vec3 {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(other: Vec3): Vec3 = set(other.x, other.y, other.z)

    fun normalize(): Vec3 {
        val length = sqrt(x * x + y * y + z * z)
        if (length > 1e-6f) {
            x /= length
            y /= length
            z /= length
        }
        return this
    }

    fun cross(other: Vec3, out: Vec3): Vec3 {
        val cx = y * other.z - z * other.y
        val cy = z * other.x - x * other.z
        val cz = x * other.y - y * other.x
        return out.set(cx, cy, cz)
    }

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun copyFrom(other: Vec3): Vec3 = set(other)
}

/**
 * A 4×4 matrix, column-major.
 *
 * `values[0..3]` is the first column, `values[4..7]` the second, and so on — the same layout the GL
 * documentation calls "column-major order" and the same one `glUniformMatrix4fv` reads from an array.
 */
class Mat4 {

    val values = FloatArray(16)

    init {
        identity()
    }

    fun identity(): Mat4 {
        values.fill(0f)
        values[0] = 1f
        values[5] = 1f
        values[10] = 1f
        values[15] = 1f
        return this
    }

    fun copyFrom(other: Mat4): Mat4 {
        other.values.copyInto(values)
        return this
    }

    /** `this = this * other`, the order that makes chained calls read left-to-right. */
    fun multiply(other: Mat4): Mat4 {
        val a = values
        val b = other.values
        val result = SCRATCH_A
        for (column in 0 until 4) {
            val offset = column * 4
            val b0 = b[offset]
            val b1 = b[offset + 1]
            val b2 = b[offset + 2]
            val b3 = b[offset + 3]
            result[offset] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
            result[offset + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
            result[offset + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
            result[offset + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
        }
        result.copyInto(values)
        return this
    }

    fun translate(x: Float, y: Float, z: Float): Mat4 {
        values[12] += values[0] * x + values[4] * y + values[8] * z
        values[13] += values[1] * x + values[5] * y + values[9] * z
        values[14] += values[2] * x + values[6] * y + values[10] * z
        values[15] += values[3] * x + values[7] * y + values[11] * z
        return this
    }

    fun scale(x: Float, y: Float, z: Float): Mat4 {
        for (row in 0 until 4) {
            values[row] *= x
            values[4 + row] *= y
            values[8 + row] *= z
        }
        return this
    }

    /** Rotation about Y in radians, the axis the platter and the tonearm turn around. */
    fun rotateY(radians: Float): Mat4 {
        val c = cos(radians)
        val s = sin(radians)
        val a = values
        val m0 = a[0] * c - a[8] * s
        val m1 = a[1] * c - a[9] * s
        val m2 = a[2] * c - a[10] * s
        val m3 = a[3] * c - a[11] * s
        val n0 = a[0] * s + a[8] * c
        val n1 = a[1] * s + a[9] * c
        val n2 = a[2] * s + a[10] * c
        val n3 = a[3] * s + a[11] * c
        a[0] = m0
        a[1] = m1
        a[2] = m2
        a[3] = m3
        a[8] = n0
        a[9] = n1
        a[10] = n2
        a[11] = n3
        return this
    }

    /** Rotation about X in radians, used to tip the tonearm's headshell onto the record. */
    fun rotateX(radians: Float): Mat4 {
        val c = cos(radians)
        val s = sin(radians)
        val a = values
        for (column in 0 until 4) {
            val offset = column * 4
            val w0 = a[offset + 1]
            val w1 = a[offset + 2]
            a[offset + 1] = w0 * c - w1 * s
            a[offset + 2] = w0 * s + w1 * c
        }
        return this
    }

    /**
     * A right-handed perspective projection.
     *
     * The deck is small — a third of a unit across — so the near plane is close and the field of view is
     * narrow: a wide angle on a small object looks like a fisheye photograph of a toy.
     */
    fun perspective(fieldOfViewDegrees: Float, aspect: Float, near: Float, far: Float): Mat4 {
        val f = 1f / tan(Math.toRadians(fieldOfViewDegrees.toDouble() / 2.0)).toFloat()
        values.fill(0f)
        values[0] = f / aspect
        values[5] = f
        values[10] = (far + near) / (near - far)
        values[11] = -1f
        values[14] = 2f * far * near / (near - far)
        return this
    }

    /** A right-handed look-at, with the camera's basis built from a forward/right/up triple. */
    fun lookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 {
        val forward = FORWARD.set(target.x - eye.x, target.y - eye.y, target.z - eye.z).normalize()
        val right = RIGHT.set(0f, 0f, 0f)
        forward.cross(up, right).normalize()
        val realUp = UP.set(0f, 0f, 0f)
        right.cross(forward, realUp).normalize()

        values[0] = right.x
        values[4] = right.y
        values[8] = right.z
        values[12] = -(right.x * eye.x + right.y * eye.y + right.z * eye.z)
        values[1] = realUp.x
        values[5] = realUp.y
        values[9] = realUp.z
        values[13] = -(realUp.x * eye.x + realUp.y * eye.y + realUp.z * eye.z)
        values[2] = -forward.x
        values[6] = -forward.y
        values[10] = -forward.z
        values[14] = forward.x * eye.x + forward.y * eye.y + forward.z * eye.z
        values[3] = 0f
        values[7] = 0f
        values[11] = 0f
        values[15] = 1f
        return this
    }

    /**
     * The normal matrix: the inverse transpose of the upper-left 3×3.
     *
     * Without it, a non-uniformly scaled part — the plinth, the lampshade — would light up as though its
     * normals were scaled with it, and the surfaces that should catch the light would go dark.
     */
    fun normalMatrix(out: FloatArray) {
        val a = values
        val determinant = a[0] * (a[5] * a[10] - a[6] * a[9]) -
            a[4] * (a[1] * a[10] - a[2] * a[9]) +
            a[8] * (a[1] * a[6] - a[2] * a[5])
        if (kotlin.math.abs(determinant) < 1e-8f) {
            out[0] = 1f
            out[1] = 0f
            out[2] = 0f
            out[3] = 0f
            out[4] = 1f
            out[5] = 0f
            out[6] = 0f
            out[7] = 0f
            out[8] = 1f
            return
        }
        val inverse = 1f / determinant
        out[0] = (a[5] * a[10] - a[6] * a[9]) * inverse
        out[1] = (a[2] * a[9] - a[1] * a[10]) * inverse
        out[2] = (a[1] * a[6] - a[2] * a[5]) * inverse
        out[3] = (a[6] * a[8] - a[4] * a[10]) * inverse
        out[4] = (a[0] * a[10] - a[2] * a[8]) * inverse
        out[5] = (a[2] * a[4] - a[0] * a[6]) * inverse
        out[6] = (a[4] * a[9] - a[5] * a[8]) * inverse
        out[7] = (a[1] * a[8] - a[0] * a[9]) * inverse
        out[8] = (a[0] * a[5] - a[1] * a[4]) * inverse
    }

    private companion object {
        val SCRATCH_A = FloatArray(16)
        val FORWARD = Vec3()
        val RIGHT = Vec3()
        val UP = Vec3()
    }
}

/** Linear interpolation, used by every animation that eases between two values. */
fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)

/**
 * A frame-rate-independent one-pole filter.
 *
 * Animations that ease towards a target by multiplying by a constant each frame run at the frame rate:
 * the same code makes the arm move twice as fast on a 120 Hz display as on a 60 Hz one. This filter takes
 * the elapsed time instead, so the half-life is the same number of milliseconds everywhere.
 */
class Smoothed(initial: Float = 0f, private var halfLifeSeconds: Float = 0.1f) {

    var value: Float = initial
        private set

    private var target: Float = initial

    fun setTarget(target: Float) = apply { this.target = target }

    fun snap(target: Float) {
        this.target = target
        value = target
    }

    fun update(deltaSeconds: Float): Float {
        if (deltaSeconds <= 0f || halfLifeSeconds <= 0f) return value
        val factor = 1f - Math.pow(0.5, (deltaSeconds / halfLifeSeconds).toDouble()).toFloat()
        value += (target - value) * factor.coerceIn(0f, 1f)
        return value
    }

    fun configure(halfLifeSeconds: Float): Smoothed = apply { this.halfLifeSeconds = halfLifeSeconds }

    val atTarget: Boolean get() = kotlin.math.abs(target - value) < 1e-4f
}
