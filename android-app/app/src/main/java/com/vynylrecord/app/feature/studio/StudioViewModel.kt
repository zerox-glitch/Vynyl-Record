package com.vynylrecord.app.feature.studio

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vynylrecord.app.VynylGraph
import com.vynylrecord.app.core.audio.AudioDecoder
import com.vynylrecord.app.core.audio.StudioFormat
import com.vynylrecord.app.core.audio.VoiceRecorder
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.data.prefs.VynylSettings
import com.vynylrecord.app.core.model.AudioAsset
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylControl
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.storage.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Studio's state machine.
 *
 * Five steps, in the order a record is actually made:
 *
 * 1. **Capture** — speak, pause, resume, stop; or bring in a file you already have.
 * 2. **Details** — who it is for, who it is from, the occasion, and what the label says.
 * 3. **Recipe** — the vinyl preset, and the knobs underneath it when the preset is not quite right.
 * 4. **Appearance** — the finish, and the background music that plays under the voice.
 * 5. **Press** — the render, with the nine stages visible as they run.
 *
 * The state lives here rather than in the composables because the steps depend on each other: whether the
 * Press step can be reached depends on whether there is a source file, and what "Preview" plays depends on
 * the recipe the previous step just changed.
 */
class StudioViewModel(
    application: Application,
    private val recordId: String?,
) : AndroidViewModel(application) {

    private val graph = VynylGraph.of(application)
    private val files = FileStore(application)
    private val recorder = VoiceRecorder(viewModelScope)

    /** Which step the user is on. */
    enum class Step(val index: Int, val labelRes: Int) {
        CAPTURE(0, com.vynylrecord.app.R.string.studio_step_capture),
        DETAILS(1, com.vynylrecord.app.R.string.studio_step_dedication),
        RECIPE(2, com.vynylrecord.app.R.string.studio_step_recipe),
        APPEARANCE(3, com.vynylrecord.app.R.string.studio_step_appearance),
        PRESS(4, com.vynylrecord.app.R.string.studio_step_render),
        ;

        companion object {
            val ordered: List<Step> = entries.toList()
        }
    }

    /** Everything the screen draws, in one object, so there is never a second source of truth. */
    data class UiState(
        val step: Step = Step.CAPTURE,
        val record: Record? = null,
        val settings: VynylSettings = VynylSettings(),
        val hasSource: Boolean = false,
        val recorder: VoiceRecorder.State = VoiceRecorder.State.Idle,
        val levels: List<Float> = emptyList(),
        val meter: VoiceRecorder.Meter = VoiceRecorder.Meter(),
        val backgrounds: List<AudioAsset> = emptyList(),
        val selectedBackground: AudioAsset? = null,
        val error: String? = null,
        val busy: Boolean = false,
        val microphoneUnavailable: Boolean = false,
        val sourceDurationMs: Long = 0L,
    ) {
        val isRecording: Boolean get() = recorder is VoiceRecorder.State.Recording
        val isPaused: Boolean get() = recorder is VoiceRecorder.State.Paused
        val isRendering: Boolean get() = record?.renderState?.isRunning == true
        val isRendered: Boolean get() = record?.renderState == RenderState.COMPLETED

        /** The capture step is only finished once there is audio to press. */
        val canContinue: Boolean
            get() = when (step) {
                Step.CAPTURE -> hasSource
                Step.DETAILS -> true
                Step.RECIPE -> true
                Step.APPEARANCE -> true
                Step.PRESS -> false
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = graph.preferences.current()
            val loaded = recordId?.let { graph.recordRepository.find(it) }
            _state.value = _state.value.copy(
                settings = settings,
                record = loaded,
                hasSource = loaded?.let { graph.recordRepository.resolveSource(it) != null } ?: false,
                sourceDurationMs = loaded?.sourceDurationMilliseconds ?: 0L,
                selectedBackground = loaded?.backgroundAssetId?.let { id -> graph.assetRepository.find(id) },
                step = when {
                    loaded == null -> Step.CAPTURE
                    loaded.renderState.isRunning || loaded.renderState == RenderState.COMPLETED -> Step.PRESS
                    loaded.let { graph.recordRepository.resolveSource(it) != null } -> Step.DETAILS
                    else -> Step.CAPTURE
                },
            )
        }

        viewModelScope.launch {
            graph.assetRepository.backgroundMusic.collect { list ->
                _state.value = _state.value.copy(backgrounds = list)
            }
        }

        viewModelScope.launch {
            recorder.state.collect { recorderState ->
                val current = _state.value
                _state.value = when (recorderState) {
                    is VoiceRecorder.State.Recording -> current.copy(
                        recorder = recorderState,
                        sourceDurationMs = recorderState.elapsedMs,
                        error = null,
                        microphoneUnavailable = false,
                    )

                    is VoiceRecorder.State.Paused -> current.copy(recorder = recorderState, sourceDurationMs = recorderState.elapsedMs)

                    is VoiceRecorder.State.Finished -> current.copy(
                        recorder = recorderState,
                        sourceDurationMs = recorderState.durationMs,
                        busy = true,
                    )

                    is VoiceRecorder.State.Failed -> current.copy(
                        recorder = recorderState,
                        error = recorderState.message,
                        busy = false,
                    )

                    else -> current.copy(recorder = recorderState)
                }
                if (recorderState is VoiceRecorder.State.Finished) {
                    adoptCapture(recorderState)
                }
            }
        }

        viewModelScope.launch { recorder.levels.collect { levels -> _state.value = _state.value.copy(levels = levels) } }
        viewModelScope.launch { recorder.meter.collect { meter -> _state.value = _state.value.copy(meter = meter) } }
    }

    // ------------------------------------------------------------------ capture

    fun startRecording() {
        val target = File(files.stagingRoot(), "capture-${System.currentTimeMillis()}.wav")
        if (!recorder.start(target)) {
            target.delete()
            _state.value = _state.value.copy(
                error = "The microphone is not available. You can still import a file.",
                microphoneUnavailable = true,
            )
        }
    }

    fun pauseRecording() = recorder.pause()

    fun resumeRecording() = recorder.resume()

    fun stopRecording() = recorder.stop()

    /** The recorder stops itself at the side limit; the screen says so rather than just going quiet. */
    val reachedSideLimit: Boolean
        get() = _state.value.sourceDurationMs >= com.vynylrecord.app.core.audio.VoiceRecorder.MAX_DURATION_MS - 5_000L

    /** Throws the take away and starts again. */
    fun discardRecording() {
        recorder.cancel()
        _state.value = _state.value.copy(
            sourceDurationMs = 0L,
            error = null,
            recorder = VoiceRecorder.State.Idle,
            hasSource = _state.value.record?.let { graph.recordRepository.resolveSource(it) != null } ?: false,
        )
    }

    private fun adoptCapture(finished: VoiceRecorder.State.Finished) {
        viewModelScope.launch {
            val record = attachSource(finished.file, suggestedTitle = "")
            _state.value = _state.value.copy(
                busy = false,
                record = record,
                hasSource = record != null,
                sourceDurationMs = finished.durationMs,
                step = if (record != null) Step.DETAILS else Step.CAPTURE,
                error = if (record == null) "That recording could not be saved." else null,
            )
        }
    }

    /** Imports an audio file the user picked through the system picker. */
    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            val displayName = AudioDecoder.displayName(getApplication(), uri)
            val staged = File(files.stagingRoot(), "import-${System.currentTimeMillis()}")
            val copied = AudioDecoder.stageDocument(getApplication(), uri, staged)
            if (copied == null) {
                _state.value = _state.value.copy(busy = false, error = "That file could not be opened.")
                return@launch
            }
            // Anything that is not already the studio's format is decoded once, here, so the press is fast
            // later and so a failure is reported while the user is still looking at the capture step.
            val wav = File(files.stagingRoot(), "import-${System.currentTimeMillis()}.wav")
            val decoded = withContext(Dispatchers.Default) {
                AudioDecoder.decodeToWav(getApplication(), copied, wav, StudioFormat.SAMPLE_RATE) != null
            }
            copied.delete()
            if (!decoded) {
                wav.delete()
                _state.value = _state.value.copy(
                    busy = false,
                    error = "That file could not be read as audio. Try a WAV, M4A or MP3 file.",
                )
                return@launch
            }
            val record = attachSource(wav, suggestedTitle = displayName.substringBeforeLast('.'))
            wav.delete()
            _state.value = _state.value.copy(
                busy = false,
                record = record,
                hasSource = record != null,
                sourceDurationMs = record?.sourceDurationMilliseconds ?: 0L,
                step = if (record != null) Step.DETAILS else Step.CAPTURE,
                error = if (record == null) "That recording could not be saved." else null,
            )
        }
    }

    /**
     * Moves a finished capture or import into the record's own directory.
     *
     * A new record is created when there is none; when one already exists — a second take, or a re-import —
     * its source is replaced and everything else the user typed is kept. Losing a dedication because someone
     * re-recorded a sentence would be a small betrayal.
     */
    private suspend fun attachSource(source: File, suggestedTitle: String): Record? = try {
        val existing = _state.value.record
        val settings = _state.value.settings
        if (existing == null) {
            graph.recordRepository.createDraft(
                capturedSource = source,
                title = suggestedTitle,
                preset = settings.defaultPreset,
                style = settings.lastStyle,
                occasion = Occasion.fromId(settings.lastOccasion),
                senderName = settings.senderName,
                defaultMusicLevel = settings.defaultMusicLevel,
            )
        } else {
            files.placeAtomically(source, files.createSourceFile(existing.id))
            val refreshed = existing.copy(
                sourcePath = files.sourceFile(existing.id).absolutePath,
                sourceDurationMilliseconds = durationOf(files.sourceFile(existing.id)),
                // A re-recorded draft is a draft again: the previous master no longer matches the voice.
                renderState = RenderState.DRAFT,
                renderProgress = 0f,
                renderStageLabel = "Draft",
                renderError = null,
                updatedAt = System.currentTimeMillis(),
            )
            graph.recordRepository.save(refreshed)
        }
    } catch (error: Exception) {
        Log.w(TAG, "could not attach the source audio", error)
        null
    }

    private fun durationOf(file: File): Long = try {
        WavCodec.Reader(file).use { reader -> reader.frameCount * 1000L / reader.sampleRate }
    } catch (error: Exception) {
        0L
    }

    // ------------------------------------------------------------------ details, recipe, appearance

    fun goTo(step: Step) {
        val current = _state.value
        if (step.index > Step.CAPTURE.index && !current.hasSource) return
        _state.value = current.copy(step = step)
    }

    fun next() {
        val current = _state.value
        val next = Step.entries.getOrNull(current.step.index + 1) ?: return
        if (next == Step.PRESS && !current.hasSource && !current.isRendered) return
        _state.value = current.copy(step = next)
    }

    fun back() {
        val previous = Step.entries.getOrNull(_state.value.step.index - 1) ?: return
        _state.value = _state.value.copy(step = previous)
    }

    fun updateDetails(
        title: String? = null,
        recipient: String? = null,
        sender: String? = null,
        dedication: String? = null,
        occasion: Occasion? = null,
        occasionDate: String? = null,
        sideA: String? = null,
    ) {
        val record = _state.value.record ?: return
        persist(
            record.copy(
                title = title ?: record.title,
                recipientName = recipient ?: record.recipientName,
                senderName = sender ?: record.senderName,
                dedication = dedication ?: record.dedication,
                occasion = occasion ?: record.occasion,
                occasionDate = occasionDate ?: record.occasionDate,
                sideALabel = sideA ?: record.sideALabel,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun choosePreset(preset: VinylPresetId) {
        val record = _state.value.record ?: return
        // Choosing a preset clears the moved knobs: "back to Warm Vintage" has to mean exactly that, or the
        // panel would keep a tweak the user has forgotten about.
        persist(
            record.copy(
                presetId = preset,
                controls = record.controls.resetTo(preset),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun setControl(control: VinylControl, value: Float) {
        val record = _state.value.record ?: return
        persist(record.copy(controls = record.controls.with(control, value), updatedAt = System.currentTimeMillis()))
    }

    fun resetControls() {
        val record = _state.value.record ?: return
        persist(record.copy(controls = record.controls.resetTo(record.presetId), updatedAt = System.currentTimeMillis()))
    }

    fun chooseStyle(style: VinylStyleId) {
        val record = _state.value.record ?: return
        viewModelScope.launch { graph.preferences.setLastStyle(style) }
        persist(record.copy(styledId = style, updatedAt = System.currentTimeMillis()))
    }

    fun chooseBackground(asset: AudioAsset?) {
        val record = _state.value.record ?: return
        persist(
            record.copy(
                backgroundAssetId = asset?.id,
                backgroundVolume = asset?.defaultVolume ?: record.backgroundVolume,
                backgroundTrimStartMs = asset?.trimStartMilliseconds ?: 0L,
                backgroundTrimEndMs = asset?.trimEndMilliseconds ?: 0L,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        _state.value = _state.value.copy(selectedBackground = asset)
    }

    fun setBackgroundVolume(value: Float) {
        val record = _state.value.record ?: return
        persist(record.copy(backgroundVolume = value.coerceIn(0f, 0.8f), updatedAt = System.currentTimeMillis()))
    }

    fun setNeedleIntro(milliseconds: Int) {
        val record = _state.value.record ?: return
        persist(
            record.copy(
                controls = record.controls.with(VinylControl.NEEDLE_INTRO, milliseconds / 800f),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun persist(record: Record) {
        _state.value = _state.value.copy(record = record)
        viewModelScope.launch { graph.recordRepository.save(record) }
    }

    // ------------------------------------------------------------------ pressing

    /** Starts the press. Returns false when there is nothing to press. */
    fun press(): Boolean {
        val record = _state.value.record ?: return false
        if (record.renderState.isRunning) return true
        if (!_state.value.hasSource && !record.isReady) return false

        // The pre-flight check runs before the render rather than during it: a missing background asset or
        // a record with no source is worth knowing about while the user is still looking at the button.
        val blocking = com.vynylrecord.app.core.audio.render.RenderPreflight
            .check(record, _state.value.selectedBackground)
            .firstOrNull { it.level == com.vynylrecord.app.core.audio.render.RenderPreflight.Finding.Level.ERROR }
        if (blocking != null) {
            _state.value = _state.value.copy(error = blocking.message)
            return false
        }
        val jobId = java.util.UUID.randomUUID().toString()
        viewModelScope.launch {
            graph.recordRepository.markQueued(record.id, jobId)
            graph.renderScheduler.enqueue(record.id, jobId)
        }
        _state.value = _state.value.copy(
            step = Step.PRESS,
            record = record.copy(renderState = RenderState.QUEUED, renderJobId = jobId, renderProgress = 0f),
        )
        return true
    }

    fun cancelPress() {
        val record = _state.value.record ?: return
        viewModelScope.launch {
            graph.renderScheduler.cancel(record.id)
            graph.recordRepository.markCancelled(record.id)
        }
    }

    /** The record as it stands, for the player to open after a press finishes. */
    fun currentRecord(): Record? = _state.value.record

    override fun onCleared() {
        recorder.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "VynylStudioViewModel"
    }
}
