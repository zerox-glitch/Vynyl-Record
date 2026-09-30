package com.vynylrecord.turntable.studio

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.RecordSide
import com.vynylrecord.turntable.model.VinylStyle
import com.vynylrecord.turntable.press.PcmAudio
import com.vynylrecord.turntable.press.VinylPresser
import com.vynylrecord.turntable.press.WavIO
import com.vynylrecord.turntable.vault.Press
import com.vynylrecord.turntable.vault.PressRecipe
import com.vynylrecord.turntable.vault.PressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Studio: capture, dedicate, choose a recipe and a look, then cut a real side.
 *
 * ## What "pressing" means here
 *
 * [press] runs the whole [VinylPresser] chain over the captured or imported master and writes a WAV
 * into the vault. The character is *in the file*, not applied during playback, so the pressing sounds
 * the same everywhere and can be handed to someone else.
 *
 * ## Threading and memory
 *
 * Decoding and pressing happen on [Dispatchers.Default], never on the main thread. A side is tens of
 * megabytes of float PCM, so the chain is written to mutate one buffer rather than copy it, sides are
 * pressed one at a time, and the decoded master is released as soon as the last side is written.
 *
 * ## Offline
 *
 * Nothing here opens a socket. Capture uses the device microphone, import uses the Storage Access
 * Framework, and both produce a file in app-private storage.
 */
class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PressStore(File(application.filesDir, VAULT_DIRECTORY))
    private val recorder = VoiceRecorder(application)
    private val presser = VinylPresser()

    private val _state = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = _state.asStateFlow()

    private var captureTicker: Job? = null
    private var pressJob: Job? = null

    init {
        refreshVault()
        applyDefaultLabel()
    }

    // ------------------------------------------------------------------ label draft

    /** The catalogue number this view model last assigned by itself, so it can keep it current. */
    private var autoCatalogue: String? = null

    /** Pre-fills the label so a pressing is never unlabelled, leaving the dedication to the user. */
    private fun applyDefaultLabel() {
        val today = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date())
        val catalogue = _state.value.suggestedCatalogue
        autoCatalogue = catalogue
        _state.value = _state.value.copy(
            title = "",
            sender = "You",
            date = today,
            catalogue = catalogue,
        )
    }

    fun setStage(stage: StudioStage) {
        _state.value = _state.value.copy(stage = stage, error = null)
    }

    fun nextStage() = setStage(_state.value.stage.next())

    fun previousStage() = setStage(_state.value.stage.previous())

    fun setTitle(value: String) = updateLabel { it.copy(title = value.take(MAX_TITLE)) }

    fun setRecipient(value: String) = updateLabel { it.copy(recipient = value.take(MAX_NAME)) }

    fun setSender(value: String) = updateLabel { it.copy(sender = value.take(MAX_NAME)) }

    fun setDate(value: String) = updateLabel { it.copy(date = value.take(MAX_DATE)) }

    fun setCatalogue(value: String) {
        // Once the number has been typed, it belongs to the user and is no longer kept in step.
        autoCatalogue = null
        updateLabel { it.copy(catalogue = value.take(MAX_CATALOGUE)) }
    }

    fun setSide(side: RecordSide) = updateLabel { it.copy(side = side) }

    fun flipSide() = updateLabel { it.copy(side = it.side.flipped()) }

    private fun updateLabel(block: (StudioUiState) -> StudioUiState) {
        _state.value = block(_state.value).copy(error = null)
    }

    fun setStyle(style: VinylStyle) {
        _state.value = _state.value.copy(style = style)
    }

    fun setSpeed(speed: PlatterSpeed) {
        _state.value = _state.value.copy(speed = speed)
    }

    // ------------------------------------------------------------------ recipe

    fun setRecipe(recipe: PressRecipe) {
        _state.value = _state.value.copy(
            recipe = recipe,
            recipePreset = PressRecipe.closestPreset(recipe),
        )
    }

    fun setRecipePreset(preset: PressRecipe.Preset) = setRecipe(preset.recipe)

    fun setCrackle(value: Float) = updateRecipe { it.copy(crackle = value.coerceIn(0f, 1f)) }

    fun setSurfaceNoise(value: Float) = updateRecipe { it.copy(surfaceNoise = value.coerceIn(0f, 1f)) }

    fun setWowFlutter(value: Float) = updateRecipe { it.copy(wowFlutter = value.coerceIn(0f, 1f)) }

    fun setWarmth(value: Float) = updateRecipe { it.copy(warmth = value.coerceIn(0f, 1f)) }

    fun setRoomTone(value: Float) = updateRecipe { it.copy(roomTone = value.coerceIn(0f, 1f)) }

    fun setHiss(value: Float) = updateRecipe { it.copy(hiss = value.coerceIn(0f, 1f)) }

    fun setSaturation(value: Float) = updateRecipe { it.copy(saturation = value.coerceIn(0f, 1f)) }

    fun setTrimSilence(enabled: Boolean) = updateRecipe { it.copy(trimSilence = enabled) }

    fun setNormalize(enabled: Boolean) = updateRecipe { it.copy(normalize = enabled) }

    private fun updateRecipe(block: (PressRecipe) -> PressRecipe) {
        val updated = block(_state.value.recipe)
        setRecipe(updated)
    }

    // ------------------------------------------------------------------ capture

    /**
     * Starts recording. The caller must already hold the microphone permission; when it does not,
     * the recorder reports why and nothing is written.
     */
    fun startRecording() {
        if (_state.value.press.active) return
        val file = File(getApplication<Application>().cacheDir, "capture-${System.currentTimeMillis()}.wav")
        if (!recorder.start(file)) {
            _state.value = _state.value.copy(
                error = recorder.lastError ?: "The microphone could not be opened.",
                isRecording = false,
            )
            return
        }
        _state.value = _state.value.copy(
            isRecording = true,
            recordingMs = 0L,
            inputLevel = 0f,
            error = null,
            notice = null,
        )
        startCaptureTicker()
    }

    /** Stops recording and decodes the capture into the current source. */
    fun stopRecording() {
        if (!_state.value.isRecording) return
        stopCaptureTicker()
        val file = recorder.stop()
        _state.value = _state.value.copy(isRecording = false, inputLevel = 0f)
        if (file == null) {
            _state.value = _state.value.copy(error = recorder.lastError ?: "Nothing was captured.")
            return
        }
        loadSource(file, SourceKind.RECORDING, file.name)
    }

    fun cancelRecording() {
        stopCaptureTicker()
        recorder.discard()
        _state.value = _state.value.copy(isRecording = false, inputLevel = 0f, recordingMs = 0L)
    }

    private fun startCaptureTicker() {
        captureTicker?.cancel()
        captureTicker = viewModelScope.launch {
            while (isActive && _state.value.isRecording) {
                _state.value = _state.value.copy(
                    recordingMs = recorder.elapsedMs(),
                    inputLevel = recorder.level,
                )
                if (recorder.elapsedMs() >= VoiceRecorder.MAX_RECORDING_MS) {
                    stopRecording()
                    _state.value = _state.value.copy(notice = "Side limit reached — that is a long side.")
                }
                delay(CAPTURE_TICK_MS)
            }
        }
    }

    private fun stopCaptureTicker() {
        captureTicker?.cancel()
        captureTicker = null
    }

    // ------------------------------------------------------------------ import

    /**
     * Imports an audio file the user picked.
     *
     * The URI is a `content://` from the Storage Access Framework; only local providers can be picked
     * because the app has no network permission and no activity that would accept a remote one.
     */
    fun importAudio(uri: Uri, displayName: String? = null) {
        val context = getApplication<Application>()
        val name = displayName ?: AudioDecoder.displayName(context, uri)

        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, notice = "Reading $name")
            val staged = withContext(Dispatchers.IO) {
                try {
                    val target = File(context.cacheDir, "import-${System.currentTimeMillis()}.audio")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().buffered().use { output -> input.copyTo(output) }
                    } ?: return@withContext null
                    target
                } catch (error: IOException) {
                    Log.w(TAG, "Could not stage the imported file", error)
                    null
                } catch (error: SecurityException) {
                    Log.w(TAG, "No permission to read the imported file", error)
                    null
                }
            }
            if (staged == null) {
                _state.value = _state.value.copy(error = "That file could not be opened.", notice = null)
                return@launch
            }
            loadSource(staged, SourceKind.IMPORT, name)
        }
    }

    /** Loads an existing press back into the Studio as the side to work on. */
    fun editExisting(press: Press) {
        _state.value = _state.value.copy(
            title = press.metadata.title,
            recipient = press.metadata.recipient,
            sender = press.metadata.sender,
            date = press.metadata.date.orEmpty(),
            catalogue = press.metadata.catalogue.orEmpty(),
            side = press.metadata.side,
            style = press.style,
            speed = press.speed,
            recipe = press.recipe,
            recipePreset = PressRecipe.closestPreset(press.recipe),
            stage = StudioStage.DEDICATE,
            notice = "Editing ${press.metadata.title}",
            error = null,
        )
        val file = store.audioFile(press)
        if (file.isFile) {
            loadSource(file, SourceKind.RECORDING, press.metadata.title)
        } else {
            _state.value = _state.value.copy(error = "That pressing's audio is missing from storage.")
        }
    }

    /**
     * Decodes a source file and makes it the master.
     *
     * Decoding runs off the main thread; the waveform is computed from the decoded buffer, which is
     * then released. Nothing is retained in memory until the press starts.
     */
    private fun loadSource(file: File, kind: SourceKind, displayName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, notice = "Preparing $displayName")
            val prepared = withContext(Dispatchers.Default) {
                // One decode path for both sources: it reads a WAV directly and hands anything else to
                // MediaCodec, always at the studio rate. Channels are left as they are -- the press
                // chain is channel-agnostic, and duplicating a mono note to stereo would double a
                // buffer that is already tens of megabytes.
                val audio = AudioDecoder.decode(getApplication(), file) ?: return@withContext null

                SourceInfo(
                    kind = kind,
                    displayName = displayName,
                    durationMs = audio.durationMs,
                    sampleRate = audio.sampleRate,
                    channels = audio.channels,
                    peakDecibels = AudioDecoder.peakDecibels(audio),
                    waveform = WavIO.envelope(audio, WAVEFORM_BUCKETS).toList(),
                    spansMultipleSides = audio.durationMs > MAX_SIDE_MS,
                )
            }
            if (prepared == null) {
                _state.value = _state.value.copy(
                    error = "That file could not be read on this device.",
                    notice = null,
                )
                return@launch
            }
            if (prepared.durationMs < MIN_SOURCE_MS) {
                _state.value = _state.value.copy(
                    source = prepared,
                    error = "That recording is too short to press — try again.",
                    notice = null,
                )
                return@launch
            }
            if (prepared.peakDecibels <= -59f) {
                _state.value = _state.value.copy(
                    source = prepared,
                    error = "That recording is silent — there is nothing to press.",
                    notice = null,
                )
                return@launch
            }
            stagedSourceFile = file
            _state.value = _state.value.copy(
                source = prepared,
                notice = if (prepared.spansMultipleSides) {
                    "${prepared.durationLabel} — that is ${sideCount(prepared.durationMs)} sides"
                } else {
                    "Ready to press"
                },
                error = null,
                stage = StudioStage.DEDICATE,
            )
        }
    }

    fun clearSource() {
        stagedSourceFile = null
        _state.value = _state.value.copy(source = null, notice = null, stage = StudioStage.CAPTURE)
    }

    // ------------------------------------------------------------------ pressing

    /**
     * Cuts the side(s) and writes them into the vault.
     *
     * The master is split into sides of at most [MAX_SIDE_MS] so a long recording becomes Side A and
     * Side B rather than a single impossible record. Each side is pressed and written before the next
     * is started, so peak memory is one side plus the master.
     */
    fun press() {
        val current = _state.value
        val source = current.source ?: return
        if (!current.canPress) return
        if (pressJob?.isActive == true) return

        val sourceFile = sourceFileFor(source)
        if (sourceFile == null || !sourceFile.isFile) {
            _state.value = current.copy(error = "The master recording is no longer available.")
            return
        }

        pressJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                press = PressProgress(active = true, fraction = 0f, stage = "Reading the master", sideLabel = "Side A", sideCount = sideCount(source.durationMs)),
                error = null,
                notice = null,
            )

            val master = withContext(Dispatchers.Default) {
                WavIO.read(sourceFile) ?: AudioDecoder.decode(getApplication(), sourceFile)
            }
            if (master == null) {
                _state.value = _state.value.copy(
                    press = PressProgress(),
                    error = "The master could not be read back for pressing.",
                )
                return@launch
            }

            val recipe = _state.value.recipe
            val metadata = metadataFromState(_state.value)
            val sides = splitIntoSides(master)
            val pressedSides = mutableListOf<Press>()

            for ((index, slice) in sides.withIndex()) {
                val sideOrdinal = (metadata.side.ordinal + index) % RecordSide.entries.size
                val side = RecordSide.entries[sideOrdinal]
                val sideLabel = side.displayName
                val sideMetadata = metadata.copy(side = side)
                val startFraction = index.toFloat() / sides.size

                val audio = withContext(Dispatchers.Default) {
                    // Each side owns its buffer: the master is shared and is released after the loop.
                    val working = if (sides.size == 1) {
                        master
                    } else {
                        PcmAudio(
                            master.sampleRate,
                            master.channels,
                            master.samples.copyOfRange(
                                slice.startFrame * master.channels,
                                (slice.startFrame + slice.frameCount) * master.channels,
                            ),
                            slice.frameCount,
                        )
                    }
                    presser.press(working, recipe) { fraction, stage ->
                        _state.value = _state.value.copy(
                            press = PressProgress(
                                active = true,
                                fraction = (startFraction + fraction / sides.size).coerceIn(0f, 1f),
                                stage = stage,
                                sideLabel = sideLabel,
                                sideIndex = index + 1,
                                sideCount = sides.size,
                            ),
                        )
                    }
                }

                val written = withContext(Dispatchers.IO) { writeSide(audio, sideMetadata, recipe, source) }
                if (written == null) {
                    _state.value = _state.value.copy(
                        press = PressProgress(),
                        error = "The side could not be written to storage.",
                    )
                    return@launch
                }
                pressedSides += written
            }

            refreshVault()
            _state.value = _state.value.copy(
                press = PressProgress(),
                pressed = pressedSides.firstOrNull(),
                notice = if (pressedSides.size > 1) {
                    "Pressed ${pressedSides.size} sides"
                } else {
                    "Pressed and stored in the vault"
                },
                error = null,
            )
        }
    }

    /** Writes one pressed side into the vault and returns the stored record. */
    private fun writeSide(
        audio: PcmAudio,
        metadata: RecordMetadata,
        recipe: PressRecipe,
        source: SourceInfo,
    ): Press? = try {
        val allocated = store.allocate(
            metadata = metadata,
            style = _state.value.style,
            speed = _state.value.speed,
            recipe = recipe,
            sourceLabel = sourceLabelFor(source),
            durationMs = audio.durationMs,
        )
        val directory = store.directoryFor(allocated.id)
        val target = File(directory, allocated.fileName)
        if (!WavIO.write(target, audio)) throw IOException("write failed: ${target.absolutePath}")
        store.attachAudio(allocated, target, audio.durationMs)
    } catch (error: IOException) {
        Log.e(TAG, "Could not write a pressed side", error)
        null
    }

    fun dismissPressed() {
        _state.value = _state.value.copy(pressed = null)
    }

    // ------------------------------------------------------------------ vault

    fun refreshVault() {
        viewModelScope.launch {
            val presses = withContext(Dispatchers.IO) {
                if (!store.ensureReady()) {
                    emptyList()
                } else {
                    store.list()
                }
            }
            val bytes = withContext(Dispatchers.IO) { store.totalBytes() }
            val updated = _state.value.copy(vault = presses, vaultBytes = bytes)
            // The catalogue number counts the vault, so it has to move with it. A number the user
            // typed themselves is left strictly alone.
            val suggestion = updated.suggestedCatalogue
            if (updated.catalogue.isBlank() || updated.catalogue == autoCatalogue) {
                autoCatalogue = suggestion
                _state.value = updated.copy(catalogue = suggestion)
            } else {
                _state.value = updated
            }
        }
    }

    fun deletePress(press: Press) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { store.delete(press) }
            refreshVault()
            _state.value = _state.value.copy(
                notice = if (removed) "Removed ${press.metadata.title}" else "That pressing could not be removed",
            )
        }
    }

    fun renamePress(press: Press, metadata: RecordMetadata) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) { runCatching { store.rename(press, metadata) }.getOrNull() }
            refreshVault()
            _state.value = _state.value.copy(
                notice = if (updated != null) "Renamed to ${updated.metadata.title}" else "That pressing could not be renamed",
            )
        }
    }

    fun clearVault() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            refreshVault()
            _state.value = _state.value.copy(notice = "Vault cleared")
        }
    }

    /** The audio file of a vault record, for loading it onto the deck. */
    fun audioFileOf(press: Press): File = store.audioFile(press)

    /** The other side of a two-sided pressing, if it was pressed in the same session. */
    fun siblingSide(press: Press): Press? = _state.value.vault.firstOrNull {
        it.id != press.id && it.metadata.title == press.metadata.title && it.metadata.side != press.metadata.side
    }

    /**
     * The sibling side to play next, or null when this record has only one side.
     *
     * Used by the deck's flip control: flipping a two-sided pressing should load the other side, not
     * merely reprint the label.
     */
    fun audioFileOfSibling(press: Press): File? {
        val sibling = siblingSide(press) ?: return null
        val file = store.audioFile(sibling)
        return file.takeIf { it.isFile }
    }

    // ------------------------------------------------------------------ messages

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(
            error = message,
            press = PressProgress(),
        )
    }

    val isBusy: Boolean get() = _state.value.press.active

    // ------------------------------------------------------------------ helpers

    /** Where the current master lives on disk: the cache for a capture, the vault for an edit. */
    private var stagedSourceFile: File? = null

    private fun sourceFileFor(source: SourceInfo): File? = stagedSourceFile

    private fun metadataFromState(state: StudioUiState): RecordMetadata = RecordMetadata(
        title = state.title.trim().ifBlank { "Untitled Side" },
        recipient = state.recipient.trim().ifBlank { "You" },
        sender = state.sender.trim().ifBlank { "Vynyl" },
        side = state.side,
        date = state.date.trim().takeIf { it.isNotEmpty() },
        catalogue = state.catalogue.trim().takeIf { it.isNotEmpty() },
    ).normalized()

    /** A one-line provenance for the vault row. */
    private fun sourceLabelFor(source: SourceInfo): String = when (source.kind) {
        SourceKind.RECORDING -> "Recorded in Studio"
        SourceKind.IMPORT -> "Imported: ${source.displayName}"
    }

    private fun sideCount(durationMs: Long): Int =
        if (durationMs <= 0L) 1 else ((durationMs + MAX_SIDE_MS - 1) / MAX_SIDE_MS).toInt().coerceAtLeast(1)

    /** Where one side sits inside the decoded master. */
    private data class SideSlice(val startFrame: Int, val frameCount: Int)

    /**
     * Splits the master into sides, as frame ranges over the one decoded buffer.
     *
     * Nothing is copied here: each side is copied the moment before it is pressed, and the copy is
     * released once the side has been written. A single-side master -- the usual case for a voice note
     * -- is pressed in place, so a three-minute note needs one buffer, not three.
     */
    private fun splitIntoSides(master: PcmAudio): List<SideSlice> {
        if (master.durationMs <= MAX_SIDE_MS) return listOf(SideSlice(0, master.frameCount))
        val framesPerSide = (MAX_SIDE_MS * master.sampleRate / 1000L).toInt()
        val sides = mutableListOf<SideSlice>()
        var start = 0
        while (start < master.frameCount) {
            val frames = minOf(framesPerSide, master.frameCount - start)
            sides += SideSlice(start, frames)
            start += frames
        }
        return sides
    }

    private companion object {
        const val TAG = "VynylStudio"

        /** Where the vault lives inside app-private storage. */
        const val VAULT_DIRECTORY = "presses"

        /** A side of a 7-inch at 33 rpm holds about this much. */
        const val MAX_SIDE_MS = 3L * 60L * 1000L

        /** Anything shorter than this is a mis-tap, not a recording. */
        const val MIN_SOURCE_MS = 350L

        const val CAPTURE_TICK_MS = 100L
        const val WAVEFORM_BUCKETS = 160

        const val MAX_TITLE = 48
        const val MAX_NAME = 32
        const val MAX_DATE = 24
        const val MAX_CATALOGUE = 16
    }
}
