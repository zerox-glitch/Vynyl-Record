package com.vynylrecord.app.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vynylrecord.app.core.model.AssetCategory
import com.vynylrecord.app.core.model.AudioAsset

/**
 * A row in the Sound Lab's library.
 *
 * Bundled and imported assets live in the same table because they are the same thing to every consumer —
 * a background bed with a level and a trim. The difference is provenance, and [isBundled] plus
 * [bundledResourceName] record it: a bundled asset can be switched off but never deleted, because it is
 * part of the app; an imported one is the user's own file and can go.
 */
@Entity(
    tableName = "audio_assets",
    indices = [Index("category"), Index(value = ["bundledResourceName"], unique = true)],
)
data class AudioAssetEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
    val sourcePath: String?,
    val bundledResourceName: String?,
    val isBundled: Boolean,
    val enabled: Boolean,
    val defaultVolume: Float,
    val trimStartMilliseconds: Long,
    val trimEndMilliseconds: Long,
    val durationMilliseconds: Long,
    val sizeBytes: Long,
    val createdAt: Long,
) {
    fun toModel(): AudioAsset = AudioAsset(
        id = id,
        title = title,
        category = AssetCategory.fromId(category),
        sourcePath = sourcePath,
        bundledResourceName = bundledResourceName,
        isBundled = isBundled,
        enabled = enabled,
        defaultVolume = defaultVolume,
        trimStartMilliseconds = trimStartMilliseconds,
        trimEndMilliseconds = trimEndMilliseconds,
        durationMilliseconds = durationMilliseconds,
        sizeBytes = sizeBytes,
        createdAt = createdAt,
    )

    companion object {
        fun from(asset: AudioAsset): AudioAssetEntity = AudioAssetEntity(
            id = asset.id,
            title = asset.title,
            category = asset.category.id,
            sourcePath = asset.sourcePath,
            bundledResourceName = asset.bundledResourceName,
            isBundled = asset.isBundled,
            enabled = asset.enabled,
            defaultVolume = asset.defaultVolume,
            trimStartMilliseconds = asset.trimStartMilliseconds,
            trimEndMilliseconds = asset.trimEndMilliseconds,
            durationMilliseconds = asset.durationMilliseconds,
            sizeBytes = asset.sizeBytes,
            createdAt = asset.createdAt,
        )
    }
}
