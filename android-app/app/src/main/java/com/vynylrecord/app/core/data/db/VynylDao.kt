package com.vynylrecord.app.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Queries over the Vault.
 *
 * Every read the UI performs is a `Flow`, so the Vault redraws itself when a render finishes without any
 * screen subscribing to a render's progress directly. Filtering and sorting happen in SQL rather than in
 * Kotlin: a library of a hundred records is small enough that either would work, but the query is the
 * honest place to express "newest first, favourites first".
 */
@Dao
interface RecordDao {

    @Query("SELECT * FROM records ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE id = :id")
    fun observeById(id: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun findById(id: String): RecordEntity?

    @Query("SELECT * FROM records WHERE renderState IN ('queued', 'rendering') ORDER BY updatedAt ASC")
    suspend fun findActiveRenders(): List<RecordEntity>

    @Query("SELECT * FROM records WHERE renderState IN ('queued', 'rendering') ORDER BY updatedAt ASC")
    fun observeActiveRenders(): Flow<List<RecordEntity>>

    @Query("SELECT COUNT(*) FROM records WHERE renderState = 'completed'")
    suspend fun countCompleted(): Int

    @Query("SELECT COALESCE(SUM(durationMilliseconds), 0) FROM records WHERE renderState = 'completed'")
    suspend fun totalRecordedMilliseconds(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM records")
    suspend fun totalBytes(): Long

    @Upsert
    suspend fun upsert(entity: RecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: RecordEntity): Long

    @Update
    suspend fun update(entity: RecordEntity)

    @Delete
    suspend fun delete(entity: RecordEntity)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        UPDATE records SET
            renderState = :state,
            renderProgress = :progress,
            renderStageLabel = :stageLabel,
            renderJobId = :jobId,
            renderError = :error,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateRenderStatus(
        id: String,
        state: String,
        progress: Float,
        stageLabel: String,
        jobId: String?,
        error: String?,
        updatedAt: Long,
    )

    @Query("UPDATE records SET favourite = :favourite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateFavourite(id: String, favourite: Boolean, updatedAt: Long)

    @Query(
        """
        UPDATE records SET
            masterAudioPath = :masterPath,
            coverArtworkPath = :artworkPath,
            waveformPath = :waveformPath,
            waveformSamples = :waveformSamples,
            durationMilliseconds = :durationMs,
            sizeBytes = :sizeBytes,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateMaster(
        id: String,
        masterPath: String?,
        artworkPath: String?,
        waveformPath: String?,
        waveformSamples: String,
        durationMs: Long,
        sizeBytes: Long,
        updatedAt: Long,
    )

    @Query("UPDATE records SET lastPlayedAt = :playedAt, playCount = playCount + 1 WHERE id = :id")
    suspend fun markPlayed(id: String, playedAt: Long)

    @Query("SELECT * FROM records")
    suspend fun all(): List<RecordEntity>
}

/**
 * Queries over the Sound Lab.
 *
 * The bundled assets are seeded on first launch and matched by their resource name from then on, so
 * updating the app does not duplicate them and does not overwrite a user's decision to switch one off.
 */
@Dao
interface AudioAssetDao {

    @Query("SELECT * FROM audio_assets ORDER BY isBundled DESC, category ASC, title ASC")
    fun observeAll(): Flow<List<AudioAssetEntity>>

    @Query("SELECT * FROM audio_assets WHERE category = :category ORDER BY title ASC")
    fun observeByCategory(category: String): Flow<List<AudioAssetEntity>>

    @Query("SELECT * FROM audio_assets WHERE id = :id")
    suspend fun findById(id: String): AudioAssetEntity?

    @Query("SELECT * FROM audio_assets WHERE bundledResourceName = :resource LIMIT 1")
    suspend fun findByResource(resource: String): AudioAssetEntity?

    @Query("SELECT * FROM audio_assets")
    suspend fun all(): List<AudioAssetEntity>

    @Upsert
    suspend fun upsert(entity: AudioAssetEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AudioAssetEntity>)

    @Query("UPDATE audio_assets SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query(
        """
        UPDATE audio_assets SET
            trimStartMilliseconds = :startMs,
            trimEndMilliseconds = :endMs,
            defaultVolume = :volume
        WHERE id = :id
        """,
    )
    suspend fun updateTrim(id: String, startMs: Long, endMs: Long, volume: Float)

    @Query("UPDATE audio_assets SET durationMilliseconds = :durationMs, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun updateMeasured(id: String, durationMs: Long, sizeBytes: Long)

    @Query("DELETE FROM audio_assets WHERE id = :id AND isBundled = 0")
    suspend fun deleteImported(id: String): Int

    @Query("SELECT COUNT(*) FROM audio_assets WHERE isBundled = 1")
    suspend fun countBundled(): Int
}
