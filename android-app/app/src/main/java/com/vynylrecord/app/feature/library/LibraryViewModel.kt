package com.vynylrecord.app.feature.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vynylrecord.app.VynylGraph
import com.vynylrecord.app.core.audio.AudioDecoder
import com.vynylrecord.app.core.data.prefs.OutputFormat
import com.vynylrecord.app.core.data.prefs.VynylSettings
import com.vynylrecord.app.core.export.ExportManager
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.audio.render.RenderPipeline
import com.vynylrecord.app.core.storage.FileStore
import com.vynylrecord.app.core.storage.RecordImport
import com.vynylrecord.app.core.storage.VynylBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Vault: every record, and everything that can be done to one.
 *
 * Filtering and sorting happen here rather than in SQL because the vault also filters on things a query
 * cannot know — whether a record's audio is still on disk, whether a search term matches the recipient's
 * name as well as the title. A hundred records is a list the app can hold in memory and filter honestly.
 *
 * Undo is real: [delete] takes a copy of the record's files into the cache, and [undoDelete] puts them back.
 * A confirmation dialog is not a substitute for being able to change your mind — people tap "Delete" by
 * accident on the second row of a list, and losing a grandparent's voice to a mis-tap is not acceptable.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = VynylGraph.of(application)
    private val files = FileStore(application)
    private val exports = ExportManager(application, files, graph.recordRepository, RenderPipeline(application, files))

    /** How the shelf is ordered. */
    enum class SortOption(val label: String) {
        NEWEST("Newest first"),
        OLDEST("Oldest first"),
        TITLE("Title A–Z"),
        DURATION("Longest first"),
        LAST_PLAYED("Last played"),
    }

    /** What can be filtered on. */
    data class Filters(
        val query: String = "",
        val occasion: Occasion? = null,
        val preset: VinylPresetId? = null,
        val style: VinylStyleId? = null,
        val favouriteOnly: Boolean = false,
        val state: RenderState? = null,
    ) {
        val isActive: Boolean
            get() = query.isNotBlank() || occasion != null || preset != null || style != null || favouriteOnly || state != null

        val activeCount: Int
            get() = listOf(occasion != null, preset != null, style != null, favouriteOnly, state != null)
                .count { it } + if (query.isNotBlank()) 1 else 0
    }

    data class UiState(
        val records: List<Record> = emptyList(),
        val all: List<Record> = emptyList(),
        val filters: Filters = Filters(),
        val sort: SortOption = SortOption.NEWEST,
        val grid: Boolean = true,
        val settings: VynylSettings = VynylSettings(),
        val loading: Boolean = true,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val pendingDelete: Record? = null,
        val undoable: Record? = null,
        val exported: ExportManager.Result.Ready? = null,
        val exportedTitle: String = "",
        val importPreview: ImportPreview? = null,
        val busy: Boolean = false,
    ) {
        val empty: Boolean get() = all.isEmpty() && !loading
        val nothingMatches: Boolean get() = all.isNotEmpty() && records.isEmpty()
    }

    /** A bundle that has been opened and is waiting for the user to confirm the import. */
    data class ImportPreview(
        val source: File,
        /** The scratch directory the bundle was extracted into; deleted whichever way the user answers. */
        val scratch: File,
        val title: String,
        val dedication: String,
        val durationLabel: String,
        val bytes: Long,
        val includesSource: Boolean,
        val contents: VynylBundle.Contents,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastDeleted: Pair<Record, File>? = null

    init {
        viewModelScope.launch {
            combine(graph.recordRepository.records, graph.preferences.settings) { records, settings ->
                records to settings
            }.collect { (records, settings) ->
                val current = _state.value
                _state.value = current.copy(
                    all = records,
                    settings = settings,
                    loading = false,
                    // The layout preference is the source of truth on first load; after that the user's taps
                    // are, and each tap writes the preference back.
                    grid = if (current.loading) settings.gridLayout else current.grid,
                    records = applyFilters(records, current.filters, current.sort),
                )
            }
        }
    }

    fun setQuery(query: String) = updateFilters { it.copy(query = query) }

    fun setOccasion(occasion: Occasion?) = updateFilters { it.copy(occasion = occasion) }

    fun setPreset(preset: VinylPresetId?) = updateFilters { it.copy(preset = preset) }

    fun setStyle(style: VinylStyleId?) = updateFilters { it.copy(style = style) }

    fun toggleFavouriteFilter() = updateFilters { it.copy(favouriteOnly = !it.favouriteOnly) }

    fun setStateFilter(state: RenderState?) = updateFilters { it.copy(state = state) }

    fun clearFilters() {
        val current = _state.value
        _state.value = current.copy(
            filters = Filters(),
            records = applyFilters(current.all, Filters(), current.sort),
        )
    }

    fun setSort(sort: SortOption) {
        val current = _state.value
        _state.value = current.copy(sort = sort, records = applyFilters(current.all, current.filters, sort))
    }

    fun setGrid(grid: Boolean) {
        _state.value = _state.value.copy(grid = grid)
        viewModelScope.launch { graph.preferences.setGridLayout(grid) }
    }

    private fun updateFilters(transform: (Filters) -> Filters) {
        val current = _state.value
        val filters = transform(current.filters)
        _state.value = current.copy(filters = filters, records = applyFilters(current.all, filters, current.sort))
    }

    private fun applyFilters(records: List<Record>, filters: Filters, sort: SortOption): List<Record> {
        val filtered = records.filter { record ->
            val matchesQuery = filters.query.isBlank() || listOf(
                record.title,
                record.recipientName,
                record.senderName,
                record.dedication,
                record.occasion.label,
                record.presetId.displayName,
                record.styledId.displayName,
            ).any { it.contains(filters.query, ignoreCase = true) }

            matchesQuery &&
                (filters.occasion == null || record.occasion == filters.occasion) &&
                (filters.preset == null || record.presetId == filters.preset) &&
                (filters.style == null || record.styledId == filters.style) &&
                (!filters.favouriteOnly || record.favourite) &&
                (filters.state == null || record.renderState == filters.state)
        }
        return when (sort) {
            SortOption.NEWEST -> filtered.sortedByDescending { it.updatedAt }
            SortOption.OLDEST -> filtered.sortedBy { it.createdAt }
            SortOption.TITLE -> filtered.sortedBy { it.displayTitle.lowercase() }
            SortOption.DURATION -> filtered.sortedByDescending { it.durationMilliseconds }
            SortOption.LAST_PLAYED -> filtered.sortedByDescending { it.lastPlayedAt }
        }
    }

    // ------------------------------------------------------------------ record actions

    fun toggleFavourite(id: String) {
        viewModelScope.launch { graph.recordRepository.toggleFavourite(id) }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            val copy = graph.recordRepository.duplicate(id)
            message(if (copy != null) "\"${copy.displayTitle}\" added to the Vault" else "That record could not be duplicated.")
        }
    }

    /** Marks a record for another press, keeping its source and its settings. */
    fun reRender(id: String) {
        viewModelScope.launch {
            val record = graph.recordRepository.find(id) ?: return@launch
            if (graph.recordRepository.resolveSource(record) == null) {
                message("This record has no source audio left to press again.", error = true)
                return@launch
            }
            val jobId = java.util.UUID.randomUUID().toString()
            graph.recordRepository.markQueued(id, jobId)
            graph.renderScheduler.enqueue(id, jobId)
            message("Pressing \"${record.displayTitle}\" again")
        }
    }

    /** Asks; the confirmation dialog is what calls [confirmDelete]. */
    fun requestDelete(record: Record) {
        _state.value = _state.value.copy(pendingDelete = record)
    }

    fun cancelDelete() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    /**
     * Deletes a record, keeping its files in the cache so the delete can be undone.
     *
     * The cache copy is what makes undo real rather than "restore from a backup you should have made". It is
     * deleted for good when the undo window closes or another record is deleted.
     */
    fun confirmDelete() {
        val record = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            val staging = File(files.renderDirectory("undo-${record.id}"))
            val directory = files.recordDirectory(record.id)
            val kept = directory.copyRecursively(staging, overwrite = true)
            val removed = graph.recordRepository.delete(record.id)
            if (removed && kept) {
                lastDeleted?.second?.let { old -> com.vynylrecord.app.core.storage.StorageLayout.deleteTree(old) }
                lastDeleted = record to staging
                _state.value = _state.value.copy(
                    pendingDelete = null,
                    undoable = record,
                    message = "\"${record.displayTitle}\" deleted",
                )
            } else if (removed) {
                _state.value = _state.value.copy(pendingDelete = null, message = "\"${record.displayTitle}\" deleted")
            } else {
                _state.value = _state.value.copy(
                    pendingDelete = null,
                    message = "The audio could not be deleted. Try again.",
                    messageIsError = true,
                )
            }
        }
    }

    /** Puts a deleted record back, files and all. */
    fun undoDelete() {
        val (record, staging) = lastDeleted ?: return
        viewModelScope.launch {
            val target = files.recordDirectory(record.id)
            com.vynylrecord.app.core.storage.StorageLayout.deleteTree(target)
            if (staging.copyRecursively(target, overwrite = true)) {
                graph.recordRepository.save(record.copy(updatedAt = System.currentTimeMillis()))
                message("\"${record.displayTitle}\" is back")
            } else {
                message("That record could not be restored.", error = true)
            }
            com.vynylrecord.app.core.storage.StorageLayout.deleteTree(staging)
            lastDeleted = null
            _state.value = _state.value.copy(undoable = null)
        }
    }

    /** Dismisses the message bar. The undo offer goes with it: leaving it up forever would be a lie. */
    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, messageIsError = false, undoable = null)
    }

    private fun message(text: String, error: Boolean = false) {
        _state.value = _state.value.copy(message = text, messageIsError = error)
    }

    // ------------------------------------------------------------------ export

    /** Exports and holds the result for the share sheet. */
    fun export(record: Record, format: OutputFormat? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val settings = _state.value.settings
            val results = withContext(Dispatchers.Default) {
                exports.exportAudio(
                    record = record,
                    format = format ?: settings.outputFormat,
                    quality = settings.audioQuality,
                    includeSource = settings.includeSourceInExport,
                )
            }
            val ready = results.filterIsInstance<ExportManager.Result.Ready>().firstOrNull()
            val failure = results.filterIsInstance<ExportManager.Result.Failed>().firstOrNull()
            _state.value = _state.value.copy(
                busy = false,
                exported = ready,
                exportedTitle = record.displayTitle,
                message = when {
                    ready != null -> "Export ready: ${ready.file.name}"
                    failure != null -> failure.message
                    else -> "Nothing was exported."
                },
                messageIsError = ready == null,
            )
        }
    }

    fun dismissExport() {
        _state.value = _state.value.copy(exported = null)
    }

    /** The share intent for whatever was exported most recently. */
    fun shareIntent(): android.content.Intent? {
        val ready = _state.value.exported ?: return null
        return exports.shareIntent(ready, _state.value.exportedTitle)
    }

    // ------------------------------------------------------------------ import

    /** Opens a `.vynyl` bundle the user picked and shows what is inside before importing it. */
    fun previewImport(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val staged = withContext(Dispatchers.IO) {
                val target = File(files.stagingRoot(), "bundle-${System.currentTimeMillis()}.${VynylBundle.EXTENSION}")
                AudioDecoder.stageDocument(getApplication(), uri, target)
            }
            if (staged == null) {
                _state.value = _state.value.copy(busy = false, message = "That file could not be opened.", messageIsError = true)
                return@launch
            }
            // Extracted into the cache, not into the records root: a bundle being previewed is not a record yet.
            val scratch = files.renderDirectory("preview-${System.currentTimeMillis()}")
            val read = withContext(Dispatchers.IO) { VynylBundle.read(staged, scratch) }
            when (read) {
                is VynylBundle.BundleResult.Read -> {
                    val record = read.contents.manifest.record
                    _state.value = _state.value.copy(
                        busy = false,
                        importPreview = ImportPreview(
                            source = staged,
                            scratch = scratch,
                            title = record.displayTitle,
                            dedication = record.dedicationLine,
                            durationLabel = record.durationLabel,
                            bytes = read.contents.bytes,
                            includesSource = read.contents.source != null,
                            contents = read.contents,
                        ),
                    )
                }

                is VynylBundle.BundleResult.Failed -> {
                    staged.delete()
                    com.vynylrecord.app.core.storage.StorageLayout.deleteTree(scratch)
                    _state.value = _state.value.copy(busy = false, message = read.message, messageIsError = true)
                }

                else -> Unit
            }
        }
    }

    /** Commits the previewed bundle. */
    fun confirmImport() {
        val preview = _state.value.importPreview ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, importPreview = null)
            val imported = withContext(Dispatchers.IO) {
                RecordImport.commit(graph.recordRepository, files, preview.contents)
            }
            preview.source.delete()
            com.vynylrecord.app.core.storage.StorageLayout.deleteTree(preview.scratch)
            _state.value = _state.value.copy(
                busy = false,
                message = if (imported != null) "\"${imported.displayTitle}\" imported" else "That bundle could not be imported.",
                messageIsError = imported == null,
            )
        }
    }

    fun cancelImport() {
        val preview = _state.value.importPreview
        preview?.source?.delete()
        preview?.scratch?.let { com.vynylrecord.app.core.storage.StorageLayout.deleteTree(it) }
        _state.value = _state.value.copy(importPreview = null)
    }

    fun notePlayed(id: String) {
        viewModelScope.launch { graph.recordRepository.markPlayed(id) }
    }

    fun dismissBusy() {
        _state.value = _state.value.copy(busy = false)
    }

    private companion object {
        const val TAG = "VynylLibraryViewModel"
    }
}
