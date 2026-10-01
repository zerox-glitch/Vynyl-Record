package com.vynylrecord.app.core.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * File operations the app performs on itself: staging, atomic moves, exports and deletions.
 *
 * Two rules run through all of it:
 *
 * 1. **A file is only ever visible in its final place once it is complete.** Renders write into the
 *    cache and are renamed into place. A rename inside one filesystem is atomic, so a reader can never
 *    find a half-written master where a finished one should be, and a process killed mid-render leaves
 *    a stale cache directory rather than a broken record.
 *
 * 2. **Nothing is deleted until the thing that replaced it is on disk.** A re-render writes the new
 *    master first and removes the old one afterwards, so a failure leaves the previous version intact.
 */
class FileStore(private val context: Context) {

    val filesRoot: File get() = StorageLayout.filesRoot(context)
    val cacheRoot: File get() = StorageLayout.cacheRoot(context)

    fun recordsRoot(): File = StorageLayout.recordsRoot(filesRoot).apply { mkdirs() }
    fun assetsRoot(): File = StorageLayout.assetsRoot(filesRoot).apply { mkdirs() }

    fun recordDirectory(recordId: String): File = StorageLayout.recordDirectory(filesRoot, recordId)
    fun sourceFile(recordId: String): File = StorageLayout.sourceFile(filesRoot, recordId)
    fun masterFile(recordId: String): File = StorageLayout.masterFile(filesRoot, recordId)
    fun artworkFile(recordId: String): File = StorageLayout.artworkFile(filesRoot, recordId)
    fun waveformFile(recordId: String): File = StorageLayout.waveformFile(filesRoot, recordId)
    fun assetFile(assetId: String, extension: String = "wav"): File = StorageLayout.assetFile(filesRoot, assetId, extension)
    fun exportsRoot(): File = StorageLayout.exportsRoot(filesRoot).apply { mkdirs() }
    fun renderDirectory(jobId: String): File = StorageLayout.renderDirectory(cacheRoot, jobId).apply { mkdirs() }
    fun stagingRoot(): File = StorageLayout.stagingRoot(cacheRoot).apply { mkdirs() }
    fun decodedRoot(): File = StorageLayout.decodedRoot(cacheRoot).apply { mkdirs() }
    fun sharedRoot(): File = StorageLayout.sharedRoot(cacheRoot).apply { mkdirs() }

    fun ensureRecordDirectory(recordId: String): File = recordDirectory(recordId).apply { mkdirs() }

    /** Creates an empty file in the record's directory, ready for a capture or an import. */
    fun createSourceFile(recordId: String): File = StorageLayout.ensureParent(sourceFile(recordId))

    // ------------------------------------------------------------------ atomic placement

    /**
     * Moves a finished file into its permanent home, replacing whatever was there.
     *
     * If the rename fails because the two locations are on different filesystems — which happens when a
     * device mounts the cache apart from the data partition — the file is copied and then the copy is
     * verified by size before the source is removed.
     */
    fun placeAtomically(from: File, to: File): Boolean {
        if (!from.isFile || from.length() <= 0L) return false
        StorageLayout.ensureParent(to)
        if (to.exists()) {
            val backup = File(to.parentFile, "${to.name}.old")
            backup.delete()
            to.renameTo(backup)
            if (from.renameTo(to)) {
                backup.delete()
                return true
            }
            backup.renameTo(to)
        } else if (from.renameTo(to)) {
            return true
        }
        return try {
            from.copyTo(to, overwrite = true)
            if (to.length() == from.length()) {
                from.delete()
                true
            } else {
                to.delete()
                false
            }
        } catch (error: IOException) {
            Log.w(TAG, "could not place ${from.name}", error)
            false
        }
    }

    /** A scratch file for this render, inside its own job directory. */
    fun scratch(jobId: String, name: String): File = StorageLayout.ensureParent(File(renderDirectory(jobId), name))

    /** Cleans up after a render, whether it finished or not. */
    fun discardJob(jobId: String) {
        val directory = StorageLayout.renderDirectory(cacheRoot, jobId)
        if (directory.exists()) StorageLayout.deleteTree(directory)
    }

    /** Removes every abandoned render scratch directory. Called when the app starts. */
    fun sweepStaleRenders(): Int {
        val root = File(cacheRoot, StorageLayout.RENDER)
        val children = root.listFiles() ?: return 0
        var removed = 0
        val cutoff = System.currentTimeMillis() - STALE_RENDER_MS
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.lastModified() < cutoff) {
                StorageLayout.deleteTree(child)
                removed++
            }
        }
        return removed
    }

    // ------------------------------------------------------------------ exports and sharing

    /**
     * Writes a file into the exports directory under a name a person can recognise.
     *
     * Exports are kept rather than handed out immediately: the share sheet gets a fresh copy, but the
     * file also stays in `files/exports` so "Export" a second time does not re-render anything, and so a
     * user who dismissed the share sheet can find the file again from Settings.
     */
    fun exportTarget(recordTitle: String, suffix: String, extension: String): File {
        val base = safeFileName(recordTitle).ifBlank { "vynyl-record" }
        val stem = if (suffix.isBlank()) base else "$base-$suffix"
        var candidate = File(exportsRoot(), "$stem.$extension")
        var counter = 2
        while (candidate.exists() && candidate.length() > 0L) {
            candidate = File(exportsRoot(), "$stem-$counter.$extension")
            counter++
            if (counter > 99) break
        }
        return StorageLayout.ensureParent(candidate)
    }

    /** A file name that survives every filesystem this app writes to. */
    fun safeFileName(title: String): String = title
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim('.', ' ')
        .take(60)

    /** A `content://` Uri the share sheet can read, through the app's own FileProvider. */
    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Copies [file] into the shared cache under a friendly name and returns the Uri.
     *
     * Used when the name matters to the receiving app — a messaging client shows the file name, and
     * `master.wav` is a worse name to receive than `For Ammi - Side A.wav`.
     */
    fun stageForSharing(file: File, displayName: String): Uri {
        val target = StorageLayout.ensureParent(File(sharedRoot(), displayName))
        if (target.exists()) target.delete()
        file.copyTo(target, overwrite = true)
        return shareUri(target)
    }

    /** Copies an export to a destination the user chose through the system picker. */
    fun copyToDocument(source: File, destination: Uri): Boolean = try {
        val output: OutputStream = context.contentResolver.openOutputStream(destination, "wt")
            ?: return false
        output.use { stream -> source.inputStream().buffered().use { input -> input.copyTo(stream) } }
        true
    } catch (error: Exception) {
        Log.w(TAG, "could not write to the chosen destination", error)
        false
    }

    /** Copies a document the user picked into the app, returning the staged file. */
    fun copyFromDocument(source: Uri, destination: File): Boolean = try {
        StorageLayout.ensureParent(destination)
        val input: InputStream = context.contentResolver.openInputStream(source) ?: return false
        input.use { stream -> destination.outputStream().buffered().use { stream.copyTo(it) } }
        destination.length() > 0L
    } catch (error: Exception) {
        Log.w(TAG, "could not read the chosen document", error)
        false
    }

    // ------------------------------------------------------------------ deletion

    /**
     * Deletes a record's files.
     *
     * The database row is removed only after this succeeds, so a record is never a row pointing at
     * nothing. A failed delete leaves a directory behind, which the Settings screen shows and a retry can
     * clear — the opposite order would silently lose audio.
     */
    fun deleteRecordFiles(recordId: String): Boolean {
        val directory = recordDirectory(recordId)
        if (!directory.exists()) return true
        val removed = directory.deleteRecursively()
        if (!removed) Log.w(TAG, "record directory ${directory.name} could not be fully removed")
        return removed
    }

    fun deleteAssetFile(assetId: String, extension: String = "wav"): Boolean {
        val file = assetFile(assetId, extension)
        return !file.exists() || file.delete()
    }

    /** Storage figures for the Settings panel. */
    fun usage(): StorageUsage = StorageUsage(
        recordsBytes = StorageLayout.sizeOf(StorageLayout.recordsRoot(filesRoot)),
        assetsBytes = StorageLayout.sizeOf(StorageLayout.assetsRoot(filesRoot)),
        exportsBytes = StorageLayout.sizeOf(StorageLayout.exportsRoot(filesRoot)),
        renderCacheBytes = StorageLayout.renderCacheBytes(cacheRoot),
        assetCacheBytes = StorageLayout.assetCacheBytes(cacheRoot),
        freeBytes = runCatching { filesRoot.usableSpace }.getOrDefault(0L),
    )

    fun clearCaches(): Long = StorageLayout.clearCache(cacheRoot)

    private companion object {
        const val TAG = "VynylFileStore"

        /** A render directory older than this was abandoned by a process that died. */
        const val STALE_RENDER_MS = 6L * 60L * 60L * 1000L
    }
}

/** What the app is using, in bytes. */
data class StorageUsage(
    val recordsBytes: Long,
    val assetsBytes: Long,
    val exportsBytes: Long,
    val renderCacheBytes: Long,
    val assetCacheBytes: Long,
    val freeBytes: Long,
) {
    val cacheBytes: Long get() = renderCacheBytes + assetCacheBytes
    val appBytes: Long get() = recordsBytes + assetsBytes + exportsBytes + cacheBytes

    val recordsLabel: String get() = formatBytes(recordsBytes)
    val assetsLabel: String get() = formatBytes(assetsBytes)
    val exportsLabel: String get() = formatBytes(exportsBytes)
    val cacheLabel: String get() = formatBytes(cacheBytes)
    val freeLabel: String get() = formatBytes(freeBytes)
}

/** Byte counts the way a person reads them. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(Locale.US, bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(Locale.US, bytes / 1_024.0)
    else -> "$bytes B"
}
