package com.vynylrecord.app.core.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.vynylrecord.app.core.audio.dsp.LoopSource
import com.vynylrecord.app.core.audio.dsp.PcmSource
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.model.AudioAsset
import com.vynylrecord.app.core.storage.FileStore
import java.io.File

/**
 * Background music, resolved to PCM at the studio format and cached.
 *
 * A record's background layer is a bed under a voice, so the same bed is usually rendered more than once
 * — the first press, then a re-press after the crackle was turned up, then another after the dedication
 * changed. Decoding the source every time would mean paying for the same AAC decode three times. So each
 * asset is decoded once into the cache and reused; the cache entry is invalidated when the asset file's
 * timestamp moves, which is what happens when an imported asset is replaced.
 *
 * ## Bundled assets
 *
 * The five bundled beds ship inside the APK under `assets/audio/`, compressed. `MediaExtractor` cannot
 * read a compressed asset directly, so the file is copied out to `cache/asset-staging` once and decoded
 * from there. That copy is a codec limitation, not a design choice, and it is why the staging directory
 * exists in the layout at all.
 */
class MusicBank(
    private val context: Context,
    private val files: FileStore,
) {

    /** A resolved background layer, ready to be read into the render loop. */
    class Prepared(
        val source: PcmSource,
        val asset: AudioAsset,
        val isLooping: Boolean,
        val frames: Int,
    ) : AutoCloseable {
        override fun close() = source.close()
    }

    /**
     * Opens [asset] as a [PcmSource], trimmed to the asset's own trim points or to the overrides.
     *
     * The trim is resolved here rather than in the DSP: a trim is a property of the asset, and the mixer
     * should not have to know that a bed was cut eight seconds in.
     */
    fun prepare(
        asset: AudioAsset,
        trimStartMs: Long = asset.trimStartMilliseconds,
        trimEndMs: Long = asset.trimEndMilliseconds,
        loop: Boolean = true,
    ): Prepared? {
        val decoded = decodedFile(asset) ?: return null
        val reader = try {
            WavCodec.Reader(decoded)
        } catch (error: Exception) {
            Log.w(TAG, "could not open the decoded bed for ${asset.id}", error)
            return null
        }
        val totalFrames = reader.frameCount
        if (totalFrames <= 0) {
            reader.close()
            return null
        }

        val startFrame = frameAt(trimStartMs, reader.sampleRate).coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        val endFrame = when {
            trimEndMs <= trimStartMs -> totalFrames
            else -> frameAt(trimEndMs, reader.sampleRate).coerceIn(startFrame + 1, totalFrames)
        }
        val trimmed: PcmSource = if (startFrame == 0 && endFrame == totalFrames) {
            reader
        } else {
            TrimSource(reader, startFrame, endFrame)
        }

        val loopFrames = trimmed.frameCount
        return if (loop && loopFrames > reader.sampleRate / 4) {
            // A bed shorter than the voice repeats; the cross-fade in LoopSource hides the seam, which is
            // the one artefact a listener would notice immediately on a looped background. A quarter
            // second of cross-fade is below the point where a fade becomes audible as a fade.
            val crossfade = (reader.sampleRate / 4).coerceAtMost(loopFrames / 3)
            Prepared(LoopSource(trimmed, loopFrames, crossfade), asset, true, -1)
        } else {
            Prepared(trimmed, asset, false, loopFrames)
        }
    }

    /** The decoded PCM for an asset, decoding it if the cache is cold. Null when the file cannot be read. */
    fun decodedFile(asset: AudioAsset): File? {
        if (!asset.enabled) return null

        // Imported audio is stored decoded, so it is already in the format the renderer wants.
        if (!asset.isBundled) {
            val path = asset.sourcePath ?: return null
            val file = File(path)
            return if (file.isFile && file.length() > 0L) file else null
        }

        val source = stagedBundledFile(asset) ?: return null
        val decoded = File(files.decodedRoot(), "${asset.id}.wav")
        val fresh = decoded.isFile && decoded.length() > 44L && decoded.lastModified() >= source.lastModified()
        if (fresh) return decoded

        decoded.delete()
        val result = AudioDecoder.decodeToWav(context, source, decoded, StudioFormat.SAMPLE_RATE)
        return result?.wavFile
    }

    /** Copies a bundled asset out of the APK so a media decoder can read it. */
    private fun stagedBundledFile(asset: AudioAsset): File? {
        val resource = asset.bundledResourceName ?: return null
        val staged = File(files.stagingRoot(), resource.replace('/', '_'))
        if (staged.isFile && staged.length() > 0L) return staged

        return try {
            context.assets.open(ASSET_ROOT + resource).use { input ->
                staged.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (staged.length() > 0L) staged else null
        } catch (error: Exception) {
            Log.w(TAG, "bundled asset $resource could not be staged", error)
            staged.delete()
            null
        }
    }

    /**
     * Reads an asset's duration without decoding it, so the Sound Lab can show how long a bed is the
     * moment the app is installed rather than after the user tries to use it.
     */
    fun measureDurationMs(asset: AudioAsset): Long {
        if (asset.isBundled) {
            val staged = stagedBundledFile(asset) ?: return 0L
            return durationViaRetriever(staged)
        }
        val file = asset.sourcePath?.let { File(it) } ?: return 0L
        return if (file.isFile) durationViaRetriever(file) else 0L
    }

    private fun durationViaRetriever(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            raw?.toLongOrNull() ?: 0L
        } catch (error: Exception) {
            Log.w(TAG, "duration unavailable for ${file.name}", error)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frameAt(milliseconds: Long, sampleRate: Int): Int =
        ((milliseconds.coerceAtLeast(0L) * sampleRate) / 1000L).toInt()

    private companion object {
        const val TAG = "VynylMusicBank"
        const val ASSET_ROOT = "audio/"
    }
}

/**
 * A window onto part of a [PcmSource].
 *
 * Used for trimmed background music. It reports the trimmed length as its frame count, so the renderer's
 * "is the music long enough for the voice" decision is made on the trimmed bed, which is the one that
 * will actually play.
 */
class TrimSource(
    private val inner: PcmSource,
    private val start: Int,
    private val end: Int,
) : PcmSource {

    override val sampleRate: Int get() = inner.sampleRate
    override val frameCount: Int get() = (end - start).coerceAtLeast(0)

    private var position = 0
    private var skipped = false
    private val skipScratch = FloatArray(2_048 * 2)

    override fun read(out: FloatArray, offset: Int, frames: Int): Int {
        if (!skipped) {
            var remaining = start
            val scratch = skipScratch
            while (remaining > 0) {
                val want = minOf(remaining, scratch.size / 2)
                val read = inner.read(scratch, 0, want)
                if (read <= 0) break
                remaining -= read
            }
            skipped = true
        }
        val available = frameCount - position
        if (available <= 0) return 0
        val want = minOf(frames, available)
        val read = inner.read(out, offset, want)
        if (read <= 0) return 0
        position += read
        return read
    }

    override fun close() = inner.close()
}
