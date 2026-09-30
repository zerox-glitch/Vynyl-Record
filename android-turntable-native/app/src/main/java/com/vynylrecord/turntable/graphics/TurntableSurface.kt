package com.vynylrecord.turntable.graphics

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent

/**
 * The `GLSurfaceView` that owns the EGL context, wired for OpenGL ES 3.0.
 *
 * ## Why this is a subclass and not a plain view
 *
 * Two decisions matter:
 *
 *  * `preserveEGLContextOnPause` stays **false**. Android cannot be trusted to keep a context alive
 *    across a background/foreground cycle, and a context that survives *sometimes* is worse than one
 *    that never does: the scene would have to be correct in both cases anyway. Keeping it false
 *    makes the lifecycle simple — context created → build the scene; context gone → the driver has
 *    already freed every buffer and texture, so nothing can leak and there is no stale handle to
 *    guard against.
 *  * The render mode is continuous while the deck is alive, because the platter, the arm and the
 *    backdrop are all animated. When the host goes to the background the GL thread is stopped
 *    outright ([pauseRendering]), which is the only way to actually guarantee "stop rendering when
 *    not visible" rather than hoping the driver is polite.
 *
 * Touch is deliberately not handled here: the Compose layer owns the gesture detectors and forwards
 * deltas through [TurntableRenderer]'s gesture queue, so a stream of move events never makes the
 * render thread wait on the UI thread.
 */
class TurntableSurface(
    context: Context,
    val renderer: TurntableRenderer,
) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = false
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Stops the GL thread. Every GL object dies with the context; the scene rebuilds on resume. */
    fun pauseRendering() {
        try {
            onPause()
        } catch (ignored: IllegalStateException) {
            // The surface was never resumed, so there is nothing to stop.
        }
        renderer.onRenderingPaused()
    }

    /** Restarts the GL thread; the renderer rebuilds the scene in `onSurfaceCreated`. */
    fun resumeRendering() {
        onResume()
        renderer.onRenderingResumed()
    }

    /** Tears the surface down for good, when Compose releases the view. */
    fun release() = pauseRendering()

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
