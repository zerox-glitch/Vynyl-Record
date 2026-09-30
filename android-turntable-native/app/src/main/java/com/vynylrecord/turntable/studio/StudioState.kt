package com.vynylrecord.turntable.studio

import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordSide
import com.vynylrecord.turntable.model.VinylStyle
import com.vynylrecord.turntable.vault.Press
import com.vynylrecord.turntable.vault.PressRecipe

/**
 * The four steps of the Studio, in order of the strip at the top of the screen.
 *
 * The enum carries the label and the one-line promise for each step, so the strip, the accessibility
 * description and the tests all read from the same place.
 */
enum class StudioStage(val step: Int, val label: String, val promise: String) {
    CAPTURE(1, "Capture", "Speak, and it goes into the wax"),
    DEDICATE(2, "Dedicate", "Say who it is for"),
    RECIPE(3, "Recipe", "Set how it should sound"),
    ATMOSPHERE(4, "Atmosphere", "Choose how it should look"),
    ;

    val isFirst: Boolean get() = this == CAPTURE
    val isLast: Boolean get() = this == ATMOSPHERE

    fun next(): StudioStage = entries.getOrElse(ordinal + 1) { this }

    fun previous(): StudioStage = entries.getOrElse(ordinal - 1) { this }

    companion object {
        val ordered: List<StudioStage> = entries.toList()
    }
}

/** Where the audio being pressed came from. Shown on the vault row and the pressing sheet. */
enum class SourceKind(val label: String) {
    RECORDING("Recorded in Studio"),
    IMPORT("Imported recording"),
}

/**
 * The master being pressed: a recording that is in memory, or an imported file that has been decoded.
 *
 * [waveform] is a list rather than a `FloatArray` so the state compares by value — a `StateFlow` that
 * emits a new array instance every time would recompose the waveform for nothing.
 */
data class SourceInfo(
    val kind: SourceKind,
    val displayName: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val peakDecibels: Float,
    val waveform: List<Float>,
    /** True when the source is longer than one side and will be cut across several. */
    val spansMultipleSides: Boolean = false,
) {
    val durationLabel: String
        get() {
            val seconds = durationMs / 1000L
            return "%d:%02d".format(seconds / 60L, seconds % 60L)
        }

    val channelsLabel: String get() = if (channels == 1) "mono" else "stereo"
}

/** Progress of the press, which runs off the main thread and blocks the step strip while it does. */
data class PressProgress(
    val active: Boolean = false,
    /** 0..1 across the whole job, including all sides. */
    val fraction: Float = 0f,
    val stage: String = "",
    /** What is being worked on right now: "Side A", "Side B" ... */
    val sideLabel: String = "",
    val sideIndex: Int = 1,
    val sideCount: Int = 1,
) {
    val percent: Int get() = (fraction * 100f).toInt().coerceIn(0, 100)
}

/**
 * Everything the Studio, the Vault and the Settings surfaces draw.
 *
 * One immutable snapshot, updated by [StudioViewModel] on the main thread. The draft fields are the
 * label's own text: they start pre-filled so a pressing is never unlabelled, and every one of them is
 * editable.
 */
data class StudioUiState(
    val stage: StudioStage = StudioStage.CAPTURE,
    // ---- the label
    val title: String = "",
    val recipient: String = "",
    val sender: String = "You",
    val date: String = "",
    val catalogue: String = "",
    val side: RecordSide = RecordSide.A,
    // ---- the pressing
    val style: VinylStyle = VinylStyle.DEFAULT,
    val speed: PlatterSpeed = PlatterSpeed.THIRTY_THREE,
    val recipe: PressRecipe = PressRecipe.STUDIO,
    val recipePreset: PressRecipe.Preset = PressRecipe.Preset.HOUSE_CUT,
    // ---- the capture
    val isRecording: Boolean = false,
    val recordingMs: Long = 0L,
    val inputLevel: Float = 0f,
    val source: SourceInfo? = null,
    // ---- the job
    val press: PressProgress = PressProgress(),
    val pressed: Press? = null,
    // ---- the vault
    val vault: List<Press> = emptyList(),
    val vaultBytes: Long = 0L,
    // ---- messages
    val error: String? = null,
    val notice: String? = null,
) {

    /** The label has enough on it to be worth printing: a title and someone to dedicate it to. */
    val dedicationComplete: Boolean
        get() = title.isNotBlank() && recipient.isNotBlank() && sender.isNotBlank()

    /** True when the press button should be live. */
    val canPress: Boolean get() = source != null && dedicationComplete && !press.active && !isRecording

    val recordingLabel: String
        get() {
            val seconds = recordingMs / 1000L
            return "%02d:%02d".format(seconds / 60L, seconds % 60L)
        }

    /** Vault storage, for the Settings row. */
    val vaultSizeLabel: String
        get() = when {
            vaultBytes >= 1024L * 1024L -> "%.1f MB".format(vaultBytes / (1024f * 1024f))
            vaultBytes >= 1024L -> "%d KB".format(vaultBytes / 1024L)
            else -> "$vaultBytes B"
        }

    /** Catalogue number for the next pressing: VYN 001, VYN 002 ... */
    val suggestedCatalogue: String get() = "VYN %03d".format(vault.size + 1)
}
