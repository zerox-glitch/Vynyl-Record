package com.vynylrecord.app.feature.studio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vynylrecord.app.R
import com.vynylrecord.app.core.design.BrassDivider
import com.vynylrecord.app.core.design.BrassPanel
import com.vynylrecord.app.core.design.Haptics
import com.vynylrecord.app.core.design.SectionHeading
import com.vynylrecord.app.core.design.SettingsRow
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
import com.vynylrecord.app.core.model.VinylControl
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId

/**
 * The Studio: five steps from a spoken memory to a pressed record.
 *
 * The steps are not tabs; they are a sequence with a back button and one amber action, because making a
 * record is a sequence. Somebody who has just recorded their grandmother should not have to work out which
 * of five screens to visit next — the screen should already be asking the next question.
 *
 * Everything here is real. The recorder captures through `AudioRecord`, the press is the same render the
 * WorkManager job runs, the presets are the five recipes the DSP chain implements, and the advanced controls
 * write the values the chain reads. There is no simulated progress: the bar moves because the render moved.
 */
@Composable
fun StudioScreen(
    recordId: String?,
    onOpenPlayer: (String) -> Unit,
    onOpenVault: () -> Unit,
    viewModel: StudioViewModel = viewModel(factory = studioViewModelFactory(recordId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importAudio(it) }
    }
    // The microphone permission is requested when the record button is pressed, never at launch: an app that
    // asks for the microphone before it has been asked to record anything deserves to be refused.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording()
    }

    Column(Modifier.fillMaxSize().background(VynylColors.Obsidian)) {
        StudioHeader(state = state, onStep = viewModel::goTo)
        Box(Modifier.weight(1f)) {
            when (state.step) {
                StudioViewModel.Step.CAPTURE -> CaptureStep(
                    state = state,
                    onRecord = {
                        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) viewModel.startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)
                        Haptics.play(view, Haptics.Moment.RECORD_START, state.settings.haptics)
                    },
                    onPause = {
                        viewModel.pauseRecording()
                        Haptics.play(view, Haptics.Moment.RECORD_PAUSE, state.settings.haptics)
                    },
                    onResume = { viewModel.resumeRecording() },
                    onStop = { viewModel.stopRecording() },
                    onDiscard = viewModel::discardRecording,
                    onImport = { picker.launch(arrayOf("audio/*")) },
                )

                StudioViewModel.Step.DETAILS -> DetailsStep(state = state, viewModel = viewModel)
                StudioViewModel.Step.RECIPE -> RecipeStep(state = state, viewModel = viewModel)
                StudioViewModel.Step.APPEARANCE -> AppearanceStep(state = state, viewModel = viewModel)
                StudioViewModel.Step.PRESS -> PressStep(
                    state = state,
                    onPress = viewModel::press,
                    onCancel = viewModel::cancelPress,
                    onOpenPlayer = { state.record?.id?.let(onOpenPlayer) },
                    onOpenVault = onOpenVault,
                )
            }
        }
        StudioFooter(state = state, onBack = viewModel::back, onNext = viewModel::next)
    }
}

/** The five steps across the top, with the current one amber. */
@Composable
private fun StudioHeader(state: StudioViewModel.UiState, onStep: (StudioViewModel.Step) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.studio_title), style = VynylType.title, color = VynylColors.Cream)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(state.sourceDurationMs), style = VynylType.mono, color = VynylColors.Amber)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(StudioViewModel.Step.ordered) { step ->
                // A step beyond the capture is only reachable once there is something to press.
                val reachable = step.index == 0 || state.hasSource || state.isRendered
                VynylChip(
                    text = stringResource(step.labelRes),
                    selected = step == state.step,
                    onClick = { if (reachable) onStep(step) },
                )
            }
        }
    }
}

/** Back and Continue, with the amber action only when there is one. */
@Composable
private fun StudioFooter(state: StudioViewModel.UiState, onBack: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.step.index > 0) VynylTextButton(text = "Back", onClick = onBack)
        Spacer(Modifier.weight(1f))
        if (state.step != StudioViewModel.Step.PRESS) {
            VynylPrimaryButton(
                text = if (state.step == StudioViewModel.Step.APPEARANCE) "Review the record" else "Continue",
                onClick = onNext,
                enabled = state.canContinue && !state.busy,
            )
        }
    }
}

/**
 * Step one: capture.
 *
 * One button with three states — record, pause, stop — because that is what a person does with a memory:
 * they start, they think, they carry on, they finish. The level meter is the live one from the capture
 * loop, so it moves with the voice rather than with a timer.
 */
@Composable
private fun CaptureStep(
    state: StudioViewModel.UiState,
    onRecord: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        BrassPanel {
            SectionHeading(
                title = if (state.hasSource) "Your recording" else "Speak the memory",
                subtitle = if (state.hasSource) {
                    "Record it again, or carry on to the details."
                } else {
                    "Press record, then speak. Pause and carry on as many times as you like — it is one recording."
                },
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (state.isRecording) VynylColors.Ruby else VynylColors.Muted),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when {
                        state.isRecording -> "Recording"
                        state.isPaused -> "Paused"
                        state.hasSource -> "Captured"
                        else -> "Ready"
                    },
                    style = VynylType.label,
                    color = VynylColors.Cream,
                )
                Spacer(Modifier.weight(1f))
                Text(formatDuration(state.sourceDurationMs), style = VynylType.monoLarge, color = VynylColors.Amber)
            }
            Spacer(Modifier.height(12.dp))
            if (state.levels.isEmpty()) {
                Text(
                    "The live level appears here as you speak.",
                    style = VynylType.caption,
                    color = VynylColors.Muted,
                )
            } else {
                WaveformStrip(
                    peaks = state.levels,
                    height = 72.dp,
                    color = VynylColors.Brass,
                    playedColor = VynylColors.Amber,
                    progress = 1f,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Peak ${state.meter.peakDb.toInt()} dBFS · ${state.meter.clippingLabel}",
                style = VynylType.monoSmall,
                color = if (state.meter.peakDb > -3f) VynylColors.Error else VynylColors.Muted,
            )
            if (state.sourceDurationMs >= com.vynylrecord.app.core.audio.VoiceRecorder.MAX_DURATION_MS - 5_000L) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.studio_max_length_reached), style = VynylType.caption, color = VynylColors.Amber)
            }
        }

        Spacer(Modifier.height(16.dp))

        BrassPanel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.isRecording -> {
                        VynylPrimaryButton(text = stringResource(R.string.studio_record_pause), onClick = onPause)
                        VynylSecondaryButton(text = stringResource(R.string.studio_record_stop), onClick = onStop)
                    }

                    state.isPaused -> {
                        VynylPrimaryButton(text = stringResource(R.string.studio_record_resume), onClick = onResume)
                        VynylSecondaryButton(text = stringResource(R.string.studio_record_stop), onClick = onStop)
                    }

                    else -> {
                        VynylPrimaryButton(
                            text = if (state.hasSource) "Record again" else stringResource(R.string.studio_record_start),
                            onClick = onRecord,
                            enabled = !state.busy,
                            icon = VynylIcons.Record,
                        )
                        if (state.hasSource) {
                            VynylTextButton(
                                text = stringResource(R.string.studio_record_cancel),
                                onClick = onDiscard,
                                color = VynylColors.Error,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            BrassDivider()
            Spacer(Modifier.height(12.dp))
            SettingsRow(
                title = "Import a file instead",
                detail = "WAV, M4A, MP3 — anything this phone can play",
                trailing = {
                    VynylSecondaryButton(
                        text = stringResource(R.string.studio_import),
                        onClick = onImport,
                        icon = VynylIcons.Import,
                    )
                },
            )
        }

        state.error?.let { message ->
            Spacer(Modifier.height(16.dp))
            BrassPanel {
                Text(message, style = VynylType.body, color = VynylColors.Error)
                if (state.microphoneUnavailable) {
                    Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.studio_mic_rationale), style = VynylType.caption, color = VynylColors.Muted)
                }
            }
        }

        if (state.busy) {
            Spacer(Modifier.height(16.dp))
            Text("Preparing your audio…", style = VynylType.caption, color = VynylColors.Muted)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Step two: who it is for, and what the label says. */
@Composable
private fun DetailsStep(state: StudioViewModel.UiState, viewModel: StudioViewModel) {
    val record = state.record ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        BrassPanel {
            SectionHeading(title = "The dedication", subtitle = "These words are printed on the label and on the sleeve.")
            Spacer(Modifier.height(12.dp))
            StudioField("Title", record.title, "Sunday morning, the kitchen", { viewModel.updateDetails(title = it.take(60)) })
            Spacer(Modifier.height(12.dp))
            StudioField("For", record.recipientName, "Ammi", { viewModel.updateDetails(recipient = it.take(40)) })
            Spacer(Modifier.height(12.dp))
            StudioField("From", record.senderName, "Your name", { viewModel.updateDetails(sender = it.take(40)) })
            Spacer(Modifier.height(12.dp))
            StudioField(
                label = "Dedication",
                value = record.dedication,
                placeholder = "A line to remember this by",
                onValueChange = { viewModel.updateDetails(dedication = it.take(160)) },
                singleLine = false,
            )
        }

        Spacer(Modifier.height(16.dp))

        BrassPanel {
            SectionHeading(title = "The occasion", subtitle = "It changes how the record is described and how it is filed.")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Occasion.all.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { occasion ->
                            Box(Modifier.weight(1f)) {
                                VynylChip(
                                    text = occasion.label,
                                    selected = occasion == record.occasion,
                                    onClick = { viewModel.updateDetails(occasion = occasion) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            StudioField("Date", record.occasionDate, "14 February 2026", { viewModel.updateDetails(occasionDate = it.take(30)) })
            Spacer(Modifier.height(12.dp))
            StudioField("Side A label", record.sideALabel, "Side A", { viewModel.updateDetails(sideA = it.take(20)) })
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** A text field in the app's own colours, with its label above it. */
@Composable
private fun StudioField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = VynylType.monoSmall, color = VynylColors.Muted)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = VynylType.body, color = VynylColors.Muted.copy(alpha = 0.6f)) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            textStyle = VynylType.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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
    }
}

/**
 * Step three: the recipe.
 *
 * Five presets, each a complete chain, and the fourteen controls underneath. The preset is the primary
 * control because it is the one a person can understand at a glance; the controls are there because somebody
 * who wants a record with more crackle should be able to have one, and because a preset that cannot be
 * adjusted is a black box.
 */
@Composable
private fun RecipeStep(state: StudioViewModel.UiState, viewModel: StudioViewModel) {
    val record = state.record ?: return
    val view = LocalView.current
    var showAdvanced by remember { mutableStateOf(record.controls.isCustomized) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        BrassPanel {
            SectionHeading(
                title = "The pressing",
                subtitle = "Each preset is a whole chain: tone, saturation, speed drift, surface noise and the needle.",
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VinylPresetId.all.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        selected = preset == record.presetId,
                        onSelect = {
                            viewModel.choosePreset(preset)
                            Haptics.play(view, Haptics.Moment.PRESET, state.settings.haptics)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        BrassPanel {
            SettingsRow(
                title = "Advanced controls",
                detail = if (record.controls.isCustomized) {
                    "${record.controls.movedControls.size} adjusted by hand"
                } else {
                    "Exactly what ${record.presetId.displayName} does"
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (record.controls.isCustomized) {
                            VynylTextButton(text = "Reset", onClick = viewModel::resetControls, color = VynylColors.Amber)
                        }
                        Switch(
                            checked = showAdvanced,
                            onCheckedChange = { showAdvanced = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = VynylColors.Obsidian,
                                checkedTrackColor = VynylColors.Amber,
                                uncheckedThumbColor = VynylColors.Muted,
                                uncheckedTrackColor = VynylColors.Panel,
                            ),
                        )
                    }
                },
            )
            if (showAdvanced) {
                Spacer(Modifier.height(12.dp))
                BrassDivider()
                Spacer(Modifier.height(12.dp))
                VinylControl.ordered.forEach { control ->
                    ControlSlider(
                        control = control,
                        value = record.controls.value(control),
                        display = control.display(record.controls.recipe),
                        onValueChange = { viewModel.setControl(control, it) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PresetRow(preset: VinylPresetId, selected: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) VynylColors.Panel else VynylColors.DeepStone)
            .border(1.dp, if (selected) VynylColors.Amber else VynylColors.Border, shape)
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(preset.displayName, style = VynylType.headline, color = VynylColors.Cream)
            Text(
                preset.description,
                style = VynylType.caption,
                color = VynylColors.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(preset.character, style = VynylType.monoSmall, color = VynylColors.Brass)
        }
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(VynylColors.Amber))
    }
}

/** One control: its name, the value it currently holds, and the slider. */
@Composable
private fun ControlSlider(control: VinylControl, value: Float, display: String, onValueChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(control.label, style = VynylType.label, color = VynylColors.Cream)
            Spacer(Modifier.weight(1f))
            Text(display, style = VynylType.mono, color = VynylColors.Amber)
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = VynylColors.AmberBright,
                activeTrackColor = VynylColors.Amber,
                inactiveTrackColor = VynylColors.Panel,
            ),
        )
        Text(control.hint, style = VynylType.monoSmall, color = VynylColors.Muted)
    }
}

/** Step four: the finish and the music underneath it. */
@Composable
private fun AppearanceStep(state: StudioViewModel.UiState, viewModel: StudioViewModel) {
    val record = state.record ?: return
    val view = LocalView.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        BrassPanel {
            SectionHeading(title = "The finish", subtitle = "Five pressings. The label's colour changes with each one.")
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(VinylStyleId.all) { style ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(118.dp)
                            .clickable {
                                viewModel.chooseStyle(style)
                                Haptics.play(view, Haptics.Moment.PRESET, state.settings.haptics)
                            },
                    ) {
                        VinylDiscPreview(
                            style = style,
                            title = record.title,
                            size = 104.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .border(
                                    width = if (style == record.styledId) 2.dp else 1.dp,
                                    color = if (style == record.styledId) VynylColors.Amber else VynylColors.Border,
                                    shape = CircleShape,
                                ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            style.displayName,
                            style = VynylType.label,
                            color = if (style == record.styledId) VynylColors.AmberBright else VynylColors.Cream,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            style.subtitle,
                            style = VynylType.monoSmall,
                            color = VynylColors.Muted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        BrassPanel {
            SectionHeading(
                title = "Music underneath",
                subtitle = "Bundled beds or your own file from the Sound Lab. The voice always sits on top of the bed.",
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BackgroundRow(
                    title = "No background",
                    detail = "Just the voice, the vinyl and the needle",
                    selected = record.backgroundAssetId == null,
                    onSelect = { viewModel.chooseBackground(null) },
                )
                state.backgrounds.forEach { asset ->
                    BackgroundRow(
                        title = asset.displayTitle,
                        detail = "${asset.durationLabel} · ${asset.category.label}",
                        selected = asset.id == record.backgroundAssetId,
                        onSelect = { viewModel.chooseBackground(asset) },
                    )
                }
            }
            if (record.backgroundAssetId != null) {
                Spacer(Modifier.height(16.dp))
                BrassDivider()
                Spacer(Modifier.height(12.dp))
                Text("Music level", style = VynylType.label, color = VynylColors.Cream)
                Slider(
                    value = record.backgroundVolume.coerceIn(0f, 0.8f),
                    onValueChange = viewModel::setBackgroundVolume,
                    valueRange = 0f..0.8f,
                    colors = SliderDefaults.colors(
                        thumbColor = VynylColors.AmberBright,
                        activeTrackColor = VynylColors.Amber,
                        inactiveTrackColor = VynylColors.Panel,
                    ),
                )
                Text(
                    "${(record.backgroundVolume / 0.8f * 100).toInt()}% · ducked further under the voice while you speak",
                    style = VynylType.monoSmall,
                    color = VynylColors.Muted,
                )
                Spacer(Modifier.height(12.dp))
                Text("Needle lead-in", style = VynylType.label, color = VynylColors.Cream)
                Slider(
                    value = record.controls.recipe.needleIntroMs / 800f,
                    onValueChange = { viewModel.setNeedleIntro((it * 800f).toInt()) },
                    colors = SliderDefaults.colors(
                        thumbColor = VynylColors.AmberBright,
                        activeTrackColor = VynylColors.Amber,
                        inactiveTrackColor = VynylColors.Panel,
                    ),
                )
                Text(
                    "${record.controls.recipe.needleIntroMs} ms of groove before the voice starts",
                    style = VynylType.monoSmall,
                    color = VynylColors.Muted,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BackgroundRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) VynylColors.Panel else VynylColors.DeepStone)
            .border(1.dp, if (selected) VynylColors.Amber else VynylColors.Border, shape)
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = VynylType.label, color = VynylColors.Cream)
            Text(detail, style = VynylType.caption, color = VynylColors.Muted)
        }
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(VynylColors.Amber))
    }
}

/**
 * Step five: the press.
 *
 * The nine stages are listed in the order the chain runs them, with the one in progress in amber and the
 * finished ones ticked. When it is done the action becomes "Play the record" — the result is not a dialog
 * but the record itself, on the turntable.
 */
@Composable
private fun PressStep(
    state: StudioViewModel.UiState,
    onPress: () -> Boolean,
    onCancel: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenVault: () -> Unit,
) {
    val record = state.record

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        BrassPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VinylDiscPreview(
                    style = record?.styledId ?: VinylStyleId.DEFAULT,
                    title = record?.title ?: "",
                    size = 84.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(record?.displayTitle ?: "No record yet", style = VynylType.headline, color = VynylColors.Cream)
                    Text(
                        record?.dedicationLine ?: "",
                        style = VynylType.caption,
                        color = VynylColors.Muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${record?.presetId?.displayName ?: ""} · ${record?.styledId?.displayName ?: ""}",
                        style = VynylType.monoSmall,
                        color = VynylColors.Brass,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        BrassPanel {
            when {
                state.isRendering -> {
                    SectionHeading(
                        title = stringResource(R.string.studio_render_running),
                        subtitle = renderSubtitle(record?.renderProgress ?: 0f),
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { (record?.renderProgress ?: 0f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = VynylColors.Amber,
                        trackColor = VynylColors.Panel,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${((record?.renderProgress ?: 0f) * 100).toInt()}%", style = VynylType.mono, color = VynylColors.Amber)
                    Spacer(Modifier.height(16.dp))
                    RenderStageList(progress = record?.renderProgress ?: 0f)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "You can leave this screen. The press keeps going and the Vault will show it when it is done.",
                        style = VynylType.caption,
                        color = VynylColors.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    VynylSecondaryButton(text = "Cancel the press", onClick = onCancel, icon = VynylIcons.Stop)
                }

                state.isRendered -> {
                    SectionHeading(title = "Your record is ready", subtitle = "Play it on the turntable, or keep it in the Vault.")
                    Spacer(Modifier.height(12.dp))
                    if (record?.waveformPeaks?.isNotEmpty() == true) {
                        WaveformStrip(record.waveformPeaks, height = 64.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${record?.durationLabel ?: "0:00"} · ${record?.sizeLabel ?: ""}",
                        style = VynylType.mono,
                        color = VynylColors.Amber,
                    )
                    Spacer(Modifier.height(16.dp))
                    VynylPrimaryButton(
                        text = "Play the record",
                        onClick = onOpenPlayer,
                        icon = VynylIcons.Play,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    VynylSecondaryButton(
                        text = "Back to the Vault",
                        onClick = onOpenVault,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    VynylTextButton(text = "Press it again", onClick = { onPress() }, modifier = Modifier.fillMaxWidth())
                }

                else -> {
                    SectionHeading(
                        title = "Press the record",
                        subtitle = "The press runs on this phone, and takes about as long as the recording did.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Source ${formatDuration(state.sourceDurationMs)} · " +
                            "${record?.controls?.recipe?.needleIntroMs ?: 0} ms lead-in · " +
                            "seed ${record?.deterministicSeed?.toString(16) ?: "—"}",
                        style = VynylType.monoSmall,
                        color = VynylColors.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    RenderStageList(progress = 0f)
                    Spacer(Modifier.height(16.dp))
                    VynylPrimaryButton(
                        text = stringResource(R.string.studio_render_action),
                        onClick = { onPress() },
                        enabled = (state.hasSource || state.isRendered) && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        icon = VynylIcons.Render,
                    )
                }
            }
        }

        record?.renderError?.let { error ->
            Spacer(Modifier.height(16.dp))
            BrassPanel {
                SectionHeading(title = "The press stopped", subtitle = error)
                Spacer(Modifier.height(12.dp))
                VynylSecondaryButton(text = "Try again", onClick = { onPress() })
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** The stage a progress figure is in, in the words the list uses. */
private fun renderSubtitle(progress: Float): String =
    com.vynylrecord.app.core.model.RenderStage.forFraction(progress).label

/** The nine stages, with the finished ones ticked and the current one amber. */
@Composable
private fun RenderStageList(progress: Float) {
    val current = com.vynylrecord.app.core.model.RenderStage.forFraction(progress)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.vynylrecord.app.core.model.RenderStage.ordered.forEach { stage ->
            val done = progress > 0f && progress >= stage.upTo
            val active = progress > 0f && progress < 1f && stage == current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                done -> VynylColors.Success
                                active -> VynylColors.Amber
                                else -> VynylColors.Muted.copy(alpha = 0.5f)
                            },
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stage.label,
                    style = VynylType.label,
                    color = when {
                        done -> VynylColors.Cream
                        active -> VynylColors.AmberBright
                        else -> VynylColors.Muted
                    },
                )
                Spacer(Modifier.weight(1f))
                Text("${(stage.upTo * 100).toInt()}%", style = VynylType.monoSmall, color = VynylColors.Muted)
            }
        }
    }
}

/** `m:ss`, so a duration in the Studio reads the way it does everywhere else in the app. */
private fun formatDuration(milliseconds: Long): String {
    val total = (milliseconds / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}

/**
 * A factory so the screen's view model receives the record it was opened with.
 *
 * The alternative — reading the id out of the navigation back stack inside the view model — would drag
 * navigation into a class that otherwise knows nothing about screens, and would make the Studio impossible to
 * test without a nav host.
 */
internal fun studioViewModelFactory(recordId: String?): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(
            modelClass: Class<T>,
            extras: androidx.lifecycle.viewmodel.CreationExtras,
        ): T {
            // The application is the one thing the framework injects; everything else this view model
            // needs comes from the graph, which is why the factory is six lines rather than a framework.
            val application = extras[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
            @Suppress("UNCHECKED_CAST")
            return StudioViewModel(application, recordId) as T
        }
    }
