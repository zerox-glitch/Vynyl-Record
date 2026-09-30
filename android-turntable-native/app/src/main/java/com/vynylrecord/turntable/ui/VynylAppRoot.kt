package com.vynylrecord.turntable.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynylrecord.turntable.player.TurntableViewModel
import com.vynylrecord.turntable.studio.StudioStage
import com.vynylrecord.turntable.studio.StudioViewModel
import com.vynylrecord.turntable.vault.Press

/** The five sections of the app. */
enum class AppTab(val label: String, val icon: VynylIcon) {
    STUDIO("Studio", VynylIcon.MIC),
    DECK("3D Deck", VynylIcon.DISC),
    LAB("Sound Lab", VynylIcon.FLASK),
    VAULT("Vault", VynylIcon.VAULT),
    SETTINGS("Settings", VynylIcon.GEAR),
}

/** Stable handles for the shell's instrumentation tests. */
object ShellTestTags {
    const val BOTTOM_BAR = "app_bottom_bar"
    const val CONTENT = "app_content"

    fun tab(tab: AppTab): String = "app_tab_${tab.name}"
}

/**
 * The application shell: five tabs, one deck, one vault.
 *
 * ## What it wires together
 *
 * [StudioViewModel] owns capture, pressing and the vault; [TurntableViewModel] owns the deck, the
 * audio engine and the renderer. This composable is the only place the two meet, and they meet in
 * exactly two directions:
 *
 *  * **Vault ▶ deck.** Pressing a side — or tapping play on a vault row — loads that file into the
 *    deck with its label, style and speed, then switches to the deck tab.
 *  * **Deck ▶ vault.** Flipping the record on the deck loads the sibling side's file when the pressing
 *    has one, so a two-sided record really is two sides.
 *
 * ## Permissions
 *
 * The microphone is requested the first time someone taps record, never at launch, and a refusal is
 * reported as a sentence rather than a silent dead button. Importing from storage uses the system
 * picker, which needs no permission at all on any supported version.
 */
@Composable
fun VynylAppRoot(
    player: TurntableViewModel,
    studio: StudioViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playerState by player.controller.state.collectAsStateWithLifecycle()
    val studioState by studio.state.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(AppTab.STUDIO) }
    /** The vault record currently on the deck, so flipping it can find the other side. */
    var loadedPress by remember { mutableStateOf<Press?>(null) }

    val reducedMotion = rememberSystemReducedMotion()

    LaunchedEffect(reducedMotion) {
        player.controller.setReducedMotion(reducedMotion)
    }

    // A record must be on the platter when the deck is first looked at, so the demo pressing is loaded
    // once and replaced the moment the user presses something of their own.
    LaunchedEffect(Unit) {
        if (!playerState.hasRecord) player.loadDemoRecord()
    }

    val pickAudio = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            studio.importAudio(uri)
        }
    }

    val askForMicrophone = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            studio.startRecording()
        } else {
            studio.reportError(
                "Recording needs the microphone. You can still import a recording you already have.",
            )
        }
    }

    fun beginRecording() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) studio.startRecording() else askForMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Puts a pressed side on the deck and shows it. */
    fun playOnDeck(press: Press) {
        val file = studio.audioFileOf(press)
        if (!file.isFile) {
            studio.reportError("That pressing's audio file is missing from storage.")
            return
        }
        player.controller.setVinylStyle(press.style)
        player.controller.setSpeed(press.speed)
        player.controller.setMetadata(press.metadata)
        player.controller.load(file, press.metadata)
        loadedPress = press
        tab = AppTab.DECK
    }

    // The Studio raises a newly pressed side; it goes straight onto the deck, which is the moment the
    // whole app is built around.
    LaunchedEffect(studioState.pressed?.id) {
        studioState.pressed?.let { press ->
            playOnDeck(press)
            studio.dismissPressed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VynylColors.Background)
            .statusBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(ShellTestTags.CONTENT),
        ) {
            when (tab) {
                AppTab.STUDIO -> StudioScreen(
                    state = studioState,
                    onStageSelected = studio::setStage,
                    onNext = studio::nextStage,
                    onBack = studio::previousStage,
                    onStartRecording = ::beginRecording,
                    onStopRecording = studio::stopRecording,
                    onImport = { pickAudio.launch(arrayOf("audio/*")) },
                    onClearSource = studio::clearSource,
                    onTitleChange = studio::setTitle,
                    onRecipientChange = studio::setRecipient,
                    onSenderChange = studio::setSender,
                    onDateChange = studio::setDate,
                    onCatalogueChange = studio::setCatalogue,
                    onSideChange = studio::setSide,
                    onStyleChange = studio::setStyle,
                    onSpeedChange = studio::setSpeed,
                    onRecipeChange = studio::setRecipe,
                    onPress = studio::press,
                    onDismissOverlay = {
                        studio.clearError()
                        studio.clearNotice()
                    },
                )

                AppTab.DECK -> TurntableDemoScreen(
                    uiState = playerState,
                    controller = player.controller,
                    onError = studio::reportError,
                    onFlipSide = {
                        val press = loadedPress
                        val sibling = press?.let { studio.siblingSide(it) }
                        val siblingFile = press?.let { studio.audioFileOfSibling(it) }
                        if (press != null && sibling != null && siblingFile != null) {
                            // A two-sided pressing really is two sides: park the mechanism, reprint the
                            // label for the other side, load its file and start it.
                            player.controller.pause()
                            player.controller.setMetadata(sibling.metadata)
                            player.controller.load(siblingFile, sibling.metadata)
                            player.controller.play()
                            loadedPress = sibling
                        } else if (press == null) {
                            // The demonstration pressing has one side; flipping only reprints its label.
                            player.controller.flipSide()
                        } else {
                            studio.reportError("This pressing has only one side.")
                        }
                    },
                    footer = {
                        if (playerState.hasRecord) {
                            NowPlayingCard(
                                title = playerState.metadata.title,
                                detail = "${playerState.phaseLabel} · ${playerState.elapsedLabel} · " +
                                    "${playerState.speedLabel} · Side ${playerState.metadata.side.shortName}",
                                onClick = { player.controller.togglePlayPause() },
                                trailing = {
                                    VynylIconGlyph(
                                        icon = if (playerState.showAsPlaying) VynylIcon.PAUSE else VynylIcon.PLAY,
                                        tint = VynylColors.AmberBright,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    },
                )

                AppTab.LAB -> SoundLabScreen(
                    state = studioState,
                    onRecipeChange = studio::setRecipe,
                    onPress = studio::press,
                    onOpenStudio = { tab = AppTab.STUDIO },
                )

                AppTab.VAULT -> VaultScreen(
                    state = studioState,
                    onPlay = ::playOnDeck,
                    onEdit = {
                        studio.editExisting(it)
                        tab = AppTab.STUDIO
                    },
                    onDelete = studio::deletePress,
                    onRename = studio::renamePress,
                    onOpenStudio = { tab = AppTab.STUDIO },
                )

                AppTab.SETTINGS -> SettingsScreen(
                    uiState = playerState,
                    vaultCount = studioState.vault.size,
                    vaultSize = studioState.vaultSizeLabel,
                    onQualityChange = player.controller::setQuality,
                    onCameraPresetChange = player.controller::setCameraPreset,
                    onClearVault = studio::clearVault,
                    onOpenDeck = { tab = AppTab.DECK },
                )
            }
        }

        BottomBar(
            selected = tab,
            onSelect = { tab = it },
            vaultCount = studioState.vault.size,
        )
    }
}

/**
 * The bottom bar: five destinations, the current one in an amber capsule.
 *
 * Material3's `NavigationBar` would give the same structure with Material's own metrics; this is
 * drawn by hand because the target look is a warm analog console rather than Material's surface
 * tinting, and because the icons are the app's own glyphs rather than a dependency's.
 */
@Composable
private fun BottomBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    vaultCount: Int,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ShellTestTags.BOTTOM_BAR),
        color = VynylColors.Panel,
        border = BorderStroke(1.dp, VynylColors.Brass.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = buildString {
                                append(tab.label)
                                if (tab == AppTab.VAULT && vaultCount > 0) append(", $vaultCount sides")
                                if (isSelected) append(", selected")
                            }
                            role = Role.Tab
                            this.selected = isSelected
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        onClick = { onSelect(tab) },
                        // The tag sits on the clickable surface rather than the column, so a test clicks
                        // the thing a finger would click.
                        modifier = Modifier.testTag(ShellTestTags.tab(tab)),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = if (isSelected) VynylColors.Amber else Color.Transparent,
                        border = if (isSelected) {
                            BorderStroke(1.dp, VynylColors.AmberBright)
                        } else {
                            null
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            VynylIconGlyph(
                                icon = tab.icon,
                                tint = if (isSelected) VynylColors.Background else VynylColors.Muted,
                                modifier = Modifier.size(18.dp),
                            )
                            if (isSelected) {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VynylColors.Background,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (!isSelected) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = VynylColors.Muted,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reads the system's animation scale once per composition.
 *
 * Reading this setting is the only environment the app consults: no analytics, no identifiers, no
 * content observers. If the read is blocked by the OEM, animations stay on.
 */
@Composable
private fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (error: Throwable) {
            false
        }
    }
}
