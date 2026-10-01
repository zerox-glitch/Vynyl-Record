package com.vynylrecord.turntable.vault

import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.VinylStyle
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID

/**
 * The vault on disk.
 *
 * ```
 *   files/presses/
 *     vyn-20260930-215355-8f31c2/     <- one press, self-contained
 *       press.properties              <- label, style, speed, recipe, provenance
 *       8f31c2.wav                    <- the pressed audio
 * ```
 *
 * There is no database and no index file: the directory listing *is* the index. That is deliberate —
 * a partially written press can never corrupt the records around it, an unreadable record is skipped
 * rather than fatal, and a press can be removed by deleting one folder.
 *
 * Everything here is plain `java.io` and `java.util`, with no Android dependency, so the whole store
 * is unit tested on the JVM against a temporary directory.
 */
class PressStore(private val root: File) {

    /** Creates the vault directory if it is not there yet. Safe to call repeatedly. */
    fun ensureReady(): Boolean = root.isDirectory || root.mkdirs()

    /**
     * Every readable press, newest first.
     *
     * Directories that are missing a description, or whose audio has gone, are skipped: a vault that
     * refuses to open because one record is damaged is worse than a vault with one record missing.
     */
    fun list(): List<Press> {
        val children = root.listFiles() ?: return emptyList()
        return children.asSequence()
            .filter { it.isDirectory }
            .mapNotNull { directory -> read(directory) }
            .sortedByDescending { it.createdAtEpochMs }
            .toList()
    }

    fun find(id: String): Press? = list().firstOrNull { it.id == id }

    /** The audio file of a press. Existence is not guaranteed; [list] filters those out. */
    fun audioFile(press: Press): File = File(directoryFor(press.id), press.fileName)

    fun directoryFor(id: String): File = File(root, id)

    /**
     * Allocates a new press: id, directory and audio file name. Nothing is written yet, so a press
     * that fails to render leaves no trace.
     */
    fun allocate(
        metadata: RecordMetadata,
        style: VinylStyle,
        speed: PlatterSpeed,
        recipe: PressRecipe,
        sourceLabel: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        durationMs: Long = 0L,
    ): Press {
        val stamp = java.util.Calendar.getInstance().let { calendar ->
            "%04d%02d%02d-%02d%02d%02d".format(
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE),
                calendar.get(java.util.Calendar.SECOND),
            )
        }
        val suffix = UUID.randomUUID().toString().take(6)
        val id = "vyn-$stamp-$suffix"
        return Press(
            id = id,
            metadata = metadata.normalized(),
            style = style,
            speed = speed,
            recipe = recipe,
            fileName = "side-$suffix.wav",
            createdAtEpochMs = nowEpochMs,
            durationMs = durationMs,
            byteCount = 0L,
            sourceLabel = sourceLabel,
        )
    }

    /**
     * Writes the description beside the audio.
     *
     * The press directory must already contain the audio file; the byte count is read back from it so
     * the stored size always describes what is actually on disk.
     */
    @Throws(IOException::class)
    fun save(press: Press): Press {
        val directory = directoryFor(press.id)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("could not create ${directory.absolutePath}")
        }
        val audio = File(directory, press.fileName)
        val described = press.copy(byteCount = if (audio.isFile) audio.length() else press.byteCount)

        val target = File(directory, Press.PROPERTIES_FILE)
        val temporary = File(directory, "${Press.PROPERTIES_FILE}.tmp")
        temporary.outputStream().buffered().use { stream ->
            described.toProperties().store(stream, "Vynyl pressed side")
        }
        // Rename is atomic within a directory, so a reader either sees the old description or the
        // new one -- never a half-written file.
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return described
    }

    /** Records the audio the press was rendered into, then writes the description. */
    @Throws(IOException::class)
    fun attachAudio(press: Press, audio: File, durationMs: Long): Press {
        val directory = directoryFor(press.id)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("could not create ${directory.absolutePath}")
        }
        val target = File(directory, press.fileName)
        if (audio.absolutePath != target.absolutePath) {
            audio.copyTo(target, overwrite = true)
        }
        return save(press.copy(durationMs = durationMs, byteCount = target.length()))
    }

    fun rename(press: Press, metadata: RecordMetadata): Press = save(press.copy(metadata = metadata.normalized()))

    fun delete(press: Press): Boolean = deleteRecursively(directoryFor(press.id))

    /** Bytes on disk across the whole vault, including the descriptions. */
    fun totalBytes(): Long = (root.listFiles() ?: emptyArray()).sumOf { sizeOf(it) }

    fun count(): Int = (root.listFiles() ?: emptyArray()).count { it.isDirectory }

    /** Removes every press. Used by "clear the vault" in Settings. */
    fun clear(): Boolean {
        var ok = true
        for (child in root.listFiles() ?: emptyArray()) {
            ok = deleteRecursively(child) && ok
        }
        return ok
    }

    private fun read(directory: File): Press? {
        val description = File(directory, Press.PROPERTIES_FILE)
        if (!description.isFile) return null
        val properties = Properties()
        try {
            description.inputStream().buffered().use(properties::load)
        } catch (error: IOException) {
            return null
        }
        val press = Press.fromProperties(properties) ?: return null
        // The stored file name must stay inside the press directory: a description is data, and data
        // is not allowed to point the app at another file.
        if (press.fileName.contains('/') || press.fileName.contains('\\') || press.fileName.startsWith(".")) {
            return null
        }
        if (!File(directory, press.fileName).isFile) return null
        return press
    }

    private fun sizeOf(file: File): Long =
        if (file.isFile) file.length() else (file.listFiles() ?: emptyArray()).sumOf { sizeOf(it) }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            for (child in file.listFiles() ?: emptyArray()) {
                if (!deleteRecursively(child)) return false
            }
        }
        return file.delete()
    }
}
