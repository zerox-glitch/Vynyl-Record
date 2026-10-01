package com.vynylrecord.app.feature.soundlab

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vynylrecord.app.R
import com.vynylrecord.app.VynylGraph
import com.vynylrecord.app.core.design.BrassDivider
import com.vynylrecord.app.core.design.BrassPanel
import com.vynylrecord.app.core.design.EmptyState
import com.vynylrecord.app.core.design.SectionHeading
import com.vynylrecord.app.core.design.VynylChip
import com.vynylrecord.app.core.design.VynylColors
import com.vynylrecord.app.core.design.VynylIcons
import com.vynylrecord.app.core.design.VynylPrimaryButton
import com.vynylrecord.app.core.design.VynylSecondaryButton
import com.vynylrecord.app.core.design.VynylTextButton
import com.vynylrecord.app.core.design.VynylType
import com.vynylrecord.app.core.model.AssetCategory
import com.vynylrecord.app.core.model.AudioAsset
import com.vynylrecord.app.core.storage.formatBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Sound Lab: what can play underneath a voice.
 *
 * Three categories and two kinds of asset, and the difference between them is shown rather than explained:
 * a bundled bed has a small "in the app" mark and no delete action, because it is part of the app; an
 * imported bed has a bin, because it is the user's own file. Both can be switched off, trimmed and given a
 * level.
 *
 * Importing decodes the file immediately — see [com.vynylrecord.app.core.data.repository.AssetRepository] —
 * so a bed that cannot be decoded is refused here, while the user is looking at the screen, rather than at
 * press time when they have already recorded a memory.
 */
class SoundLabViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = VynylGraph.of(application)

    data class UiState(
        val assets: List<AudioAsset> = emptyList(),
        val category: AssetCategory = AssetCategory.BACKGROUND_MUSIC,
        val importing: Boolean = false,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val editing: AudioAsset? = null,
        val pendingDelete: AudioAsset? = null,
        val totalBytes: Long = 0L,
    ) {
        val visible: List<AudioAsset> get() = assets.filter { it.category == category }
        val bundled: List<AudioAsset> get() = visible.filter { it.isBundled }
        val imported: List<AudioAsset> get() = visible.filter { !it.isBundled }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.assetRepository.assets.collect { assets ->
                _state.value = _state.value.copy(
                    assets = assets,
                    totalBytes = assets.sumOf { it.sizeBytes },
                    editing = _state.value.editing?.let { current -> assets.firstOrNull { it.id == current.id } ?: current },
                )
            }
        }
        viewModelScope.launch { graph.assetRepository.syncBundled() }
    }

    fun setCategory(category: AssetCategory) {
        _state.value = _state.value.copy(category = category)
    }

    fun setEnabled(asset: AudioAsset, enabled: Boolean) {
        viewModelScope.launch {
            graph.assetRepository.setEnabled(asset.id, enabled)
            _state.value = _state.value.copy(
                message = if (enabled) "\"${asset.displayTitle}\" is available again" else "\"${asset.displayTitle}\" is switched off",
            )
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, message = null)
            when (val result = graph.assetRepository.import(uri, "Imported music")) {
                is com.vynylrecord.app.core.data.repository.AssetRepository.ImportResult.Imported -> {
                    _state.value = _state.value.copy(
                        importing = false,
                        message = "\"${result.asset.displayTitle}\" added · ${result.asset.durationLabel}",
                        category = result.asset.category,
                    )
                }

                is com.vynylrecord.app.core.data.repository.AssetRepository.ImportResult.Failed -> {
                    _state.value = _state.value.copy(importing = false, message = result.message, messageIsError = true)
                }
            }
        }
    }

    fun openEditor(asset: AudioAsset) {
        _state.value = _state.value.copy(editing = asset)
    }

    fun closeEditor() {
        _state.value = _state.value.copy(editing = null)
    }

    fun saveTrim(asset: AudioAsset, startMs: Long, endMs: Long, volume: Float) {
        viewModelScope.launch {
            graph.assetRepository.setTrim(asset.id, startMs, endMs, volume)
            _state.value = _state.value.copy(
                message = "Trim saved for \"${asset.displayTitle}\"",
                editing = null,
            )
        }
    }

    fun requestDelete(asset: AudioAsset) {
        if (asset.isBundled) {
            // Bundled assets are part of the app; the honest answer is to explain rather than to remove it.
            _state.value = _state.value.copy(
                message = "\"${asset.displayTitle}\": " + getApplication<Application>()
                    .getString(R.string.sound_lab_bundled_note),
            )
            return
        }
        _state.value = _state.value.copy(pendingDelete = asset)
    }

    fun cancelDelete() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    fun confirmDelete() {
        val asset = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            val removed = graph.assetRepository.delete(asset.id)
            _state.value = _state.value.copy(
                pendingDelete = null,
                message = if (removed) "\"${asset.displayTitle}\" deleted" else "That asset could not be deleted.",
                messageIsError = !removed,
            )
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, messageIsError = false)
    }
}

@Composable
fun SoundLabScreen(viewModel: SoundLabViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(it) }
    }

    Column(Modifier.fillMaxSize().background(VynylColors.Obsidian)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.tab_sound_lab), style = VynylType.title, color = VynylColors.Cream)
            Text(
                "${state.assets.size} assets · ${formatBytes(state.totalBytes)} on this phone",
                style = VynylType.caption,
                color = VynylColors.Muted,
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AssetCategory.entries.toList()) { category ->
                    VynylChip(
                        text = category.label,
                        selected = category == state.category,
                        onClick = { viewModel.setCategory(category) },
                    )
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BrassPanel {
                    SectionHeading(
                        title = stringResource(R.string.sound_lab_add),
                        subtitle = "A song, a recording, anything this phone can play. It is decoded once and kept " +
                            "in the app so a press never depends on the original file.",
                    )
                    Spacer(Modifier.height(12.dp))
                    VynylPrimaryButton(
                        text = if (state.importing) "Importing…" else stringResource(R.string.studio_import),
                        onClick = { picker.launch(arrayOf("audio/*")) },
                        enabled = !state.importing,
                        icon = VynylIcons.Import,
                    )
                }
            }

            if (state.bundled.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sound_lab_bundled).uppercase(),
                        style = VynylType.monoSmall,
                        color = VynylColors.Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(state.bundled, key = { it.id }) { asset ->
                    AssetCard(
                        asset = asset,
                        onToggle = { enabled -> viewModel.setEnabled(asset, enabled) },
                        onEdit = { viewModel.openEditor(asset) },
                        onDelete = null,
                    )
                }
            }

            if (state.imported.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sound_lab_yours).uppercase(),
                        style = VynylType.monoSmall,
                        color = VynylColors.Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(state.imported, key = { it.id }) { asset ->
                    AssetCard(
                        asset = asset,
                        onToggle = { enabled -> viewModel.setEnabled(asset, enabled) },
                        onEdit = { viewModel.openEditor(asset) },
                        onDelete = { viewModel.requestDelete(asset) },
                    )
                }
            }

            if (state.visible.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing in this category yet",
                        body = "The app ships with five beds and a needle drop. Anything else you add lands here.",
                    )
                }
            }
        }

        state.message?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(VynylColors.Panel)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    style = VynylType.caption,
                    color = if (state.messageIsError) VynylColors.Error else VynylColors.Cream,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::dismissMessage) {
                    Text("Dismiss", style = VynylType.label, color = VynylColors.Muted)
                }
            }
        }
    }

    state.editing?.let { asset ->
        TrimDialog(
            asset = asset,
            onDismiss = viewModel::closeEditor,
            onSave = { start, end, volume -> viewModel.saveTrim(asset, start, end, volume) },
        )
    }

    state.pendingDelete?.let { asset ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            containerColor = VynylColors.DeepStone,
            title = { Text("Delete \"${asset.displayTitle}\"?", style = VynylType.headline, color = VynylColors.Cream) },
            text = {
                Text(
                    "Records that already use it keep their pressed audio; a record pressed afterwards simply has " +
                        "no background layer.",
                    style = VynylType.body,
                    color = VynylColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("Delete", style = VynylType.label, color = VynylColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text("Keep it", style = VynylType.label, color = VynylColors.Cream)
                }
            },
        )
    }
}

/** One asset, with its switch, its length and its actions. */
@Composable
private fun AssetCard(
    asset: AudioAsset,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    BrassPanel(contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    asset.displayTitle,
                    style = VynylType.headline,
                    color = if (asset.enabled) VynylColors.Cream else VynylColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(asset.durationLabel)
                        if (asset.sizeBytes > 0L) append(" · ").append(asset.sizeLabel)
                        if (asset.trimStartMilliseconds > 0L || asset.trimEndMilliseconds > 0L) append(" · trimmed")
                        if (asset.isBundled) append(" · in the app")
                    },
                    style = VynylType.monoSmall,
                    color = VynylColors.Muted,
                )
            }
            Switch(
                checked = asset.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VynylColors.Obsidian,
                    checkedTrackColor = VynylColors.Amber,
                    uncheckedThumbColor = VynylColors.Muted,
                    uncheckedTrackColor = VynylColors.Panel,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        BrassDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            VynylSecondaryButton(text = stringResource(R.string.sound_lab_trim), onClick = onEdit)
            Spacer(Modifier.weight(1f))
            if (onDelete != null) {
                VynylTextButton(text = "Delete", onClick = onDelete, color = VynylColors.Error)
            } else {
                Text(stringResource(R.string.sound_lab_bundled), style = VynylType.monoSmall, color = VynylColors.Muted)
            }
        }
    }
}

/**
 * The trim editor.
 *
 * Two sliders and a level. The trim is what decides how much of a three-minute song plays under a
 * forty-second memory, so it is worth a dialog of its own rather than a hidden default.
 */
@Composable
private fun TrimDialog(
    asset: AudioAsset,
    onDismiss: () -> Unit,
    onSave: (Long, Long, Float) -> Unit,
) {
    var start by remember(asset.id) { mutableStateOf(asset.trimStartMilliseconds.toFloat()) }
    var end by remember(asset.id) {
        mutableStateOf((if (asset.trimEndMilliseconds > 0L) asset.trimEndMilliseconds else asset.durationMilliseconds).toFloat())
    }
    var volume by remember(asset.id) { mutableStateOf(asset.defaultVolume) }
    val duration = asset.durationMilliseconds.coerceAtLeast(1L)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VynylColors.DeepStone,
        title = { Text(asset.displayTitle, style = VynylType.headline, color = VynylColors.Cream) },
        text = {
            Column {
                Text(
                    "%s of %s selected".format(secondsLabel((end - start).toLong()), secondsLabel(duration)),
                    style = VynylType.mono,
                    color = VynylColors.Amber,
                )
                Spacer(Modifier.height(12.dp))
                Text("Starts at", style = VynylType.label, color = VynylColors.Cream)
                Slider(
                    value = start.coerceIn(0f, duration.toFloat()),
                    onValueChange = { value ->
                        start = value.coerceAtMost(end - 500f)
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = sliderColors(),
                )
                Text(secondsLabel(start.toLong()), style = VynylType.monoSmall, color = VynylColors.Muted)
                Spacer(Modifier.height(12.dp))
                Text("Ends at", style = VynylType.label, color = VynylColors.Cream)
                Slider(
                    value = end.coerceIn(0f, duration.toFloat()),
                    onValueChange = { value -> end = value.coerceAtLeast(start + 500f) },
                    valueRange = 0f..duration.toFloat(),
                    colors = sliderColors(),
                )
                Text(secondsLabel(end.toLong()), style = VynylType.monoSmall, color = VynylColors.Muted)
                Spacer(Modifier.height(12.dp))
                Text("Level under the voice", style = VynylType.label, color = VynylColors.Cream)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    valueRange = 0f..0.8f,
                    colors = sliderColors(),
                )
                Text("${(volume / 0.8f * 100).toInt()}%", style = VynylType.monoSmall, color = VynylColors.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(start.toLong(), end.toLong(), volume) }) {
                Text("Save", style = VynylType.label, color = VynylColors.Amber)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = VynylType.label, color = VynylColors.Muted) }
        },
    )
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = VynylColors.AmberBright,
    activeTrackColor = VynylColors.Amber,
    inactiveTrackColor = VynylColors.Panel,
)

private fun secondsLabel(milliseconds: Long): String {
    val total = (milliseconds / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}
