package com.vynylrecord.turntable.graphics.gl

import android.opengl.GLES30
import android.util.Log

/** Thin helpers around the raw GLES 3.0 entry points used by the renderer. */
object GlUtil {

    const val TAG = "VynylGL"

    /** Drains the GL error queue and logs it. Debug builds only; no-op on the hot path. */
    fun checkGlError(operation: String): Int {
        var error = GLES30.glGetError()
        var first = error
        var guard = 0
        while (error != GLES30.GL_NO_ERROR && guard < 8) {
            Log.e(TAG, "$operation failed: ${glErrorName(error)}")
            error = GLES30.glGetError()
            guard++
        }
        return first
    }

    fun glErrorName(error: Int): String = when (error) {
        GLES30.GL_INVALID_ENUM -> "GL_INVALID_ENUM"
        GLES30.GL_INVALID_VALUE -> "GL_INVALID_VALUE"
        GLES30.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION"
        GLES30.GL_INVALID_FRAMEBUFFER_OPERATION -> "GL_INVALID_FRAMEBUFFER_OPERATION"
        GLES30.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY"
        else -> "0x%04X".format(error)
    }

    fun clearErrors() {
        var guard = 0
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR && guard < 16) guard++
    }

    /** Creates a GL buffer object holding [data]. */
    fun createBuffer(target: Int, data: java.nio.Buffer): Int {
        val handles = IntArray(1)
        GLES30.glGenBuffers(1, handles, 0)
        val handle = handles[0]
        GLES30.glBindBuffer(target, handle)
        GLES30.glBufferData(target, data.remaining() * elementSize(data), data, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(target, 0)
        return handle
    }

    private fun elementSize(buffer: java.nio.Buffer): Int = when (buffer) {
        is java.nio.FloatBuffer -> 4
        is java.nio.ShortBuffer -> 2
        is java.nio.IntBuffer -> 4
        else -> 1
    }

    fun deleteBuffer(handle: Int) {
        if (handle == 0) return
        GLES30.glDeleteBuffers(1, intArrayOf(handle), 0)
    }

    fun deleteTexture(handle: Int) {
        if (handle == 0) return
        GLES30.glDeleteTextures(1, intArrayOf(handle), 0)
    }

    fun deleteFramebuffer(handle: Int) {
        if (handle == 0) return
        GLES30.glDeleteFramebuffers(1, intArrayOf(handle), 0)
    }

    fun deleteRenderbuffer(handle: Int) {
        if (handle == 0) return
        GLES30.glDeleteRenderbuffers(1, intArrayOf(handle), 0)
    }
}
