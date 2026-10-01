package com.vynylrecord.app.core.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.vynylrecord.app.core.audio.render.ArtworkRenderer
import com.vynylrecord.app.core.audio.render.RenderPipeline
import com.vynylrecord.app.core.data.prefs.AudioQuality
import com.vynylrecord.app.core.data.prefs.OutputFormat
import com.vynylrecord.app.core.data.repository.RecordRepository
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.storage.FileStore
import com.vynylrecord.app.core.storage.VynylBundle
import java.io.File
import java.io.FileOutputStream

/**
 * Getting a record out of the app.
 *
 * Four things can leave: the audio as M4A, the audio as WAV, the sleeve as a picture, and the whole record
 * as a `.vynyl` bundle that carries the source, the artwork and the recipe as well. Each is written into
 * `files/exports`, and the share sheet is handed a copy in `cache/shared` through FileProvider.
 *
 * ## Why the exports directory exists
 *
 * A share sheet is a UI, and a user can dismiss it. If the export only existed as a `content://` URI handed
 * to another app, dismissing the sheet would throw the work away. Keeping the file means the second attempt
 * is instant, and Settings can show the user everything they have exported.
 *
 * ## Encoding is on demand
 *
 * The master is WAV, always. M4A is produced when someone asks for it, because most listening happens inside
 * the app, where Media3 plays the master directly, and encoding a four-minute record costs a second or two
 * that nobody should pay twice.
 */
class ExportManager(
    private val context: Context,
    private val files: FileStore,
    private val records: RecordRepository,
    private val pipeline: RenderPipeline,
) {

    /** What an export produced. */
    sealed interface Result {
        data class Ready(val file: File, val uri: Uri, val mimeType: String, val bytes: Long) : Result
        data class Failed(val message: String) : Result
    }

    /** The steps an export goes through, named the way the export sheet shows them. */
    enum class Step(val label: String) {
        PREPARING("Preparing"),
        ENCODING("Encoding"),
        WRITING("Writing the file"),
        BUNDLING("Packing the record"),
        DONE("Ready to share"),
    }

    /** Audio: M4A, WAV, or both, according to the user's output format preference. */
    fun exportAudio(
        record: Record,
        format: OutputFormat,
        quality: AudioQuality,
        includeSource: Boolean,
        onStep: (Step, Float) -> Unit = { _, _ -> },
    ): List<Result> {
        val master = records.resolveMaster(record) ?: return listOf(Result.Failed("This record has no audio to export."))
        val results = mutableListOf<Result>()
        val baseName = files.safeFileName(record.displayTitle).ifBlank { "vynyl-record" }

        if (format == OutputFormat.M4A || format == OutputFormat.BOTH) {
            onStep(Step.ENCODING, 0f)
            val target = files.exportTarget(baseName, "Side A", "m4a")
            val encoded = pipeline.encodeM4a(
                master = master,
                target = target,
                bitRate = quality.bitRate,
                onProgress = { fraction -> onStep(Step.ENCODING, fraction) },
            )
            if (encoded != null) {
                results += shareable(encoded, "$baseName.m4a", "audio/mp4")
            } else {
                results += Result.Failed("The M4A could not be encoded on this device.")
            }
        }

        if (format == OutputFormat.WAV || format == OutputFormat.BOTH) {
            onStep(Step.WRITING, 0f)
            val target = files.exportTarget(baseName, "master", "wav")
            if (pipeline.exportWav(master, target)) {
                results += shareable(target, "$baseName.wav", "audio/wav")
            } else {
                results += Result.Failed("The WAV could not be written.")
            }
        }

        if (includeSource) {
            val source = records.resolveSource(record)
            if (source != null && source.isFile) {
                val target = files.exportTarget(baseName, "original", "wav")
                if (pipeline.exportWav(source, target)) {
                    results += shareable(target, "$baseName original.wav", "audio/wav")
                }
            }
        }

        onStep(Step.DONE, 1f)
        return results
    }

    /** The sleeve, as a PNG at full size. */
    fun exportArtwork(record: Record, onStep: (Step, Float) -> Unit = { _, _ -> }): Result {
        onStep(Step.PREPARING, 0f)
        val baseName = files.safeFileName(record.displayTitle).ifBlank { "vynyl-record" }
        val target = files.exportTarget(baseName, "cover", "png")
        // Redrawn at export size rather than copied: the artwork stored beside the record is whatever size
        // the render produced, and an export is a thing to print or to post.
        val bitmap: Bitmap? = try {
            val drawn = ArtworkRenderer.render(record, ArtworkRenderer.DEFAULT_SIZE)
            FileOutputStream(target).use { stream -> drawn.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            drawn.recycle()
            target.takeIf { it.length() > 0L }
        } catch (error: Exception) {
            Log.w(TAG, "artwork export failed", error)
            null
        }
        onStep(Step.DONE, 1f)
        return if (bitmap != null) {
            shareable(bitmap, "$baseName cover.png", "image/png")
        } else {
            Result.Failed("The artwork could not be drawn.")
        }
    }

    /** The whole record, as a `.vynyl` bundle. */
    fun exportBundle(
        record: Record,
        appVersion: String,
        includeSource: Boolean,
        onStep: (Step, Float) -> Unit = { _, _ -> },
    ): Result {
        onStep(Step.BUNDLING, 0.2f)
        val baseName = files.safeFileName(record.displayTitle).ifBlank { "vynyl-record" }
        val target = files.exportTarget(baseName, "", VynylBundle.EXTENSION)
        val written = VynylBundle.write(
            target = target,
            record = record,
            appVersion = appVersion,
            masterFile = records.resolveMaster(record),
            sourceFile = records.resolveSource(record),
            artworkFile = records.resolveArtwork(record),
            waveformFile = files.waveformFile(record.id).takeIf { it.isFile },
            includeSource = includeSource,
        )
        return when (written) {
            is VynylBundle.BundleResult.Written -> {
                onStep(Step.DONE, 1f)
                shareable(written.file, "$baseName.${VynylBundle.EXTENSION}", VynylBundle.MIME_TYPE)
            }

            is VynylBundle.BundleResult.Failed -> Result.Failed(written.message)
            else -> Result.Failed("The bundle could not be written.")
        }
    }

    /** The share intent for an exported file, ready to launch. */
    fun shareIntent(result: Result.Ready, title: String): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = result.mimeType
            putExtra(Intent.EXTRA_STREAM, result.uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title — pressed with Vynyl Record, on this device.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Share \"$title\"").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** The share intent for several files at once, used by "share both formats". */
    fun shareManyIntent(results: List<Result.Ready>, title: String): Intent? {
        val ready = results.filterIsInstance<Result.Ready>()
        if (ready.isEmpty()) return null
        if (ready.size == 1) return shareIntent(ready.first(), title)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (ready.all { it.mimeType.startsWith("audio/") }) "audio/*" else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(ready.map { it.uri }))
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Share \"$title\"").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** Everything in the exports folder, for the Settings screen's storage panel. */
    fun existingExports(): List<File> =
        files.exportsRoot().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun deleteExport(file: File): Boolean = file.delete()

    private fun shareable(file: File, displayName: String, mimeType: String): Result.Ready = try {
        val uri = files.stageForSharing(file, displayName)
        Result.Ready(file = file, uri = uri, mimeType = mimeType, bytes = file.length())
    } catch (error: Exception) {
        Log.w(TAG, "could not stage ${file.name} for sharing", error)
        Result.Failed("The file was written but could not be shared.")
    }

    private companion object {
        const val TAG = "VynylExportManager"
    }
}
