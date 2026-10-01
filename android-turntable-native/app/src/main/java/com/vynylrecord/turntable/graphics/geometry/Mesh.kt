package com.vynylrecord.turntable.graphics.geometry

/**
 * Interleaved vertex layout shared by every mesh in the scene.
 *
 * ```
 *  offset  0 : vec3 position  (metres, model space)
 *  offset  3 : vec3 normal
 *  offset  6 : vec2 texCoord  (discs: x = angle / 2PI, y = normalised radius)
 *  offset  8 : vec3 tangent   (circumferential on discs, lengthwise on brushed parts)
 * ```
 *
 * 11 floats / 44 bytes per vertex. Indexed drawing with 16-bit indices keeps the whole
 * turntable comfortably below the 65 535 vertex ceiling per mesh.
 */
object VertexFormat {
    const val FLOATS_PER_VERTEX = 11
    const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

    const val POSITION_OFFSET = 0
    const val NORMAL_OFFSET = 3
    const val TEX_COORD_OFFSET = 6
    const val TANGENT_OFFSET = 8

    const val ATTRIB_POSITION = 0
    const val ATTRIB_NORMAL = 1
    const val ATTRIB_TEX_COORD = 2
    const val ATTRIB_TANGENT = 3

    const val MAX_VERTICES = 65_535
}

/** Axis-aligned bounds plus a bounding sphere used for cheap frustum rejection. */
class Bounds {
    @JvmField var minX = Float.MAX_VALUE
    @JvmField var minY = Float.MAX_VALUE
    @JvmField var minZ = Float.MAX_VALUE
    @JvmField var maxX = -Float.MAX_VALUE
    @JvmField var maxY = -Float.MAX_VALUE
    @JvmField var maxZ = -Float.MAX_VALUE

    @JvmField var centerX = 0f
    @JvmField var centerY = 0f
    @JvmField var centerZ = 0f
    @JvmField var radius = 0f

    fun include(x: Float, y: Float, z: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (z < minZ) minZ = z
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        if (z > maxZ) maxZ = z
    }

    fun finish() {
        centerX = (minX + maxX) * 0.5f
        centerY = (minY + maxY) * 0.5f
        centerZ = (minZ + maxZ) * 0.5f
        val dx = maxX - centerX
        val dy = maxY - centerY
        val dz = maxZ - centerZ
        radius = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    override fun toString(): String =
        "Bounds(x=[$minX, $maxX], y=[$minY, $maxY], z=[$minZ, $maxZ], r=$radius)"
}

/**
 * Immutable CPU-side mesh. Uploaded once to a [com.vynylrecord.turntable.graphics.gl.GpuMesh]
 * and never rebuilt per frame.
 */
class Mesh(
    val name: String,
    val vertices: FloatArray,
    val indices: ShortArray,
    val vertexCount: Int,
    val indexCount: Int,
    val bounds: Bounds,
) {
    val triangleCount: Int get() = indexCount / 3

    override fun toString(): String =
        "Mesh($name, ${vertexCount}v, ${triangleCount}t, r=${bounds.radius})"
}
