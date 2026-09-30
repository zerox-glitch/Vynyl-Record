package com.vynylrecord.turntable.graphics

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Decides, once per process, whether the 3D path can run at all and at what quality.
 *
 * Two moments matter:
 *
 *  * **Before any GL context exists** an activity can ask [isEs3Supported], which starts a throwaway
 *    probe context. If the answer is *no*, the UI swaps in the static Compose deck instead of
 *    handing the user a black rectangle.
 *  * **Once the real context is current** [onLiveCapabilities] receives what the driver actually
 *    reports and picks the default quality — but never overrides a quality the user has chosen.
 */
class GraphicsEnvironment(private val context: Context) {

    /** True when an OpenGL ES 3.0 context can be created on this device. */
    val isEs3Supported: Boolean by lazy { GlCapabilities.isEs3Available(context) }

    @Volatile
    var capabilities: GlCapabilities? = null
        private set

    @Volatile
    var quality: RenderQuality = RenderQuality.DEFAULT
        private set

    /** Set once the user picks a quality by hand; automatic detection stops overriding it. */
    @Volatile
    var qualityChosenByUser: Boolean = false
        private set

    val labelTextureSize: Int get() = quality.labelTextureSize

    /** Called from the GL thread the first time the real context is current. */
    fun onLiveCapabilities(detected: GlCapabilities) {
        capabilities = detected
        if (!qualityChosenByUser) quality = RenderQuality.recommendFor(detected)
        GlCapabilities.logStartupDiagnostics(detected)
    }

    fun setQuality(newQuality: RenderQuality) {
        qualityChosenByUser = true
        quality = newQuality
    }

    /** Adopts a quality that came from persisted settings. */
    fun restoreQuality(name: String?) {
        val restored = RenderQuality.fromNameOrDefault(name)
        if (restored != quality) {
            qualityChosenByUser = true
            quality = restored
        }
    }

    /** How many MSAA samples the scene target may actually use right now. */
    fun effectiveSamples(): Int = RenderQuality.clampSamples(quality, capabilities)

    /** `true` when the device is a low-RAM device, for diagnostics and for the Low preset. */
    fun isLowRamDevice(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.isLowRamDevice
    }

    fun summary(): String = buildString {
        append("quality=").append(quality.displayName)
        append(" msaa=").append(effectiveSamples())
        capabilities?.let { append(" ").append(it.describe()) }
    }

    companion object {
        private const val TAG = "VynylGraphics"

        /** Pre-flight check the UI can run before composing a GL surface. */
        fun canRender3d(context: Context): Boolean =
            runCatching { GlCapabilities.isEs3Available(context) }
                .onFailure { Log.w(TAG, "OpenGL ES probe failed; falling back to the static deck", it) }
                .getOrDefault(false)
    }
}
