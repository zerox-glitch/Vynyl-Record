package com.vynylrecord.turntable.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vynylrecord.turntable.studio.StudioUiState
import com.vynylrecord.turntable.vault.PressRecipe

/** Stable handles for the Sound Lab's instrumentation tests. */
object SoundLabTestTags {
    const val SCENE = "sound_lab_scene"
    const val PRESET_ROW = "sound_lab_presets"
    const val APPLY = "sound_lab_apply"
    const val SOURCE = "sound_lab_source"

    fun preset(preset: PressRecipe.Preset): String = "sound_lab_preset_${preset.name}"

    fun slider(name: String): String = "sound_lab_slider_$name"
}

/**
 * The Sound Lab: the same press chain as the Studio, separated so a sound can be dialled in and then
 * cut into a record.
 *
 * The important thing this screen states plainly is that the character is *baked*. Turning a knob
 * here does not filter the deck's playback — it changes what the next pressing sounds like, and the
 * recipe travels with the record into the vault.
 */
@Composable
fun SoundLabScreen(
    state: StudioUiState,
    onRecipeChange: (PressRecipe) -> Unit,
    onPress: () -> Unit,
    onOpenStudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = state.source

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VynylColors.Background)
            .testTag(SoundLabTestTags.SCENE),
    ) {
        ScreenHeader(
            title = "Sound Lab",
            subtitle = "Design the sound, then cut it into the record",
        )

        StatusBar(
            notice = state.notice,
            error = state.error,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                LabelBlock(title = "The master this will be cut into") {
                    if (source == null) {
                        Text(
                            text = "No master is loaded. Capture a voice in the Studio first, and the " +
                                "recipe you set here will be cut into it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VynylColors.Muted,
                        )
                        VynylSecondaryButton(
                            label = "Go to the Studio",
                            onClick = onOpenStudio,
                            icon = VynylIcon.MIC,
                        )
                    } else {
                        Text(
                            text = source.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = VynylColors.Cream,
                            modifier = Modifier.testTag(SoundLabTestTags.SOURCE),
                        )
                        Waveform(
                            values = source.waveform,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Metric("Length", source.durationLabel)
                            Metric("Format", "${source.sampleRate / 1000} kHz ${source.channelsLabel}")
                            Metric("Peak", "%+.1f dB".format(source.peakDecibels))
                        }
                    }
                }
            }

            item {
                SectionLabel(text = "Character presets")
                Spacer(modifier = Modifier.height(8.dp))
                ChipRow(modifier = Modifier.testTag(SoundLabTestTags.PRESET_ROW)) {
                    PressRecipe.Preset.entries.forEach { preset ->
                        VynylChoiceChip(
                            label = preset.displayName,
                            supporting = preset.blurb,
                            selected = state.recipePreset == preset,
                            onClick = { onRecipeChange(preset.recipe) },
                            modifier = Modifier.testTag(SoundLabTestTags.preset(preset)),
                        )
                    }
                }
            }

            item {
                LabelBlock(
                    title = "Surface",
                    onReset = { onRecipeChange(state.recipe.copy(crackle = 0.34f, surfaceNoise = 0.26f, hiss = 0.14f)) },
                ) {
                    LabelledSlider(
                        label = "Crackle",
                        value = state.recipe.crackle,
                        onValueChange = { onRecipeChange(state.recipe.copy(crackle = it)) },
                        modifier = Modifier.testTag(SoundLabTestTags.slider("crackle")),
                    )
                    LabelledSlider(
                        label = "Groove noise",
                        value = state.recipe.surfaceNoise,
                        onValueChange = { onRecipeChange(state.recipe.copy(surfaceNoise = it)) },
                        modifier = Modifier.testTag(SoundLabTestTags.slider("noise")),
                    )
                    LabelledSlider(
                        label = "Tape hiss",
                        value = state.recipe.hiss,
                        onValueChange = { onRecipeChange(state.recipe.copy(hiss = it)) },
                        modifier = Modifier.testTag(SoundLabTestTags.slider("hiss")),
                    )
                }
            }

            item {
                LabelBlock(
                    title = "The turntable",
                    onReset = { onRecipeChange(state.recipe.copy(wowFlutter = 0.22f, roomTone = 0.22f)) },
                ) {
                    LabelledSlider(
                        label = "Wow & flutter",
                        value = state.recipe.wowFlutter,
                        onValueChange = { onRecipeChange(state.recipe.copy(wowFlutter = it)) },
                        modifier = Modifier.testTag(SoundLabTestTags.slider("wow")),
                    )
                    LabelledSlider(
                        label = "Room tone",
                        value = state.recipe.roomTone,
                        onValueChange = { onRecipeChange(state.recipe.copy(roomTone = it)) },
                        modifier = Modifier.testTag(SoundLabTestTags.slider("room")),
                    )
                }
            }

            item {
                LabelBlock(
                    title = "Tone and drive",
                    onReset = { onRecipeChange(state.recipe.copy(warmth = 0.55f, saturation = 0.32f)) },
                ) {
                    LabelledSlider(
                        label = "Warmth",
                        value = state.recipe.warmth,
                        onValueChange = { onRecipeChange(state.recipe.copy(warmth = it)) },
                        modifier = Modifier.testTag(SoundLabTestTags.slider("warmth")),
                    )
                    LabelledSlider(
                        label = "Cutter drive",
                        value = state.recipe.saturation,
                        onValueChange = { onRecipeChange(state.recipe.copy(saturation = it)) },
                        modifier = Modifier.testTag(SoundLabTestTags.slider("drive")),
                    )
                }
            }

            item {
                LabelBlock(title = "Mastering") {
                    SwitchRow(
                        label = "Trim silence and cut lead-in",
                        checked = state.recipe.trimSilence,
                        onCheckedChange = { onRecipeChange(state.recipe.copy(trimSilence = it)) },
                    )
                    SwitchRow(
                        label = "Normalise to full level",
                        checked = state.recipe.normalize,
                        onCheckedChange = { onRecipeChange(state.recipe.copy(normalize = it)) },
                    )
                }
            }

            item {
                LabelBlock(title = "What this will sound like") {
                    Text(
                        text = state.recipe.describe(),
                        style = MaterialTheme.typography.titleSmall,
                        color = VynylColors.Cream,
                    )
                    Text(
                        text = if (state.recipe.isMaster) {
                            "A flat transfer: the side will sound like the master, with no record in it."
                        } else {
                            "Cut into the file when you press: crackle, groove noise, pitch drift, warmth " +
                                "and room are baked in, so the pressing keeps its character anywhere it is played."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = VynylColors.Muted,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    VynylPrimaryButton(
                        label = if (source == null) "Pick a master first" else "Cut this side",
                        onClick = onPress,
                        enabled = state.canPress,
                        icon = VynylIcon.DISC,
                        modifier = Modifier.testTag(SoundLabTestTags.APPLY),
                    )
                }
            }
        }
    }
}

