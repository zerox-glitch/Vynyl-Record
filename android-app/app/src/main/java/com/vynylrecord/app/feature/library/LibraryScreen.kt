package com.vynylrecord.app.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vynylrecord.app.R
import com.vynylrecord.app.core.design.BrassDivider
import com.vynylrecord.app.core.design.BrassPanel
import com.vynylrecord.app.core.design.EmptyState
import com.vynylrecord.app.core.design.SectionHeading
import com.vynylrecord.app.core.design.StateBadge
import com.vynylrecord.app.core.design.VinylDiscPreview
import com.vynylrecord.app.core.design.VynylChip
import com.vynylrecord.app.core.design.VynylColors
import com.vynylrecord.app.core.design.VynylIcons
import com.vynylrecord.app.core.design.VynylPrimaryButton
import com.vynylrecord.app.core.design.VynylSecondaryButton
import com.vynylrecord.app.core.design.VynylTextButton
import com.vynylrecord.app.core.design.VynylType
import com.vynylrecord.app.core.design.WaveformStrip
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.storage.formatBytes

/**
 * The Master Vault: the shelf.
 *
 * Everything a user has pressed lives here, in the order they last touched it. The screen has four jobs and
 * does all of them without a separate screen for each: it *browses* (grid or list), it *finds* (search and
 * filters), it *acts* (play, edit, re-press, duplicate, export, share, delete) and it *imports*.
 *
 * The empty state is the app's own sentence — "Your shelf is waiting for its first voice." — rather than a
 * generic shrug, because an empty library in an app about keeping voices is a specific feeling and the copy
 * should meet it.
 */
@Composable
fun LibraryScreen(
    pendingImportPath: String?,
    onImportConsumed: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onEditRecord: (String) -> Unit,
    onNewRecord: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var actionTarget by remember { mutableStateOf<Record?>(null) }
    var showFilters by remember { mutableStateOf(false) }

    val bundlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.previewImport(it) }
    }

    // A `.vynyl` file the app was opened with: previewed rather than imported, so the user sees what it is.
    LaunchedEffect(pendingImportPath, state.settings.onboardingComplete) {
        if (!pendingImportPath.isNullOrBlank()) {
            viewModel.previewImport(android.net.Uri.parse(pendingImportPath))
            onImportConsumed()
        }
    }

    Column(Modifier.fillMaxSize().background(VynylColors.Obsidian)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(R.string.vault_title), style = VynylType.title, color = VynylColors.Cream)
                    Text(stringResource(R.string.vault_subtitle), style = VynylType.caption, color = VynylColors.Muted)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${state.records.size} of ${state.all.size}",
                    style = VynylType.mono,
                    color = VynylColors.Muted,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.filters.query,
                onValueChange = viewModel::setQuery,
                placeholder = {
                    Text(
                        stringResource(R.string.vault_search_hint),
                        style = VynylType.body,
                        color = VynylColors.Muted.copy(alpha = 0.6f),
                    )
                },
                leadingIcon = { Icon(VynylIcons.Search, contentDescription = null, tint = VynylColors.Muted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = VynylType.body,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VynylColors.Amber,
                    unfocusedBorderColor = VynylColors.Border,
                    focusedTextColor = VynylColors.Cream,
                    unfocusedTextColor = VynylColors.Cream,
                    cursorColor = VynylColors.Amber,
                    focusedContainerColor = VynylColors.Panel,
                    unfocusedContainerColor = VynylColors.Panel,
                ),
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    VynylChip(
                        text = if (state.filters.isActive) "Filters · ${state.filters.activeCount}" else "Filters",
                        selected = state.filters.isActive,
                        onClick = { showFilters = !showFilters },
                    )
                }
                item {
                    VynylChip(
                        text = state.sort.label,
                        selected = false,
                        onClick = {
                            val options = LibraryViewModel.SortOption.entries
                            viewModel.setSort(options[(options.indexOf(state.sort) + 1) % options.size])
                        },
                    )
                }
                item {
                    VynylChip(
                        text = if (state.grid) "Grid" else "List",
                        selected = false,
                        onClick = { viewModel.setGrid(!state.grid) },
                    )
                }
                item {
                    VynylChip(
                        text = "Favourites",
                        selected = state.filters.favouriteOnly,
                        onClick = { viewModel.toggleFavouriteFilter() },
                    )
                }
                item {
                    VynylChip(text = "Import", selected = false, onClick = { bundlePicker.launch(arrayOf("*/*")) })
                }
            }
            if (showFilters) {
                Spacer(Modifier.height(12.dp))
                FilterPanel(
                    filters = state.filters,
                    onOccasion = viewModel::setOccasion,
                    onPreset = viewModel::setPreset,
                    onStyle = viewModel::setStyle,
                    onState = viewModel::setStateFilter,
                    onClear = viewModel::clearFilters,
                )
            }
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Opening the shelf…", style = VynylType.body, color = VynylColors.Muted)
            }

            state.empty -> EmptyState(
                title = stringResource(R.string.vault_empty_title),
                body = stringResource(R.string.vault_empty_body),
                modifier = Modifier.fillMaxSize(),
                action = { VynylPrimaryButton(text = "Press your first record", onClick = onNewRecord) },
            )

            state.nothingMatches -> EmptyState(
                title = "Nothing matches",
                body = "No record on the shelf fits that search or those filters.",
                modifier = Modifier.fillMaxSize(),
                action = { VynylSecondaryButton(text = "Clear the filters", onClick = viewModel::clearFilters) },
            )

            state.grid -> GridOfRecords(
                records = state.records,
                onOpen = onOpenRecord,
                onLongPress = { actionTarget = it },
                onFavourite = viewModel::toggleFavourite,
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.records, key = { it.id }) { record ->
                    RecordRow(
                        record = record,
                        onOpen = onOpenRecord,
                        onLongPress = { actionTarget = it },
                        onFavourite = viewModel::toggleFavourite,
                    )
                }
            }
        }

        // The message bar doubles as the undo bar: one place where the app says what just happened.
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
                if (state.undoable != null) {
                    TextButton(onClick = { viewModel.undoDelete() }) {
                        Text(stringResource(R.string.vault_undo), style = VynylType.label, color = VynylColors.Amber)
                    }
                }
                if (state.exported != null) {
                    TextButton(
                        onClick = { viewModel.shareIntent()?.let { intent -> context.startActivity(intent) } },
                    ) {
                        Text("Share", style = VynylType.label, color = VynylColors.Amber)
                    }
                }
                TextButton(onClick = { viewModel.dismissMessage(); viewModel.dismissExport() }) {
                    Text("Dismiss", style = VynylType.label, color = VynylColors.Muted)
                }
            }
        }
    }

    // ---- the action sheet for one record
    actionTarget?.let { record ->
        RecordActionsSheet(
            record = record,
            onDismiss = { actionTarget = null },
            onPlay = { actionTarget = null; onOpenRecord(record.id) },
            onEdit = { actionTarget = null; onEditRecord(record.id) },
            onReRender = { actionTarget = null; viewModel.reRender(record.id) },
            onDuplicate = { actionTarget = null; viewModel.duplicate(record.id) },
            onFavourite = { actionTarget = null; viewModel.toggleFavourite(record.id) },
            onExport = { actionTarget = null; viewModel.export(record) },
            onShare = {
                actionTarget = null
                viewModel.export(record)
            },
            onDelete = { actionTarget = null; viewModel.requestDelete(record) },
        )
    }

    // ---- the delete confirmation, which says what will happen rather than asking "are you sure?"
    state.pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            containerColor = VynylColors.DeepStone,
            title = {
                Text(
                    stringResource(R.string.vault_delete_title) + "  ${record.displayTitle}",
                    style = VynylType.headline,
                    color = VynylColors.Cream,
                )
            },
            text = {
                Text(stringResource(R.string.vault_delete_body), style = VynylType.body, color = VynylColors.Muted)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
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

    // ---- the import preview: what the bundle holds, before anything is written
    state.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            containerColor = VynylColors.DeepStone,
            title = {
                Text(
                    stringResource(R.string.vault_import_title) + "  ${preview.title}",
                    style = VynylType.headline,
                    color = VynylColors.Cream,
                )
            },
            text = {
                Column {
                    Text(preview.dedication, style = VynylType.body, color = VynylColors.Cream)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${preview.durationLabel} · ${formatBytes(preview.bytes)}" +
                            if (preview.includesSource) " · includes the original recording" else "",
                        style = VynylType.monoSmall,
                        color = VynylColors.Muted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) {
                    Text("Import", style = VynylType.label, color = VynylColors.Amber)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) {
                    Text("Not now", style = VynylType.label, color = VynylColors.Muted)
                }
            },
        )
    }
}

/** The grid: sleeves on a shelf. */
@Composable
private fun GridOfRecords(
    records: List<Record>,
    onOpen: (String) -> Unit,
    onLongPress: (Record) -> Unit,
    onFavourite: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(records, key = { it.id }) { record ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(VynylColors.DeepStone)
                    .border(1.dp, VynylColors.Border, RoundedCornerShape(14.dp))
                    .combinedClickable(onClick = { onOpen(record.id) }, onLongClick = { onLongPress(record) })
                    .padding(12.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    VinylDiscPreview(style = record.styledId, title = record.title, size = 132.dp)
                    if (record.favourite) {
                        Box(Modifier.align(Alignment.TopEnd).clickable { onFavourite(record.id) }) {
                            Icon(
                                VynylIcons.Favourite,
                                contentDescription = stringResource(R.string.cd_favourite),
                                tint = VynylColors.AmberBright,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    record.displayTitle,
                    style = VynylType.headline,
                    color = VynylColors.Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${record.occasion.label} · ${record.durationLabel}",
                    style = VynylType.caption,
                    color = VynylColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                if (record.isReady && record.waveformPeaks.isNotEmpty()) {
                    WaveformStrip(record.waveformPeaks, height = 24.dp)
                } else {
                    StateBadge(record.renderState, record.renderProgress, record.renderStageLabel)
                }
            }
        }
    }
}

/** The list: the same records, with more of their metadata visible. */
@Composable
private fun RecordRow(
    record: Record,
    onOpen: (String) -> Unit,
    onLongPress: (Record) -> Unit,
    onFavourite: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(VynylColors.DeepStone)
            .border(1.dp, VynylColors.Border, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = { onOpen(record.id) }, onLongClick = { onLongPress(record) })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VinylDiscPreview(style = record.styledId, title = record.title, size = 62.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(record.displayTitle, style = VynylType.headline, color = VynylColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${record.occasion.label} · ${record.presetId.displayName}" +
                    if (record.recipientName.isNotBlank()) " · for ${record.recipientName}" else "",
                style = VynylType.caption,
                color = VynylColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (record.isReady && record.waveformPeaks.isNotEmpty()) {
                WaveformStrip(record.waveformPeaks, height = 26.dp)
            } else {
                StateBadge(record.renderState, record.renderProgress, record.renderStageLabel)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(record.durationLabel, style = VynylType.mono, color = VynylColors.Amber)
            if (record.sizeBytes > 0L) Text(record.sizeLabel, style = VynylType.monoSmall, color = VynylColors.Muted)
            Box(Modifier.padding(top = 4.dp).clickable { onFavourite(record.id) }) {
                Icon(
                    VynylIcons.Favourite,
                    contentDescription = if (record.favourite) "Remove from favourites" else "Add to favourites",
                    tint = if (record.favourite) VynylColors.AmberBright else VynylColors.Muted.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The filter panel: occasions, presets, finishes and states, each as a row of chips. */
@Composable
private fun FilterPanel(
    filters: LibraryViewModel.Filters,
    onOccasion: (Occasion?) -> Unit,
    onPreset: (VinylPresetId?) -> Unit,
    onStyle: (VinylStyleId?) -> Unit,
    onState: (RenderState?) -> Unit,
    onClear: () -> Unit,
) {
    BrassPanel(contentPadding = PaddingValues(12.dp)) {
        SectionHeading(title = "Filters", subtitle = "Narrow the shelf down to what you are looking for.")
        Spacer(Modifier.height(8.dp))
        FilterRow(
            title = "Occasion",
            options = Occasion.all.map { it.id to it.label },
            selected = filters.occasion?.id,
            onSelect = { id -> onOccasion(id?.let { value -> Occasion.fromId(value) }) },
        )
        FilterRow(
            title = "Preset",
            options = VinylPresetId.all.map { it.id to it.displayName },
            selected = filters.preset?.id,
            onSelect = { id -> onPreset(id?.let { value -> VinylPresetId.fromId(value) }) },
        )
        FilterRow(
            title = "Finish",
            options = VinylStyleId.all.map { it.id to it.displayName },
            selected = filters.style?.id,
            onSelect = { id -> onStyle(id?.let { value -> VinylStyleId.fromId(value) }) },
        )
        FilterRow(
            title = "State",
            options = RenderState.entries.map { it.id to it.label },
            selected = filters.state?.id,
            onSelect = { id -> onState(id?.let { value -> RenderState.fromId(value) }) },
        )
        Spacer(Modifier.height(8.dp))
        BrassDivider()
        Spacer(Modifier.height(8.dp))
        Row {
            VynylTextButton(text = "Clear all", onClick = onClear, color = VynylColors.Amber)
        }
    }
}

@Composable
private fun FilterRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title.uppercase(), style = VynylType.monoSmall, color = VynylColors.Muted)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                VynylChip(
                    text = stringResource(R.string.vault_filter_any),
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
            }
            items(options, key = { it.first }) { (id, label) ->
                VynylChip(text = label, selected = id == selected, onClick = { onSelect(id) })
            }
        }
    }
}

/** The action sheet: every verb that applies to a record, with its consequence spelled out. */
@Composable
private fun RecordActionsSheet(
    record: Record,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onReRender: () -> Unit,
    onDuplicate: () -> Unit,
    onFavourite: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VynylColors.DeepStone,
        title = { Text(record.displayTitle, style = VynylType.headline, color = VynylColors.Cream) },
        text = {
            Column {
                StateBadge(record.renderState, record.renderProgress, record.renderStageLabel)
                Spacer(Modifier.height(12.dp))
                ActionRow(
                    stringResource(R.string.vault_action_play),
                    if (record.isReady) null else "Press it first",
                    onPlay,
                    record.isReady,
                )
                ActionRow(stringResource(R.string.vault_action_edit), "Dedication, preset, finish", onEdit, true)
                ActionRow(
                    stringResource(R.string.vault_action_render),
                    "Same voice, same settings, a fresh master",
                    onReRender,
                    record.sourcePath != null,
                )
                ActionRow(
                    stringResource(R.string.vault_action_duplicate),
                    "A copy you can change without touching this one",
                    onDuplicate,
                    true,
                )
                ActionRow(
                    if (record.favourite) "Remove from favourites" else "Add to favourites",
                    null,
                    onFavourite,
                    true,
                )
                ActionRow(stringResource(R.string.vault_action_export), "M4A, WAV, or a .vynyl bundle", onExport, true)
                ActionRow(stringResource(R.string.vault_action_share), "Through any app on this phone", onShare, true)
                Spacer(Modifier.height(4.dp))
                BrassDivider()
                Spacer(Modifier.height(4.dp))
                ActionRow(
                    stringResource(R.string.vault_action_delete),
                    "You can undo this straight afterwards",
                    onDelete,
                    true,
                    destructive = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", style = VynylType.label, color = VynylColors.Muted) }
        },
    )
}

@Composable
private fun ActionRow(
    title: String,
    detail: String?,
    onClick: () -> Unit,
    enabled: Boolean,
    destructive: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = VynylType.label,
                color = when {
                    !enabled -> VynylColors.Muted.copy(alpha = 0.5f)
                    destructive -> VynylColors.Error
                    else -> VynylColors.Cream
                },
            )
            if (detail != null) {
                Text(detail, style = VynylType.monoSmall, color = VynylColors.Muted)
            }
        }
    }
}
