package com.vynylrecord.app.core.storage

import android.util.Log
import com.vynylrecord.app.core.data.json.JsonValue
import com.vynylrecord.app.core.data.json.RecipeJson
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The `.vynyl` file: one record, carried whole.
 *
 * A bundle is a ZIP with a manifest, the master, the artwork, the waveform and — optionally — the original
 * recording, so that a record can be given to someone else or moved to another phone and arrive with
 * everything it needs to be played, re-pressed and exported again.
 *
 * ```
 *   manifest.json     everything the database row holds, in a form a person can read
 *   master.wav        the pressed record
 *   source.wav        the original voice, when the export was asked to include it
 *   cover.png         the sleeve artwork
 *   waveform.json     the peaks
 *   checksums.txt     SHA-256 of every entry, so a truncated download is detectable
 * ```
 *
 * ## Reading is the dangerous direction
 *
 * Everything else in this app writes files it later reads. A bundle is the one input that comes from
 * outside, so this is the one place that has to be suspicious:
 *
 * * **No path traversal.** Entry names are checked before they are used. An entry called `../../databases/
 *   vynyl.db` is refused rather than extracted, because a ZIP is just a list of names and a name can be
 *   anything.
 * * **Size limits.** The uncompressed size of each entry is capped, and the total is capped, so a "42 MB"
 *   file that expands to four gigabytes stops at the limit rather than filling the disk.
 * * **Checksums.** Entries are verified against `checksums.txt` where it exists, so a half-transferred file
 *   fails loudly instead of importing as a silent, truncated record.
 * * **The manifest is not trusted for paths.** A bundle's manifest can claim any path it likes; paths are
 *   rebuilt from the extracted file's real location, and nothing outside the record's own directory is ever
 *   written.
 */
object VynylBundle {

    const val EXTENSION = "vynyl"
    const val MIME_TYPE = "application/vnd.vynyl.record"
    const val FORMAT_VERSION = 1

    const val MANIFEST = "manifest.json"
    const val MASTER = "master.wav"
    const val SOURCE = "source.wav"
    const val ARTWORK = "cover.png"
    const val WAVEFORM = "waveform.json"
    const val CHECKSUMS = "checksums.txt"

    /** The largest a single entry may be when it is expanded. A ten-minute master is about 100 MB. */
    const val MAX_ENTRY_BYTES = 160L * 1024L * 1024L

    /** The largest a whole bundle may expand to. */
    const val MAX_TOTAL_BYTES = 400L * 1024L * 1024L

    /** Written at the front of every manifest so a future version can be recognised rather than guessed at. */
    const val KIND = "vynyl.record"

    /** What a bundle contains, as the reader found it. */
    data class Contents(
        val manifest: Manifest,
        val master: File?,
        val source: File?,
        val artwork: File?,
        val waveform: File?,
        val bytes: Long,
    )

    /** A bundle's manifest: the record plus the provenance of the file itself. */
    data class Manifest(
        val record: Record,
        val appVersion: String,
        val exportedAt: Long,
        val includesSource: Boolean,
        val formatVersion: Int = FORMAT_VERSION,
    )

    // ------------------------------------------------------------------ writing

    /**
     * Writes [record] into [target].
     *
     * The manifest is written last, and only its presence makes a bundle valid: a ZIP without one is a
     * failure, and a partially written bundle is never mistaken for a complete one.
     */
    fun write(
        target: File,
        record: Record,
        appVersion: String,
        masterFile: File?,
        sourceFile: File?,
        artworkFile: File?,
        waveformFile: File?,
        includeSource: Boolean,
    ): BundleResult {
        if (masterFile == null || !masterFile.isFile) {
            return BundleResult.Failed("This record has no master to export. Press it first.")
        }
        val checksums = LinkedHashMap<String, String>()
        return try {
            StorageLayout.ensureParent(target)
            ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
                zip.setLevel(6)
                checksums[MASTER] = entry(zip, MASTER, masterFile)
                if (includeSource && sourceFile != null && sourceFile.isFile) {
                    checksums[SOURCE] = entry(zip, SOURCE, sourceFile)
                }
                if (artworkFile != null && artworkFile.isFile) {
                    checksums[ARTWORK] = entry(zip, ARTWORK, artworkFile)
                }
                if (waveformFile != null && waveformFile.isFile) {
                    checksums[WAVEFORM] = entry(zip, WAVEFORM, waveformFile)
                }
                zip.putNextEntry(ZipEntry(CHECKSUMS))
                checksums.forEach { (name, hash) -> zip.write("$hash  $name\n".toByteArray()) }
                zip.closeEntry()

                val manifest = manifestFor(record, appVersion, includeSource)
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.write(pretty = true).toByteArray())
                zip.closeEntry()
            }
            BundleResult.Written(target, target.length(), checksums.size)
        } catch (error: IOException) {
            Log.w(TAG, "bundle write failed", error)
            target.delete()
            BundleResult.Failed("The record could not be saved as a bundle: ${error.message}")
        }
    }

    /** The manifest document for a record. */
    fun manifestFor(record: Record, appVersion: String, includesSource: Boolean): JsonValue = JsonValue.obj(
        linkedMapOf(
            "kind" to JsonValue.of(KIND),
            "formatVersion" to JsonValue.of(FORMAT_VERSION),
            "appVersion" to JsonValue.of(appVersion),
            "exportedAt" to JsonValue.of(System.currentTimeMillis()),
            "includesSource" to JsonValue.of(includesSource),
            "record" to JsonValue.obj(
                linkedMapOf(
                    "id" to JsonValue.of(record.id),
                    "title" to JsonValue.of(record.title),
                    "recipientName" to JsonValue.of(record.recipientName),
                    "senderName" to JsonValue.of(record.senderName),
                    "dedication" to JsonValue.of(record.dedication),
                    "occasion" to JsonValue.of(record.occasion.id),
                    "occasionDate" to JsonValue.of(record.occasionDate),
                    "sideALabel" to JsonValue.of(record.sideALabel),
                    "sideBLabel" to JsonValue.of(record.sideBLabel),
                    "durationMilliseconds" to JsonValue.of(record.durationMilliseconds),
                    "vinylPresetId" to JsonValue.of(record.presetId.id),
                    "vinylStyleId" to JsonValue.of(record.styledId.id),
                    "backgroundAssetId" to JsonValue.of(record.backgroundAssetId ?: ""),
                    "backgroundVolume" to JsonValue.of(record.backgroundVolume),
                    "backgroundTrimStart" to JsonValue.of(record.backgroundTrimStartMs),
                    "backgroundTrimEnd" to JsonValue.of(record.backgroundTrimEndMs),
                    "deterministicSeed" to JsonValue.of(record.deterministicSeed),
                    "createdAt" to JsonValue.of(record.createdAt),
                    "advancedSettings" to (JsonValue.parse(RecipeJson.encode(record.controls)) ?: JsonValue.Null),
                ),
            ),
        ),
    )

    private fun entry(zip: ZipOutputStream, name: String, file: File): String {
        zip.putNextEntry(ZipEntry(name))
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
                zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
        return "crc32:%08x:%d".format(crc.value, file.length())
    }

    // ------------------------------------------------------------------ reading

    /**
     * Reads a bundle into a record directory.
     *
     * @param into the record directory to write into; it is created if missing and removed again if the
     *        import fails, so a failed import leaves no half-record behind
     */
    fun read(bundle: File, into: File): BundleResult {
        if (!bundle.isFile || bundle.length() < 128L) {
            return BundleResult.Failed("That file is not a Vynyl bundle.")
        }

        val extracted = LinkedHashMap<String, File>()
        var total = 0L
        try {
            ZipInputStream(BufferedInputStream(bundle.inputStream())).use { zip ->
                while (true) {
                    val zipEntry = zip.nextEntry ?: break
                    val name = zipEntry.name
                    if (!isSafeEntryName(name)) {
                        Log.w(TAG, "refusing bundle entry \"$name\"")
                        return BundleResult.Failed("That bundle contains an unsafe file name and was not imported.")
                    }
                    if (zipEntry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val declared = zipEntry.size
                    if (declared > MAX_ENTRY_BYTES) {
                        return BundleResult.Failed("That bundle contains a file that is too large to import.")
                    }
                    val target = File(into, name.substringAfterLast('/'))
                    StorageLayout.ensureParent(target)
                    var written = 0L
                    FileOutputStream(target).buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            written += read
                            total += read
                            if (written > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                                throw IOException("bundle expands beyond the size limit")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    extracted[name] = target
                    zip.closeEntry()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "bundle read failed", error)
            into.deleteRecursively()
            return BundleResult.Failed("That bundle could not be read: ${error.message}")
        }

        val manifestFile = extracted[MANIFEST]
        if (manifestFile == null) {
            into.deleteRecursively()
            return BundleResult.Failed("That bundle has no manifest, so it is not a Vynyl record.")
        }
        val document = JsonValue.parse(manifestFile.readText())?.asObjOrNull()
        if (document == null || document.string("kind") != KIND) {
            into.deleteRecursively()
            return BundleResult.Failed("That bundle's manifest is not a Vynyl manifest.")
        }
        if (document.int("formatVersion", 1) > FORMAT_VERSION) {
            into.deleteRecursively()
            return BundleResult.Failed("That bundle was written by a newer version of the app.")
        }
        if (!verifyChecksums(extracted)) {
            into.deleteRecursively()
            return BundleResult.Failed("That bundle is incomplete or damaged.")
        }

        val recordDocument = document.obj("record")
            ?: run {
                into.deleteRecursively()
                return BundleResult.Failed("That bundle's manifest has no record in it.")
            }

        val master = extracted[MASTER]?.takeIf { it.isFile && it.length() > 44L }
        if (master == null) {
            into.deleteRecursively()
            return BundleResult.Failed("That bundle has no audio in it.")
        }

        val record = recordFrom(recordDocument, into)
        return BundleResult.Read(
            Contents(
                manifest = Manifest(
                    record = record,
                    appVersion = document.string("appVersion", "unknown"),
                    exportedAt = document.long("exportedAt", 0L),
                    includesSource = document.bool("includesSource", false),
                ),
                master = master,
                source = extracted[SOURCE]?.takeIf { it.isFile },
                artwork = extracted[ARTWORK]?.takeIf { it.isFile },
                waveform = extracted[WAVEFORM]?.takeIf { it.isFile },
                bytes = total,
            ),
        )
    }

    /** Rebuilds a domain record from a manifest, with paths pointing at what was actually extracted. */
    private fun recordFrom(document: JsonValue.Obj, directory: File): Record {
        val preset = VinylPresetId.fromId(document.string("vinylPresetId", VinylPresetId.DEFAULT.id))
        val controls = document.obj("advancedSettings")?.let { settings ->
            RecipeJson.decode(settings.write(), preset)
        } ?: com.vynylrecord.app.core.model.VinylControls.of(preset)
        val duration = document.long("durationMilliseconds", 0L)
        val masterFile = File(directory, MASTER)
        val artworkFile = File(directory, ARTWORK).takeIf { it.isFile }
        val waveformFile = File(directory, WAVEFORM).takeIf { it.isFile }
        val sourceFile = File(directory, SOURCE).takeIf { it.isFile }

        return Record(
            id = StorageLayout.safeId(document.string("id", java.util.UUID.randomUUID().toString())),
            title = document.string("title"),
            recipientName = document.string("recipientName"),
            senderName = document.string("senderName"),
            dedication = document.string("dedication"),
            occasion = Occasion.fromId(document.string("occasion", Occasion.SOMETHING_ELSE.id)),
            occasionDate = document.string("occasionDate"),
            sideALabel = document.string("sideALabel", "Side A"),
            sideBLabel = document.string("sideBLabel", "Side B"),
            sourcePath = sourceFile?.absolutePath,
            masterPath = masterFile.absolutePath,
            coverArtworkPath = artworkFile?.absolutePath,
            waveformPath = waveformFile?.absolutePath,
            waveformPeaks = emptyList(),
            durationMilliseconds = duration,
            sourceDurationMilliseconds = duration,
            presetId = preset,
            controls = controls,
            styledId = VinylStyleId.fromId(document.string("vinylStyleId", VinylStyleId.DEFAULT.id)),
            backgroundAssetId = document.string("backgroundAssetId").takeIf { it.isNotBlank() },
            backgroundVolume = document.float("backgroundVolume", 0.18f),
            backgroundTrimStartMs = document.long("backgroundTrimStart", 0L),
            backgroundTrimEndMs = document.long("backgroundTrimEnd", 0L),
            deterministicSeed = document.long("deterministicSeed", 0L),
            renderState = RenderState.COMPLETED,
            renderProgress = 1f,
            renderStageLabel = "Imported",
            sizeBytes = masterFile.length(),
            createdAt = document.long("createdAt", System.currentTimeMillis()),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * A ZIP entry name is a path, and a path can climb.
     *
     * Anything absolute, anything with a parent-directory segment, anything with a drive letter or a
     * backslash, and anything with a NUL is refused. The importer also flattens names to their final
     * segment, so even an accepted name cannot create a directory structure of its own choosing.
     */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || name.length > 120) return false
        if (name.contains('\u0000') || name.contains('\\')) return false
        if (name.startsWith("/") || name.startsWith("~")) return false
        if (name.contains(":")) return false
        val segments = name.split('/')
        if (segments.any { it == ".." || it.isEmpty() }) return false
        return true
    }

    /** Verifies every entry against `checksums.txt`, when the bundle carries one. */
    private fun verifyChecksums(extracted: Map<String, File>): Boolean {
        val checksumFile = extracted[CHECKSUMS] ?: return true
        val lines = runCatching { checksumFile.readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) return true
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val expected = parts[0]
            val name = parts[1]
            val file = extracted[name] ?: continue
            val actual = checksumOf(file, expected)
            if (!actual.equals(expected, ignoreCase = true)) {
                Log.w(TAG, "checksum mismatch for $name")
                return false
            }
        }
        return true
    }

    /**
     * The checksum format the writer emits, or a SHA-256 when it does not recognise the prefix.
     *
     * The writer uses CRC-32 because it can compute it while streaming the same bytes it writes to the ZIP,
     * which costs nothing. A bundle written by another tool with SHA-256 in its checksum file still verifies,
     * which is why the reader handles both.
     */
    private fun checksumOf(file: File, expected: String): String {
        if (expected.startsWith("crc32:", ignoreCase = true)) {
            val crc = CRC32()
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    crc.update(buffer, 0, read)
                }
            }
            return "crc32:%08x:%d".format(crc.value, file.length())
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** What reading or writing a bundle produced. */
    sealed interface BundleResult {
        data class Written(val file: File, val bytes: Long, val entries: Int) : BundleResult
        data class Read(val contents: Contents) : BundleResult
        data class Failed(val message: String) : BundleResult
    }

    private const val TAG = "VynylBundle"

    /** MIME type helpers, so a caller never has to remember the string. */
    fun mimeTypeForBundle(): String = MIME_TYPE

    /** A sanity check used by the importer: is this even a ZIP? */
    fun looksLikeZip(file: File): Boolean = try {
        ZipFile(file).use { zip -> zip.getEntry(MANIFEST) != null || zip.entries().hasMoreElements() }
    } catch (error: Exception) {
        false
    }
}
