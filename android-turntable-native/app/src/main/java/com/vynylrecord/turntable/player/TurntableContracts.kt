package com.vynylrecord.turntable.player

import android.net.Uri
import com.vynylrecord.turntable.audio.TransportSnapshot
import com.vynylrecord.turntable.graphics.CameraPreset
import com.vynylrecord.turntable.graphics.RenderQuality
import com.vynylrecord.turntable.graphics.animation.VisualPhase
import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.VinylStyle
import java.io.File

/**
 * The audio half of the player, as the controller sees it.
 *
 * [com.vynylrecord.turntable.audio.AudioEngine] is the production implementation; tests use a fake,
 * which is what keeps "play/pause/seek state" testable without an Android device.
 */
interface PlaybackBackend {
    val isReady: Boolean

    /**
     * Stages a bundled asset into app-private storage and returns the file.
     * Returns null when the asset is missing. Must not touch the network.
     */
    suspend fun prepareBundledSource(assetPath: String): File?

    /** Loads a local file. Returns false when it cannot be opened. */
    fun load(file: File, displayName: String): Boolean

    /** Loads a local `file://` or `content://` source. Implementations must refuse anything else. */
    fun loadUri(uri: Uri, displayName: String): Boolean

    /** Starts (or resumes) local playback. Safe to call when nothing is loaded. */
    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun snapshot(): TransportSnapshot

    fun release()
}

/**
 * The renderer half of the player, as the controller sees it.
 *
 * Every method is safe to call from any thread: the renderer stores commands in volatile fields and
 * applies them on its own thread. Implementations must not block.
 */
interface RendererCommands {
    fun applyVinylStyle(style: VinylStyle)

    fun applyMetadata(metadata: RecordMetadata)

    fun applyQuality(quality: RenderQuality)

    fun applyReducedMotion(enabled: Boolean)

    fun animatorSetRecordAvailable(available: Boolean)

    fun animatorRequestPlay()

    fun animatorRequestPause()

    fun animatorRequestSeek(progress: Float)

    fun animatorRequestCompleted()

    fun animatorRequestError()

    fun animatorRequestReset()

    fun animatorSetSpeed(speed: PlatterSpeed)

    /** Publishes the transport to the render thread's [com.vynylrecord.turntable.graphics.animation.AudioSnapshot]. */
    fun syncAudio(transport: TransportSnapshot)

    fun resetCamera()

    fun applyCameraPreset(preset: CameraPreset)
}

/**
 * Everything the renderer reports back to the rest of the app.
 *
 * Implemented by the Compose host, which forwards to [TurntableController]. All four callbacks are
 * invoked on the **render thread**; the controller is responsible for marshalling them.
 */
interface RendererCallbacks {

    /**
     * The GL context is live and the deck is built.
     *
     * @param description human-readable scene summary for logs and the debug overlay.
     * @param triangles triangles in the assembled deck.
     * @param quality the quality actually in use, which may be the device-derived default rather
     *   than whatever the caller asked for.
     */
    fun onRendererReady(description: String, triangles: Int, quality: RenderQuality)

    /**
     * The 3D path could not start (no ES 3.0 context, shader compilation failure, incomplete
     * framebuffer). The host shows the static deck instead of a black rectangle.
     */
    fun onRendererFailed(reason: String)

    /** The stylus has landed: audio may start. Fires once per cueing. */
    fun onNeedleContact()

    /** The mechanism changed phase; used for the status line and accessibility announcements. */
    fun onVisualPhaseChanged(phase: VisualPhase)

    /** Frame statistics for the debug overlay. Throttled to a few updates per second. */
    fun onFrameStatistics(fps: Float, drawCalls: Int, triangles: Int, samples: Int)
}
