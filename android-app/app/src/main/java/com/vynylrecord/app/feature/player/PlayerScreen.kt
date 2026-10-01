package com.vynylrecord.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vynylrecord.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vynylrecord.app.core.design.BrassDivider
import com.vynylrecord.app.core.design.BrassPanel
import com.vynylrecord.app.core.design.Haptics
import com.vynylrecord.app.core.design.SectionHeading
import com.vynylrecord.app.core.design.SettingsRow
import com.vynylrecord.app.core.design.VinylDiscPreview
import com.vynylrecord.app.core.design.VynylColors
import com.vynylrecord.app.core.design.VynylIcons
import com.vynylrecord.app.core.design.VynylPrimaryButton
import com.vynylrecord.app.core.design.VynylSecondaryButton
import com.vynylrecord.app.core.design.VynylTextButton
import com.vynylrecord.app.core.design.VynylType
import com.vynylrecord.app.core.graphics.DeckPose
import com.vynylrecord.app.core.graphics.DeckState
import com.vynylrecord.app.core.model.VinylStyleId

/**
 * The 3D player: one record, on one deck, with a needle that lands before the sound starts.
 *
 * The screen is three layers. Behind everything is the GL deck, on a surface the 3D renderer owns. Above it,
 * laid out by Compose, are the transport, the metadata and the camera controls. Between them sits a frame
 * loop that advances the animator with real elapsed time and hands the pose to the renderer — which is why
 * the record turns at the same speed on a 120 Hz and a 60 Hz screen, and why the audio starts on the frame
 * the stylus reaches the groove rather than when the button was pressed.
 *
 * If the device cannot give the app OpenGL ES 3.0 — or the driver refuses the program — the same screen is
 * drawn with the flat deck and everything still works. That case is handled by a state, not by an error
 * dialog.
 */
@Composable
fun PlayerScreen(
    recordId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: PlayerViewModel = viewModel(factory = playerViewModelFactory(recordId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var cameraTouching by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var showExportSheet by remember { mutableStateOf(false) }

    // The frame loop. The deck is advanced with the real time between frames, so the platter's inertia and
    // the arm's damping are the same on every device.
    var pose by remember { mutableStateOf(DeckPose()) }
    LaunchedEffect(state.ready, state.mode) {
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                val delta = if (previous == 0L) 0f else (now - previous) / 1_000_000_000f
                previous = now
                pose = viewModel.onFrame(delta, cameraTouching)
            }
        }
    }

    // The screen wakes the record up when it appears and sends the deck to sleep when it goes away; a record
    // that keeps playing behind another app would be a background service, and this is an in-app player.
    LaunchedEffect(Unit) {
        viewModel.onVisible()
    }

    Box(Modifier.fillMaxSize().background(VynylColors.Obsidian)) {
        when (state.mode) {
            PlayerViewModel.RenderMode.THREE_D -> TurntableSurface(
                pose = pose,
                state = state.playback.deckState,
                record = state.record,
                style = state.record?.styledId ?: VinylStyleId.DEFAULT,
                metadataRevision = viewModel.metadataRevision,
                renderProgress = state.record?.renderProgress ?: 0f,
                quality = state.settings.graphicsQuality,
                shadows = state.settings.shadows,
                showStatistics = state.settings.showFps,
                camera = viewModel.camera,
                onStatistics = { stats -> viewModel.onStatistics(stats.summary) },
                onUnavailable = viewModel::onRendererUnavailable,
                onCameraTouched = { touching -> cameraTouching = touching },
                modifier = Modifier.fillMaxSize(),
            )

            PlayerViewModel.RenderMode.STATIC -> StaticDeckFallback(
                record = state.record,
                isPlaying = state.playback.isPlaying,
                progress = state.playback.progress,
                message = state.fallbackMessage,
                reducedMotion = state.settings.reducedMotion,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                VynylTextButton(text = "Back", onClick = onBack)
                Spacer(Modifier.weight(1f))
                Text(state.cameraPreset, style = VynylType.monoSmall, color = VynylColors.Muted)
                Spacer(Modifier.width(12.dp))
                VynylTextButton(
                    text = stringResource(R.string.player_camera_next),
                    onClick = viewModel::nextCameraPreset,
                    color = VynylColors.Amber,
                )
            }

            Spacer(Modifier.height(240.dp))

            BrassPanel(modifier = Modifier.padding(horizontal = 16.dp)) {
                val record = state.record
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VinylDiscPreview(
                        style = record?.styledId ?: VinylStyleId.DEFAULT,
                        title = record?.title ?: "",
                        size = 56.dp,
                        spinning = state.playback.isPlaying && !state.settings.reducedMotion,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            record?.displayTitle ?: "No record",
                            style = VynylType.headline,
                            color = VynylColors.Cream,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            record?.signatureLine ?: "",
                            style = VynylType.caption,
                            color = VynylColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DeckStateBadge(state.playback.deckState)
                }

                Spacer(Modifier.height(16.dp))

                // The waveform is a scrubber: dragging it moves the needle and the audio together.
                val progress = scrubFraction ?: state.playback.progress
                com.vynylrecord.app.core.design.WaveformStrip(
                    peaks = record?.waveformPeaks ?: emptyList(),
                    height = 48.dp,
                    progress = progress,
                )
                Slider(
                    value = progress,
                    onValueChange = { value -> scrubFraction = value },
                    onValueChangeFinished = {
                        scrubFraction?.let { value -> viewModel.seekTo(value) }
                        scrubFraction = null
                    },
                    valueRange = 0f..1f,
                    enabled = state.playback.isPrepared,
                    colors = SliderDefaults.colors(
                        thumbColor = VynylColors.AmberBright,
                        activeTrackColor = VynylColors.Amber,
                        inactiveTrackColor = VynylColors.Panel,
                    ),
                )
                Row {
                    Text(state.playback.positionLabel, style = VynylType.mono, color = VynylColors.Amber)
                    Spacer(Modifier.weight(1f))
                    Text(state.playback.durationLabel, style = VynylType.mono, color = VynylColors.Muted)
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VynylSecondaryButton(
                        text = "Restart",
                        onClick = { viewModel.seekTo(0f) },
                        enabled = state.playback.isPrepared,
                    )
                    VynylPrimaryButton(
                        text = if (state.playback.isPlaying) "Pause" else "Play",
                        onClick = {
                            viewModel.togglePlay()
                            val moment = if (state.playback.isPlaying) Haptics.Moment.RECORD_PAUSE else Haptics.Moment.NEEDLE_DROP
                            Haptics.play(view, moment, state.settings.haptics)
                        },
                        icon = if (state.playback.isPlaying) VynylIcons.Pause else VynylIcons.Play,
                        enabled = state.playback.isPrepared,
                        modifier = Modifier.weight(1f),
                    )
                    VynylSecondaryButton(text = "Stop", onClick = viewModel::stop, enabled = state.playback.isPrepared)
                }

                state.playback.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = VynylType.caption, color = VynylColors.Error)
                }
            }

            Spacer(Modifier.height(16.dp))

            BrassPanel(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionHeading(
                    title = "The record",
                    subtitle = stringResource(R.string.player_needle_note) + " Everything on this label was " +
                        "pressed on this phone.",
                )
                Spacer(Modifier.height(8.dp))
                state.record?.let { record ->
                    MetadataRow("For", record.recipientName.ifBlank { "—" })
                    MetadataRow("From", record.senderName.ifBlank { "—" })
                    MetadataRow("Occasion", record.occasion.label)
                    MetadataRow("Date", record.occasionDate.ifBlank { "—" })
                    MetadataRow("Pressing", record.presetId.displayName)
                    MetadataRow("Finish", record.styledId.displayName)
                    MetadataRow("Length", record.durationLabel)
                    MetadataRow("Pressed", record.createdLabel)
                }
                Spacer(Modifier.height(12.dp))
                BrassDivider()
                Spacer(Modifier.height(12.dp))
                SettingsRow(
                    title = "Camera",
                    detail = "Drag to orbit, pinch to zoom, double tap for the next view",
                    trailing = {
                        VynylTextButton(
                        text = stringResource(R.string.player_camera_reset),
                        onClick = viewModel::resetCamera,
                        color = VynylColors.Amber,
                    )
                    },
                )
                SettingsRow(
                    title = "Orbit while idle",
                    detail = "Drifts slowly around the deck when nobody is touching it",
                    trailing = {
                        androidx.compose.material3.Switch(
                            checked = state.settings.autoOrbit,
                            onCheckedChange = viewModel::setAutoOrbit,
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = VynylColors.Obsidian,
                                checkedTrackColor = VynylColors.Amber,
                            ),
                        )
                    },
                )
                if (state.settings.showFps) {
                    Text(state.statistics.ifBlank { "measuring…" }, style = VynylType.monoSmall, color = VynylColors.Amber)
                }
            }

            Spacer(Modifier.height(16.dp))

            BrassPanel(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionHeading(title = "Take it with you", subtitle = "Everything is written on this phone first; the share sheet is the last step.")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VynylSecondaryButton(
                        text = stringResource(R.string.player_export),
                        onClick = { showExportSheet = true },
                        icon = VynylIcons.Export,
                    )
                    VynylSecondaryButton(
                        text = "Edit",
                        onClick = { state.record?.id?.let(onEdit) },
                        icon = VynylIcons.Edit,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Leaving the player puts the deck to sleep, which stops the audio and the animation together.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.onHidden() }
    }

    if (showExportSheet) {
        ExportSheet(
            state = state,
            shareIntent = viewModel.shareIntent(),
            onDismiss = {
                showExportSheet = false
                viewModel.closeExportSheet()
            },
            onExport = { kind -> viewModel.export(kind) },
            onShare = { intent -> runCatching { context.startActivity(intent) } },
        )
    }

    // The settings say whether haptics are on; the needle's arrival is the one moment the player itself
    // cannot feel, because it happens inside the render loop.
    LaunchedEffect(state.playback.deckState) {
        if (state.playback.deckState == DeckState.PLAYING && state.settings.haptics) {
            Haptics.play(context, Haptics.Moment.NEEDLE_DROP, true)
        }
    }
    LaunchedEffect(state.playback.deckState) {
        if (state.playback.deckState == DeckState.ENDED) {
            Haptics.play(context, Haptics.Moment.COMPLETE, state.settings.haptics)
            viewModel.markPlayed()
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label.uppercase(), style = VynylType.monoSmall, color = VynylColors.Muted, modifier = Modifier.width(96.dp))
        Text(value, style = VynylType.body, color = VynylColors.Cream)
    }
}

/** The deck's current state, as a badge that changes with it. */
@Composable
private fun DeckStateBadge(state: DeckState) {
    val (color, label) = when (state) {
        DeckState.PLAYING -> VynylColors.Success to state.label
        DeckState.CUEING, DeckState.LOADING, DeckState.SEEKING -> VynylColors.Amber to state.label
        DeckState.PAUSED -> VynylColors.Muted to state.label
        DeckState.ENDED -> VynylColors.Brass to state.label
        DeckState.ERROR -> VynylColors.Error to state.label
        DeckState.RENDERING -> VynylColors.AmberBright to state.label
        else -> VynylColors.Muted to state.label
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = VynylType.monoSmall, color = color)
    }
}

/**
 * The export sheet: the four things a record can leave as.
 *
 * It is a sheet rather than four menu entries because the choice is the whole point — an M4A for sharing, a
 * WAV for keeping, the artwork for printing, and a bundle for handing the entire record to someone else.
 */
@Composable
private fun ExportSheet(
    state: PlayerViewModel.UiState,
    onDismiss: () -> Unit,
    onExport: (PlayerViewModel.ExportKind) -> Unit,
    onShare: (android.content.Intent) -> Unit,
    shareIntent: android.content.Intent? = null,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VynylColors.DeepStone,
        title = {
            Text(
                stringResource(R.string.export_title) + "  ${state.record?.displayTitle ?: "record"}",
                style = VynylType.headline,
                color = VynylColors.Cream,
            )
        },
        text = {
            Column {
                PlayerViewModel.ExportKind.entries.forEach { kind ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onExport(kind) }
                            .padding(vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(kind.labelRes), style = VynylType.label, color = VynylColors.Cream)
                            Text(stringResource(kind.detailRes), style = VynylType.monoSmall, color = VynylColors.Muted)
                        }
                    }
                }
                state.exportMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = VynylType.caption, color = VynylColors.Amber)
                }
            }
        },
        confirmButton = {
            if (state.exported != null) {
                androidx.compose.material3.TextButton(onClick = { shareIntent?.let(onShare) }) {
                    Text(stringResource(R.string.export_share), style = VynylType.label, color = VynylColors.Amber)
                }
            } else {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Close", style = VynylType.label, color = VynylColors.Muted)
                }
            }
        },
        dismissButton = {
            if (state.exported != null) {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Done", style = VynylType.label, color = VynylColors.Muted)
                }
            }
        },
    )
}

/** The player's view model factory, which is the only place the record's id enters the screen. */
internal fun playerViewModelFactory(recordId: String): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(
            modelClass: Class<T>,
            extras: androidx.lifecycle.viewmodel.CreationExtras,
        ): T {
            // The application is the one thing the framework injects; everything else this view model
            // needs comes from the graph, which is why the factory is six lines rather than a framework.
            val application = extras[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
            @Suppress("UNCHECKED_CAST")
            return PlayerViewModel(application, recordId) as T
        }
    }
