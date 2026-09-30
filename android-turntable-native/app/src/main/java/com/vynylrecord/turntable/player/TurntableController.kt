package com.vynylrecord.turntable.player

import android.net.Uri
import android.util.Log
import com.vynylrecord.turntable.audio.DemoAudio
import com.vynylrecord.turntable.audio.TransportSnapshot
import com.vynylrecord.turntable.graphics.CameraPreset
import com.vynylrecord.turntable.graphics.RenderQuality
import com.vynylrecord.turntable.graphics.animation.VisualPhase
import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.VinylStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The single entry point every host uses to drive the deck.
 *
 * ## The contract
 *
 * ```
 *   load(...) ──▶ the record is staged, the label is printed, the mechanism is told a record exists
 *   play()    ──▶ the mechanism starts; **audio starts later**, when the stylus lands
 *   pause()   ──▶ the stylus lifts, the platter keeps turning, the transport stops
 *   seekTo()  ──▶ the transport jumps and the arm glides to the matching groove
 * ```
 *
 * That ordering is the whole point of the class: [play] does **not** touch the transport. It asks
 * the mechanism to start, and the mechanism calls back through [onNeedleContact] when the stylus
 * actually touches the vinyl. The same rule protects completion, errors and pausing.
 *
 * ## Threading
 *
 * Every public method is safe to call from the main thread, which is where the audio backend lives.
 * The renderer callbacks ([onNeedleContact], [onVisualPhaseChanged], [onFrameStatistics]) arrive on
 * the render thread; they only write into a `StateFlow` or hand work to [scope], never touching the
 * player from the wrong thread.
 *
 * ## State
 *
 * [state] is the only thing the UI reads. It is updated from the ticker below and from the transport
 * events, never once per frame: recomposition is driven by *visible* change, not by time.
 */
class TurntableController(
    private val backend: PlaybackBackend,
    private val scope: CoroutineScope,
    initialMetadata: RecordMetadata = DEMO_METADATA,
) : PlaybackEvents {

    private val _state = MutableStateFlow(
        TurntableUiState(
            metadata = initialMetadata,
            vinylStyle = VinylStyle.DEFAULT,
        ),
    )
    val state: StateFlow<TurntableUiState> = _state.asStateFlow()

    /** The renderer, once a surface exists. Replaced on rotation, never held across it. */
    @Volatile
    private var commands: RendererCommands? = null

    private var ticker: Job? = null
    private var wasPlaying = false
    private var disposed = false
    private var loadedFile: File? = null

    // ------------------------------------------------------------------ renderer plumbing

    /**
     * Binds a renderer and pushes the entire current state into it.
     *
     * This is what makes context loss survivable: the surface can come and go, and every time one
     * appears it is handed the style, metadata, quality, speed and reduced-motion setting again.
     */
    fun attachRenderer(newCommands: RendererCommands?) {
        commands = newCommands
        val renderer = newCommands ?: return
        val current = _state.value
        renderer.applyQuality(current.quality)
        renderer.applyReducedMotion(current.reducedMotion)
        renderer.applyVinylStyle(current.vinylStyle)
        renderer.applyMetadata(current.metadata)
        renderer.animatorSetSpeed(current.speed)
        renderer.animatorSetRecordAvailable(current.hasRecord)
        pushTransport()
    }

    fun detachRenderer() {
        commands = null
    }

    // ------------------------------------------------------------------ loading

    /** Loads the bundled demonstration pressing. Safe to call repeatedly; the file is reused. */
    fun loadBundled(assetPath: String = DEMO_ASSET_PATH, metadata: RecordMetadata = DEMO_METADATA) {
        scope.launch {
            val staged = try {
                backend.prepareBundledSource(assetPath)
            } catch (error: Exception) {
                Log.e(TAG, "Could not stage $assetPath", error)
                null
            }
            if (staged == null) {
                reportError("The bundled demonstration recording is missing from this build.")
                return@launch
            }
            load(staged, metadata)
        }
    }

    /** Loads an app-private file. This is the integration path for a user's own pressings. */
    fun load(file: File, metadata: RecordMetadata = _state.value.metadata) {
        if (disposed) return
        if (!backend.load(file, file.name)) {
            reportError("That recording could not be opened on this device.")
            return
        }
        loadedFile = file
        onRecordLoaded(metadata, file.name)
    }

    /**
     * Loads a `file://` or `content://` URI - for example one the Storage Access Framework handed
     * back. Remote URIs are refused by the engine, which has no network code path at all.
     */
    fun loadUri(uri: Uri, displayName: String, metadata: RecordMetadata = _state.value.metadata) {
        if (disposed) return
        if (!backend.loadUri(uri, displayName)) {
            reportError("That recording could not be opened on this device.")
            return
        }
        loadedFile = null
        onRecordLoaded(metadata, displayName)
    }

    private fun onRecordLoaded(metadata: RecordMetadata, sourceName: String) {
        val current = _state.value
        _state.value = current.copy(
            hasRecord = true,
            metadata = metadata.normalized(),
            sourceName = sourceName,
            isCompleted = false,
            errorMessage = null,
            visualPhase = VisualPhase.LOADING_RECORD,
        )
        commands?.applyMetadata(metadata.normalized())
        commands?.animatorSetRecordAvailable(true)
        pushTransport()
        startTicker()
    }

    /** Removes the record: the arm parks, the platter coasts down. */
    fun unload() {
        _state.value = _state.value.copy(hasRecord = false, isPlaying = false, playRequested = false)
        commands?.animatorSetRecordAvailable(false)
        wasPlaying = false
        pushTransport()
    }

    // ------------------------------------------------------------------ transport

    /** Starts the mechanism. Audio does not begin until the stylus lands. */
    fun play() {
        if (disposed) return
        val current = _state.value
        if (!current.hasRecord || current.errorMessage != null) return
        if (current.playRequested || current.isPlaying) return
        _state.value = current.copy(playRequested = true, isCompleted = false, errorMessage = null)
        commands?.animatorRequestPlay()
        startTicker()
    }

    fun pause() {
        if (disposed) return
        val current = _state.value
        if (!current.playRequested && !current.isPlaying) return
        _state.value = current.copy(playRequested = false)
        backend.pause()
        commands?.animatorRequestPause()
        pushTransport()
    }

    fun togglePlayPause() {
        val current = _state.value
        if (current.isPlaying || current.playRequested || current.isStarting) pause() else play()
    }

    /** Absolute seek, clamped to the loaded track. */
    fun seekTo(positionMs: Long) {
        if (!_state.value.hasRecord) return
        val duration = _state.value.durationMs
        val clamped = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        backend.seekTo(clamped)
        _state.value = _state.value.copy(positionMs = clamped, isCompleted = false)
        commands?.animatorRequestSeek(progressFor(clamped))
        pushTransport()
    }

    /** Seek by normalised progress, which is what the slider reports. */
    fun seekToProgress(progress: Float) {
        val duration = _state.value.durationMs
        if (duration <= 0L) return
        seekTo((progress.coerceIn(0f, 1f) * duration).toLong())
    }

    /** Skip by a relative amount; the sign decides the direction. */
    fun skipBy(deltaMs: Long) {
        val current = _state.value
        if (!current.hasRecord) return
        seekTo(current.positionMs + deltaMs)
    }

    /** Restarts the side from the top. */
    fun replay() {
        val current = _state.value
        if (!current.hasRecord) return
        seekTo(0L)
        play()
    }

    // ------------------------------------------------------------------ presentation

    fun setVinylStyle(style: VinylStyle) {
        if (_state.value.vinylStyle == style) return
        _state.value = _state.value.copy(vinylStyle = style)
        commands?.applyVinylStyle(style)
    }

    fun setNextVinylStyle() = setVinylStyle(_state.value.vinylStyle.next())

    fun setPreviousVinylStyle() = setVinylStyle(_state.value.vinylStyle.previous())

    fun setMetadata(metadata: RecordMetadata) {
        val normalized = metadata.normalized()
        if (_state.value.metadata.rendersSameLabelAs(normalized)) return
        _state.value = _state.value.copy(metadata = normalized)
        commands?.applyMetadata(normalized)
    }

    fun flipSide() {
        setMetadata(_state.value.metadata.copy(side = _state.value.metadata.side.flipped()))
    }

    fun setSpeed(speed: PlatterSpeed) {
        if (_state.value.speed == speed) return
        _state.value = _state.value.copy(speed = speed)
        commands?.animatorSetSpeed(speed)
    }

    fun toggleSpeed() = setSpeed(_state.value.speed.toggled())

    fun setQuality(quality: RenderQuality) {
        _state.value = _state.value.copy(quality = quality, qualityChosenByUser = true)
        commands?.applyQuality(quality)
    }

    fun setNextQuality() = setQuality(_state.value.quality.next())

    fun setCameraPreset(preset: CameraPreset) {
        _state.value = _state.value.copy(cameraPreset = preset)
        commands?.applyCameraPreset(preset)
    }

    fun resetCamera() {
        _state.value = _state.value.copy(cameraPreset = CameraPreset.HERO)
        commands?.resetCamera()
    }

    fun setReducedMotion(enabled: Boolean) {
        if (_state.value.reducedMotion == enabled) return
        _state.value = _state.value.copy(reducedMotion = enabled)
        commands?.applyReducedMotion(enabled)
    }

    fun setEs3Available(available: Boolean) {
        _state.value = _state.value.copy(isEs3Available = available)
    }

    // ------------------------------------------------------------------ renderer callbacks

    /** Fired on the render thread the frame the stylus touches the vinyl. */
    fun onNeedleContact() {
        if (disposed) return
        val current = _state.value
        if (!current.playRequested) {
            // The mechanism found the groove on its own (for example after a seek while playing):
            // keep the transport in step rather than starting audio nobody asked for.
            if (current.isPlaying) return
        }
        scope.launch {
            if (_state.value.hasRecord) backend.play()
        }
    }

    /** Fired on the render thread whenever the mechanism changes phase. */
    fun onVisualPhaseChanged(phase: VisualPhase) {
        if (disposed) return
        val previous = _state.value
        if (previous.visualPhase == phase) return
        _state.value = previous.copy(
            visualPhase = phase,
            isCompleted = phase == VisualPhase.COMPLETED,
            playRequested = if (phase == VisualPhase.PAUSED || phase == VisualPhase.COMPLETED) {
                false
            } else {
                previous.playRequested
            },
        )
    }

    /** Fired on the render thread once the deck is built. */
    fun onRendererReady(description: String, triangles: Int, quality: RenderQuality) {
        if (disposed) return
        val current = _state.value
        _state.value = current.copy(
            glDescription = description,
            // The device's recommendation only wins until the user picks a quality by hand.
            quality = if (current.qualityChosenByUser) current.quality else quality,
        )
    }

    /** Fired on the render thread when the 3D path cannot run; the UI falls back to the static deck. */
    fun onRendererFailed(reason: String) {
        if (disposed) return
        Log.w(TAG, "3D unavailable: $reason")
        _state.value = _state.value.copy(
            isEs3Available = false,
            glDescription = reason,
        )
    }

    fun onFrameStatistics(fps: Float, drawCalls: Int, triangles: Int, samples: Int) {
        if (disposed) return
        val statistics = FrameStatistics(
            fps = fps,
            drawCalls = drawCalls,
            triangles = triangles,
            msaaSamples = samples,
        )
        val current = _state.value
        if (current.frameStatistics == statistics) return
        _state.value = current.copy(frameStatistics = statistics)
    }

    // ------------------------------------------------------------------ PlaybackEvents

    override fun onPlayingChanged(isPlaying: Boolean) {
        if (disposed) return
        wasPlaying = isPlaying
        _state.value = _state.value.copy(isPlaying = isPlaying)
        pushTransport()
    }

    override fun onPlaybackCompleted() {
        if (disposed) return
        // The mechanism owns the choreography; it sees the completion flag in its own snapshot and
        // lifts the stylus, returns the arm and coasts the platter down.
        _state.value = _state.value.copy(isPlaying = false, isCompleted = true, playRequested = false)
        pushTransport()
    }

    override fun onPlaybackError(message: String) {
        if (disposed) return
        reportError(message)
    }

    // ------------------------------------------------------------------ ticker

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && !disposed) {
                val playing = _state.value.isPlaying
                delay(if (playing) PLAYING_TICK_MS else IDLE_TICK_MS)
                tick()
            }
        }
    }

    /**
     * Polls the transport.
     *
     * Media3 has no progress callback, so progress is sampled rather than pushed. Ten times a second
     * while playing is enough for a seek bar and for the arm to track smoothly — the *platter angle*
     * is integrated on the render thread from elapsed time, so nothing here affects animation
     * smoothness.
     */
    private fun tick() {
        if (disposed) return
        val transport = backend.snapshot()
        val current = _state.value

        // Someone stopped playback without asking us: audio focus went away, headphones were pulled,
        // or the player failed. Raise the stylus instead of miming a record that is not playing.
        if (wasPlaying && !transport.isPlaying && !transport.isCompleted && current.playRequested) {
            commands?.animatorRequestPause()
            _state.value = current.copy(playRequested = false)
        }
        wasPlaying = transport.isPlaying

        _state.value = _state.value.copy(
            hasRecord = transport.hasMedia && _state.value.hasRecord,
            isPlaying = transport.isPlaying,
            isBuffering = transport.isBuffering,
            isCompleted = _state.value.isCompleted || transport.isCompleted,
            positionMs = transport.positionMs,
            durationMs = transport.durationMs,
            sourceName = transport.sourceName.ifEmpty { _state.value.sourceName },
        )
        pushTransport()

        if (!_state.value.hasRecord && !_state.value.isPlaying) {
            stopTicker()
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** Hands the renderer the transport so the arm can follow real progress. */
    private fun pushTransport() {
        val renderer = commands ?: return
        val snapshot = backend.snapshot()
        renderer.syncAudio(snapshot)
    }

    private fun reportError(message: String) {
        _state.value = _state.value.copy(
            errorMessage = message,
            playRequested = false,
            isPlaying = false,
        )
        commands?.animatorRequestError()
    }

    private fun progressFor(positionMs: Long): Float {
        val duration = _state.value.durationMs
        return if (duration > 0L) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }

    /** Clears an error so the user can try again. */
    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    /** Parks the mechanism and releases the player. Called when the host goes away for good. */
    fun dispose() {
        if (disposed) return
        disposed = true
        stopTicker()
        commands?.animatorRequestReset()
        commands = null
        try {
            backend.release()
        } catch (error: Exception) {
            Log.w(TAG, "Audio backend failed to release cleanly", error)
        }
    }

    companion object {
        private const val TAG = "VynylController"
        private const val PLAYING_TICK_MS = 100L
        private const val IDLE_TICK_MS = 250L

        /** Asset path of the bundled demonstration pressing. */
        const val DEMO_ASSET_PATH = DemoAudio.ASSET_PATH

        /** The label the demo ships with, so the deck is never an unlabelled disc. */
        val DEMO_METADATA = RecordMetadata(
            title = "Golden Hour",
            recipient = "You",
            sender = "Vynyl",
            catalogue = "VYN 001",
        )
    }
}
