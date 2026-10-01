package com.vynylrecord.turntable.graphics.gl

import android.opengl.GLES30
import com.vynylrecord.turntable.graphics.geometry.Mesh
import com.vynylrecord.turntable.graphics.geometry.VertexFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Immutable GPU-side mesh: one VAO plus an immutable VBO/EBO pair.
 *
 * Meshes are uploaded exactly once per quality level (or per style for the label texture) and
 * reused every frame, satisfying the "do not generate geometry every frame" requirement. Vertex
 * attribute pointers live in the VAO, so drawing is a single `glDrawElements` call with no
 * per-frame state setup beyond the program and uniforms.
 */
class GpuMesh(val mesh: Mesh) {

    private var vertexBuffer = 0
    private var indexBuffer = 0
    private var vertexArray = 0

    val triangleCount: Int get() = mesh.triangleCount

    val isUploaded: Boolean get() = vertexArray != 0

    /** Allocates GL objects. Must be called on the GL thread with a current context. */
    fun upload() {
        if (vertexArray != 0) return

        val vertexByteCount = mesh.vertexCount * VertexFormat.STRIDE_BYTES
        val vertexBufferData = ByteBuffer.allocateDirect(vertexByteCount)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBufferData.put(mesh.vertices, 0, mesh.vertexCount * VertexFormat.FLOATS_PER_VERTEX)
        vertexBufferData.position(0)

        val indexByteCount = mesh.indexCount * 2
        val indexBufferData = ByteBuffer.allocateDirect(indexByteCount)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        indexBufferData.put(mesh.indices, 0, mesh.indexCount)
        indexBufferData.position(0)

        val handles = IntArray(3)
        GLES30.glGenBuffers(2, handles, 0)
        vertexBuffer = handles[0]
        indexBuffer = handles[1]
        vertexArray = GLES30.glGenVertexArrays()

        GLES30.glBindVertexArray(vertexArray)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexByteCount, vertexBufferData, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexByteCount, indexBufferData, GLES30.GL_STATIC_DRAW)

        val stride = VertexFormat.STRIDE_BYTES
        GLES30.glEnableVertexAttribArray(VertexFormat.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            VertexFormat.ATTRIB_POSITION, 3, GLES30.GL_FLOAT, false, stride,
            VertexFormat.POSITION_OFFSET * 4,
        )
        GLES30.glEnableVertexAttribArray(VertexFormat.ATTRIB_NORMAL)
        GLES30.glVertexAttribPointer(
            VertexFormat.ATTRIB_NORMAL, 3, GLES30.GL_FLOAT, false, stride,
            VertexFormat.NORMAL_OFFSET * 4,
        )
        GLES30.glEnableVertexAttribArray(VertexFormat.ATTRIB_TEX_COORD)
        GLES30.glVertexAttribPointer(
            VertexFormat.ATTRIB_TEX_COORD, 2, GLES30.GL_FLOAT, false, stride,
            VertexFormat.TEX_COORD_OFFSET * 4,
        )
        GLES30.glEnableVertexAttribArray(VertexFormat.ATTRIB_TANGENT)
        GLES30.glVertexAttribPointer(
            VertexFormat.ATTRIB_TANGENT, 3, GLES30.GL_FLOAT, false, stride,
            VertexFormat.TANGENT_OFFSET * 4,
        )

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Bind the VAO and issue the draw call. No allocation, no error checks on this path. */
    fun draw() {
        if (vertexArray == 0) return
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    fun release() {
        if (vertexBuffer != 0) {
            GLES30.glDeleteBuffers(2, intArrayOf(vertexBuffer, indexBuffer), 0)
            vertexBuffer = 0
            indexBuffer = 0
        }
        if (vertexArray != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
            vertexArray = 0
        }
    }
}
