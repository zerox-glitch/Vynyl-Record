package com.vynylrecord.app.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vynylrecord.app.core.data.json.RecipeJson
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId

/**
 * A record's row.
 *
 * There is no audio in this table and there never will be. The audio is a file; this is the index card
 * that says which file, what it is called, and how it was pressed. Room's job is to answer "what is in my
 * Vault" and "which records are still rendering" quickly, and a row is a few hundred bytes because of it.
 *
 * [waveformSamples] is the one exception, and it is a deliberate one: 96 numbers, 400 bytes, enough for a
 * library card. Reading the master to draw a waveform every time the Vault scrolls would turn a list into
 * a file-parsing exercise.
 */
@Entity(
    tableName = "records",
    indices = [
        Index("updatedAt"),
        Index("renderState"),
        Index("occasion"),
        Index("favourite"),
    ],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val title: String,
    val recipientName: String,
    val senderName: String,
    val dedication: String,
    val occasion: String,
    val occasionDate: String,
    val sideALabel: String,
    val sideBLabel: String,
    @ColumnInfo(name = "sourceAudioPath") val sourceAudioPath: String?,
    @ColumnInfo(name = "masterAudioPath") val masterAudioPath: String?,
    val coverArtworkPath: String?,
    val waveformPath: String?,
    /** Compact peaks, comma separated, already normalised. */
    val waveformSamples: String,
    val durationMilliseconds: Long,
    val sourceDurationMilliseconds: Long,
    val vinylPresetId: String,
    val vinylStyleId: String,
    val backgroundAssetId: String?,
    val backgroundVolume: Float,
    val backgroundTrimStartMs: Long,
    val backgroundTrimEndMs: Long,
    /** The full recipe plus which knobs were moved; see [RecipeJson]. */
    val advancedSettingsJson: String,
    val deterministicSeed: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val renderState: String,
    val renderProgress: Float,
    val renderStageLabel: String,
    val renderJobId: String?,
    val renderError: String?,
    val sizeBytes: Long,
    val favourite: Boolean,
    val lastPlayedAt: Long,
    val playCount: Int,
) {
    fun toModel(): Record = Record(
        id = id,
        title = title,
        recipientName = recipientName,
        senderName = senderName,
        dedication = dedication,
        occasion = Occasion.fromId(occasion),
        occasionDate = occasionDate,
        sideALabel = sideALabel,
        sideBLabel = sideBLabel,
        sourcePath = sourceAudioPath,
        masterPath = masterAudioPath,
        coverArtworkPath = coverArtworkPath,
        waveformPath = waveformPath,
        waveformPeaks = decodePeaks(waveformSamples),
        durationMilliseconds = durationMilliseconds,
        sourceDurationMilliseconds = sourceDurationMilliseconds,
        presetId = VinylPresetId.fromId(vinylPresetId),
        controls = RecipeJson.decode(advancedSettingsJson, VinylPresetId.fromId(vinylPresetId)),
        styledId = VinylStyleId.fromId(vinylStyleId),
        backgroundAssetId = backgroundAssetId,
        backgroundVolume = backgroundVolume,
        backgroundTrimStartMs = backgroundTrimStartMs,
        backgroundTrimEndMs = backgroundTrimEndMs,
        deterministicSeed = deterministicSeed,
        renderState = RenderState.fromId(renderState),
        renderProgress = renderProgress,
        renderStageLabel = renderStageLabel,
        renderJobId = renderJobId,
        renderError = renderError,
        sizeBytes = sizeBytes,
        favourite = favourite,
        lastPlayedAt = lastPlayedAt,
        playCount = playCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun from(record: Record): RecordEntity = RecordEntity(
            id = record.id,
            title = record.title,
            recipientName = record.recipientName,
            senderName = record.senderName,
            dedication = record.dedication,
            occasion = record.occasion.id,
            occasionDate = record.occasionDate,
            sideALabel = record.sideALabel,
            sideBLabel = record.sideBLabel,
            sourceAudioPath = record.sourcePath,
            masterAudioPath = record.masterPath,
            coverArtworkPath = record.coverArtworkPath,
            waveformPath = record.waveformPath,
            waveformSamples = encodePeaks(record.waveformPeaks),
            durationMilliseconds = record.durationMilliseconds,
            sourceDurationMilliseconds = record.sourceDurationMilliseconds,
            vinylPresetId = record.presetId.id,
            vinylStyleId = record.styledId.id,
            backgroundAssetId = record.backgroundAssetId,
            backgroundVolume = record.backgroundVolume,
            backgroundTrimStartMs = record.backgroundTrimStartMs,
            backgroundTrimEndMs = record.backgroundTrimEndMs,
            advancedSettingsJson = RecipeJson.encode(record.controls),
            deterministicSeed = record.deterministicSeed,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
            renderState = record.renderState.id,
            renderProgress = record.renderProgress,
            renderStageLabel = record.renderStageLabel,
            renderJobId = record.renderJobId,
            renderError = record.renderError,
            sizeBytes = record.sizeBytes,
            favourite = record.favourite,
            lastPlayedAt = record.lastPlayedAt,
            playCount = record.playCount,
        )

        /**
         * Peaks are stored scaled by a thousand and comma separated.
         *
         * Text rather than a BLOB so a row stays readable in a database dump, and scaled rather than raw
         * floats so a peak that should be `0.42` does not come back as `0.42000001` and make the
         * library's diffing think a record changed.
         */
        fun encodePeaks(peaks: List<Float>): String =
            peaks.joinToString(",") { (it.coerceIn(0f, 1f) * 1_000f).toInt().toString() }

        fun decodePeaks(encoded: String): List<Float> {
            if (encoded.isBlank()) return emptyList()
            return encoded.split(',').mapNotNull { it.trim().toIntOrNull()?.let { value -> value / 1_000f } }
        }
    }
}
