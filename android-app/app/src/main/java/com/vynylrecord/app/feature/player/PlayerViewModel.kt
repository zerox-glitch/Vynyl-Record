package com.vynylrecord.app.feature.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vynylrecord.app.VynylGraph
import com.vynylrecord.app.R
import com.vynylrecord.app.core.data.prefs.VynylSettings
import com.vynylrecord.app.core.export.ExportManager
import com.vynylrecord.app.core.graphics.DeckPose
import com.vynylrecord.app.core.graphics.TurntableCamera
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.playback.RecordPlayerController
import com.vynylrecord.app.core.storage.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The 3D player's view model.
 *
 * It owns the record, the audio transport and the camera, and it decides which of three things the screen
 * shows: the 3D deck, the same screen in reduced motion, or the static deck when the device cannot give us
 * OpenGL ES 3.0. That decision is made here rather than in the composable because it depends on the device's
 * configuration and on whether the EGL context could be created at all — both of which are answers the
 * renderer reports, not guesses the UI can make.
 */
class PlayerViewModel(
    application: Application,
    private val recordId: String,
) : AndroidViewModel(application) {

    private val graph = VynylGraph.of(application)
    private val files = FileStore(application)
    private val controller = RecordPlayerController(application)

    /** How the deck is being drawn. */
    enum class RenderMode { THREE_D, STATIC }

    data class UiState(
        val record: Record? = null,
        val settings: VynylSettings = VynylSettings(),
        val playback: RecordPlayerController.UiState = RecordPlayerController.UiState(),
        val mode: RenderMode = RenderMode.THREE_D,
        val fallbackMessage: String = "",
        val cameraPreset: String = TurntableCamera.Preset.THREE_QUARTER.label,
        val statistics: String = "",
        val ready: Boolean = false,
        val exportMessage: String? = null,
        val exported: ExportManager.Result.Ready? = null,
        val showExportSheet: Boolean = false,
    ) {
        val hasRecord: Boolean get() = record != null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val camera: TurntableCamera get() = controller.camera

    /**
     * Bumped by the transport whenever the record's text or finish changes.
     *
     * The label texture is redrawn on this value and not on every frame, which is the difference between a
     * metadata edit costing one bitmap and a playing record costing sixty uploads a second.
     */
    val metadataRevision: Long get() = controller.metadataRevision

    init {
        viewModelScope.launch {
            val settings = graph.preferences.current()
            val record = graph.recordRepository.find(recordId)
            if (record == null) {
                _state.value = _state.value.copy(ready = true, playback = RecordPlayerController.UiState(error = "That record is not in the Vault."))
                return@launch
            }
            if (!hasGlEs3(application)) {
                _state.value = _state.value.copy(
                    record = record,
                    settings = settings,
                    mode = RenderMode.STATIC,
                    fallbackMessage = "This device does not support OpenGL ES 3.0, so the deck is drawn flat.",
                    ready = true,
                )
                return@launch
            }
            val master = graph.recordRepository.resolveMaster(record)
            controller.setReducedMotion(settings.reducedMotion)
            val loaded = master != null && controller.loadRecord(record, master)
            _state.value = _state.value.copy(record = record, settings = settings, ready = true)
            if (!loaded) {
                _state.value = _state.value.copy(
                    mode = RenderMode.STATIC,
                    fallbackMessage = "The audio for this record could not be opened.",
                )
            }
        }

        viewModelScope.launch {
            controller.state.collect { playback ->
                _state.value = _state.value.copy(playback = playback)
            }
        }

        viewModelScope.launch {
            graph.recordRepository.observe(recordId).collect { record ->
                if (record != null) {
                    controller.setMetadata(record)
                    _state.value = _state.value.copy(record = record)
                }
            }
        }
    }

    /** Advances the deck. Called from the screen's frame loop with real elapsed seconds. */
    fun onFrame(deltaSeconds: Float, cameraTouching: Boolean): DeckPose {
        val pose = controller.onFrame(deltaSeconds, cameraTouching)
        val playback = controller.state.value
        if (playback.deckState != _state.value.playback.deckState) {
            _state.value = _state.value.copy(playback = playback)
        }
        return pose
    }

    fun play() = controller.play()

    fun pause() = controller.pause()

    fun togglePlay() {
        if (_state.value.playback.isPlaying) pause() else play()
    }

    fun stop() = controller.stop()

    fun seekTo(fraction: Float) = controller.seekTo(fraction)

    fun setStyle(style: VinylStyleId) {
        controller.setVinylStyle(style)
        _state.value = _state.value.copy(record = controller.record)
        // The choice travels with the record, so the Vault shows the finish the user last picked.
        viewModelScope.launch {
            val record = controller.record ?: return@launch
            graph.recordRepository.save(record.copy(styledId = style, updatedAt = System.currentTimeMillis()))
        }
    }

    fun resetCamera() {
        controller.resetCamera()
        _state.value = _state.value.copy(cameraPreset = "Three-quarter")
    }

    fun nextCameraPreset() {
        controller.cycleCameraPreset()
        _state.value = _state.value.copy(cameraPreset = controller.cameraPresetLabel)
    }

    fun setCameraPreset(index: Int) {
        controller.setCameraPreset(index)
        _state.value = _state.value.copy(cameraPreset = controller.cameraPresetLabel)
    }

    fun setAutoOrbit(enabled: Boolean) = controller.setAutoOrbit(enabled)

    fun onStatistics(summary: String) {
        _state.value = _state.value.copy(statistics = summary)
    }

    /** The renderer could not start: fall back to the flat deck rather than showing a black rectangle. */
    fun onRendererUnavailable(reason: String) {
        _state.value = _state.value.copy(mode = RenderMode.STATIC, fallbackMessage = reason)
    }

    fun onHidden() = controller.onHidden()

    fun onVisible() = controller.onVisible()

    fun openExportSheet() {
        _state.value = _state.value.copy(showExportSheet = true)
    }

    fun closeExportSheet() {
        _state.value = _state.value.copy(showExportSheet = false, exported = null, exportMessage = null)
    }

    /** Exports one of the four things the player can hand over. */
    fun export(kind: ExportKind) {
        val record = _state.value.record ?: return
        viewModelScope.launch {
            val settings = _state.value.settings
            val manager = ExportManager(getApplication(), files, graph.recordRepository, com.vynylrecord.app.core.audio.render.RenderPipeline(getApplication(), files))
            val result = withContext(Dispatchers.Default) {
                when (kind) {
                    ExportKind.M4A -> manager.exportAudio(record, com.vynylrecord.app.core.data.prefs.OutputFormat.M4A, settings.audioQuality, settings.includeSourceInExport)
                    ExportKind.WAV -> manager.exportAudio(record, com.vynylrecord.app.core.data.prefs.OutputFormat.WAV, settings.audioQuality, settings.includeSourceInExport)
                    ExportKind.ARTWORK -> listOf(manager.exportArtwork(record))
                    ExportKind.BUNDLE -> listOf(manager.exportBundle(record, appVersion(getApplication()), settings.includeSourceInExport))
                }
            }
            val ready = result.filterIsInstance<ExportManager.Result.Ready>().firstOrNull()
            val failed = result.filterIsInstance<ExportManager.Result.Failed>().firstOrNull()
            _state.value = _state.value.copy(
                exported = ready,
                exportMessage = ready?.let { "Saved as ${it.file.name} · ${it.bytes / 1024} KB" } ?: failed?.message ?: "Nothing was written.",
            )
        }
    }

    /** The share intent for the last export. */
    fun shareIntent(): android.content.Intent? {
        val ready = _state.value.exported ?: return null
        val manager = ExportManager(getApplication(), files, graph.recordRepository, com.vynylrecord.app.core.audio.render.RenderPipeline(getApplication(), files))
        return manager.shareIntent(ready, _state.value.record?.displayTitle ?: "Vynyl record")
    }

    fun markPlayed() {
        viewModelScope.launch { graph.recordRepository.markPlayed(recordId) }
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }

    /** The four exports the player offers. */
    enum class ExportKind(val labelRes: Int, val detailRes: Int) {
        M4A(R.string.export_m4a, R.string.export_m4a_detail),
        WAV(R.string.export_wav, R.string.export_wav_detail),
        ARTWORK(R.string.export_artwork, R.string.export_artwork_detail),
        BUNDLE(R.string.export_bundle, R.string.export_bundle_detail),
    }

    private companion object {
        /**
         * Whether the device advertises OpenGL ES 3.0.
         *
         * A device can still fail to give a context after claiming this, which is why the renderer reports
         * its own failure as well; this check is the cheap one that avoids creating a surface at all on
         * hardware that has already said no.
         */
        fun hasGlEs3(application: Application): Boolean {
            val configuration = application.getSystemService(android.app.ActivityManager::class.java)
                ?.deviceConfigurationInfo
            return configuration != null && configuration.reqGlEsVersion >= 0x30000
        }

        fun appVersion(application: Application): String = try {
            application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "1.0"
        } catch (error: Exception) {
            "1.0"
        }
    }
}
