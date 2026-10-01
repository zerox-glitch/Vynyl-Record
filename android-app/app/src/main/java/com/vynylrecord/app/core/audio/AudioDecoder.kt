package com.vynylrecord.app.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.vynylrecord.app.core.audio.dsp.WavCodec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns anything the device can play into a 44.1 kHz stereo WAV, without holding it in memory.
 *
 * ## Why WAV in the middle
 *
 * The render chain runs on WAV. That is not a limitation but the reason the pipeline is cheap: the
 * decoder runs once, the resampler runs once, and every stage after that reads 16-bit samples straight
 * off a file. The alternative — decoding inside the mix loop — would mean the same compressed bytes were
 * decoded once per stage that needs a second pass, and it would make the record's length unknowable until
 * the decode finished, so a progress bar could not be honest.
 *
 * ## Decoding
 *
 * `MediaExtractor` + `MediaCodec`, which are the platform's own decoders: the app contains no audio
 * codec of its own, which is why there is no licensing question and no bundled native library. A file that
 * is already a WAV skips the codec entirely — it is already PCM, and a decode round trip would only add a
 * generation of rounding.
 *
 * Everything is fail-soft. An unsupported codec, a truncated file, a DRM-protected download and a
 * zero-length document all come back as `null` with a reason, never as an exception the UI has to guess
 * about.
 */
object AudioDecoder {

    private const val TAG = "VynylDecoder"
    private const val TIMEOUT_US = 10_000L

    /** What a decode produced. */
    data class Decoded(
        val wavFile: File,
        val durationMs: Long,
        val sourceChannels: Int,
        val sourceSampleRate: Int,
        val peak: Float,
        val channelsFolded: Boolean,
    )

    /**
     * Decodes [source] into [target] as 16-bit stereo PCM at [targetSampleRate].
     *
     * @param onProgress 0..1, driven by the presentation timestamps the decoder reports
     */
    fun decodeToWav(
        context: Context,
        source: File,
        target: File,
        targetSampleRate: Int = StudioFormat.SAMPLE_RATE,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Decoded? {
        // A WAV that is already at the studio format is passed straight through, byte for byte.
        val header = try {
            source.inputStream().buffered().use { WavCodec.readHeader(it) }
        } catch (error: Exception) {
            Log.w(TAG, "could not read ${source.name}", error)
            null
        }

        if (header != null) {
            if (header.sampleRate == targetSampleRate) {
                // The reader already folds surround to stereo and handles every PCM width this app
                // writes, so a copy is the whole job.
                val copied = copyAsWav(source, target, onProgress, isCancelled) ?: return null
                return copied
            }
            // A WAV at another rate still needs resampling, but no codec.
            return resampleWav(source, target, targetSampleRate, onProgress, isCancelled)
        }

        return decodeWithCodec(context, source, target, targetSampleRate, onProgress, isCancelled)
    }

    /** Decodes a `content://` document into the app's cache, then decodes it as a file. */
    fun stageDocument(context: Context, uri: Uri, into: File): File? = try {
        into.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            into.outputStream().buffered().use { output -> stream.copyTo(output) }
        }
        if (into.length() <= 0L) {
            into.delete()
            null
        } else {
            into
        }
    } catch (error: Exception) {
        Log.w(TAG, "could not stage $uri", error)
        null
    }

    /** The display name a picker gave us, or a fallback. */
    fun displayName(context: Context, uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return fromResolver?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "recording"
    }

    /** Opens a decodable audio track, or null when the container has none. */
    fun openTrack(context: Context, source: File): Tracked? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = index
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) {
                extractor.release()
                null
            } else {
                extractor.selectTrack(track)
                Tracked(extractor, format)
            }
        } catch (error: Exception) {
            Log.w(TAG, "no decodable track in ${source.name}", error)
            runCatching { extractor.release() }
            null
        }
    }

    class Tracked(val extractor: MediaExtractor, val format: MediaFormat) {
        val mime: String get() = format.getString(MediaFormat.KEY_MIME) ?: "audio/unknown"
        val durationUs: Long
            get() = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
    }

    // ------------------------------------------------------------------ WAV passthrough

    private fun copyAsWav(
        source: File,
        target: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Decoded? {
        val reader = try {
            WavCodec.Reader(source)
        } catch (error: Exception) {
            return null
        }
        val header = source.inputStream().buffered().use { WavCodec.readHeader(it) }
        reader.use { stream ->
            val frames = stream.frameCount
            if (frames <= 0) return null
            val writer = WavCodec.Writer(target, stream.sampleRate, 2, 16)
            val block = FloatArray(8_192 * 2)
            var written = 0
            var peak = 0f
            try {
                while (written < frames) {
                    if (isCancelled()) {
                        writer.close()
                        target.delete()
                        return null
                    }
                    val read = stream.read(block, 0, block.size / 2)
                    if (read <= 0) break
                    peak = max(peak, peakOf(block, read * 2))
                    writer.write(block, 0, read)
                    written += read
                    onProgress(written.toFloat() / frames)
                }
                writer.finish()
            } catch (error: Exception) {
                Log.w(TAG, "copy failed", error)
                runCatching { writer.close() }
                return null
            }
            return Decoded(
                wavFile = target,
                durationMs = written * 1000L / stream.sampleRate,
                sourceChannels = header?.channels ?: 2,
                sourceSampleRate = stream.sampleRate,
                peak = peak,
                channelsFolded = (header?.channels ?: 2) > 2,
            )
        }
    }

    private fun resampleWav(
        source: File,
        target: File,
        targetSampleRate: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Decoded? {
        val reader = try {
            WavCodec.Reader(source)
        } catch (error: Exception) {
            return null
        }
        reader.use { stream ->
            val inFrames = stream.frameCount
            if (inFrames <= 0) return null
            val outFrames = (inFrames.toLong() * targetSampleRate / stream.sampleRate).toInt()
            val ratio = stream.sampleRate.toDouble() / targetSampleRate
            val writer = WavCodec.Writer(target, targetSampleRate, 2, 16)
            val block = FloatArray(4_096 * 2)
            val out = FloatArray(4_096 * 2)
            var produced = 0
            var peak = 0f
            var position = 0.0
            var tailLeft = 0f
            var tailRight = 0f
            var hasTail = false
            try {
                while (produced < outFrames) {
                    if (isCancelled()) {
                        writer.close()
                        target.delete()
                        return null
                    }
                    val read = stream.read(block, 0, block.size / 2)
                    if (read <= 0) break
                    var producedThisBlock = 0
                    while (position < read - 1 && produced + producedThisBlock < outFrames) {
                        val index = position.toInt()
                        val fraction = (position - index).toFloat()
                        val left = block[index * 2] + (block[(index + 1) * 2] - block[index * 2]) * fraction
                        val right = block[index * 2 + 1] + (block[(index + 1) * 2 + 1] - block[index * 2 + 1]) * fraction
                        out[producedThisBlock * 2] = left
                        out[producedThisBlock * 2 + 1] = right
                        producedThisBlock++
                        position += ratio
                    }
                    position -= read
                    tailLeft = block[(read - 1) * 2]
                    tailRight = block[(read - 1) * 2 + 1]
                    hasTail = true
                    peak = max(peak, peakOf(out, producedThisBlock * 2))
                    writer.write(out, 0, producedThisBlock)
                    produced += producedThisBlock
                    onProgress((produced.toFloat() / outFrames).coerceIn(0f, 1f))
                }
                // The interpolation needs one frame of lookahead; when the source ends exactly on a
                // block boundary the last frame comes from the carried tail rather than being dropped.
                if (hasTail && produced < outFrames) {
                    out[0] = tailLeft
                    out[1] = tailRight
                    writer.write(out, 0, 1)
                    produced++
                }
                writer.finish()
            } catch (error: Exception) {
                Log.w(TAG, "resample failed", error)
                runCatching { writer.close() }
                return null
            }
            return Decoded(
                wavFile = target,
                durationMs = produced * 1000L / targetSampleRate,
                sourceChannels = 2,
                sourceSampleRate = stream.sampleRate,
                peak = peak,
                channelsFolded = false,
            )
        }
    }

    // ------------------------------------------------------------------ codec decode

    private fun decodeWithCodec(
        context: Context,
        source: File,
        target: File,
        targetSampleRate: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Decoded? {
        val tracked = openTrack(context, source) ?: return null
        tracked.extractor.use { extractor ->
            val format = tracked.format
            val mime = tracked.mime
            val sourceRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                targetSampleRate
            }
            val sourceChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            val durationUs = tracked.durationUs

            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                val ratio = sourceRate.toDouble() / targetSampleRate
                val writer = WavCodec.Writer(target, targetSampleRate, 2, 16)
                // 16-bit source samples are turned into float, resampled and written a block at a time.
                val decoded = FloatArray(16_384 * 2)
                val resampled = FloatArray(16_384 * 2)
                var decodedFrames = 0
                var producedTotal = 0
                var peak = 0f
                var inputDone = false
                var outputDone = false
                var position = 0.0

                while (!outputDone) {
                    if (isCancelled()) {
                        runCatching { writer.close() }
                        target.delete()
                        return null
                    }
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Some decoders only report their real rate after the first frame.
                        continue
                    }
                    if (outputIndex < 0) continue

                    if (info.size > 0) {
                        val buffer: ByteBuffer = codec.getOutputBuffer(outputIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        decodedFrames = convertToFloat(buffer, sourceChannels, decoded, decodedFrames)
                        // Convert as much as fits, then resample everything buffered.
                        val produced = resampleInPlace(
                            decoded, decodedFrames, sourceChannels, resampled, ratio, position,
                        )
                        position = produced.position
                        if (produced.frames > 0) {
                            peak = max(peak, peakOf(resampled, produced.frames * 2))
                            writer.write(resampled, 0, produced.frames)
                            producedTotal += produced.frames
                        }
                        decodedFrames = 0
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true

                    if (durationUs > 0L && info.presentationTimeUs > 0L) {
                        onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                }

                writer.finish()
                return Decoded(
                    wavFile = target,
                    durationMs = producedTotal * 1000L / targetSampleRate,
                    sourceChannels = sourceChannels,
                    sourceSampleRate = sourceRate,
                    peak = peak,
                    channelsFolded = sourceChannels > 2,
                )
            } catch (error: Exception) {
                Log.w(TAG, "decode failed for ${source.name}", error)
                target.delete()
                return null
            } finally {
                runCatching {
                    codec?.stop()
                    codec?.release()
                }
            }
        }
    }

    /** PCM in an output buffer to interleaved float, folding >2 channels to stereo. */
    private fun convertToFloat(
        buffer: ByteBuffer,
        channels: Int,
        out: FloatArray,
        startFrame: Int,
    ): Int {
        val order = buffer.order(ByteOrder.nativeOrder())
        var frames = startFrame
        val bytesPerFrame = 2 * channels
        while (buffer.remaining() >= bytesPerFrame && (frames + 1) * 2 <= out.size) {
            val base = frames * 2
            out[base] = 0f
            out[base + 1] = 0f
            for (channel in 0 until channels) {
                val sample = if (order == ByteOrder.LITTLE_ENDIAN) {
                    (buffer.short.toInt() and 0xFFFF).let { if (it >= 0x8000) it - 0x10000 else it }
                } else {
                    buffer.short.toInt().toShort().toInt()
                }
                val value = sample / 32_768f
                when {
                    // Mono decodes into both sides. The written WAV is stereo either way, so leaving the
                    // right channel empty would import a voice memo that only plays in one ear.
                    channels == 1 -> {
                        out[base] = value
                        out[base + 1] = value
                    }

                    channels == 2 -> out[base + channel] = value
                    channel == 0 -> out[base] += value
                    channel == 1 -> out[base + 1] += value
                    else -> {
                        out[base] += value / 3f
                        out[base + 1] += value / 3f
                    }
                }
            }
            frames++
        }
        return frames
    }

    private class Resampled(val frames: Int, val position: Double)

    /**
     * Linear resampling of a decoded block.
     *
     * Linear rather than windowed-sinc on purpose: the source is speech, the resampler runs once, and the
     * chain after it applies a low-pass anyway. A sinc kernel here would cost several times as much for
     * a difference that the vinyl EQ removes.
     */
    private fun resampleInPlace(
        input: FloatArray,
        inputFrames: Int,
        channels: Int,
        out: FloatArray,
        ratio: Double,
        startPosition: Double,
    ): Resampled {
        if (inputFrames <= 0) return Resampled(0, startPosition)
        var position = startPosition
        var produced = 0
        val maxOut = out.size / 2 - 1
        while (position < inputFrames - 1 && produced < maxOut) {
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val next = (index + 1).coerceAtMost(inputFrames - 1)
            val left = input[index * channels] + (input[next * channels] - input[index * channels]) * fraction
            val right = input[index * channels + (if (channels > 1) 1 else 0)] +
                (input[next * channels + (if (channels > 1) 1 else 0)] - input[index * channels + (if (channels > 1) 1 else 0)]) * fraction
            out[produced * 2] = left
            out[produced * 2 + 1] = right
            produced++
            position += ratio
        }
        return Resampled(produced, position - inputFrames)
    }

    private fun peakOf(buffer: FloatArray, count: Int): Float {
        var peak = 0f
        for (index in 0 until count) {
            val magnitude = abs(buffer[index])
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }
}

/** The studio's fixed internal format. Every record is pressed at this rate and width. */
object StudioFormat {
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
}
