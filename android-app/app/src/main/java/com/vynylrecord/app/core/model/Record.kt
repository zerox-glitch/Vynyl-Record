package com.vynylrecord.app.core.model

import java.util.UUID

/**
 * Where a record is in the job that turns a recording into wax.
 *
 * The order matters: a render may only move *forward* through [Preparing] → [Decoding] → [Mixing] →
 * [Character] → [Mastering] → [Encoding] → [Waveform] → [Artwork] → [Completed], and a record that
 * failed or was cancelled can only go back to [Queued]. Nothing writes [Completed] except the render
 * worker, and only after it has re-opened its own output and measured it (see `RenderValidator`).
 */
enum class RenderState(val id: String, val label: String, val progress: Float) {
    DRAFT("draft", "Draft", 0f),
    QUEUED("queued", "Queued", 0.02f),
    RENDERING("rendering", "Pressing", 0.05f),
    COMPLETED("completed", "Pressed", 1f),
    FAILED("failed", "Failed", 0f),
    CANCELED("canceled", "Cancelled", 0f),
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELED

    val isRunning: Boolean get() = this == QUEUED || this == RENDERING

    companion object {
        fun fromId(id: String?): RenderState = entries.firstOrNull { it.id == id } ?: DRAFT
    }
}

/** The nine stages a render actually runs, in order. Shown to the user as they happen. */
enum class RenderStage(val index: Int, val label: String, val upTo: Float) {
    PREPARING(0, "Preparing source", 0.04f),
    DECODING(1, "Decoding", 0.14f),
    MIXING(2, "Mixing atmosphere", 0.28f),
    CHARACTER(3, "Adding vinyl character", 0.62f),
    MASTERING(4, "Mastering", 0.74f),
    ENCODING(5, "Encoding", 0.86f),
    WAVEFORM(6, "Generating waveform", 0.92f),
    ARTWORK(7, "Generating artwork", 0.97f),
    COMPLETE(8, "Complete", 1f),
    ;

    companion object {
        val ordered: List<RenderStage> = entries.toList()

        /** Maps an overall 0..1 fraction onto the stage a user would name for it. */
        fun forFraction(fraction: Float): RenderStage {
            val f = fraction.coerceIn(0f, 1f)
            return ordered.lastOrNull { f >= it.upTo - 0.0001f } ?: PREPARING
        }
    }
}

/**
 * One record, as the app thinks about it.
 *
 * This is a domain model, not a database row and not a wire format: `core/data` maps it to Room, and
 * `core/storage/bundle` maps it to JSON. Keeping the three apart is what lets the database schema move
 * without breaking bundles, and vice versa.
 *
 * Audio is never inside this object. [sourcePath] and [masterPath] are paths relative to the record's
 * own directory in app-private storage, so moving a record (or restoring a backup under a different
 * install id) does not invalidate them.
 */
data class Record(
    val id: String = UUID.randomUUID().toString(),
    // ---- the label
    val title: String = "",
    val recipientName: String = "",
    val senderName: String = "",
    val dedication: String = "",
    val occasion: Occasion = Occasion.SOMETHING_ELSE,
    val occasionDate: String = "",
    val sideALabel: String = "Side A",
    val sideBLabel: String = "Side B",
    // ---- the media
    val sourcePath: String? = null,
    val masterPath: String? = null,
    val coverArtworkPath: String? = null,
    val waveformPath: String? = null,
    /** 96 compact 0..1 peak values, so a library row can draw a waveform without touching the file. */
    val waveformPeaks: List<Float> = emptyList(),
    val durationMilliseconds: Long = 0L,
    val sourceDurationMilliseconds: Long = 0L,
    // ---- the sound
    val presetId: VinylPresetId = VinylPresetId.DEFAULT,
    val controls: VinylControls = VinylControls.of(VinylPresetId.DEFAULT),
    val styledId: VinylStyleId = VinylStyleId.DEFAULT,
    val backgroundAssetId: String? = null,
    val backgroundVolume: Float = 0.18f,
    val backgroundTrimStartMs: Long = 0L,
    val backgroundTrimEndMs: Long = 0L,
    /** Derived from the record id and the preset, so a re-render is bit-identical. */
    val deterministicSeed: Long = 0L,
    // ---- the job
    val renderState: RenderState = RenderState.DRAFT,
    val renderProgress: Float = 0f,
    val renderStageLabel: String = "",
    val renderError: String? = null,
    val renderJobId: String? = null,
    // ---- the shelf
    /** Measured when the repository lists the record; the entity carries it too. */
    val sizeBytes: Long = 0L,
    val favourite: Boolean = false,
    val lastPlayedAt: Long = 0L,
    val playCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isReady: Boolean get() = renderState == RenderState.COMPLETED && masterPath != null

    val displayTitle: String get() = title.ifBlank { "Untitled record" }

    /** The line printed under the title on the label and the artwork. */
    val dedicationLine: String
        get() = dedication.ifBlank {
            if (recipientName.isNotBlank()) "For $recipientName" else occasion.label
        }

    val signatureLine: String get() = if (senderName.isNotBlank()) "from $senderName" else ""

    /** The occasion and its date on one line: "Wedding · 14 February 2026". */
    val subtitleLine: String
        get() = listOf(occasion.label, occasionDate)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    /** When the record was pressed, in the form a person reads rather than a timestamp. */
    val createdLabel: String
        get() = CREATED_FORMAT.format(java.util.Date(createdAt))

    val durationLabel: String
        get() {
            val total = durationMilliseconds / 1000L
            return "%d:%02d".format(total / 60L, total % 60L)
        }

    val remainingLabel: String
        get() {
            val total = (durationMilliseconds / 1000L).coerceAtLeast(0L)
            return "-%d:%02d".format(total / 60L, total % 60L)
        }

    /** "3.2 MB" — used by the export sheet and the storage screen. */
    val sizeLabel: String get() = formatBytes(sizeBytes)

    val seedOrDefault: Long get() = if (deterministicSeed != 0L) deterministicSeed else Seed.derive(id, presetId.id)

    companion object {
        private val CREATED_FORMAT = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> "%d KB".format(bytes / 1024L)
            else -> "$bytes B"
        }
    }
}

/**
 * The seed every procedural layer is driven from.
 *
 * Ported from the web renderer, which derived its seeds from the recording id so that the same
 * recording and preset always produce the same bed. The same property matters more here: a record that
 * is re-rendered must not shuffle its pops, because a user who presses the same record twice and hears
 * the crackle move would reasonably conclude the second one is broken.
 *
 * FNV-1a over `recordId:presetId`, so two records of the same voice get different patterns and one
 * record always gets the same one.
 */
object Seed {
    fun derive(recordId: String, presetId: String): Long {
        var hash = 0x811C9DC5L
        val input = "$recordId:$presetId"
        for (character in input) {
            hash = hash xor character.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        // A zero seed would make the noise generators look like they never advance. Nudge it.
        return if (hash == 0L) 0x5EED1234L else hash
    }
}
