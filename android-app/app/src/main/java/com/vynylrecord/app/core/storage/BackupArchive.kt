package com.vynylrecord.app.core.storage

import android.util.Log
import com.vynylrecord.app.core.data.json.JsonValue
import com.vynylrecord.app.core.data.repository.AssetRepository
import com.vynylrecord.app.core.data.repository.RecordRepository
import com.vynylrecord.app.core.model.AudioAsset
import com.vynylrecord.app.core.model.Record
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore, as one file the user keeps wherever they like.
 *
 * The database is not in the archive. What is in it is every record's own bundle, every imported asset's
 * audio, and an index that says what they are. That is deliberate: a database dump would restore rows that
 * point at files, and if the files did not come back with it the app would be full of records that cannot
 * play. Restoring *audio* and rebuilding the index from it means a restored backup is a working library by
 * construction — the worst case of a damaged archive is a record missing, not a record broken.
 *
 * ```
 *   index.json                 what is in here, and the app version that wrote it
 *   records/{id}.vynyl         one bundle per record (the same format the share sheet produces)
 *   assets/{assetId}.wav       imported background beds
 *   assets/assets.json         their metadata: titles, trims, levels
 * ```
 *
 * A restore is additive and never destructive: an existing record with the same id is left alone unless the
 * caller asks for a replace, and an asset that already exists is not duplicated.
 */
object BackupArchive {

    const val EXTENSION = "vynylbak"
    const val MIME_TYPE = "application/vnd.vynyl.backup"
    const val INDEX = "index.json"
    const val KIND = "vynyl.backup"
    const val FORMAT_VERSION = 1

    data class Report(
        val records: Int,
        val assets: Int,
        val audioBytes: Long,
        val path: String,
    )

    data class RestoreReport(
        val recordsImported: Int,
        val recordsSkipped: Int,
        val assetsImported: Int,
        val assetsSkipped: Int,
        val failures: List<String>,
    ) {
        val summary: String
            get() = buildString {
                append("$recordsImported record(s) restored")
                if (recordsSkipped > 0) append(", $recordsSkipped already present")
                if (assetsImported > 0) append(", $assetsImported asset(s)")
                if (failures.isNotEmpty()) append(" · ${failures.size} could not be read")
            }
    }

    /** Writes everything into [target]. */
    fun backup(
        target: File,
        records: RecordRepository,
        assets: AssetRepository,
        files: FileStore,
        appVersion: String,
        recordList: List<Record>,
        assetList: List<AudioAsset>,
        includeSources: Boolean,
        onProgress: (Float) -> Unit = {},
    ): Report? {
        val scratch = File(files.renderDirectory("backup-${System.currentTimeMillis()}"))
        var recordsWritten = 0
        var assetsWritten = 0
        var bytes = 0L
        return try {
            StorageLayout.ensureParent(target)
            ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
                val recordEntries = mutableListOf<Pair<String, String>>()

                recordList.forEachIndexed { index, record ->
                    val bundleFile = File(scratch, "${StorageLayout.safeId(record.id)}.$EXTENSION")
                    val result = VynylBundle.write(
                        target = bundleFile,
                        record = record,
                        appVersion = appVersion,
                        masterFile = records.resolveMaster(record),
                        sourceFile = records.resolveSource(record),
                        artworkFile = records.resolveArtwork(record),
                        waveformFile = files.waveformFile(record.id).takeIf { it.isFile },
                        includeSource = includeSources,
                    )
                    when (result) {
                        is VynylBundle.BundleResult.Written -> {
                            val entryName = "records/${StorageLayout.safeId(record.id)}.$EXTENSION"
                            copyInto(zip, entryName, bundleFile)
                            bytes += bundleFile.length()
                            recordsWritten++
                            recordEntries += record.id to entryName
                        }

                        is VynylBundle.BundleResult.Failed -> Log.w(TAG, "skipping ${record.id}: ${result.message}")
                        else -> Unit
                    }
                    bundleFile.delete()
                    onProgress((index + 1).toFloat() / (recordList.size + assetList.size).coerceAtLeast(1))
                }

                val assetEntries = mutableListOf<Map<String, JsonValue>>()
                assetList.forEach { asset ->
                    val file = asset.sourcePath?.let { File(it) } ?: files.assetFile(asset.id, "wav")
                    if (asset.isBundled || !file.isFile) {
                        // Bundled assets are part of the app and are restored with it; only the user's own
                        // imports need to travel, and only their metadata for the bundled ones.
                        assetEntries += assetIndexEntry(asset, includeAudio = false)
                        return@forEach
                    }
                    val extension = file.extension.ifBlank { "wav" }
                    val entryName = "assets/${StorageLayout.safeId(asset.id)}.$extension"
                    copyInto(zip, entryName, file)
                    bytes += file.length()
                    assetsWritten++
                    assetEntries += assetIndexEntry(asset, includeAudio = true, entryName = entryName)
                }

                val index = JsonValue.obj(
                    linkedMapOf(
                        "kind" to JsonValue.of(KIND),
                        "formatVersion" to JsonValue.of(FORMAT_VERSION),
                        "appVersion" to JsonValue.of(appVersion),
                        "createdAt" to JsonValue.of(System.currentTimeMillis()),
                        "includesSources" to JsonValue.of(includeSources),
                        "records" to JsonValue.arr(
                            recordEntries.map { (id, entry) ->
                                JsonValue.obj(linkedMapOf("id" to JsonValue.of(id), "entry" to JsonValue.of(entry)))
                            },
                        ),
                        "assets" to JsonValue.arr(assetEntries.map { JsonValue.obj(it) }),
                    ),
                )
                zip.putNextEntry(ZipEntry(INDEX))
                zip.write(index.write(pretty = true).toByteArray())
                zip.closeEntry()

                onProgress(1f)
                Report(
                    records = recordsWritten,
                    assets = assetsWritten,
                    audioBytes = bytes,
                    path = target.absolutePath,
                )
            }
        } catch (error: IOException) {
            Log.w(TAG, "backup failed", error)
            target.delete()
            null
        } finally {
            StorageLayout.deleteTree(scratch)
        }
    }

    /** Reads an archive back into the app's storage. */
    fun restore(
        archive: File,
        records: RecordRepository,
        assets: AssetRepository,
        files: FileStore,
        onProgress: (Float) -> Unit = {},
    ): RestoreReport {
        val staging = File(files.renderDirectory("restore-${System.currentTimeMillis()}"))
        val failures = mutableListOf<String>()
        var recordsImported = 0
        var recordsSkipped = 0
        var assetsImported = 0
        var assetsSkipped = 0

        try {
            var total = 0L
            val bundles = mutableListOf<File>()
            val assetFiles = mutableListOf<Pair<String, File>>()

            ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!VynylBundle.isSafeEntryName(name)) {
                        failures += "an entry with an unsafe name was skipped"
                        zip.closeEntry()
                        continue
                    }
                    val target = File(staging, StorageLayout.safeId(name.replace('/', '_')))
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    StorageLayout.ensureParent(target)
                    var written = 0L
                    FileOutputStream(target).buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            written += read
                            total += read
                            if (written > VynylBundle.MAX_ENTRY_BYTES || total > MAX_ARCHIVE_BYTES) {
                                throw IOException("archive expands beyond the size limit")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    when {
                        name.startsWith("records/") && name.endsWith(".$EXTENSION") -> bundles += target
                        name.startsWith("assets/") && !name.endsWith(".json") -> assetFiles += name to target
                    }
                    zip.closeEntry()
                }
            }

            val index = File(staging, StorageLayout.safeId(INDEX.replace('/', '_')))
            if (!index.isFile) {
                return RestoreReport(0, 0, 0, 0, listOf("that file is not a Vynyl backup"))
            }
            val document = JsonValue.parse(index.readText())?.asObjOrNull()
            if (document == null || document.string("kind") != KIND) {
                return RestoreReport(0, 0, 0, 0, listOf("that backup's index is not a Vynyl index"))
            }

            bundles.forEachIndexed { indexPosition, bundle ->
                val directory = RecordImport.directoryFor(records, files)
                when (val result = VynylBundle.read(bundle, directory)) {
                    is VynylBundle.BundleResult.Read -> {
                        val existing = records.find(result.contents.manifest.record.id)
                        if (existing != null) {
                            // Restoring is additive: a record that is already here is not overwritten, because
                            // the one on the device may have been re-pressed since the backup was taken.
                            StorageLayout.deleteTree(directory)
                            recordsSkipped++
                        } else {
                            RecordImport.commit(records, files, result.contents)
                            recordsImported++
                        }
                    }

                    is VynylBundle.BundleResult.Failed -> {
                        failures += result.message
                        StorageLayout.deleteTree(directory)
                    }

                    else -> Unit
                }
                onProgress(((indexPosition + 1).toFloat() / (bundles.size + assetFiles.size).coerceAtLeast(1)))
            }

            // The archive's index is what knows an asset's title, trim and level; the entry name only knows
            // its id. Reading them here is what makes a restored bed the one the user had.
            val assetIndex = document.array("assets")?.items
                ?.mapNotNull { it.asObjOrNull() }
                ?.associateBy { it.string("id") }
                ?: emptyMap()

            assetFiles.forEach { (name, file) ->
                val id = name.substringAfterLast('/').substringBeforeLast('.')
                val metadata = assetIndex[id]
                val existing = assets.find(id)
                if (existing != null) {
                    assetsSkipped++
                } else if (
                    assets.importRestoredAsset(
                        id = id,
                        extracted = file,
                        extension = name.substringAfterLast('.'),
                        title = metadata?.string("title") ?: "",
                        trimStartMs = metadata?.long("trimStart") ?: 0L,
                        trimEndMs = metadata?.long("trimEnd") ?: 0L,
                        volume = metadata?.float("defaultVolume") ?: 0.18f,
                        durationMs = metadata?.long("durationMilliseconds") ?: 0L,
                    )
                ) {
                    assetsImported++
                } else {
                    failures += "an asset could not be restored"
                }
                file.delete()
            }

            onProgress(1f)
            return RestoreReport(recordsImported, recordsSkipped, assetsImported, assetsSkipped, failures)
        } catch (error: Exception) {
            Log.w(TAG, "restore failed", error)
            return RestoreReport(recordsImported, recordsSkipped, assetsImported, assetsSkipped, failures + "the archive could not be read")
        } finally {
            StorageLayout.deleteTree(staging)
        }
    }

    private fun assetIndexEntry(asset: AudioAsset, includeAudio: Boolean, entryName: String? = null): Map<String, JsonValue> =
        linkedMapOf<String, JsonValue>(
            "id" to JsonValue.of(asset.id),
            "title" to JsonValue.of(asset.title),
            "category" to JsonValue.of(asset.category.id),
            "bundled" to JsonValue.of(asset.isBundled),
            "enabled" to JsonValue.of(asset.enabled),
            "defaultVolume" to JsonValue.of(asset.defaultVolume),
            "trimStart" to JsonValue.of(asset.trimStartMilliseconds),
            "trimEnd" to JsonValue.of(asset.trimEndMilliseconds),
            "durationMilliseconds" to JsonValue.of(asset.durationMilliseconds),
            "includesAudio" to JsonValue.of(includeAudio),
            "entry" to JsonValue.of(entryName ?: ""),
        )

    private fun copyInto(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
    }

    /** The largest an archive may expand to when restoring. */
    const val MAX_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L

    private const val TAG = "VynylBackup"
}
