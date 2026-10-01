package com.vynylrecord.app.feature.settings

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vynylrecord.app.BuildConfig
import com.vynylrecord.app.R
import com.vynylrecord.app.VynylGraph
import com.vynylrecord.app.core.data.prefs.AudioQuality
import com.vynylrecord.app.core.data.prefs.GraphicsQuality
import com.vynylrecord.app.core.data.prefs.OutputFormat
import com.vynylrecord.app.core.data.prefs.VynylSettings
import com.vynylrecord.app.core.design.BrassDivider
import com.vynylrecord.app.core.design.BrassPanel
import com.vynylrecord.app.core.design.SectionHeading
import com.vynylrecord.app.core.design.SettingsRow
import com.vynylrecord.app.core.design.StatBlock
import com.vynylrecord.app.core.design.VynylChip
import com.vynylrecord.app.core.design.VynylColors
import com.vynylrecord.app.core.design.VynylSecondaryButton
import com.vynylrecord.app.core.design.VynylTextButton
import com.vynylrecord.app.core.design.VynylType
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.security.AppLock
import com.vynylrecord.app.core.storage.BackupArchive
import com.vynylrecord.app.core.storage.FileStore
import com.vynylrecord.app.core.storage.StorageUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings: privacy, audio, graphics, storage and the honest small print.
 *
 * Everything on this screen is a real switch with a real consequence, and each row says what that is. The
 * biometric row, in particular, tells the truth about the device: where no fingerprint or screen lock is
 * enrolled it explains itself and refuses to enable rather than accepting a switch that does nothing.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = VynylGraph.of(application)
    private val files = FileStore(application)
    private val lock = AppLock(application)

    data class UiState(
        val settings: VynylSettings = VynylSettings(),
        val usage: StorageUsage = StorageUsage(0, 0, 0, 0, 0, 0),
        val recordCount: Int = 0,
        val durationMs: Long = 0L,
        val lockAvailability: AppLock.Availability = AppLock.Availability.OK,
        val message: String? = null,
        val busy: Boolean = false,
        val pendingRestore: File? = null,
        val versionLabel: String = BuildConfig.VERSION_NAME,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.preferences.settings.collect { settings -> _state.value = _state.value.copy(settings = settings) }
        }
        viewModelScope.launch {
            graph.recordRepository.records.collect { records ->
                _state.value = _state.value.copy(
                    recordCount = records.size,
                    durationMs = records.sumOf { it.durationMilliseconds },
                )
            }
        }
        refreshStorage()
        _state.value = _state.value.copy(lockAvailability = lock.availability())
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val usage = withContext(Dispatchers.IO) { files.usage() }
            _state.value = _state.value.copy(usage = usage)
        }
    }

    // ---- privacy
    fun setBiometricLock(enabled: Boolean) {
        val availability = lock.availability()
        if (enabled && !availability.canEnable) {
            // Refusing here is the honest behaviour: a lock that cannot be presented must not be switchable.
            _state.value = _state.value.copy(message = availability.message, lockAvailability = availability)
            return
        }
        viewModelScope.launch {
            graph.preferences.setBiometricLock(enabled)
            _state.value = _state.value.copy(
                message = if (enabled) "The app will ask for your fingerprint or PIN when it reopens" else "The lock is off",
            )
        }
    }

    fun setLockTimeout(seconds: Int) = viewModelScope.launch { graph.preferences.setLockTimeoutSeconds(seconds) }

    fun setHideNotificationDetail(hide: Boolean) =
        viewModelScope.launch { graph.preferences.setHideNotificationDetail(hide) }

    // ---- audio
    fun setAudioQuality(quality: AudioQuality) = viewModelScope.launch { graph.preferences.setAudioQuality(quality) }

    fun setOutputFormat(format: OutputFormat) = viewModelScope.launch { graph.preferences.setOutputFormat(format) }

    fun setSampleRate(rate: Int) = viewModelScope.launch { graph.preferences.setSampleRate(rate) }

    fun setDefaultPreset(preset: VinylPresetId) = viewModelScope.launch { graph.preferences.setDefaultPreset(preset) }

    fun setDefaultMusicLevel(level: Float) = viewModelScope.launch { graph.preferences.setDefaultMusicLevel(level) }

    fun setIncludeSource(include: Boolean) = viewModelScope.launch { graph.preferences.setIncludeSourceInExport(include) }

    fun setAutoRender(auto: Boolean) = viewModelScope.launch { graph.preferences.setAutoRenderOnComplete(auto) }

    // ---- graphics
    fun setGraphicsQuality(quality: GraphicsQuality) =
        viewModelScope.launch { graph.preferences.setGraphicsQuality(quality) }

    fun setReducedMotion(reduced: Boolean) = viewModelScope.launch { graph.preferences.setReducedMotion(reduced) }

    fun setAutoOrbit(orbit: Boolean) = viewModelScope.launch { graph.preferences.setAutoOrbit(orbit) }

    fun setShadows(shadows: Boolean) = viewModelScope.launch { graph.preferences.setShadows(shadows) }

    fun setShowFps(show: Boolean) = viewModelScope.launch { graph.preferences.setShowFps(show) }

    fun setHaptics(enabled: Boolean) = viewModelScope.launch { graph.preferences.setHapticsEnabled(enabled) }

    // ---- storage
    fun clearCaches() {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { files.clearCaches() }
            _state.value = _state.value.copy(message = "Cleared ${com.vynylrecord.app.core.storage.formatBytes(freed)} of temporary files")
            refreshStorage()
        }
    }

    /** Writes a backup into the file the user chose. */
    fun backupTo(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val result = withContext(Dispatchers.IO) {
                val scratch = File(files.renderDirectory("backup-${System.currentTimeMillis()}"), "vynyl-backup.${BackupArchive.EXTENSION}")
                val report = BackupArchive.backup(
                    target = scratch,
                    records = graph.recordRepository,
                    assets = graph.assetRepository,
                    files = files,
                    appVersion = BuildConfig.VERSION_NAME,
                    // The archive is built from the current contents of the library, read once at the start
                    // rather than streamed: a backup is a snapshot, and half a library is worse than none.
                    recordList = graph.recordRepository.records.first(),
                    assetList = graph.assetRepository.assets.first(),
                    includeSources = _state.value.settings.includeSourceInExport,
                )
                val copied = report != null && files.copyToDocument(scratch, uri)
                scratch.delete()
                copied
            }
            _state.value = _state.value.copy(
                busy = false,
                message = if (result) "Backup written" else "The backup could not be written",
            )
            refreshStorage()
        }
    }

    /** Opens a backup and asks before restoring it. */
    fun previewRestore(uri: Uri) {
        viewModelScope.launch {
            val staged = withContext(Dispatchers.IO) {
                val target = File(files.stagingRoot(), "restore-${System.currentTimeMillis()}.${BackupArchive.EXTENSION}")
                if (files.copyFromDocument(uri, target)) target else null
            }
            _state.value = _state.value.copy(
                pendingRestore = staged,
                message = if (staged == null) "That file could not be opened" else null,
            )
        }
    }

    fun cancelRestore() {
        _state.value = _state.value.pendingRestore?.delete()?.let { _state.value.copy(pendingRestore = null) }
            ?: _state.value.copy(pendingRestore = null)
    }

    fun confirmRestore() {
        val archive = _state.value.pendingRestore ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, pendingRestore = null)
            val report = withContext(Dispatchers.IO) {
                BackupArchive.restore(
                    archive = archive,
                    records = graph.recordRepository,
                    assets = graph.assetRepository,
                    files = files,
                )
            }
            archive.delete()
            _state.value = _state.value.copy(busy = false, message = report.summary)
            refreshStorage()
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private companion object {
        const val TAG = "VynylSettingsViewModel"
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings

    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.backupTo(it) }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.previewRestore(it) }
    }
    var showAbout by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(VynylColors.Obsidian),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(stringResource(R.string.settings_title), style = VynylType.title, color = VynylColors.Cream)
                Text(
                    "Everything here acts on this phone only. Nothing on this screen needs a connection.",
                    style = VynylType.caption,
                    color = VynylColors.Muted,
                )
            }
        }

        // ---------------------------------------------------------------- privacy
        item {
            BrassPanel {
                SectionHeading(title = stringResource(R.string.settings_privacy_title), subtitle = "Your recordings are files in this app's own storage.")
                SettingsRow(
                    title = "Lock the app",
                    detail = state.lockAvailability.message,
                    trailing = {
                        Switch(
                            checked = settings.biometricLock,
                            enabled = state.lockAvailability.canEnable || settings.biometricLock,
                            onCheckedChange = viewModel::setBiometricLock,
                            colors = switchColors(),
                        )
                    },
                )
                if (settings.biometricLock) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Immediately", 30 to "30 seconds", 300 to "5 minutes").forEach { (seconds, label) ->
                            VynylChip(
                                text = label,
                                selected = settings.lockTimeoutSeconds == seconds,
                                onClick = { viewModel.setLockTimeout(seconds) },
                            )
                        }
                    }
                }
                SettingsRow(
                    title = "Hide titles in notifications",
                    detail = "The press notification says \"Pressing your record\" instead of its title",
                    trailing = {
                        Switch(
                            checked = settings.hideNotificationDetail,
                            onCheckedChange = viewModel::setHideNotificationDetail,
                            colors = switchColors(),
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                BrassDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_privacy_line) + " " +
                        "There is no account, no upload and no analytics: the app asks for the microphone and " +
                        "one notification, and nothing else.",
                    style = VynylType.caption,
                    color = VynylColors.Muted,
                )
            }
        }

        // ---------------------------------------------------------------- audio
        item {
            BrassPanel {
                SectionHeading(title = stringResource(R.string.settings_audio_title), subtitle = "How records are pressed and what an export contains.")
                ChoiceRow(
                    title = "Quality",
                    options = AudioQuality.entries.map { it.id to it.label },
                    selected = settings.audioQuality.id,
                    onSelect = { id -> AudioQuality.fromId(id)?.let(viewModel::setAudioQuality) },
                )
                ChoiceRow(
                    title = "Export format",
                    options = OutputFormat.entries.map { it.id to it.label },
                    selected = settings.outputFormat.id,
                    onSelect = { id -> OutputFormat.fromId(id)?.let(viewModel::setOutputFormat) },
                )
                ChoiceRow(
                    title = "Sample rate",
                    options = listOf("44100" to "44.1 kHz", "48000" to "48 kHz"),
                    selected = settings.sampleRate.toString(),
                    onSelect = { id -> id.toIntOrNull()?.let(viewModel::setSampleRate) },
                )
                SettingsRow(
                    title = "Include the original recording in exports",
                    detail = "A bundle then carries the voice exactly as it was captured",
                    trailing = {
                        Switch(
                            checked = settings.includeSourceInExport,
                            onCheckedChange = viewModel::setIncludeSource,
                            colors = switchColors(),
                        )
                    },
                )
                SettingsRow(
                    title = "Press a record as soon as it is finished",
                    detail = "Off means every record waits for you to press it by hand",
                    trailing = {
                        Switch(
                            checked = settings.autoRenderOnComplete,
                            onCheckedChange = viewModel::setAutoRender,
                            colors = switchColors(),
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text("Default pressing", style = VynylType.monoSmall, color = VynylColors.Muted)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(VinylPresetId.all) { preset ->
                        VynylChip(
                            text = preset.displayName,
                            selected = settings.defaultPreset == preset,
                            onClick = { viewModel.setDefaultPreset(preset) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Default music level", style = VynylType.label, color = VynylColors.Cream)
                Slider(
                    value = settings.defaultMusicLevel,
                    onValueChange = viewModel::setDefaultMusicLevel,
                    valueRange = 0f..0.8f,
                    colors = sliderColors(),
                )
                Text(
                    "${(settings.defaultMusicLevel / 0.8f * 100).toInt()}% under the voice",
                    style = VynylType.monoSmall,
                    color = VynylColors.Muted,
                )
            }
        }

        // ---------------------------------------------------------------- graphics
        item {
            BrassPanel {
                SectionHeading(title = stringResource(R.string.settings_graphics_title), subtitle = "The 3D deck, and how much of the phone it uses.")
                ChoiceRow(
                    title = "Detail",
                    options = GraphicsQuality.entries.map { it.id to it.label },
                    selected = settings.graphicsQuality.id,
                    onSelect = { id -> GraphicsQuality.fromId(id)?.let(viewModel::setGraphicsQuality) },
                )
                SettingsRow(
                    title = "Reduce motion",
                    detail = "The deck stops drifting and transitions are shortened",
                    trailing = {
                        Switch(
                            checked = settings.reducedMotion,
                            onCheckedChange = viewModel::setReducedMotion,
                            colors = switchColors(),
                        )
                    },
                )
                SettingsRow(
                    title = "Orbit while idle",
                    detail = "The camera drifts slowly when nobody is touching it",
                    trailing = {
                        Switch(
                            checked = settings.autoOrbit,
                            onCheckedChange = viewModel::setAutoOrbit,
                            colors = switchColors(),
                        )
                    },
                )
                SettingsRow(
                    title = "Shadows",
                    detail = "A soft shadow under the deck",
                    trailing = {
                        Switch(
                            checked = settings.shadows,
                            onCheckedChange = viewModel::setShadows,
                            colors = switchColors(),
                        )
                    },
                )
                SettingsRow(
                    title = "Show the frame rate",
                    detail = "Frames per second and triangles, over the deck",
                    trailing = {
                        Switch(
                            checked = settings.showFps,
                            onCheckedChange = viewModel::setShowFps,
                            colors = switchColors(),
                        )
                    },
                )
                SettingsRow(
                    title = "Haptics",
                    detail = "A tick when recording starts, the needle lands or a record finishes",
                    trailing = {
                        Switch(
                            checked = settings.haptics,
                            onCheckedChange = viewModel::setHaptics,
                            colors = switchColors(),
                        )
                    },
                )
            }
        }

        // ---------------------------------------------------------------- storage
        item {
            BrassPanel {
                SectionHeading(title = stringResource(R.string.settings_storage_title), subtitle = "Everything the app has written, in this phone's own storage.")
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatBlock(state.usage.recordsLabel, "Records")
                    StatBlock(state.usage.assetsLabel, "Sound assets")
                    StatBlock(state.usage.exportsLabel, "Exports")
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatBlock(state.usage.cacheLabel, "Temporary")
                    StatBlock("${state.recordCount}", "In the Vault")
                    StatBlock(formatDuration(state.durationMs), "Recorded")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "${com.vynylrecord.app.core.storage.formatBytes(state.usage.freeBytes)} free on this device",
                    style = VynylType.monoSmall,
                    color = VynylColors.Muted,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VynylSecondaryButton(text = "Clear temporary files", onClick = viewModel::clearCaches)
                    VynylSecondaryButton(
                        text = "Refresh",
                        onClick = viewModel::refreshStorage,
                    )
                }
                Spacer(Modifier.height(12.dp))
                BrassDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    "A backup contains every record and every sound you added, as one file you keep wherever you " +
                        "like. Restoring is additive: a record already in the Vault is left alone.",
                    style = VynylType.caption,
                    color = VynylColors.Muted,
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_uninstall_warning), style = VynylType.caption, color = VynylColors.Error)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VynylSecondaryButton(
                        text = stringResource(R.string.backup_write),
                        onClick = { backupPicker.launch("vynyl-backup.${BackupArchive.EXTENSION}") },
                        enabled = !state.busy,
                    )
                    VynylSecondaryButton(
                        text = stringResource(R.string.backup_restore),
                        onClick = { restorePicker.launch(arrayOf("*/*")) },
                        enabled = !state.busy,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- about
        item {
            BrassPanel {
                SectionHeading(title = stringResource(R.string.settings_about_title), subtitle = "Version ${state.versionLabel}")
                SettingsRow(
                    title = "Vynyl Record",
                    detail = stringResource(R.string.app_subtitle),
                    trailing = {
                        VynylTextButton(text = "Notices", onClick = { showAbout = true }, color = VynylColors.Amber)
                    },
                )
                Text(
                    "Privacy: no accounts, no network access, no analytics. The bundled music beds were generated " +
                        "for this project and are free to use; the full list is in LICENSES.md beside the source.",
                    style = VynylType.caption,
                    color = VynylColors.Muted,
                )
            }
        }

        if (state.busy) {
            item {
                Text("Working…", style = VynylType.caption, color = VynylColors.Amber)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            containerColor = VynylColors.DeepStone,
            title = { Text("Vynyl Record", style = VynylType.headline, color = VynylColors.Cream) },
            text = { Text(message, style = VynylType.body, color = VynylColors.Muted) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) {
                    Text("Close", style = VynylType.label, color = VynylColors.Amber)
                }
            },
        )
    }

    state.pendingRestore?.let { archive ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            containerColor = VynylColors.DeepStone,
            title = {
                Text(stringResource(R.string.backup_restore_title), style = VynylType.headline, color = VynylColors.Cream)
            },
            text = {
                Text(stringResource(R.string.backup_restore_body), style = VynylType.body, color = VynylColors.Muted)
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) {
                    Text("Restore", style = VynylType.label, color = VynylColors.Amber)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) {
                    Text("Not now", style = VynylType.label, color = VynylColors.Muted)
                }
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            containerColor = VynylColors.DeepStone,
            title = { Text("Notices", style = VynylType.headline, color = VynylColors.Cream) },
            text = {
                Text(
                    "Vynyl Record is built from AndroidX, Jetpack Compose, Media3, Room, WorkManager and Kotlin " +
                        "coroutines, all under the Apache License 2.0. The five music beds and the needle drop " +
                        "were generated for this project by tools/generate_bundled_assets.py and carry no third-" +
                        "party rights. Nothing in the app is downloaded at runtime.",
                    style = VynylType.body,
                    color = VynylColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text("Close", style = VynylType.label, color = VynylColors.Amber)
                }
            },
        )
    }
}

/** A row of chips for a choice that has three or four options. */
@Composable
private fun ChoiceRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = VynylType.body, color = VynylColors.Cream)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { it.first }) { (id, label) ->
                VynylChip(text = label, selected = id == selected, onClick = { onSelect(id) })
            }
        }
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = VynylColors.Obsidian,
    checkedTrackColor = VynylColors.Amber,
    uncheckedThumbColor = VynylColors.Muted,
    uncheckedTrackColor = VynylColors.Panel,
)

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = VynylColors.AmberBright,
    activeTrackColor = VynylColors.Amber,
    inactiveTrackColor = VynylColors.Panel,
)

private fun formatDuration(milliseconds: Long): String {
    val total = (milliseconds / 1000L).coerceAtLeast(0L)
    return if (total >= 3600) "%dh %02dm".format(total / 3600, (total % 3600) / 60) else "%dm %02ds".format(total / 60, total % 60)
}
