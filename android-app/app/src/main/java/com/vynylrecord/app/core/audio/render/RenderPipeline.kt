package com.vynylrecord.app.core.audio.render

import android.content.Context
import android.util.Log
import com.vynylrecord.app.core.audio.MusicBank
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.model.AudioAsset
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.RenderStage
import com.vynylrecord.app.core.model.VinylRecipe
import com.vynylrecord.app.core.storage.FileStore
import com.vynylrecord.app.core.storage.StorageLayout
import java.io.File
import java.io.IOException

/**
 * The whole job of pressing one record, from source file to finished master.
 *
 * This is where the DSP meets the device. [MasterRenderer] knows nothing about Android; this class knows
 * about files, codecs, storage and progress, and nothing about filters. The division is what makes the
 * audio engine testable on a desktop JVM and the app layer thin.
 *
 * ## The order of operations, and why
 *
 * ```
 *  1  open the source WAV              the capture or the import, already 44.1 kHz stereo PCM
 *  2  open the background bed          decoded from cache, trimmed, looped with a cross-faded seam
 *  3  render into cache/render/{job}   the nine DSP stages run here, block by block
 *  4  validate the finished file       decode it back: right length, right level, not silent, not clipped
 *  5  measure it                       peak, RMS, how much of it is speech, what the floor sits at
 *  6  draw the artwork                 on the platform canvas, from the record's own metadata
 *  7  extract the waveform             peaks for the library card and the player
 *  8  move the master into place       a rename, atomic, so a reader never sees a partial file
 *  9  return the updated record        the caller commits the row only now
 * ```
 *
 * Step 4 is the one that earns its place. "Rendering finished" is a claim about a *file*, and the way to
 * make that claim true is to open the file the way a player would and check what is inside it. A render
 * that produced a truncated or silent master fails here, before the database is told anything, so a
 * record in the Vault is always a record that plays.
 */
class RenderPipeline(
    private val context: Context,
    private val files: FileStore,
) {

    private val bank = MusicBank(context, files)

    /** What to press. Built from a [Record]; the trim comes from the asset, the level from the record. */
    data class Request(
        val record: Record,
        val jobId: String,
        val music: AudioAsset? = null,
        val trimStartMs: Long? = null,
        val trimEndMs: Long? = null,
        /** Overrides the record's own background level, used by the Studio preview. */
        val backgroundLevel: Float? = null,
        /** Skip the artwork and waveform steps; the caller will do them, or does not need them. */
        val accessories: Boolean = true,
    )

    /** A finished press. The record returned to the caller carries the new paths and measurements. */
    data class Outcome(
        val record: Record,
        val masterFile: File,
        val measurements: AudioMeasurements,
        val waveform: Waveform,
        val artworkFile: File?,
        val frames: Int,
        val durationMs: Long,
        val diagnostics: String,
    )

    /** Everything that can stop a press. Rendered in the UI as a sentence, never as a stack trace. */
    class RenderException(
        val stage: RenderStage,
        override val message: String,
        val causeOf: Throwable? = null,
    ) : Exception(message, causeOf)

    /**
     * Presses the record.
     *
     * @param onProgress stage and fraction, called often enough for a determinate bar
     * @param isCancelled polled between blocks and between stages; a cancelled render leaves no master
     */
    fun render(
        request: Request,
        onProgress: (RenderStage, Float) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        val record = request.record
        val jobId = request.jobId

        // ---- 1. the source
        onProgress(RenderStage.PREPARING, 0f)
        val sourceFile = resolveSource(record) ?: throw RenderException(
            RenderStage.PREPARING,
            "This record's source audio is missing. Re-record or import it again.",
        )
        val voice = try {
            WavCodec.Reader(sourceFile)
        } catch (error: Exception) {
            throw RenderException(RenderStage.PREPARING, "The source audio could not be read: ${error.message}", error)
        }

        val totalFrames = voice.frameCount
        if (totalFrames <= 0) {
            voice.close()
            throw RenderException(RenderStage.PREPARING, "The source audio is empty.")
        }
        val durationMs = totalFrames * 1000L / voice.sampleRate
        if (durationMs > RenderLimits.MAX_RECORD_MS) {
            voice.close()
            throw RenderException(
                RenderStage.PREPARING,
                "This record is ${durationMs / 1000} seconds long; the press takes up to " +
                    "${RenderLimits.MAX_RECORD_MS / 1000} seconds at a time.",
            )
        }

        // Storage is checked before the render rather than discovered part-way through it: a full disk at
        // minute three of a four-minute press would leave the user with nothing to show for the wait.
        val needed = RenderLimits.estimateMasterBytes(durationMs + record.controls.recipe.needleIntroMs)
        if (availableBytes() in 1 until (needed + RenderLimits.LOW_STORAGE_BYTES)) {
            voice.close()
            throw RenderException(
                RenderStage.PREPARING,
                "There is not enough free space to press this record. Free up some room and try again.",
            )
        }

        // Scratch space for the render. Anything already there is from a job that died.
        files.discardJob(jobId)
        val scratchMaster = files.scratch(jobId, StorageLayout.MASTER_NAME)
        scratchMaster.delete()

        val recipe = record.controls.recipe
        val seed = record.seedOrDefault
        val leadInFrames = (recipe.needleIntroMs.coerceIn(0, 2_000) * voice.sampleRate) / 1000

        // ---- 2. the background bed
        var preparedMusic: MusicBank.Prepared? = null
        if (request.music != null) {
            preparedMusic = try {
                bank.prepare(
                    asset = request.music,
                    trimStartMs = request.trimStartMs ?: request.music.trimStartMilliseconds,
                    trimEndMs = request.trimEndMs ?: request.music.trimEndMilliseconds,
                    loop = true,
                )
            } catch (error: Exception) {
                Log.w(TAG, "background bed unavailable", error)
                null
            }
        }

        // ---- 3. the render itself
        onProgress(RenderStage.DECODING, 0.5f)
        val sink = try {
            WavCodec.Writer(scratchMaster, voice.sampleRate, 2, 16)
        } catch (error: IOException) {
            voice.close()
            preparedMusic?.close()
            throw RenderException(RenderStage.PREPARING, "The render could not be started: ${error.message}", error)
        }

        val renderer = MasterRenderer(voice.sampleRate, BLOCK_FRAMES)
        val result = try {
            renderer.render(
                voice = voice,
                music = preparedMusic?.source,
                recipe = recipe,
                seed = seed,
                sink = sink,
                leadInFrames = leadInFrames,
                onProgress = { fraction ->
                    // The chain's own progress drives three of the nine stages: the voice and the bed are
                    // mixed first, the vinyl character accumulates through the middle, and the limiter and
                    // fades close it out. The bar is monotone because the chain's fraction only rises.
                    val stage = when {
                        fraction < 0.30f -> RenderStage.MIXING
                        fraction < 0.80f -> RenderStage.CHARACTER
                        else -> RenderStage.MASTERING
                    }
                    onProgress(stage, fraction)
                },
                isCancelled = isCancelled,
            )
        } catch (error: Exception) {
            renderer.closeAll(sink, voice, preparedMusic?.source)
            files.discardJob(jobId)
            throw RenderException(RenderStage.MASTERING, "The press failed: ${error.message}", error)
        }

        renderer.closeAll(preparedMusic?.source, voice)

        when (result) {
            is MasterRenderer.Result.Cancelled -> {
                sink.close()
                files.discardJob(jobId)
                throw RenderException(RenderStage.MASTERING, "The press was cancelled.")
            }

            is MasterRenderer.Result.Failed -> {
                sink.close()
                files.discardJob(jobId)
                throw RenderException(RenderStage.MASTERING, result.reason)
            }

            is MasterRenderer.Result.Completed -> Unit
        }

        val completed = result as? MasterRenderer.Result.Completed
            ?: throw RenderException(RenderStage.MASTERING, "The press stopped without producing a master.")

        try {
            sink.finish()
        } catch (error: Exception) {
            files.discardJob(jobId)
            throw RenderException(RenderStage.ENCODING, "The master could not be finished: ${error.message}", error)
        }

        // ---- 4. validation: is this actually a playable record?
        onProgress(RenderStage.ENCODING, 0.5f)
        val report = validate(scratchMaster, completed.durationMs, completed.peak)
        if (!report.ok) {
            scratchMaster.delete()
            files.discardJob(jobId)
            throw RenderException(RenderStage.ENCODING, report.message.ifBlank { "The master did not verify." })
        }

        // ---- 5. measurements
        val measurements = try {
            WavCodec.Reader(scratchMaster).use { reader -> AudioMeasurements.measure(reader) }
        } catch (error: Exception) {
            files.discardJob(jobId)
            throw RenderException(RenderStage.ENCODING, "The master could not be measured: ${error.message}", error)
        }

        // ---- 6. artwork and the waveform
        var artwork: File? = null
        var waveform = Waveform.EMPTY
        if (request.accessories) {
            onProgress(RenderStage.WAVEFORM, 0f)
            waveform = WaveformExtractor().extract(scratchMaster, completed.durationMs)
            try {
                val target = files.artworkFile(record.id)
                artwork = ArtworkRenderer.write(target, record.copy(durationMilliseconds = completed.durationMs))
            } catch (error: Exception) {
                // Artwork failing is not a reason to lose a record: the library draws a placeholder from
                // the vinyl style, and the Vault's "Re-draw artwork" action tries again.
                Log.w(TAG, "artwork could not be drawn", error)
            }
        }

        // ---- 7. into place
        onProgress(RenderStage.ARTWORK, 0.5f)
        val masterFile = files.masterFile(record.id)
        val placed = files.placeAtomically(scratchMaster, masterFile)
        if (!placed) {
            files.discardJob(jobId)
            throw RenderException(RenderStage.ARTWORK, "The master could not be saved to storage.")
        }
        if (waveform != Waveform.EMPTY) {
            runCatching { WaveformFile.write(files.waveformFile(record.id), waveform) }
        }
        files.discardJob(jobId)

        val updated = record.copy(
            masterPath = masterFile.absolutePath,
            coverArtworkPath = artwork?.absolutePath ?: record.coverArtworkPath,
            waveformPath = if (waveform == Waveform.EMPTY) record.waveformPath else files.waveformFile(record.id).absolutePath,
            waveformPeaks = WaveformExtractor().compact(waveform),
            durationMilliseconds = completed.durationMs,
            sizeBytes = masterFile.length(),
            renderState = RenderState.COMPLETED,
            renderProgress = 1f,
            renderStageLabel = RenderStage.COMPLETE.label,
            renderError = null,
            renderJobId = null,
            updatedAt = System.currentTimeMillis(),
        )

        onProgress(RenderStage.COMPLETE, 1f)
        return Outcome(
            record = updated,
            masterFile = masterFile,
            measurements = measurements,
            waveform = waveform,
            artworkFile = artwork,
            frames = completed.framesWritten,
            durationMs = completed.durationMs,
            diagnostics = completed.diagnostics,
        )
    }

    /**
     * Reads the master back the way a player would.
     *
     * The duration and peak come from the file, not from what the render believes it wrote. A file that
     * decodes to the right length with audio in it and no samples over full scale is a file that will
     * play, which is the only claim worth making.
     */
    fun validate(master: File, expectedDurationMs: Long, reportedPeak: Float): RenderValidator.Report {
        val size = master.length()
        var measuredDuration = 0L
        var measuredPeak = -1f
        var decodable = false
        try {
            WavCodec.Reader(master).use { reader ->
                val frames = reader.frameCount
                if (frames > 0) {
                    decodable = true
                    measuredDuration = frames * 1000L / reader.sampleRate
                    measuredPeak = reader.measurePeak()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "the master could not be read back", error)
        }
        return RenderValidator.validate(
            file = master,
            sizeBytes = size,
            expectedDurationMs = expectedDurationMs,
            measuredDurationMs = measuredDuration,
            peak = if (measuredPeak >= 0f) measuredPeak else reportedPeak,
            decodable = decodable,
        )
    }

    /**
     * Encodes a master into an M4A the rest of the world can play.
     *
     * The masters this app keeps are WAV: exact, seekable, and identical to what the DSP produced. M4A is
     * for leaving the device. Encoding is done on demand rather than at render time so a record the user
     * only ever listens to in the Vault never pays for the encode.
     */
    fun encodeM4a(
        master: File,
        target: File,
        bitRate: Int = 192_000,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File? {
        if (!master.isFile) return null
        target.parentFile?.mkdirs()
        val reader = try {
            WavCodec.Reader(master)
        } catch (error: Exception) {
            return null
        }
        var sink: AacEncoderSink? = null
        return try {
            reader.use { source ->
                sink = AacEncoderSink(target, source.sampleRate, bitRate)
                val encoder = sink!!
                val block = FloatArray(8_192 * 2)
                var written = 0
                val total = source.frameCount
                while (written < total) {
                    if (isCancelled()) {
                        encoder.close()
                        target.delete()
                        return null
                    }
                    val read = source.read(block, 0, block.size / 2)
                    if (read <= 0) break
                    encoder.write(block, 0, read)
                    written += read
                    onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                }
                encoder.finish()
                if (target.length() > 1_024L) {
                    target
                } else {
                    // A header and nothing else: the encoder never produced a frame.
                    target.delete()
                    null
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "M4A encode failed", error)
            runCatching { sink?.close() }
            target.delete()
            null
        }
    }

    /** Writes a plain copy of the master where the user asked for it. */
    fun exportWav(master: File, target: File): Boolean = try {
        target.parentFile?.mkdirs()
        master.copyTo(target, overwrite = true)
        true
    } catch (error: Exception) {
        Log.w(TAG, "WAV export failed", error)
        false
    }

    private fun resolveSource(record: Record): File? {
        val stored = record.sourcePath?.let { File(it) }
        if (stored != null && stored.isFile && stored.length() > 44L) return stored
        val byLayout = files.sourceFile(record.id)
        return if (byLayout.isFile && byLayout.length() > 44L) byLayout else null
    }

    private fun availableBytes(): Long = runCatching { files.filesRoot.usableSpace }.getOrDefault(Long.MAX_VALUE)

    private companion object {
        const val TAG = "VynylRenderPipeline"

        /**
         * 8192 frames is 186 ms of audio per block: large enough that per-block overhead disappears,
         * small enough that a progress callback four times a second feels live.
         */
        const val BLOCK_FRAMES = 8_192
    }
}

/**
 * The procedural layers, checked before a render rather than after it.
 *
 * A press takes seconds, and the single most annoying way to lose that time is to discover at the end
 * that the recipe asked for something impossible. These checks catch the impossible cases: a low-pass
 * below the high-pass, a stereo width the DSP would clamp, a surface level that would be inaudible
 * whatever the noise generator did.
 */
object RenderPreflight {

    data class Finding(val level: Level, val message: String) {
        enum class Level { INFO, WARNING, ERROR }
    }

    fun check(record: Record, music: AudioAsset?): List<Finding> {
        val findings = mutableListOf<Finding>()
        val recipe: VinylRecipe = record.controls.recipe

        if (recipe.highPassHz >= recipe.lowPassHz) {
            findings += Finding(
                Finding.Level.ERROR,
                "The tone controls cross over: the low-pass is below the high-pass.",
            )
        }
        if (recipe.stereoEnabled && recipe.stereoWidth < 0.5f) {
            findings += Finding(Finding.Level.WARNING, "Stereo width below 0.5 would fold the mix into itself.")
        }
        if (recipe.limiterCeil > 0.99f) {
            findings += Finding(Finding.Level.WARNING, "The limiter ceiling is at full scale; the press will be loud.")
        }
        if (record.durationMilliseconds > RenderLimits.MAX_RECORD_MS) {
            findings += Finding(
                Finding.Level.ERROR,
                "A record holds up to ${RenderLimits.MAX_RECORD_MS / 1000} seconds. This one is longer.",
            )
        }
        if (music != null && !music.enabled) {
            findings += Finding(Finding.Level.WARNING, "\"${music.displayTitle}\" is switched off in the Sound Lab.")
        }
        if (record.title.isBlank()) {
            findings += Finding(Finding.Level.INFO, "The record has no title yet; it will be labelled \"Untitled record\".")
        }
        return findings
    }
}
