package com.vynylrecord.turntable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vynylrecord.turntable.graphics.CameraPreset
import com.vynylrecord.turntable.graphics.RenderQuality
import com.vynylrecord.turntable.player.TurntableUiState

/** Stable handles for the Settings instrumentation tests. */
object SettingsTestTags {
    const val SCENE = "settings_scene"
    const val CLEAR_VAULT = "settings_clear_vault"
    const val QUALITY_ROW = "settings_quality_row"
    const val CAMERA_ROW = "settings_camera_row"
}

/**
 * Settings: the graphics ladder, motion, where things are stored, and what this build does and does
 * not do.
 *
 * The privacy block is not decoration. This app has no `INTERNET` permission at all, so "works in
 * airplane mode" is a statement of fact that a reader can verify against the manifest rather than a
 * promise in a marketing line.
 */
@Composable
fun SettingsScreen(
    uiState: TurntableUiState,
    vaultCount: Int,
    vaultSize: String,
    onQualityChange: (RenderQuality) -> Unit,
    onCameraPresetChange: (CameraPreset) -> Unit,
    onClearVault: () -> Unit,
    onOpenDeck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VynylColors.Background)
            .testTag(SettingsTestTags.SCENE),
    ) {
        ScreenHeader(
            title = "Settings",
            subtitle = "The deck, the storage, and what this app never does",
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                LabelBlock(title = "Graphics") {
                    Text(
                        text = "Quality changes the render scale, anti-aliasing, shadow detail and the " +
                            "label texture size. The device picks a default; choosing one here keeps it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VynylColors.Muted,
                    )
                    ChipRow(modifier = Modifier.testTag(SettingsTestTags.QUALITY_ROW)) {
                        RenderQuality.entries.forEach { quality ->
                            VynylChoiceChip(
                                label = quality.displayName,
                                supporting = "${(quality.renderScale * 100).toInt()}% · ${quality.maxRenderSize}px",
                                selected = uiState.quality == quality,
                                onClick = { onQualityChange(quality) },
                            )
                        }
                    }
                    DetailRow(
                        label = "Running",
                        value = if (uiState.isEs3Available) {
                            "${uiState.quality.displayName} · ${uiState.frameStatistics.formattedFps} fps"
                        } else {
                            "Compatibility surface"
                        },
                    )
                    DetailRow(
                        label = "Scene",
                        value = uiState.glDescription?.take(64) ?: "starting up",
                    )
                    DetailRow(label = "MSAA samples", value = uiState.frameStatistics.msaaSamples.toString())
                    DetailRow(label = "Triangles", value = uiState.frameStatistics.triangles.toString())
                    DetailRow(label = "Draw calls", value = uiState.frameStatistics.drawCalls.toString())
                }
            }

            item {
                LabelBlock(title = "Camera") {
                    ChipRow(modifier = Modifier.testTag(SettingsTestTags.CAMERA_ROW)) {
                        CameraPreset.entries.forEach { preset ->
                            VynylChoiceChip(
                                label = preset.displayName,
                                selected = uiState.cameraPreset == preset,
                                onClick = { onCameraPresetChange(preset) },
                            )
                        }
                    }
                    Text(
                        text = "Or drag on the deck to orbit, pinch to zoom, and two-finger drag to " +
                            "pan. The camera settles back to the framing you chose.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VynylColors.Muted,
                    )
                    VynylSecondaryButton(
                        label = "Open the deck",
                        onClick = onOpenDeck,
                        icon = VynylIcon.DISC,
                    )
                }
            }

            item {
                LabelBlock(title = "Motion") {
                    DetailRow(
                        label = "System animation scale",
                        value = if (uiState.reducedMotion) "animations off" else "animations on",
                    )
                    Text(
                        text = "This app follows the system setting. With animations off, the mechanism " +
                            "moves instantly between states and the camera stops settling, so the deck " +
                            "is usable without motion.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VynylColors.Muted,
                    )
                }
            }

            item {
                LabelBlock(title = "Storage") {
                    DetailRow(label = "Pressed sides", value = vaultCount.toString())
                    DetailRow(label = "Used on device", value = vaultSize)
                    Text(
                        text = "Every side is a WAV in this app's private storage. Nothing is uploaded, " +
                            "and nothing is shared with anyone unless you export it yourself.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VynylColors.Muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VynylSecondaryButton(
                            label = "Clear the vault",
                            onClick = { confirmClear = true },
                            icon = VynylIcon.TRASH,
                            enabled = vaultCount > 0,
                            modifier = Modifier.testTag(SettingsTestTags.CLEAR_VAULT),
                        )
                    }
                }
            }

            item {
                LabelBlock(title = "Offline by construction") {
                    Bullet("No INTERNET permission: the app cannot open a connection even if it wanted to.")
                    Bullet("No accounts, no sign-in, no analytics, no crash reporting, no identifiers.")
                    Bullet("No API keys and no configuration: install it and it works in airplane mode.")
                    Bullet("Every shader, texture and sound is generated on the device or bundled in the APK.")
                    Bullet("Your recordings stay in this app's storage. Deleting a side deletes its file.")
                }
            }

            item {
                LabelBlock(title = "About") {
                    DetailRow(label = "Renderer", value = "OpenGL ES 3.0 · procedural, no imported models")
                    DetailRow(label = "Audio", value = "44.1 kHz PCM · pressed locally")
                    DetailRow(label = "Turntable", value = "22-mesh deck built at runtime")
                    DetailRow(label = "Label", value = "drawn on a Canvas, no artwork files")
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the whole vault?", color = VynylColors.Cream) },
            text = {
                Text(
                    text = "All $vaultCount side${if (vaultCount == 1) "" else "s"} and their audio files " +
                        "will be deleted from this device. This cannot be undone.",
                    color = VynylColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearVault()
                    confirmClear = false
                }) {
                    Text("Delete everything", color = VynylColors.Ruby)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Keep my records", color = VynylColors.Cream)
                }
            },
            containerColor = VynylColors.Panel,
            titleContentColor = VynylColors.Cream,
            textContentColor = VynylColors.Muted,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = VynylColors.AmberBright,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = VynylColors.Muted,
        )
    }
}
