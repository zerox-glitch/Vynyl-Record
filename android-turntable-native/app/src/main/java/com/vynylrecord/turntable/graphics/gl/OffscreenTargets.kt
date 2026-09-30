package com.vynylrecord.turntable.graphics.gl

import android.opengl.GLES30
import android.util.Log

/**
 * Offscreen render targets for the scene pass.
 *
 * The turntable is drawn into an offscreen buffer so the backdrop can be composited behind it
 * (the scene pass writes premultiplied alpha) and so hardware MSAA can be requested without
 * paying for it on the final full-screen composite.
 *
 * Two configurations are supported and both are exercised on device:
 *
 *  * `samples > 0` - multisampled colour + depth renderbuffers, resolved into a texture with
 *    `glBlitFramebuffer` before the composite pass. MSAA quality with no post-process blur.
 *  * `samples == 0` - single-sample texture colour attachment. The composite pass falls back to
 *    its FXAA-style edge resolve, which is what low-end parts get.
 *
 * The multisampled path is verified with `glCheckFramebufferStatus`; if the driver refuses it the
 * targets silently downgrade to single sample so the app never renders into an incomplete FBO.
 */
class OffscreenTargets {

    var width = 0
        private set
    var height = 0
        private set
    var samples = 0
        private set

    private var sceneFramebuffer = 0
    private var sceneColour = 0
    private var sceneDepth = 0

    private var resolveFramebuffer = 0
    private var resolveTexture = 0
    private var resolveDepth = 0

    val isMultisampled: Boolean get() = samples > 0

    fun colourTexture(): Int = resolveTexture

    /**
     * Ensures the targets match [requestedWidth] x [requestedHeight].
     *
     * @param requestedSamples 0, 2 or 4. Values above the driver maximum are clamped.
     * @return true when the targets are usable.
     */
    fun ensure(requestedWidth: Int, requestedHeight: Int, requestedSamples: Int): Boolean {
        val safeWidth = requestedWidth.coerceAtLeast(16)
        val safeHeight = requestedHeight.coerceAtLeast(16)
        if (sceneFramebuffer != 0 && width == safeWidth && height == safeHeight && samples == requestedSamples) {
            return true
        }
        release()

        val maximumSamples = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, maximumSamples, 0)
        val clampedSamples = requestedSamples.coerceIn(0, maximumSamples[0].coerceAtLeast(0))
        if (clampedSamples > 0) {
            if (createMultisampled(safeWidth, safeHeight, clampedSamples)) {
                width = safeWidth
                height = safeHeight
                samples = clampedSamples
                createResolveTarget(safeWidth, safeHeight)
                return true
            }
            Log.w(GlUtil.TAG, "Multisampled framebuffer unavailable, falling back to single sample")
        }

        if (!createSingleSample(safeWidth, safeHeight)) return false
        width = safeWidth
        height = safeHeight
        samples = 0
        return true
    }

    private fun createMultisampled(targetWidth: Int, targetHeight: Int, requestedSamples: Int): Boolean {
        val handles = IntArray(2)
        GLES30.glGenRenderbuffers(2, handles, 0)
        sceneColour = handles[0]
        sceneDepth = handles[1]

        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneColour)
        GLES30.glRenderbufferStorageMultisample(
            GLES30.GL_RENDERBUFFER, requestedSamples, GLES30.GL_RGBA8, targetWidth, targetHeight,
        )
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneDepth)
        GLES30.glRenderbufferStorageMultisample(
            GLES30.GL_RENDERBUFFER, requestedSamples, GLES30.GL_DEPTH_COMPONENT24, targetWidth, targetHeight,
        )

        sceneFramebuffer = GLES30.glGenFramebuffers()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFramebuffer)
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, sceneColour,
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, sceneDepth,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(GlUtil.TAG, "MSAA framebuffer incomplete: 0x%04X".format(status))
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFramebuffer), 0)
            GLES30.glDeleteRenderbuffers(2, intArrayOf(sceneColour, sceneDepth), 0)
            sceneFramebuffer = 0
            sceneColour = 0
            sceneDepth = 0
            return false
        }
        return true
    }

    private fun createResolveTarget(targetWidth: Int, targetHeight: Int) {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        resolveTexture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, resolveTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, targetWidth, targetHeight, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        resolveFramebuffer = GLES30.glGenFramebuffers()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resolveFramebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, resolveTexture, 0,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun createSingleSample(targetWidth: Int, targetHeight: Int): Boolean {
        createResolveTarget(targetWidth, targetHeight)

        val handles = IntArray(1)
        GLES30.glGenRenderbuffers(1, handles, 0)
        resolveDepth = handles[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, resolveDepth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, targetWidth, targetHeight)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resolveFramebuffer)
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, resolveDepth,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(GlUtil.TAG, "Single-sample framebuffer incomplete: 0x%04X".format(status))
            return false
        }
        return true
    }

    /** Binds the buffer the scene pass should render into. */
    fun bindForScene() {
        val target = if (isMultisampled) sceneFramebuffer else resolveFramebuffer
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(0, 0, width, height)
    }

    /** Resolves multisampled contents into the sampleable texture. No-op when not multisampled. */
    fun resolve() {
        if (!isMultisampled) return
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, sceneFramebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFramebuffer)
        GLES30.glBlitFramebuffer(
            0, 0, width, height,
            0, 0, width, height,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
        )
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
    }

    fun release() {
        if (sceneFramebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFramebuffer), 0)
            sceneFramebuffer = 0
        }
        if (sceneColour != 0 || sceneDepth != 0) {
            GLES30.glDeleteRenderbuffers(2, intArrayOf(sceneColour, sceneDepth), 0)
            sceneColour = 0
            sceneDepth = 0
        }
        if (resolveDepth != 0) {
            GLES30.glDeleteRenderbuffers(1, intArrayOf(resolveDepth), 0)
            resolveDepth = 0
        }
        if (resolveFramebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(resolveFramebuffer), 0)
            resolveFramebuffer = 0
        }
        if (resolveTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(resolveTexture), 0)
            resolveTexture = 0
        }
        width = 0
        height = 0
        samples = 0
    }
}
