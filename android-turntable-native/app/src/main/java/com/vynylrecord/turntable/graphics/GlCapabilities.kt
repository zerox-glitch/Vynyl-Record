package com.vynylrecord.turntable.graphics

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES30
import android.util.Log
import com.vynylrecord.turntable.graphics.gl.GlUtil

/**
 * What the current device can actually do.
 *
 * [isEs3Available] uses the ActivityManager's reported GLES version, which is available before
 * any GL context exists - that is what drives the "static Compose fallback" decision. The rest
 * is filled in once the renderer's context is created and is used to pick a safe default quality
 * level and to clamp the requested MSAA sample count.
 */
class GlCapabilities(
    val esVersion: Int,
    val renderer: String,
    val vendor: String,
    val maxTextureSize: Int,
    val maxSamples: Int,
    val isLowRamDevice: Boolean,
) {
    val isEs3: Boolean get() = esVersion >= 0x30000

    /** Conservative descriptor handed to the UI/debug overlay. */
    fun describe(): String = "ES ${esVersion shr 16}.${(esVersion shr 8) and 0xF} · $renderer"

    companion object {
        const val TAG = "VynylGL"

        /**
         * Pre-context check. [ActivityManager.getDeviceConfigurationInfo] reports the highest
         * GLES version the device advertises, so no EGL surface is needed to decide.
         */
        fun isEs3Available(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            return try {
                activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to query GLES version", error)
                false
            }
        }

        /** Queries the live context. Must be called on the GL thread. */
        fun detectFromContext(context: Context): GlCapabilities {
            val version = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
            val major = if (version[0] != 0) version[0] else 3
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, version, 0)
            val minor = version[0]

            val maxTexture = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0)

            val maxSamples = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, maxSamples, 0)

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val lowRam = activityManager?.isLowRamDevice ?: false

            return GlCapabilities(
                esVersion = (major shl 16) or (minor shl 8),
                renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "unknown",
                vendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "unknown",
                maxTextureSize = if (maxTexture[0] > 0) maxTexture[0] else 2048,
                maxSamples = maxSamples[0].coerceAtLeast(0),
                isLowRamDevice = lowRam,
            )
        }

        /** Drains any startup GL errors; harmless in release because it is called once. */
        fun logStartupDiagnostics(capabilities: GlCapabilities) {
            if (com.vynylrecord.turntable.BuildConfig.DEBUG) {
                Log.i(TAG, "GL vendor=${capabilities.vendor} renderer=${capabilities.renderer}")
                Log.i(
                    TAG,
                    "GL ES=${capabilities.esVersion shr 16}.${(capabilities.esVersion shr 8) and 0xF} " +
                        "maxTexture=${capabilities.maxTextureSize} maxSamples=${capabilities.maxSamples} " +
                        "lowRam=${capabilities.isLowRamDevice}",
                )
                GlUtil.checkGlError("startup")
            }
        }
    }
}
