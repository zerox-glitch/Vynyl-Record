package com.vynylrecord.turntable.model

/**
 * Minimal mutable 3-component vector.
 *
 * The renderer keeps a fixed pool of these and mutates them in place: the draw loop must
 * never allocate, and a value-type style API would allocate one instance per operation.
 */
class Vec3(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var z: Float = 0f,
) {
    fun set(x: Float, y: Float, z: Float): Vec3 {
        this.x = x; this.y = y; this.z = z
        return this
    }

    fun set(other: Vec3): Vec3 = set(other.x, other.y, other.z)

    fun setZero(): Vec3 = set(0f, 0f, 0f)

    fun add(other: Vec3): Vec3 {
        x += other.x; y += other.y; z += other.z
        return this
    }

    fun addScaled(other: Vec3, scale: Float): Vec3 {
        x += other.x * scale; y += other.y * scale; z += other.z * scale
        return this
    }

    fun subtract(other: Vec3): Vec3 {
        x -= other.x; y -= other.y; z -= other.z
        return this
    }

    fun scale(factor: Float): Vec3 {
        x *= factor; y *= factor; z *= factor
        return this
    }

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    /** Writes `this x other` into [out]. Supports aliasing with [other] but not with `this`. */
    fun cross(other: Vec3, out: Vec3): Vec3 {
        val cx = y * other.z - z * other.y
        val cy = z * other.x - x * other.z
        val cz = x * other.y - y * other.x
        return out.set(cx, cy, cz)
    }

    fun lengthSquared(): Float = x * x + y * y + z * z

    fun length(): Float = kotlin.math.sqrt(lengthSquared())

    fun normalize(): Vec3 {
        val len = length()
        if (len > 1e-8f) scale(1f / len) else set(0f, 0f, 0f)
        return this
    }

    fun distanceTo(other: Vec3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun copy(): Vec3 = Vec3(x, y, z)

    override fun toString(): String = "(%.4f, %.4f, %.4f)".format(x, y, z)
}
