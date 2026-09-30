package com.vynylrecord.turntable.graphics.gl

import android.opengl.GLES30

/**
 * Single-colour-attachment framebuffer backed by a texture, with no depth buffer.
 *
 * Used for the procedural backdrop pass: the environment is cheap to evaluate but expensive to
 * resolve at full resolution, so it is generated at half the scene resolution and sampled by the
 * composite. Filtering is bilinear and the wrap mode clamps, so no seams appear when the render
 * target is resized.
 */
class SimpleTextureTarget {

    var width = 0
        private set
    var height = 0
        private set

    private var framebuffer = 0
    private var texture = 0

    fun ensure(targetWidth: Int, targetHeight: Int): Boolean {
        val safeWidth = targetWidth.coerceAtLeast(8)
        val safeHeight = targetHeight.coerceAtLeast(8)
        if (framebuffer != 0 && width == safeWidth && height == safeHeight) return true
        release()

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, safeWidth, safeHeight, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        framebuffer = GLES30.glGenFramebuffers()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            release()
            return false
        }
        width = safeWidth
        height = safeHeight
        return true
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
    }

    fun textureHandle(): Int = texture

    fun release() {
        if (framebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            framebuffer = 0
        }
        if (texture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        }
        width = 0
        height = 0
    }
}
