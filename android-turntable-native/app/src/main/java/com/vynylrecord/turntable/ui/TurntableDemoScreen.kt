package com.vynylrecord.turntable.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vynylrecord.turntable.graphics.CameraPreset
import com.vynylrecord.turntable.graphics.RenderQuality
import com.vynylrecord.turntable.model.VinylStyle
import com.vynylrecord.turntable.player.TurntableController
import com.vynylrecord.turntable.player.TurntableUiState

/** Stable handles for the instrumentation tests. */
object DemoTestTags {
    const val SCENE = "turntable_scene"
    const val PLAY_PAUSE = "play_pause"
    const val SEEK = "seek_slider"
    const val SKIP_BACK = "skip_back"
    const val SKIP_FORWARD = "skip_forward"
    const val CAMERA_RESET = "camera_reset"
    const val FULL_SCREEN = "full_screen_toggle"
    const val DEBUG_TOGGLE = "debug_toggle"
    const val OPTIONS_TOGGLE = "options_toggle"
    const val PHASE = "phase_label"
    const val ERROR = "error_banner"
    const val STYLE_ROW = "style_row"
    const val PRESET_ROW = "preset_row"
    const val QUALITY_ROW = "quality_row"

    fun styleChip(style: VinylStyle): String = "style_${style.name}"

    fun presetChip(preset: CameraPreset): String = "preset_${preset.name}"

    fun qualityChip(quality: RenderQuality): String = "quality_${quality.name}"
}

/**
 * The demo surface: a 3D deck, a transport, and the selectors that show what the renderer can do.
 *
 * Every control is a real Compose control with a content description and a 48dp+ touch target, and
 * the whole feature set works without the 3D scene - that is the accessibility contract described in
 * README.md, not a nice-to-have.
 */
@Composable
fun TurntableDemoScreen(
    uiState: TurntableUiState,
    controller: TurntableController,
    modifier: Modifier = Modifier,
    onPlaybackCompleted: () -> Unit = {},
    onError: (String) -> Unit = {},
) {
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(VynylColors.Background).safeDrawingPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!fullScreen) {
                DemoHeader(uiState = uiState)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = if (fullScreen) 0.dp else 12.dp),
            ) {
                VynylTurntable(
                    uiState = uiState,
                    controller = controller,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(if (fullScreen) 0.dp else 16.dp))
                        .testTag(DemoTestTags.SCENE),
                    onPlaybackCompleted = onPlaybackCompleted,
                    onError = onError,
                )

                if (showDebug) {
                    DebugOverlay(
                        uiState = uiState,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (com.vynylrecord.turntable.BuildConfig.DEBUG) {
                        VynylIconButton(
                            icon = VynylIcon.GAUGE,
                            contentDescription = if (showDebug) "Hide performance overlay" else "Show performance overlay",
                            selected = showDebug,
                            onClick = { showDebug = !showDebug },
                            modifier = Modifier.testTag(DemoTestTags.DEBUG_TOGGLE),
                        )
                    }
                    VynylIconButton(
                        icon = if (fullScreen) VynylIcon.COLLAPSE else VynylIcon.EXPAND,
                        contentDescription = if (fullScreen) "Leave full screen" else "Full screen 3D",
                        onClick = { fullScreen = !fullScreen },
                        modifier = Modifier.testTag(DemoTestTags.FULL_SCREEN),
                    )
                }

                uiState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VynylColors.Cream,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VynylColors.Ruby.copy(alpha = 0.92f))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag(DemoTestTags.ERROR),
                    )
                }
            }

            TransportPanel(
                uiState = uiState,
                controller = controller,
                compact = fullScreen,
                showOptions = showOptions,
                onToggleOptions = { showOptions = !showOptions },
            )
        }
    }
}

@Composable
private fun DemoHeader(uiState: TurntableUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Vynyl Record",
            style = MaterialTheme.typography.labelMedium,
            color = VynylColors.Amber,
        )
        Text(
            text = uiState.metadata.title,
            style = MaterialTheme.typography.headlineSmall,
            color = VynylColors.Cream,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${uiState.metadata.dedicationLine()} · ${uiState.metadata.signatureLine()} · Side ${uiState.metadata.side.shortName}",
            style = MaterialTheme.typography.bodySmall,
            color = VynylColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TransportPanel(
    uiState: TurntableUiState,
    controller: TurntableController,
    compact: Boolean,
    showOptions: Boolean,
    onToggleOptions: () -> Unit,
) {
    BrassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Transport row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VynylIconButton(
                    icon = VynylIcon.SKIP_BACK,
                    contentDescription = "Skip back 10 seconds",
                    enabled = uiState.canSeek,
                    onClick = { controller.skipBy(-10_000L) },
                    modifier = Modifier.testTag(DemoTestTags.SKIP_BACK),
                )
                VynylIconButton(
                    icon = if (uiState.showAsPlaying) VynylIcon.PAUSE else VynylIcon.PLAY,
                    contentDescription = if (uiState.showAsPlaying) "Pause" else "Play",
                    enabled = uiState.canPlay,
                    selected = true,
                    size = 68.dp,
                    onClick = { controller.togglePlayPause() },
                    modifier = Modifier.testTag(DemoTestTags.PLAY_PAUSE),
                )
                VynylIconButton(
                    icon = VynylIcon.SKIP_FORWARD,
                    contentDescription = "Skip forward 10 seconds",
                    enabled = uiState.canSeek,
                    onClick = { controller.skipBy(10_000L) },
                    modifier = Modifier.testTag(DemoTestTags.SKIP_FORWARD),
                )
                VynylIconButton(
                    icon = VynylIcon.RESET_CAMERA,
                    contentDescription = "Reset camera",
                    onClick = { controller.resetCamera() },
                    modifier = Modifier.testTag(DemoTestTags.CAMERA_RESET),
                )
            }

            // Seek row.
            Slider(
                value = uiState.progress,
                onValueChange = { fraction ->
                    if (uiState.canSeek) {
                        controller.seekTo((fraction * uiState.durationMs).toLong())
                    }
                },
                enabled = uiState.canSeek,
                colors = SliderDefaults.colors(
                    thumbColor = VynylColors.AmberBright,
                    activeTrackColor = VynylColors.Amber,
                    inactiveTrackColor = VynylColors.Outline,
                    disabledThumbColor = VynylColors.Outline,
                    disabledActiveTrackColor = VynylColors.Outline,
                    disabledInactiveTrackColor = VynylColors.Outline,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DemoTestTags.SEEK)
                    .semantics { contentDescription = "Playback position" },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = uiState.elapsedLabel, style = TimecodeStyle, color = VynylColors.Cream)
                Text(
                    text = uiState.phaseLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = VynylColors.Amber,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .testTag(DemoTestTags.PHASE)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(text = uiState.remainingLabel, style = TimecodeStyle, color = VynylColors.Muted)
            }

            // Secondary chips.
            ChipRow {
                VynylChoiceChip(
                    label = "${uiState.speed.displayName} rpm",
                    selected = uiState.speed == com.vynylrecord.turntable.model.PlatterSpeed.FORTY_FIVE,
                    onClick = { controller.toggleSpeed() },
                    supporting = "Platter speed",
                )
                VynylChoiceChip(
                    label = "Side ${uiState.metadata.side.shortName}",
                    selected = false,
                    onClick = { controller.flipSide() },
                    supporting = "Flip label",
                )
                VynylChoiceChip(
                    label = if (uiState.isCompleted) "Replay" else "Restart",
                    selected = false,
                    // Both put the needle back at the start of the side: a fresh cue when the side
                    // finished, a jump to the outer groove while it is still playing.
                    onClick = { controller.replay() },
                    supporting = "Needle to start",
                )
                VynylChoiceChip(
                    label = uiState.sourceName.ifEmpty { "No record" },
                    selected = false,
                    onClick = { controller.loadBundled() },
                    supporting = "Load bundled",
                )
                VynylChoiceChip(
                    label = "Options",
                    selected = showOptions,
                    onClick = onToggleOptions,
                    modifier = Modifier.testTag(DemoTestTags.OPTIONS_TOGGLE),
                    supporting = "Camera & quality",
                )
            }

            // Vinyl style: always visible, it is the headline feature.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Vinyl")
                ChipRow(modifier = Modifier.testTag(DemoTestTags.STYLE_ROW)) {
                    VinylStyle.entries.forEach { style ->
                        VynylChoiceChip(
                            label = style.displayName,
                            selected = uiState.vinylStyle == style,
                            accent = Color(style.palette.accent),
                            onClick = { controller.setVinylStyle(style) },
                            modifier = Modifier.testTag(DemoTestTags.styleChip(style)),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = showOptions) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Camera")
                        ChipRow(modifier = Modifier.testTag(DemoTestTags.PRESET_ROW)) {
                            CameraPreset.entries.forEach { preset ->
                                VynylChoiceChip(
                                    label = preset.displayName,
                                    selected = uiState.cameraPreset == preset,
                                    onClick = { controller.setCameraPreset(preset) },
                                    modifier = Modifier.testTag(DemoTestTags.presetChip(preset)),
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Render quality")
                        ChipRow(modifier = Modifier.testTag(DemoTestTags.QUALITY_ROW)) {
                            RenderQuality.entries.forEach { quality ->
                                VynylChoiceChip(
                                    label = quality.displayName,
                                    selected = uiState.quality == quality,
                                    supporting = "${(quality.renderScale * 100).toInt()}% · ${if (quality.msaaSamples > 0) "MSAA ${quality.msaaSamples}x" else "no MSAA"}",
                                    onClick = { controller.setQuality(quality) },
                                    modifier = Modifier.testTag(DemoTestTags.qualityChip(quality)),
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Motion")
                        ChipRow {
                            VynylChoiceChip(
                                label = "Reduced motion",
                                selected = uiState.reducedMotion,
                                onClick = { controller.setReducedMotion(!uiState.reducedMotion) },
                                supporting = "Shorter transitions",
                            )
                            VynylChoiceChip(
                                label = if (uiState.isEs3Available) "OpenGL ES 3.0" else "Static fallback",
                                selected = false,
                                onClick = {},
                                supporting = uiState.glDescription ?: "Renderer",
                            )
                        }
                    }
                }
            }

            if (!uiState.isEs3Available) {
                Text(
                    text = "This device has no OpenGL ES 3.0, so the deck is drawn with the static " +
                        "fallback. Playback, seeking and every control still work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VynylColors.Muted,
                )
            }
        }
    }
}

/** Debug-only performance readout. Never announces itself to accessibility services. */
@Composable
fun DebugOverlay(
    uiState: TurntableUiState,
    modifier: Modifier = Modifier,
) {
    val stats = uiState.frameStatistics
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC0C0A09))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OverlayLine("${stats.formattedFps} fps")
        OverlayLine("${stats.drawCalls} draws · ${stats.triangles} tris")
        OverlayLine("MSAA ${if (stats.msaaSamples > 0) "${stats.msaaSamples}x" else "off"}")
        OverlayLine("${uiState.quality.displayName} · ${uiState.visualPhase.name.lowercase()}")
        uiState.glDescription?.let { description -> OverlayLine(description) }
    }
}

@Composable
private fun OverlayLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            modifier = Modifier
                .size(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(VynylColors.Amber),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, style = TimecodeStyle, color = VynylColors.Cream)
    }
}
