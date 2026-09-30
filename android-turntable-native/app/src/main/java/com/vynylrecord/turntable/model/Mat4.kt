package com.vynylrecord.turntable.model

import kotlin.math.tan

/**
 * Column-major 4x4 matrix helpers operating on raw `FloatArray` storage.
 *
 * Storage matches what OpenGL expects, so [identity] output can be handed straight to
 * `glUniformMatrix4fv(..., transpose = false, ...)`. Element `m[column * 4 + row]`.
 *
 * Every function writes into a caller-provided array: the render loop reuses a fixed set
 * of matrices, and nothing here allocates.
 */
object Mat4 {

    const val SIZE = 16

    fun identity(out: FloatArray, offset: Int = 0) {
        for (i in 0 until SIZE) out[offset + i] = 0f
        out[offset + 0] = 1f
        out[offset + 5] = 1f
        out[offset + 10] = 1f
        out[offset + 15] = 1f
    }

    fun copyInto(src: FloatArray, srcOffset: Int, dst: FloatArray, dstOffset: Int) {
        System.arraycopy(src, srcOffset, dst, dstOffset, SIZE)
    }

    /** `out = a * b`. [out] may alias [a] or [b]. */
    fun multiply(a: FloatArray, aOffset: Int, b: FloatArray, bOffset: Int, out: FloatArray, outOffset: Int) {
        val a00 = a[aOffset + 0]; val a01 = a[aOffset + 1]; val a02 = a[aOffset + 2]; val a03 = a[aOffset + 3]
        val a10 = a[aOffset + 4]; val a11 = a[aOffset + 5]; val a12 = a[aOffset + 6]; val a13 = a[aOffset + 7]
        val a20 = a[aOffset + 8]; val a21 = a[aOffset + 9]; val a22 = a[aOffset + 10]; val a23 = a[aOffset + 11]
        val a30 = a[aOffset + 12]; val a31 = a[aOffset + 13]; val a32 = a[aOffset + 14]; val a33 = a[aOffset + 15]

        var c = 0
        while (c < 4) {
            val b0 = b[bOffset + c * 4 + 0]
            val b1 = b[bOffset + c * 4 + 1]
            val b2 = b[bOffset + c * 4 + 2]
            val b3 = b[bOffset + c * 4 + 3]
            out[outOffset + c * 4 + 0] = a00 * b0 + a10 * b1 + a20 * b2 + a30 * b3
            out[outOffset + c * 4 + 1] = a01 * b0 + a11 * b1 + a21 * b2 + a31 * b3
            out[outOffset + c * 4 + 2] = a02 * b0 + a12 * b1 + a22 * b2 + a32 * b3
            out[outOffset + c * 4 + 3] = a03 * b0 + a13 * b1 + a23 * b2 + a33 * b3
            c++
        }
    }

    fun setTranslation(x: Float, y: Float, z: Float, out: FloatArray, offset: Int = 0) {
        identity(out, offset)
        out[offset + 12] = x
        out[offset + 13] = y
        out[offset + 14] = z
    }

    fun setScale(x: Float, y: Float, z: Float, out: FloatArray, offset: Int = 0) {
        identity(out, offset)
        out[offset + 0] = x
        out[offset + 5] = y
        out[offset + 10] = z
    }

    fun setRotationX(degrees: Float, out: FloatArray, offset: Int = 0) {
        val radians = MathUtils.degToRad(degrees)
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        identity(out, offset)
        out[offset + 5] = c
        out[offset + 6] = s
        out[offset + 9] = -s
        out[offset + 10] = c
    }

    /** Right-handed rotation about +Y: maps +X onto (cos, 0, -sin). */
    fun setRotationY(degrees: Float, out: FloatArray, offset: Int = 0) {
        val radians = MathUtils.degToRad(degrees)
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        identity(out, offset)
        out[offset + 0] = c
        out[offset + 2] = -s
        out[offset + 8] = s
        out[offset + 10] = c
    }

    fun setRotationZ(degrees: Float, out: FloatArray, offset: Int = 0) {
        val radians = MathUtils.degToRad(degrees)
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        identity(out, offset)
        out[offset + 0] = c
        out[offset + 1] = s
        out[offset + 4] = -s
        out[offset + 5] = c
    }

    /** `out = m * translate(x, y, z)`. */
    fun translate(m: FloatArray, mOffset: Int, x: Float, y: Float, z: Float, out: FloatArray, outOffset: Int) {
        copyInto(m, mOffset, out, outOffset)
        out[outOffset + 12] = m[mOffset + 0] * x + m[mOffset + 4] * y + m[mOffset + 8] * z + m[mOffset + 12]
        out[outOffset + 13] = m[mOffset + 1] * x + m[mOffset + 5] * y + m[mOffset + 9] * z + m[mOffset + 13]
        out[outOffset + 14] = m[mOffset + 2] * x + m[mOffset + 6] * y + m[mOffset + 10] * z + m[mOffset + 14]
        out[outOffset + 15] = m[mOffset + 3] * x + m[mOffset + 7] * y + m[mOffset + 11] * z + m[mOffset + 15]
    }

    /** `out = m * rotateY(degrees)`. */
    fun rotateY(m: FloatArray, mOffset: Int, degrees: Float, out: FloatArray, outOffset: Int) {
        val radians = MathUtils.degToRad(degrees)
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        val m0 = m[mOffset + 0]; val m1 = m[mOffset + 1]; val m2 = m[mOffset + 2]; val m3 = m[mOffset + 3]
        val m8 = m[mOffset + 8]; val m9 = m[mOffset + 9]; val m10 = m[mOffset + 10]; val m11 = m[mOffset + 11]
        copyInto(m, mOffset, out, outOffset)
        out[outOffset + 0] = m0 * c + m8 * s
        out[outOffset + 1] = m1 * c + m9 * s
        out[outOffset + 2] = m2 * c + m10 * s
        out[outOffset + 3] = m3 * c + m11 * s
        out[outOffset + 8] = m8 * c - m0 * s
        out[outOffset + 9] = m9 * c - m1 * s
        out[outOffset + 10] = m10 * c - m2 * s
        out[outOffset + 11] = m11 * c - m3 * s
    }

    /** `out = m * rotateZ(degrees)`. */
    fun rotateZ(m: FloatArray, mOffset: Int, degrees: Float, out: FloatArray, outOffset: Int) {
        val radians = MathUtils.degToRad(degrees)
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        val m0 = m[mOffset + 0]; val m1 = m[mOffset + 1]; val m2 = m[mOffset + 2]; val m3 = m[mOffset + 3]
        val m4 = m[mOffset + 4]; val m5 = m[mOffset + 5]; val m6 = m[mOffset + 6]; val m7 = m[mOffset + 7]
        copyInto(m, mOffset, out, outOffset)
        out[outOffset + 0] = m0 * c + m4 * s
        out[outOffset + 1] = m1 * c + m5 * s
        out[outOffset + 2] = m2 * c + m6 * s
        out[outOffset + 3] = m3 * c + m7 * s
        out[outOffset + 4] = m4 * c - m0 * s
        out[outOffset + 5] = m5 * c - m1 * s
        out[outOffset + 6] = m6 * c - m2 * s
        out[outOffset + 7] = m7 * c - m3 * s
    }

    /** `out = m * scale(sx, sy, sz)`. */
    fun scale(m: FloatArray, mOffset: Int, sx: Float, sy: Float, sz: Float, out: FloatArray, outOffset: Int) {
        val m0 = m[mOffset + 0]; val m1 = m[mOffset + 1]; val m2 = m[mOffset + 2]; val m3 = m[mOffset + 3]
        val m4 = m[mOffset + 4]; val m5 = m[mOffset + 5]; val m6 = m[mOffset + 6]; val m7 = m[mOffset + 7]
        val m8 = m[mOffset + 8]; val m9 = m[mOffset + 9]; val m10 = m[mOffset + 10]; val m11 = m[mOffset + 11]
        out[outOffset + 0] = m0 * sx; out[outOffset + 1] = m1 * sx; out[outOffset + 2] = m2 * sx; out[outOffset + 3] = m3 * sx
        out[outOffset + 4] = m4 * sy; out[outOffset + 5] = m5 * sy; out[outOffset + 6] = m6 * sy; out[outOffset + 7] = m7 * sy
        out[outOffset + 8] = m8 * sz; out[outOffset + 9] = m9 * sz; out[outOffset + 10] = m10 * sz; out[outOffset + 11] = m11 * sz
    }

    /** Right-handed perspective projection with depth in [-1, 1]. */
    fun setPerspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float, out: FloatArray, offset: Int = 0) {
        val f = 1f / tan(MathUtils.degToRad(fovYDegrees) * 0.5f)
        for (i in 0 until SIZE) out[offset + i] = 0f
        out[offset + 0] = f / aspect
        out[offset + 5] = f
        out[offset + 10] = (far + near) / (near - far)
        out[offset + 11] = -1f
        out[offset + 14] = 2f * far * near / (near - far)
    }

    /** Right-handed orthographic projection with depth in [-1, 1]. */
    fun setOrthographic(
        left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float,
        out: FloatArray, offset: Int = 0,
    ) {
        for (i in 0 until SIZE) out[offset + i] = 0f
        out[offset + 0] = 2f / (right - left)
        out[offset + 5] = 2f / (top - bottom)
        out[offset + 10] = -2f / (far - near)
        out[offset + 12] = -(right + left) / (right - left)
        out[offset + 13] = -(top + bottom) / (top - bottom)
        out[offset + 14] = -(far + near) / (far - near)
        out[offset + 15] = 1f
    }

    fun setLookAt(
        eyeX: Float, eyeY: Float, eyeZ: Float,
        centerX: Float, centerY: Float, centerZ: Float,
        upX: Float, upY: Float, upZ: Float,
        out: FloatArray, offset: Int = 0,
    ) {
        var fx = centerX - eyeX
        var fy = centerY - eyeY
        var fz = centerZ - eyeZ
        var len = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz)
        if (len < 1e-6f) { fx = 0f; fy = 0f; fz = -1f; len = 1f }
        fx /= len; fy /= len; fz /= len

        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        len = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
        if (len < 1e-6f) {
            // Degenerate: view direction parallel to `up`. Nudge the up vector.
            sx = 1f; sy = 0f; sz = 0f
        } else {
            sx /= len; sy /= len; sz /= len
        }

        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        out[offset + 0] = sx; out[offset + 1] = ux; out[offset + 2] = -fx; out[offset + 3] = 0f
        out[offset + 4] = sy; out[offset + 5] = uy; out[offset + 6] = -fy; out[offset + 7] = 0f
        out[offset + 8] = sz; out[offset + 9] = uz; out[offset + 10] = -fz; out[offset + 11] = 0f
        out[offset + 12] = -(sx * eyeX + sy * eyeY + sz * eyeZ)
        out[offset + 13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        out[offset + 14] = fx * eyeX + fy * eyeY + fz * eyeZ
        out[offset + 15] = 1f
    }

    /** General 4x4 inverse. Returns false (leaving [out] untouched) when singular. */
    fun invert(m: FloatArray, mOffset: Int, out: FloatArray, outOffset: Int): Boolean {
        val inv = FloatArray(16)

        inv[0] = m[mOffset + 5] * m[mOffset + 10] * m[mOffset + 15] -
            m[mOffset + 5] * m[mOffset + 11] * m[mOffset + 14] -
            m[mOffset + 9] * m[mOffset + 6] * m[mOffset + 15] +
            m[mOffset + 9] * m[mOffset + 7] * m[mOffset + 14] +
            m[mOffset + 13] * m[mOffset + 6] * m[mOffset + 11] -
            m[mOffset + 13] * m[mOffset + 7] * m[mOffset + 10]

        inv[4] = -m[mOffset + 4] * m[mOffset + 10] * m[mOffset + 15] +
            m[mOffset + 4] * m[mOffset + 11] * m[mOffset + 14] +
            m[mOffset + 8] * m[mOffset + 6] * m[mOffset + 15] -
            m[mOffset + 8] * m[mOffset + 7] * m[mOffset + 14] -
            m[mOffset + 12] * m[mOffset + 6] * m[mOffset + 11] +
            m[mOffset + 12] * m[mOffset + 7] * m[mOffset + 10]

        inv[8] = m[mOffset + 4] * m[mOffset + 9] * m[mOffset + 15] -
            m[mOffset + 4] * m[mOffset + 11] * m[mOffset + 13] -
            m[mOffset + 8] * m[mOffset + 5] * m[mOffset + 15] +
            m[mOffset + 8] * m[mOffset + 7] * m[mOffset + 13] +
            m[mOffset + 12] * m[mOffset + 5] * m[mOffset + 11] -
            m[mOffset + 12] * m[mOffset + 7] * m[mOffset + 9]

        inv[12] = -m[mOffset + 4] * m[mOffset + 9] * m[mOffset + 14] +
            m[mOffset + 4] * m[mOffset + 10] * m[mOffset + 13] +
            m[mOffset + 8] * m[mOffset + 5] * m[mOffset + 14] -
            m[mOffset + 8] * m[mOffset + 6] * m[mOffset + 13] -
            m[mOffset + 12] * m[mOffset + 5] * m[mOffset + 10] +
            m[mOffset + 12] * m[mOffset + 6] * m[mOffset + 9]

        inv[1] = -m[mOffset + 1] * m[mOffset + 10] * m[mOffset + 15] +
            m[mOffset + 1] * m[mOffset + 11] * m[mOffset + 14] +
            m[mOffset + 9] * m[mOffset + 2] * m[mOffset + 15] -
            m[mOffset + 9] * m[mOffset + 3] * m[mOffset + 14] -
            m[mOffset + 13] * m[mOffset + 2] * m[mOffset + 11] +
            m[mOffset + 13] * m[mOffset + 3] * m[mOffset + 10]

        inv[5] = m[mOffset + 0] * m[mOffset + 10] * m[mOffset + 15] -
            m[mOffset + 0] * m[mOffset + 11] * m[mOffset + 14] -
            m[mOffset + 8] * m[mOffset + 2] * m[mOffset + 15] +
            m[mOffset + 8] * m[mOffset + 3] * m[mOffset + 14] +
            m[mOffset + 12] * m[mOffset + 2] * m[mOffset + 11] -
            m[mOffset + 12] * m[mOffset + 3] * m[mOffset + 10]

        inv[9] = -m[mOffset + 0] * m[mOffset + 9] * m[mOffset + 15] +
            m[mOffset + 0] * m[mOffset + 11] * m[mOffset + 13] +
            m[mOffset + 8] * m[mOffset + 1] * m[mOffset + 15] -
            m[mOffset + 8] * m[mOffset + 3] * m[mOffset + 13] -
            m[mOffset + 12] * m[mOffset + 1] * m[mOffset + 11] +
            m[mOffset + 12] * m[mOffset + 3] * m[mOffset + 9]

        inv[13] = m[mOffset + 0] * m[mOffset + 9] * m[mOffset + 14] -
            m[mOffset + 0] * m[mOffset + 10] * m[mOffset + 13] -
            m[mOffset + 8] * m[mOffset + 1] * m[mOffset + 14] +
            m[mOffset + 8] * m[mOffset + 2] * m[mOffset + 13] +
            m[mOffset + 12] * m[mOffset + 1] * m[mOffset + 10] -
            m[mOffset + 12] * m[mOffset + 2] * m[mOffset + 9]

        inv[2] = m[mOffset + 1] * m[mOffset + 6] * m[mOffset + 15] -
            m[mOffset + 1] * m[mOffset + 7] * m[mOffset + 14] -
            m[mOffset + 5] * m[mOffset + 2] * m[mOffset + 15] +
            m[mOffset + 5] * m[mOffset + 3] * m[mOffset + 14] +
            m[mOffset + 13] * m[mOffset + 2] * m[mOffset + 7] -
            m[mOffset + 13] * m[mOffset + 3] * m[mOffset + 6]

        inv[6] = -m[mOffset + 0] * m[mOffset + 6] * m[mOffset + 15] +
            m[mOffset + 0] * m[mOffset + 7] * m[mOffset + 14] +
            m[mOffset + 4] * m[mOffset + 2] * m[mOffset + 15] -
            m[mOffset + 4] * m[mOffset + 3] * m[mOffset + 14] -
            m[mOffset + 12] * m[mOffset + 2] * m[mOffset + 7] +
            m[mOffset + 12] * m[mOffset + 3] * m[mOffset + 6]

        inv[10] = m[mOffset + 0] * m[mOffset + 5] * m[mOffset + 15] -
            m[mOffset + 0] * m[mOffset + 7] * m[mOffset + 13] -
            m[mOffset + 4] * m[mOffset + 1] * m[mOffset + 15] +
            m[mOffset + 4] * m[mOffset + 3] * m[mOffset + 13] +
            m[mOffset + 12] * m[mOffset + 1] * m[mOffset + 7] -
            m[mOffset + 12] * m[mOffset + 3] * m[mOffset + 5]

        inv[14] = -m[mOffset + 0] * m[mOffset + 5] * m[mOffset + 14] +
            m[mOffset + 0] * m[mOffset + 6] * m[mOffset + 13] +
            m[mOffset + 4] * m[mOffset + 1] * m[mOffset + 14] -
            m[mOffset + 4] * m[mOffset + 2] * m[mOffset + 13] -
            m[mOffset + 12] * m[mOffset + 1] * m[mOffset + 6] +
            m[mOffset + 12] * m[mOffset + 2] * m[mOffset + 5]

        inv[3] = -m[mOffset + 1] * m[mOffset + 6] * m[mOffset + 11] +
            m[mOffset + 1] * m[mOffset + 7] * m[mOffset + 10] +
            m[mOffset + 5] * m[mOffset + 2] * m[mOffset + 11] -
            m[mOffset + 5] * m[mOffset + 3] * m[mOffset + 10] -
            m[mOffset + 9] * m[mOffset + 2] * m[mOffset + 7] +
            m[mOffset + 9] * m[mOffset + 3] * m[mOffset + 6]

        inv[7] = m[mOffset + 0] * m[mOffset + 6] * m[mOffset + 11] -
            m[mOffset + 0] * m[mOffset + 7] * m[mOffset + 10] -
            m[mOffset + 4] * m[mOffset + 2] * m[mOffset + 11] +
            m[mOffset + 4] * m[mOffset + 3] * m[mOffset + 10] +
            m[mOffset + 8] * m[mOffset + 2] * m[mOffset + 7] -
            m[mOffset + 8] * m[mOffset + 3] * m[mOffset + 6]

        inv[11] = -m[mOffset + 0] * m[mOffset + 5] * m[mOffset + 11] +
            m[mOffset + 0] * m[mOffset + 7] * m[mOffset + 9] +
            m[mOffset + 4] * m[mOffset + 1] * m[mOffset + 11] -
            m[mOffset + 4] * m[mOffset + 3] * m[mOffset + 9] -
            m[mOffset + 8] * m[mOffset + 1] * m[mOffset + 7] +
            m[mOffset + 8] * m[mOffset + 3] * m[mOffset + 5]

        inv[15] = m[mOffset + 0] * m[mOffset + 5] * m[mOffset + 10] -
            m[mOffset + 0] * m[mOffset + 6] * m[mOffset + 9] -
            m[mOffset + 4] * m[mOffset + 1] * m[mOffset + 10] +
            m[mOffset + 4] * m[mOffset + 2] * m[mOffset + 9] +
            m[mOffset + 8] * m[mOffset + 1] * m[mOffset + 6] -
            m[mOffset + 8] * m[mOffset + 2] * m[mOffset + 5]

        val determinant = m[mOffset + 0] * inv[0] + m[mOffset + 1] * inv[4] +
            m[mOffset + 2] * inv[8] + m[mOffset + 3] * inv[12]
        if (kotlin.math.abs(determinant) < 1e-12f) return false

        val inverseDeterminant = 1f / determinant
        for (i in 0 until SIZE) out[outOffset + i] = inv[i] * inverseDeterminant
        return true
    }

    /**
     * Upper-left 3x3 inverse-transpose, written row-major into a 9-float array, suitable for
     * `glUniformMatrix3fv(..., transpose = false, ...)`.
     */
    fun normalMatrix3(m: FloatArray, mOffset: Int, out: FloatArray, outOffset: Int = 0) {
        val a00 = m[mOffset + 0]; val a01 = m[mOffset + 1]; val a02 = m[mOffset + 2]
        val a10 = m[mOffset + 4]; val a11 = m[mOffset + 5]; val a12 = m[mOffset + 6]
        val a20 = m[mOffset + 8]; val a21 = m[mOffset + 9]; val a22 = m[mOffset + 10]

        val c00 = a11 * a22 - a12 * a21
        val c01 = a12 * a20 - a10 * a22
        val c02 = a10 * a21 - a11 * a20
        val determinant = a00 * c00 + a01 * c01 + a02 * c02
        if (kotlin.math.abs(determinant) < 1e-12f) {
            // Fall back to the plain rotation part.
            out[outOffset + 0] = a00; out[outOffset + 1] = a01; out[outOffset + 2] = a02
            out[outOffset + 3] = a10; out[outOffset + 4] = a11; out[outOffset + 5] = a12
            out[outOffset + 6] = a20; out[outOffset + 7] = a21; out[outOffset + 8] = a22
            return
        }
        val inverseDeterminant = 1f / determinant

        // Inverse = (1/det) * adjugate, then transpose so rows become columns of the inverse.
        out[outOffset + 0] = c00 * inverseDeterminant
        out[outOffset + 3] = c01 * inverseDeterminant
        out[outOffset + 6] = c02 * inverseDeterminant

        out[outOffset + 1] = (a02 * a21 - a01 * a22) * inverseDeterminant
        out[outOffset + 4] = (a00 * a22 - a02 * a20) * inverseDeterminant
        out[outOffset + 7] = (a01 * a20 - a00 * a21) * inverseDeterminant

        out[outOffset + 2] = (a01 * a12 - a02 * a11) * inverseDeterminant
        out[outOffset + 5] = (a02 * a10 - a00 * a12) * inverseDeterminant
        out[outOffset + 8] = (a00 * a11 - a01 * a10) * inverseDeterminant
    }

    fun transformPoint(m: FloatArray, mOffset: Int, x: Float, y: Float, z: Float, out: Vec3): Vec3 {
        val w = m[mOffset + 3] * x + m[mOffset + 7] * y + m[mOffset + 11] * z + m[mOffset + 15]
        val inverseW = if (kotlin.math.abs(w) > 1e-9f) 1f / w else 1f
        out.x = (m[mOffset + 0] * x + m[mOffset + 4] * y + m[mOffset + 8] * z + m[mOffset + 12]) * inverseW
        out.y = (m[mOffset + 1] * x + m[mOffset + 5] * y + m[mOffset + 9] * z + m[mOffset + 13]) * inverseW
        out.z = (m[mOffset + 2] * x + m[mOffset + 6] * y + m[mOffset + 10] * z + m[mOffset + 14]) * inverseW
        return out
    }

    fun transformDirection(m: FloatArray, mOffset: Int, x: Float, y: Float, z: Float, out: Vec3): Vec3 {
        out.x = m[mOffset + 0] * x + m[mOffset + 4] * y + m[mOffset + 8] * z
        out.y = m[mOffset + 1] * x + m[mOffset + 5] * y + m[mOffset + 9] * z
        out.z = m[mOffset + 2] * x + m[mOffset + 6] * y + m[mOffset + 10] * z
        return out
    }
}
