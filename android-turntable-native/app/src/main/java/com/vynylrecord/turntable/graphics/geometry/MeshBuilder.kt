package com.vynylrecord.turntable.graphics.geometry

import com.vynylrecord.turntable.model.Mat4

/**
 * Growable builder for indexed meshes.
 *
 * Meshes are generated once during renderer initialisation (or when the quality level
 * changes), never inside the draw loop. Capacities are pre-sized from a triangle estimate so
 * typical builds do not reallocate.
 */
class MeshBuilder(name: String, vertexEstimate: Int = 256) {

    private val meshName = name
    private var vertexData = FloatArray(vertexEstimate.coerceAtLeast(16) * VertexFormat.FLOATS_PER_VERTEX)
    private var indexData = ShortArray(vertexEstimate.coerceAtLeast(16) * 6)

    /** Scratch buffers reused by [append] so transforms never allocate. */
    private val scratchInverse = FloatArray(16)
    private val scratchNormal3 = FloatArray(9)
    private val scratchTangent3 = FloatArray(9)

    var vertexCount: Int = 0
        private set

    var indexCount: Int = 0
        private set

    private val bounds = Bounds()

    private fun ensureVertexCapacity(extraVertices: Int) {
        val needed = (vertexCount + extraVertices) * VertexFormat.FLOATS_PER_VERTEX
        if (needed <= vertexData.size) return
        var newSize = vertexData.size
        while (newSize < needed) newSize *= 2
        vertexData = vertexData.copyOf(newSize)
    }

    private fun ensureIndexCapacity(extraIndices: Int) {
        val needed = indexCount + extraIndices
        if (needed <= indexData.size) return
        var newSize = indexData.size
        while (newSize < needed) newSize *= 2
        indexData = indexData.copyOf(newSize)
    }

    /** Appends a fully specified vertex and returns its index. */
    fun addVertex(
        px: Float, py: Float, pz: Float,
        nx: Float, ny: Float, nz: Float,
        u: Float, v: Float,
        tx: Float, ty: Float, tz: Float,
    ): Int {
        check(vertexCount < VertexFormat.MAX_VERTICES) {
            "$meshName exceeded the 16-bit index limit (${VertexFormat.MAX_VERTICES} vertices)"
        }
        ensureVertexCapacity(1)
        val offset = vertexCount * VertexFormat.FLOATS_PER_VERTEX
        vertexData[offset + 0] = px
        vertexData[offset + 1] = py
        vertexData[offset + 2] = pz
        vertexData[offset + 3] = nx
        vertexData[offset + 4] = ny
        vertexData[offset + 5] = nz
        vertexData[offset + 6] = u
        vertexData[offset + 7] = v
        vertexData[offset + 8] = tx
        vertexData[offset + 9] = ty
        vertexData[offset + 10] = tz
        bounds.include(px, py, pz)
        return vertexCount++
    }

    fun addPositionOnly(px: Float, py: Float, pz: Float): Int = addVertex(
        px, py, pz,
        0f, 1f, 0f,
        0f, 0f,
        1f, 0f, 0f,
    )

    fun triangle(a: Int, b: Int, c: Int) {
        ensureIndexCapacity(3)
        indexData[indexCount++] = a.toShort()
        indexData[indexCount++] = b.toShort()
        indexData[indexCount++] = c.toShort()
    }

    /** Counter-clockwise quad split into two triangles. */
    fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    /**
     * Same winding as [quad] but rotated so the shared edge is a-c on both halves; used where
     * the diagonal direction matters for shading stability on near-planar quads.
     */
    fun quadAlternate(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, d)
        triangle(b, c, d)
    }

    /** Writes the running index into the vertex stream (for de-indexed helpers). */
    fun lastVertexIndex(): Int = vertexCount - 1

    fun build(): Mesh {
        bounds.finish()
        val vertices = vertexData.copyOf(vertexCount * VertexFormat.FLOATS_PER_VERTEX)
        val indices = indexData.copyOf(indexCount)
        orientTrianglesWithNormals(vertices, indices)
        return Mesh(
            name = meshName,
            vertices = vertices,
            indices = indices,
            vertexCount = vertexCount,
            indexCount = indexCount,
            bounds = bounds,
        )
    }

    /**
     * Appends [mesh] transformed by [transform] (model matrix, column-major, 16 floats).
     * Pass `null` to append in place. Winding is preserved: only the caller-visible result
     * matters and mirrored transforms must be avoided or the winding patched by hand.
     */
    fun append(mesh: Mesh, transform: FloatArray?, transformOffset: Int = 0) {
        val base = vertexCount
        ensureVertexCapacity(mesh.vertexCount)
        ensureIndexCapacity(mesh.indexCount)

        if (transform == null) {
            System.arraycopy(
                mesh.vertices, 0, vertexData, base * VertexFormat.FLOATS_PER_VERTEX,
                mesh.vertexCount * VertexFormat.FLOATS_PER_VERTEX,
            )
            for (i in 0 until mesh.vertexCount) {
                val o = (base + i) * VertexFormat.FLOATS_PER_VERTEX
                bounds.include(vertexData[o], vertexData[o + 1], vertexData[o + 2])
            }
        } else {
            Mat4.invert(transform, transformOffset, scratchInverse, 0)
            // Transpose of the inverse gives the correct 3x3 normal transform; the tangent is a
            // surface direction so it only needs the plain 3x3.
            scratchNormal3[0] = scratchInverse[0]; scratchNormal3[1] = scratchInverse[4]; scratchNormal3[2] = scratchInverse[8]
            scratchNormal3[3] = scratchInverse[1]; scratchNormal3[4] = scratchInverse[5]; scratchNormal3[5] = scratchInverse[9]
            scratchNormal3[6] = scratchInverse[2]; scratchNormal3[7] = scratchInverse[6]; scratchNormal3[8] = scratchInverse[10]
            scratchTangent3[0] = transform[transformOffset + 0]; scratchTangent3[1] = transform[transformOffset + 1]; scratchTangent3[2] = transform[transformOffset + 2]
            scratchTangent3[3] = transform[transformOffset + 4]; scratchTangent3[4] = transform[transformOffset + 5]; scratchTangent3[5] = transform[transformOffset + 6]
            scratchTangent3[6] = transform[transformOffset + 8]; scratchTangent3[7] = transform[transformOffset + 9]; scratchTangent3[8] = transform[transformOffset + 10]

            for (i in 0 until mesh.vertexCount) {
                val src = i * VertexFormat.FLOATS_PER_VERTEX
                val dst = (base + i) * VertexFormat.FLOATS_PER_VERTEX
                val px = mesh.vertices[src + 0]
                val py = mesh.vertices[src + 1]
                val pz = mesh.vertices[src + 2]
                val nx = mesh.vertices[src + 3]
                val ny = mesh.vertices[src + 4]
                val nz = mesh.vertices[src + 5]
                val tx = mesh.vertices[src + 8]
                val ty = mesh.vertices[src + 9]
                val tz = mesh.vertices[src + 10]

                vertexData[dst + 0] = transform[transformOffset + 0] * px + transform[transformOffset + 4] * py +
                    transform[transformOffset + 8] * pz + transform[transformOffset + 12]
                vertexData[dst + 1] = transform[transformOffset + 1] * px + transform[transformOffset + 5] * py +
                    transform[transformOffset + 9] * pz + transform[transformOffset + 13]
                vertexData[dst + 2] = transform[transformOffset + 2] * px + transform[transformOffset + 6] * py +
                    transform[transformOffset + 10] * pz + transform[transformOffset + 14]

                vertexData[dst + 3] = normalizeOrFallback(
                    scratchNormal3[0] * nx + scratchNormal3[3] * ny + scratchNormal3[6] * nz,
                    scratchNormal3[1] * nx + scratchNormal3[4] * ny + scratchNormal3[7] * nz,
                    scratchNormal3[2] * nx + scratchNormal3[5] * ny + scratchNormal3[8] * nz,
                    0f, 1f, 0f, dst + 3,
                )
                vertexData[dst + 6] = mesh.vertices[src + 6]
                vertexData[dst + 7] = mesh.vertices[src + 7]
                vertexData[dst + 8] = normalizeOrFallback(
                    scratchTangent3[0] * tx + scratchTangent3[3] * ty + scratchTangent3[6] * tz,
                    scratchTangent3[1] * tx + scratchTangent3[4] * ty + scratchTangent3[7] * tz,
                    scratchTangent3[2] * tx + scratchTangent3[5] * ty + scratchTangent3[8] * tz,
                    1f, 0f, 0f, dst + 8,
                )
                bounds.include(vertexData[dst], vertexData[dst + 1], vertexData[dst + 2])
            }
        }

        for (i in 0 until mesh.indexCount) {
            indexData[indexCount + i] = (mesh.indices[i] + base).toShort()
        }
        indexCount += mesh.indexCount
        vertexCount += mesh.vertexCount
    }

    @Suppress("LongParameterList")
    private fun normalizeOrFallback(
        x: Float, y: Float, z: Float,
        fx: Float, fy: Float, fz: Float,
        offset: Int,
    ) {
        val length = kotlin.math.sqrt(x * x + y * y + z * z)
        if (length > 1e-8f) {
            vertexData[offset] = x / length
            vertexData[offset + 1] = y / length
            vertexData[offset + 2] = z / length
        } else {
            vertexData[offset] = fx
            vertexData[offset + 1] = fy
            vertexData[offset + 2] = fz
        }
    }

    /** Convenience: appends [mesh] translated in place. */
    fun appendTranslated(mesh: Mesh, x: Float, y: Float, z: Float) {
        val translation = translationScratch
        Mat4.setTranslation(x, y, z, translation, 0)
        append(mesh, translation, 0)
    }

    private val translationScratch = FloatArray(16)
}

/**
 * Makes every triangle's winding agree with the vertex normals it was given.
 *
 * The generators describe surfaces parametrically, and a lathe and a disc do not agree on which way
 * "counter-clockwise" runs once their caps are emitted. Rather than hand-auditing forty loops, the
 * builder compares each triangle's geometric normal against its averaged vertex normals and flips
 * the two non-adjacent indices when they disagree. After this pass the renderer can enable
 * back-face culling with the default counter-clockwise front face and trust it: a triangle is
 * culled exactly when its normals face away from the camera.
 *
 * Runs once per mesh build (renderer initialisation or a quality change), never per frame.
 */
private fun orientTrianglesWithNormals(vertices: FloatArray, indices: ShortArray) {
    val stride = VertexFormat.FLOATS_PER_VERTEX
    var triangle = 0
    while (triangle + 2 < indices.size) {
        val ia = indices[triangle].toInt()
        val ib = indices[triangle + 1].toInt()
        val ic = indices[triangle + 2].toInt()
        triangle += 3
        if (ia < 0 || ib < 0 || ic < 0) continue
        if (ia >= vertices.size / stride || ib >= vertices.size / stride || ic >= vertices.size / stride) continue

        val ax = vertices[ia * stride]
        val ay = vertices[ia * stride + 1]
        val az = vertices[ia * stride + 2]
        val abx = vertices[ib * stride] - ax
        val aby = vertices[ib * stride + 1] - ay
        val abz = vertices[ib * stride + 2] - az
        val acx = vertices[ic * stride] - ax
        val acy = vertices[ic * stride + 1] - ay
        val acz = vertices[ic * stride + 2] - az

        // Right-hand-rule normal of the emitted winding.
        val gx = aby * acz - abz * acy
        val gy = abz * acx - abx * acz
        val gz = abx * acy - aby * acx

        // Average of the three vertex normals: the direction the surface says it faces.
        val nx = vertices[ia * stride + 3] + vertices[ib * stride + 3] + vertices[ic * stride + 3]
        val ny = vertices[ia * stride + 4] + vertices[ib * stride + 4] + vertices[ic * stride + 4]
        val nz = vertices[ia * stride + 5] + vertices[ib * stride + 5] + vertices[ic * stride + 5]
        val agreement = gx * nx + gy * ny + gz * nz
        if (agreement < 0f) {
            indices[triangle - 2] = ic.toShort()
            indices[triangle - 1] = ib.toShort()
        }
    }
}
