package com.vynylrecord.turntable.studio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.vynylrecord.turntable.press.PcmAudio
import com.vynylrecord.turntable.press.WavIO
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Turns whatever audio the user picked into studio-rate float PCM.
 *
 * The app never declares `INTERNET`, and it never asks a server to transcode anything: an imported
 * file is decoded on the device with `MediaExtractor` and `MediaCodec`, resampled in plain Kotlin,
 * and pressed locally. Sources with more than two channels are folded to stereo by the decoder.
 *
 * Returns null rather than throwing for anything it cannot read, so the Studio can say "that file
 * could not be read on this device" instead of dying.
 */
object AudioDecoder {

    /**
     * Decodes [file] to float PCM at [targetSampleRate].
     *
     * @param maxDurationMs ceiling on how much is decoded, so a two-hour podcast cannot exhaust the
     *   heap on a phone. Defaults to [MAX_DECODED_MS].
     */
    fun decode(
        context: Context,
        file: File,
        targetSampleRate: Int = PcmAudio.STUDIO_SAMPLE_RATE,
        maxDurationMs: Long = MAX_DECODED_MS,
        onProgress: (Float) -> Unit = {},
    ): PcmAudio? {
        val raw = decodeToSourceRate(context, file, maxDurationMs, onProgress) ?: return null
        return if (raw.sampleRate == targetSampleRate) raw else raw.resampleTo(targetSampleRate)
    }

    /** Decodes at the file's own sample rate. Useful for tests and for the waveform of an import. */
    fun decodeToSourceRate(
        context: Context,
        file: File,
        maxDurationMs: Long = MAX_DECODED_MS,
        onProgress: (Float) -> Unit = {},
    ): PcmAudio? {
        // A WAV is read directly: it is already PCM, and MediaCodec would only add a generation of
        // rounding to it.
        if (file.extension.equals("wav", ignoreCase = true) || looksLikeRiff(file)) {
            val direct = WavIO.read(file)
            if (direct != null) {
                onProgress(1f)
                return direct
            }
        }
        return decodeWithCodec(file, maxDurationMs, onProgress)
    }

    private fun decodeWithCodec(file: File, maxDurationMs: Long, onProgress: (Float) -> Unit): PcmAudio? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getIntOr(MediaFormat.KEY_SAMPLE_RATE, PcmAudio.STUDIO_SAMPLE_RATE)
            val sourceChannels = format.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
            val channels = sourceChannels.coerceAtMost(2)
            val durationUs = format.getLongOr(MediaFormat.KEY_DURATION, 0L)
            val frameCeiling = if (maxDurationMs > 0L) {
                (maxDurationMs * sampleRate / 1000L).toInt()
            } else {
                Int.MAX_VALUE
            }

            val created = MediaCodec.createDecoderByType(mime)
            codec = created
            created.configure(format, null, null, 0)
            created.start()

            // Allocate from the container's own duration where it knows it (plus a second of slack),
            // and only fall back to the ceiling when it does not. A 40-second voice note must not
            // reserve six minutes of float PCM on the way in.
            val durationFrames = if (durationUs > 0L) (durationUs * sampleRate / 1_000_000L).toInt() else 0
            var capacity = when {
                durationFrames in 1..frameCeiling -> (durationFrames + sampleRate).coerceAtMost(frameCeiling)
                else -> minOf(frameCeiling, sampleRate * 60)
            }.coerceAtLeast(sampleRate / 2)
            var into = FloatArray(capacity * channels)
            var frames = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = created.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = created.getInputBuffer(inputIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            created.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            created.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = created.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder can hand back a different channel count than the container
                        // promised; the samples are interpreted with the channel count from here on.
                    }

                    else -> if (outputIndex >= 0) {
                        val buffer = created.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val needed = frames + info.size / 2 / sourceChannels + 1
                            if (needed > capacity) {
                                // Grow by doubling, capped at the ceiling. Rare enough that the copy is
                                // worth the memory saved on every other file.
                                val grown = minOf(frameCeiling, maxOf(needed, capacity * 2))
                                into = into.copyOf(grown * channels)
                                capacity = grown
                            }
                            frames = appendSamples(buffer, info.size, channels, sourceChannels, into, frames)
                        }
                        created.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }

                if (durationUs > 0L) onProgress((frames * 1000L / sampleRate.toFloat() / durationUs).coerceIn(0f, 1f))
                if (frames >= frameCeiling) outputDone = true
            }

            if (frames == 0) return null
            onProgress(1f)
            PcmAudio(sampleRate, channels, into, frames)
        } catch (error: IOException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        } catch (error: IllegalStateException) {
            null
        } finally {
            codec?.let { runCatching { it.stop() } }
            codec?.let { runCatching { it.release() } }
            runCatching { extractor.release() }
        }
    }

    /** Reads one decoded buffer into the float array, folding channels as it goes. */
    private fun appendSamples(
        buffer: ByteBuffer,
        byteCount: Int,
        channels: Int,
        sourceChannels: Int,
        into: FloatArray,
        framesWritten: Int,
    ): Int {
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val available = byteCount / 2
        var frames = framesWritten
        var index = 0
        while (index + sourceChannels <= available) {
            val base = frames * channels
            if (base + channels > into.size) break
            for (channel in 0 until channels) {
                into[base + channel] = shorts.get(index + channel) / 32768f
            }
            index += sourceChannels
            frames++
        }
        return frames
    }

    /** Cheap check for a RIFF/WAVE header, so a mislabelled file still takes the direct path. */
    private fun looksLikeRiff(file: File): Boolean = try {
        val header = ByteArray(12)
        file.inputStream().use { stream ->
            if (stream.read(header) == header.size) {
                String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE"
            } else {
                false
            }
        }
    } catch (error: IOException) {
        false
    }

    /** Number of decodable audio tracks in a file, used to reject a video the user picked by mistake. */
    fun hasAudio(file: File): Boolean = try {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val found = (0 until extractor.trackCount).any { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.release()
        found
    } catch (error: Exception) {
        false
    }

    /** Estimated duration without decoding, for the import confirmation. */
    fun durationMs(file: File): Long = try {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var duration = 0L
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                duration = max(duration, format.getLongOr(MediaFormat.KEY_DURATION, 0L) / 1000L)
            }
        }
        extractor.release()
        duration
    } catch (error: Exception) {
        0L
    }

    /** A short, safe label for a picker URI, used as the press's provenance line. */
    fun displayName(context: Context, uri: android.net.Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Imported recording"
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() } ?: fallback
                } else {
                    fallback
                }
            } ?: fallback
        } catch (error: Exception) {
            fallback
        }
    }

    private const val TIMEOUT_US = 10_000L

    /** Ten minutes of stereo at 48 kHz is the most this will ever hold. */
    const val MAX_DECODED_MS = 10L * 60L * 1000L

    private fun MediaFormat.getIntOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.getLongOr(key: String, fallback: Long): Long =
        if (containsKey(key)) getLong(key) else fallback

    /** Peak in dBFS, for the level readout. -inf clamps to -60. */
    fun peakDecibels(audio: PcmAudio): Float {
        val peak = audio.peak
        if (peak <= 1e-6f) return -60f
        return (20.0 * kotlin.math.log10(peak.toDouble())).toFloat().coerceIn(-60f, 6f)
    }

}
