package com.vynylrecord.app.core.data.repository

import android.util.Log
import com.vynylrecord.app.core.audio.render.RenderPipeline
import com.vynylrecord.app.core.data.db.RecordDao
import com.vynylrecord.app.core.data.db.RecordEntity
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.RenderStage
import com.vynylrecord.app.core.model.Seed
import com.vynylrecord.app.core.model.VinylControls
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.storage.FileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * The Vault.
 *
 * This is the only place that writes a record row, and it is deliberately strict about the one rule that
 * matters: **a record is only marked complete once its master exists on disk and has been verified**.
 * Every other state transition is cheap to get wrong; that one is not, because a record in the Vault is a
 * promise that there is something to play.
 *
 * Files and rows are kept in step by ordering: files are written first, then the row. A crash between the
 * two leaves an orphaned file — which the startup sweep cleans up — rather than a row pointing at
 * nothing, which the user would see as a broken record forever.
 */
class RecordRepository(
    private val dao: RecordDao,
    private val files: FileStore,
) {

    val records: Flow<List<Record>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    val activeRenders: Flow<List<Record>> = dao.observeActiveRenders().map { rows -> rows.map { it.toModel() } }

    fun observe(id: String): Flow<Record?> = dao.observeById(id).map { it?.toModel() }

    suspend fun find(id: String): Record? = dao.findById(id)?.toModel()

    suspend fun count(): Int = dao.countCompleted()

    suspend fun totalDurationMs(): Long = dao.totalRecordedMilliseconds()

    suspend fun totalBytes(): Long = dao.totalBytes()

    /**
     * Creates a new record from a captured or imported source file.
     *
     * The source is moved into `files/records/{id}/source.wav` before the row exists, so a draft is always
     * a record whose audio is already safe. A draft whose user never finishes it still has its memory in
     * it, and the Vault shows drafts as drafts rather than hiding them.
     */
    suspend fun createDraft(
        capturedSource: File,
        title: String,
        preset: VinylPresetId = VinylPresetId.DEFAULT,
        style: VinylStyleId = VinylStyleId.DEFAULT,
        occasion: Occasion = Occasion.SOMETHING_ELSE,
        senderName: String = "",
        defaultMusicLevel: Float = 0.18f,
    ): Record {
        val id = java.util.UUID.randomUUID().toString()
        files.ensureRecordDirectory(id)
        val target = files.createSourceFile(id)
        if (!capturedSource.absolutePath.equals(target.absolutePath)) {
            if (capturedSource.renameTo(target).not()) {
                files.placeAtomically(capturedSource, target)
            }
        }
        capturedSource.delete()

        val durationMs = readDurationMs(target)
        val record = Record(
            id = id,
            title = title,
            occasion = occasion,
            senderName = senderName,
            sourcePath = target.absolutePath,
            sourceDurationMilliseconds = durationMs,
            durationMilliseconds = durationMs,
            presetId = preset,
            controls = VinylControls.of(preset),
            styledId = style,
            backgroundVolume = defaultMusicLevel,
            deterministicSeed = Seed.derive(id, preset.id),
            renderState = RenderState.DRAFT,
            renderStageLabel = "Draft",
        )
        dao.upsert(RecordEntity.from(record))
        Log.i(TAG, "draft $id created from ${target.length()} bytes")
        return record
    }

    /** Saves metadata changes from the Studio's details step. */
    suspend fun save(record: Record): Record {
        val stamped = record.copy(updatedAt = System.currentTimeMillis())
        dao.upsert(RecordEntity.from(stamped))
        return stamped
    }

    suspend fun setFavourite(id: String, favourite: Boolean) {
        dao.updateFavourite(id, favourite, System.currentTimeMillis())
    }

    suspend fun toggleFavourite(id: String): Boolean {
        val current = dao.findById(id)?.favourite ?: false
        setFavourite(id, !current)
        return !current
    }

    suspend fun markPlayed(id: String) = dao.markPlayed(id, System.currentTimeMillis())

    /** Marks a record for the press queue. The work itself is enqueued by the render coordinator. */
    suspend fun markQueued(id: String, jobId: String) {
        dao.updateRenderStatus(
            id = id,
            state = RenderState.QUEUED.id,
            progress = 0f,
            stageLabel = RenderStage.PREPARING.label,
            jobId = jobId,
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun markRendering(id: String, jobId: String, stage: RenderStage, progress: Float) {
        dao.updateRenderStatus(
            id = id,
            state = RenderState.RENDERING.id,
            progress = progress.coerceIn(0f, 1f),
            stageLabel = stage.label,
            jobId = jobId,
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun markFailed(id: String, message: String) {
        dao.updateRenderStatus(
            id = id,
            state = RenderState.FAILED.id,
            progress = 0f,
            stageLabel = "Press failed",
            jobId = null,
            error = message,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun markCancelled(id: String) {
        dao.updateRenderStatus(
            id = id,
            state = RenderState.CANCELED.id,
            progress = 0f,
            stageLabel = "Press cancelled",
            jobId = null,
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Commits a finished press.
     *
     * Called only with an [RenderPipeline.Outcome], which cannot exist unless the master was written and
     * read back successfully — so this function does not have to wonder whether the file is real.
     */
    suspend fun commitRender(outcome: RenderPipeline.Outcome): Record {
        val record = outcome.record
        dao.updateMaster(
            id = record.id,
            masterPath = record.masterPath,
            artworkPath = record.coverArtworkPath,
            waveformPath = record.waveformPath,
            waveformSamples = RecordEntity.encodePeaks(record.waveformPeaks),
            durationMs = record.durationMilliseconds,
            sizeBytes = record.sizeBytes,
            updatedAt = System.currentTimeMillis(),
        )
        dao.updateRenderStatus(
            id = record.id,
            state = RenderState.COMPLETED.id,
            progress = 1f,
            stageLabel = RenderStage.COMPLETE.label,
            jobId = null,
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
        return dao.findById(record.id)?.toModel() ?: record
    }

    /** Copies a record, audio and all, as a fresh draft. */
    suspend fun duplicate(id: String): Record? {
        val source = dao.findById(id)?.toModel() ?: return null
        val copyId = java.util.UUID.randomUUID().toString()
        files.ensureRecordDirectory(copyId)

        val base = source.copy(
            id = copyId,
            title = if (source.title.isBlank()) "" else "${source.title} (copy)",
            sourcePath = null,
            masterPath = null,
            coverArtworkPath = null,
            waveformPath = null,
            waveformPeaks = emptyList(),
            sizeBytes = 0L,
            renderState = RenderState.DRAFT,
            renderProgress = 0f,
            renderStageLabel = "Draft",
            renderError = null,
            renderJobId = null,
            favourite = false,
            lastPlayedAt = 0L,
            playCount = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            deterministicSeed = Seed.derive(copyId, source.presetId.id),
        )

        val sourceFile = files.sourceFile(id)
        val duplicate = if (sourceFile.isFile && sourceFile.length() > 44L) {
            sourceFile.copyTo(files.createSourceFile(copyId), overwrite = true)
            base.copy(sourcePath = files.sourceFile(copyId).absolutePath)
        } else {
            // Nothing to re-press from, but the finished record is still the user's: the copy carries the
            // master so it can be played, exported and shared, just not pressed again.
            base.copy(
                masterPath = source.masterPath,
                coverArtworkPath = source.coverArtworkPath,
                waveformPath = source.waveformPath,
                waveformPeaks = source.waveformPeaks,
                durationMilliseconds = source.durationMilliseconds,
                sizeBytes = source.sizeBytes,
                renderState = if (source.masterPath != null) RenderState.COMPLETED else RenderState.DRAFT,
            )
        }

        dao.upsert(RecordEntity.from(duplicate))
        return duplicate
    }

    /**
     * Deletes a record, its audio and everything derived from it.
     *
     * Files first, row second: if the file delete fails there is still a row, and the user can try again.
     * The reverse order would leave audio on disk with nothing pointing at it.
     */
    suspend fun delete(id: String): Boolean {
        val removed = files.deleteRecordFiles(id)
        if (removed) dao.deleteById(id)
        return removed
    }

    /** Duplicates a record's own folder for a re-render, so the old master survives until the new one works. */
    suspend fun backupMasterForRerender(id: String): File? {
        val master = files.masterFile(id)
        if (!master.isFile) return null
        val backup = File(master.parentFile, "master.previous.wav")
        return try {
            master.copyTo(backup, overwrite = true)
            backup
        } catch (error: Exception) {
            Log.w(TAG, "could not keep the previous master", error)
            null
        }
    }

    /**
     * The startup sweep.
     *
     * Two jobs. Records that claim to be complete but whose master is gone are marked failed with an
     * explanation — a record that says it is ready and then cannot play is the worst possible state to
     * leave a user in. Records still marked queued or rendering from a process that was killed are reset
     * so they can be pressed again, keeping their source.
     */
    suspend fun reconcileOnStartup(): ReconcileReport {
        var missing = 0
        var stranded = 0
        for (entity in dao.all()) {
            val record = entity.toModel()
            when {
                record.renderState == RenderState.COMPLETED -> {
                    val master = resolveMaster(record)
                    if (master == null) {
                        missing++
                        markFailed(record.id, "The master file for this record is missing. Press it again.")
                    } else if (record.sizeBytes != master.length()) {
                        dao.updateMaster(
                            id = record.id,
                            masterPath = master.absolutePath,
                            artworkPath = record.coverArtworkPath,
                            waveformPath = record.waveformPath,
                            waveformSamples = RecordEntity.encodePeaks(record.waveformPeaks),
                            durationMs = record.durationMilliseconds,
                            sizeBytes = master.length(),
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                }

                record.renderState.isRunning -> {
                    stranded++
                    dao.updateRenderStatus(
                        id = record.id,
                        state = RenderState.FAILED.id,
                        progress = 0f,
                        stageLabel = "Press interrupted",
                        jobId = null,
                        error = "The press was interrupted. Start it again when you are ready.",
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
        val swept = files.sweepStaleRenders()
        return ReconcileReport(missingMasters = missing, strandedRenders = stranded, sweptScratchDirectories = swept)
    }

    /** The master file, wherever the row says it is, with a fallback to the canonical location. */
    fun resolveMaster(record: Record): File? = resolve(record.masterPath, files.masterFile(record.id))

    fun resolveSource(record: Record): File? = resolve(record.sourcePath, files.sourceFile(record.id))

    fun resolveArtwork(record: Record): File? = resolve(record.coverArtworkPath, files.artworkFile(record.id))

    private fun resolve(stored: String?, fallback: File): File? {
        val direct = stored?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (direct != null && direct.isFile && direct.length() > 0L) return direct
        return fallback.takeIf { it.isFile && it.length() > 0L }
    }

    private fun readDurationMs(file: File): Long = try {
        com.vynylrecord.app.core.audio.dsp.WavCodec.Reader(file).use { reader ->
            reader.frameCount * 1000L / reader.sampleRate
        }
    } catch (error: Exception) {
        Log.w(TAG, "source duration unavailable", error)
        0L
    }

    /** Orphaned audio: a record directory with no row, which a crash can leave behind. */
    suspend fun orphanedRecordDirectories(): List<File> {
        val known = dao.all().map { it.id }.toSet()
        val children = files.recordsRoot().listFiles() ?: return emptyList()
        return children.filter { it.isDirectory && it.name !in known }
    }

    suspend fun deleteOrphanedDirectories(): Int {
        var removed = 0
        for (directory in orphanedRecordDirectories()) {
            com.vynylrecord.app.core.storage.StorageLayout.deleteTree(directory)
            removed++
        }
        return removed
    }

    private companion object {
        const val TAG = "VynylRecordRepository"
    }
}

/** What the startup sweep found, reported once in Settings rather than as a dialog. */
data class ReconcileReport(
    val missingMasters: Int,
    val strandedRenders: Int,
    val sweptScratchDirectories: Int,
) {
    val isQuiet: Boolean get() = missingMasters == 0 && strandedRenders == 0
}
