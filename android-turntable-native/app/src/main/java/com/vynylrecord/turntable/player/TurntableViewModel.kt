package com.vynylrecord.turntable.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vynylrecord.turntable.audio.AudioEngine
import com.vynylrecord.turntable.graphics.GraphicsEnvironment

/**
 * Retains the player across configuration changes.
 *
 * Rotation recreates the activity on purpose — the manifest deliberately does not swallow config
 * changes, so the whole teardown path is exercised on every turn of the device — and a `ViewModel` is
 * what keeps the transport alive through it. The [AudioEngine], the [TurntableController] and the
 * scope all live here, while the GL surface is destroyed and rebuilt with a fresh EGL context.
 * Re-attaching the new renderer is just [TurntableController.attachRenderer], which re-pushes the
 * style, label, quality, speed and mechanism state, so the user sees at most one black frame.
 *
 * Integration note for the full Vynyl app: this is the class to swap for a `MediaSessionService` if
 * playback has to survive long periods in the background. Nothing else changes — the controller API
 * stays exactly the same.
 */
class TurntableViewModel(application: Application) : AndroidViewModel(application), PlaybackEvents {

    val graphics: GraphicsEnvironment = GraphicsEnvironment(application)

    private val engine = AudioEngine(application)

    val controller: TurntableController = TurntableController(
        backend = engine,
        scope = viewModelScope,
        initialMetadata = TurntableController.DEMO_METADATA,
    ).also { controller ->
        controller.setEs3Available(graphics.isEs3Supported)
    }

    init {
        engine.setListener(this)
    }

    /** Loads the bundled, locally generated sample. Never a remote URL. */
    fun loadDemoRecord() = controller.loadBundled()

    override fun onPlayingChanged(isPlaying: Boolean) {
        controller.onPlayingChanged(isPlaying)
    }

    override fun onPlaybackCompleted() {
        controller.onPlaybackCompleted()
    }

    override fun onPlaybackError(message: String) {
        controller.onPlaybackError(message)
    }

    override fun onCleared() {
        engine.setListener(null)
        // dispose() parks the mechanism and releases the backend; the extra release is a no-op and
        // documents who owns the engine's lifetime.
        controller.dispose()
        engine.release()
        super.onCleared()
    }
}
