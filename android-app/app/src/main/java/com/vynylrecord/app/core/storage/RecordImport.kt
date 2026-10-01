package com.vynylrecord.app.core.storage

import android.util.Log
import com.vynylrecord.app.core.audio.render.Waveform
import com.vynylrecord.app.core.audio.render.WaveformFile
import com.vynylrecord.app.core.audio.render.WaveformExtractor
import com.vynylrecord.app.core.data.repository.RecordRepository
import com.vynylrecord.app.core.model.Record
import java.io.File

/**
 * Turning an extracted bundle into a record the app owns.
 *
 * The importer is where a `.vynyl` file becomes part of the library, and it is careful about two things:
 *
 * * **The directory is chosen here, not by the file.** A bundle's manifest contains an id, and an id could
 *   be anything; the id is sanitised and then used only as a *name* inside the records root, never as a
 *   path.
 * * **A record is only committed once its audio is in place.** The files are moved first, the row is written
 *   second, and a failure between them cleans up the directory. That is the same rule the render pipeline
 *   follows, and it is why a bundle import cannot produce a record that cannot play.
 */
object RecordImport {

    /** A fresh, empty directory for an import, named after the bundle's (sanitised) id. */
    fun directoryFor(records: RecordRepository, files: FileStore): File {
        val id = java.util.UUID.randomUUID().toString()
        return files.ensureRecordDirectory(id)
    }

    /**
     * Moves an imported bundle's files into a record directory and writes the row.
     *
     * @return the record as it was stored, or null when the audio could not be placed
     */
    suspend fun commit(
        records: RecordRepository,
        files: FileStore,
        contents: VynylBundle.Contents,
    ): Record? {
        val incoming = contents.manifest.record
        val id = StorageLayout.safeId(incoming.id)
        val directory = files.ensureRecordDirectory(id)

        val master = contents.master?.takeIf { it.isFile } ?: return null
        if (!files.placeAtomically(master, files.masterFile(id))) {
            Log.w(TAG, "could not place the master for $id")
            return null
        }

        val source = contents.source?.takeIf { it.isFile }
        val sourcePlaced = source != null && files.placeAtomically(source, files.createSourceFile(id))

        val artwork = contents.artwork?.takeIf { it.isFile }
        val artworkPlaced = artwork != null && files.placeAtomically(artwork, files.artworkFile(id))

        // The waveform comes back as a sidecar; if the bundle did not carry one, it is read out of the
        // master instead, so a record imported from a minimal bundle still has its picture in the library.
        var waveform = contents.waveform?.let { WaveformFile.read(it) } ?: Waveform.EMPTY
        if (waveform.isEmpty) {
            waveform = WaveformExtractor().extract(files.masterFile(id))
        }
        if (!waveform.isEmpty) {
            WaveformFile.write(files.waveformFile(id), waveform)
        }

        val record = incoming.copy(
            id = id,
            sourcePath = if (sourcePlaced) files.sourceFile(id).absolutePath else null,
            masterPath = files.masterFile(id).absolutePath,
            coverArtworkPath = if (artworkPlaced) files.artworkFile(id).absolutePath else null,
            waveformPath = if (waveform.isEmpty) null else files.waveformFile(id).absolutePath,
            waveformPeaks = WaveformExtractor().compact(waveform),
            durationMilliseconds = if (incoming.durationMilliseconds > 0L) {
                incoming.durationMilliseconds
            } else {
                waveform.durationMs
            },
            sizeBytes = files.masterFile(id).length(),
            deterministicSeed = if (incoming.deterministicSeed != 0L) {
                incoming.deterministicSeed
            } else {
                com.vynylrecord.app.core.model.Seed.derive(id, incoming.presetId.id)
            },
            updatedAt = System.currentTimeMillis(),
        )
        records.save(record)
        Log.i(TAG, "imported \"${record.displayTitle}\" (${record.durationLabel})")
        return record
    }

    /** True when the directory a bundle would be extracted into has a master in it. */
    fun hasMaster(directory: File): Boolean = File(directory, VynylBundle.MASTER).let { it.isFile && it.length() > 44L }

    private const val TAG = "VynylRecordImport"
}
