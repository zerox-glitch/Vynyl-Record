package com.vynylrecord.turntable.graphics

import android.content.Context
import android.util.Log
import com.vynylrecord.turntable.audio.TransportSnapshot
import com.vynylrecord.turntable.graphics.animation.AudioSnapshot
import com.vynylrecord.turntable.graphics.animation.TurntableAnimator
import com.vynylrecord.turntable.graphics.animation.VisualPhase
import com.vynylrecord.turntable.graphics.gl.GlUtil
import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.VinylStyle
import com.vynylrecord.turntable.player.RendererCallbacks
import com.vynylrecord.turntable.player.RendererCommands
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The bridge between the UI thread and the render thread.
 *
 * ## Threading contract
 *
 * * **UI thread** calls the [RendererCommands] methods. Each one writes a `@Volatile` field or pushes
 *   into the gesture queue; none of them touches GL and none of them blocks.
 * * **Render thread** (`GLSurfaceView`'s) owns [scene], [camera] and [animator] exclusively. It drains
 *   the intents at the top of every frame and reports back through [RendererCallbacks].
 *
 * That is the whole synchronisation story: intents flow one way through volatile fields, the
 * transport snapshot flows one way through volatile fields, and gestures flow through a queue with
 * one short critical section. There is no lock on the render path and no allocation per frame.
 *
 * ## Context loss
 *
 * `GLSurfaceView` keeps the EGL *context* across a pause but destroys the EGL *surface*. So the
 * normal path is: pause stops the GL thread, resume delivers a new surface and [onSurfaceChanged]
 * resizes the targets — the scene survives untouched. If the driver really does lose the context,
 * [onSurfaceCreated] runs again, rebuilds every GL object, and re-applies the intents the host has
 * expressed so far (quality, style, metadata, reduced motion), so the user sees at most one black
 * frame.
 */
class TurntableRenderer(
    context: Context,
    private val callbacks: RendererCallbacks,
) : GLSurfaceView.Renderer, RendererCommands {

    private val appContext = context.applicationContext

    private val scene = TurntableScene(appContext)
    private val camera = CameraRig()
    private val animator = TurntableAnimator()
    private val audioSnapshot = AudioSnapshot()
    private val cameraSnapshot = CameraRig.Snapshot()
    private val gestures = GestureQueue()

    // ---------------------------------------------------------------- intents (written by the UI)

    @Volatile private var requestedQuality: RenderQuality? = null
    @Volatile private var pendingReducedMotion: Boolean = false
    @Volatile private var pendingStyle: VinylStyle = VinylStyle.DEFAULT
    @Volatile private var pendingMetadata: RecordMetadata? = null
    @Volatile private var cameraResetPending: Boolean = false
    @Volatile private var cameraPresetPending: CameraPreset? = null

    // ---------------------------------------------------------------- transport (one way, volatile)

    /**
     * The latest playback state, replaced wholesale rather than field-by-field: a data class
     * reference read on the render thread is consistent, whereas seven independent volatile fields
     * could be observed half-updated.
     */
    @Volatile private var transport = TransportSnapshot()

    // ---------------------------------------------------------------- render-thread state

    private var capabilityProbe: GlCapabilities? = null
    private var appliedQuality: RenderQuality? = null
    private var appliedReducedMotion: Boolean? = null
    private var appliedStyle: VinylStyle? = null
    private var appliedMetadata: RecordMetadata? = null
    private var appliedLabelStyle: VinylStyle? = null
    private var lastFrameNanos = 0L
    private var statisticsSeconds = 0f
    private var statisticsFrames = 0
    private var reportedFailure = false
    private var reportedReady = false

    /**
     * Set while the host has stopped the GL thread.
     *
     * `GLSurfaceView` pauses its own thread only while the surface is attached; between a detach and
     * the next attach some OEM implementations keep drawing into a dead surface. This flag makes the
     * render loop provably idle in that window, and it also resets the frame-delta clock so the first
     * frame after a resume is a normal step rather than a two-second lurch.
     */
    @Volatile private var renderingPaused = false

    // ---------------------------------------------------------------- public read-only state

    /** Latest frame statistics, for the debug overlay and for logging. */
    @Volatile var lastFps: Float = 0f
        private set

    @Volatile var lastDrawCalls: Int = 0
        private set

    @Volatile var lastTriangles: Int = 0
        private set

    @Volatile var lastSamples: Int = 0
        private set

    /** Quality actually in use, which may have come from the device rather than the caller. */
    @Volatile var activeQuality: RenderQuality = RenderQuality.DEFAULT
        private set

    val isSceneReady: Boolean get() = scene.isReady

    init {
        animator.onNeedleContact = { callbacks.onNeedleContact() }
        animator.onPhaseChanged = { phase -> callbacks.onVisualPhaseChanged(phase) }
    }

    // ---------------------------------------------------------------- RendererCommands

    override fun applyQuality(quality: RenderQuality) {
        requestedQuality = quality
    }

    override fun applyReducedMotion(enabled: Boolean) {
        pendingReducedMotion = enabled
    }

    override fun applyVinylStyle(style: VinylStyle) {
        pendingStyle = style
    }

    override fun applyMetadata(metadata: RecordMetadata) {
        pendingMetadata = metadata
    }

    override fun animatorSetRecordAvailable(available: Boolean) {
        animator.setRecordAvailable(available)
    }

    override fun animatorRequestPlay() = animator.requestPlay()

    override fun animatorRequestPause() = animator.requestPause()

    override fun animatorRequestSeek(progress: Float) = animator.requestSeek(progress)

    override fun animatorRequestCompleted() = animator.requestCompleted()

    override fun animatorRequestError() = animator.requestError()

    override fun animatorRequestReset() = animator.requestReset()

    override fun animatorSetSpeed(speed: PlatterSpeed) {
        animator.setSpeed(speed)
    }

    override fun syncAudio(snapshot: TransportSnapshot) {
        transport = snapshot
        audioSnapshot.set(
            prepared = transport.hasMedia,
            playing = transport.isPlaying,
            buffering = transport.isBuffering,
            completed = transport.isCompleted,
            error = transport.errorMessage != null,
            positionMsValue = transport.positionMs,
            durationMsValue = transport.durationMs,
        )
    }

    override fun resetCamera() {
        cameraResetPending = true
    }

    override fun applyCameraPreset(preset: CameraPreset) {
        cameraPresetPending = preset
    }

    fun gestureBeginOrbit() = gestures.beginOrbit()

    fun gestureOrbit(deltaX: Float, deltaY: Float, viewportHeight: Int) =
        gestures.addOrbit(deltaX, deltaY)

    fun gestureEndOrbit() = gestures.endOrbit()

    fun gestureZoom(scaleFactor: Float) = gestures.addZoom(scaleFactor)

    fun gesturePan(deltaX: Float, deltaY: Float, viewportHeight: Int) =
        gestures.addPan(deltaX, deltaY)

    // ---------------------------------------------------------------- GLSurfaceView callbacks

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Nothing GL-side survives a real context loss, so every "applied" marker is cleared and the
        // next frames re-apply whatever the host has asked for.
        appliedStyle = null
        appliedMetadata = null
        appliedLabelStyle = null
        appliedQuality = null
        appliedReducedMotion = null
        reportedReady = false
        lastFrameNanos = 0L
        scene.release()

        val capabilities = try {
            GlCapabilities.detectFromContext(appContext)
        } catch (error: Exception) {
            Log.e(GlUtil.TAG, "Capability probe failed", error)
            null
        }
        capabilityProbe = capabilities
        if (capabilities != null && !capabilities.isEs3) {
            fail("OpenGL ES 3.0 or newer is required for the 3D deck; this device reports ES ${capabilities.esVersion shr 16}.")
            return
        }

        val quality = requestedQuality ?: RenderQuality.recommendFor(capabilities)
        if (!scene.initialise(quality, capabilities)) {
            fail("The turntable shaders could not be built on this GPU.")
            return
        }
        activeQuality = quality
        scene.applyStyle(pendingStyle)
        appliedStyle = pendingStyle
        camera.setReducedMotion(pendingReducedMotion)
        camera.setAutoOrbitEnabled(!pendingReducedMotion)
        reportedFailure = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (!scene.isReady) return
        val quality = appliedQuality ?: activeQuality
        if (!scene.resize(quality, width, height, capabilityProbe)) {
            fail("The offscreen render target could not be created at ${width}x$height.")
            return
        }
        lastSamples = scene.activeSamples
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!scene.isReady || renderingPaused) return

        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((now - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(1f / 240f, MAX_FRAME_DELTA_SECONDS)
        }
        lastFrameNanos = now

        drainIntents()
        gestureDrainToCamera()

        camera.update(deltaSeconds)
        camera.writeTo(cameraSnapshot)

        animator.update(deltaSeconds, audioSnapshot)
        applyPendingLabel()
        scene.render(animator, cameraSnapshot, deltaSeconds)
        reportFrameStatistics(deltaSeconds)
    }

    // ---------------------------------------------------------------- intents

    private fun drainIntents() {
        val quality = requestedQuality
        if (quality != null && quality != appliedQuality) {
            val previous = appliedQuality
            appliedQuality = quality
            activeQuality = quality
            scene.rebuildGeometry(quality)
            scene.resize(quality, scene.viewportWidth, scene.viewportHeight, capabilityProbe)
            // The label bitmap resolution is part of the quality level, so it is reprinted.
            if (previous != null) appliedMetadata = null
        }

        val reduced = pendingReducedMotion
        if (reduced != appliedReducedMotion) {
            appliedReducedMotion = reduced
            camera.setReducedMotion(reduced)
            camera.setAutoOrbitEnabled(!reduced)
        }

        if (pendingStyle != appliedStyle) {
            appliedStyle = pendingStyle
            scene.applyStyle(pendingStyle)
            appliedMetadata = null
        }

        if (cameraResetPending) {
            cameraResetPending = false
            camera.reset(instant = appliedReducedMotion == true)
        }
        val preset = cameraPresetPending
        if (preset != null) {
            cameraPresetPending = null
            camera.applyPreset(preset, instant = appliedReducedMotion == true)
        }
    }

    /** Uploads the label texture only when the wording or the style actually changed. */
    private fun applyPendingLabel() {
        val metadata = pendingMetadata ?: return
        val style = pendingStyle
        val unchanged = appliedMetadata?.rendersSameLabelAs(metadata) == true && appliedLabelStyle == style
        if (unchanged && scene.hasAppliedMetadata(metadata)) return

        val bitmap = try {
            scene.renderLabelBitmap(metadata, style)
        } catch (error: OutOfMemoryError) {
            Log.w(GlUtil.TAG, "Label bitmap allocation failed; keeping the previous label", error)
            appliedMetadata = metadata
            appliedLabelStyle = style
            return
        }
        scene.uploadLabelBitmap(bitmap)
        scene.markMetadataApplied(metadata)
        appliedMetadata = metadata
        appliedLabelStyle = style
    }

    private fun gestureDrainToCamera() {
        if (!gestures.hasPending()) return
        gestures.drain(camera, scene.viewportHeight.coerceAtLeast(1))
    }

    private fun reportFrameStatistics(deltaSeconds: Float) {
        statisticsFrames++
        statisticsSeconds += deltaSeconds
        lastDrawCalls = scene.drawCalls
        lastTriangles = scene.triangleCount
        lastSamples = scene.activeSamples

        if (statisticsSeconds < STATISTICS_INTERVAL_SECONDS) return
        lastFps = statisticsFrames / statisticsSeconds
        statisticsFrames = 0
        statisticsSeconds = 0f
        callbacks.onFrameStatistics(lastFps, lastDrawCalls, lastTriangles, lastSamples)
        if (!reportedReady && scene.isReady) {
            reportedReady = true
            callbacks.onRendererReady(scene.describe(), scene.triangleCount, activeQuality)
        }
    }

    private fun fail(reason: String) {
        if (reportedFailure) return
        reportedFailure = true
        Log.e(GlUtil.TAG, reason)
        callbacks.onRendererFailed(reason)
    }

    // ---------------------------------------------------------------- lifecycle

    /** Called when the host stops showing the deck: navigation away, or the app going to background. */
    fun onRenderingPaused() {
        renderingPaused = true
        lastFrameNanos = 0L
    }

    /** Called when the deck becomes visible again. The scene rebuilds itself if the context was lost. */
    fun onRenderingResumed() {
        renderingPaused = false
        lastFrameNanos = 0L
    }

    /**
     * Frees GL objects while the context is still current.
     *
     * Called when the surface is destroyed for good (the host released the view). A plain pause does
     * not need it: the context stays alive and the objects are reused on resume.
     */
    fun releaseGlResources() {
        if (!scene.isReady) return
        scene.release()
        appliedQuality = null
        appliedStyle = null
        appliedMetadata = null
        appliedLabelStyle = null
        reportedReady = false
    }

    /**
     * Pointer input, queued for the render thread.
     *
     * One monitor protects a handful of floats. Gestures arrive in bursts of roughly one event per
     * frame, and the render thread only holds the lock long enough to copy six numbers, so contention
     * is far below a frame — and, unlike posting a `Runnable` per event, this allocates nothing.
     */
    private class GestureQueue {
        private val lock = Any()
        private var orbitX = 0f
        private var orbitY = 0f
        private var panX = 0f
        private var panY = 0f
        private var zoomLog = 0f
        private var orbitStarted = false
        private var orbitFinished = false

        fun beginOrbit() = synchronized(lock) {
            orbitStarted = true
            orbitX = 0f
            orbitY = 0f
        }

        fun addOrbit(deltaX: Float, deltaY: Float) = synchronized(lock) {
            orbitX += deltaX
            orbitY += deltaY
        }

        fun endOrbit() = synchronized(lock) {
            orbitFinished = true
        }

        fun addZoom(scaleFactor: Float) = synchronized(lock) {
            // Accumulated in log space so a stream of small factors composes into one exact zoom.
            zoomLog += kotlin.math.ln(scaleFactor.coerceAtLeast(1e-4f))
        }

        fun addPan(deltaX: Float, deltaY: Float) = synchronized(lock) {
            panX += deltaX
            panY += deltaY
        }

        fun hasPending(): Boolean = synchronized(lock) {
            orbitStarted || orbitFinished || orbitX != 0f || orbitY != 0f || panX != 0f || panY != 0f || zoomLog != 0f
        }

        fun drain(camera: CameraRig, viewportHeight: Int) {
            val started: Boolean
            val finished: Boolean
            val dx: Float
            val dy: Float
            val px: Float
            val py: Float
            val zoom: Float
            synchronized(lock) {
                started = orbitStarted
                finished = orbitFinished
                dx = orbitX
                dy = orbitY
                px = panX
                py = panY
                zoom = zoomLog
                orbitStarted = false
                orbitFinished = false
                orbitX = 0f
                orbitY = 0f
                panX = 0f
                panY = 0f
                zoomLog = 0f
            }
            if (started) camera.beginDrag()
            if (dx != 0f || dy != 0f) camera.drag(dx, dy, viewportHeight)
            if (zoom != 0f) camera.zoomBy(kotlin.math.exp(zoom))
            if (px != 0f || py != 0f) camera.pan(px, py, viewportHeight)
            if (finished) camera.endDrag()
        }
    }

    companion object {
        private const val STATISTICS_INTERVAL_SECONDS = 0.5f

        /** Frames longer than this are treated as a hitch rather than a real time step. */
        const val MAX_FRAME_DELTA_SECONDS = 0.1f

        /** Phase label for logs and the debug overlay. */
        fun describePhase(phase: VisualPhase): String = phase.name
    }
}
