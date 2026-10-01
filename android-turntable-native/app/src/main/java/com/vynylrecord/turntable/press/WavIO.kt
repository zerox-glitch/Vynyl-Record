package com.vynylrecord.turntable.press

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * De-interleaved-agnostic PCM: samples are interleaved, one frame per channel, in -1..1 floats.
 *
 * Float samples are used everywhere inside the studio because every effect in the chain is a
 * multiply-add and half of them would clip or quantise if they ran on 16-bit integers. The pressed
 * file is written back out as 16-bit PCM at the end, which is what a record is.
 */
class PcmAudio(
    val sampleRate: Int,
    /** 1 or 2. Sources with more channels are folded down before they reach this type. */
    val channels: Int,
    val samples: FloatArray,
    /**
     * Frames of audio in [samples].
     *
     * Carried separately from the array's size so stages that shorten a side — trimming silence, for
     * one — can shift the audio down inside the buffer they were handed instead of allocating a
     * second copy. A five-minute side is tens of megabytes in float PCM, so the copies a naive chain
     * makes are the difference between pressing a side and running out of heap on a cheap phone.
     */
    frameCount: Int = samples.size / channels,
) {

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels in 1..2) { "channels must be 1 or 2, was $channels" }
        require(frameCount >= 0 && frameCount * channels <= samples.size) {
            "frameCount $frameCount does not fit ${samples.size} samples at $channels channels"
        }
    }

    /** The number of frames actually in the buffer. */
    val frameCount: Int = frameCount

    val durationMs: Long get() = this.frameCount * 1000L / sampleRate

    /** Peak absolute sample, 0 when empty. */
    val peak: Float
        get() {
            var peak = 0f
            for (frame in 0 until frameCount) {
                for (channel in 0 until channels) {
                    val magnitude = kotlin.math.abs(samples[frame * channels + channel])
                    if (magnitude > peak) peak = magnitude
                }
            }
            return peak
        }

    /** RMS across all channels, used for the level meter and the waveform envelope. */
    val rms: Float
        get() {
            val count = frameCount * channels
            if (count == 0) return 0f
            var sum = 0.0
            for (index in 0 until count) sum += samples[index].toDouble() * samples[index]
            return kotlin.math.sqrt(sum / count).toFloat()
        }

    fun sampleAt(frame: Int, channel: Int): Float {
        if (frame < 0 || frame >= frameCount) return 0f
        return samples[frame * channels + channel.coerceIn(0, channels - 1)]
    }

    /** A copy with the given number of channels, folding or duplicating as needed. */
    fun withChannels(target: Int): PcmAudio {
        if (target == channels) return this
        val out = FloatArray(frameCount * target)
        for (frame in 0 until frameCount) {
            if (target == 1) {
                var sum = 0f
                for (channel in 0 until channels) sum += samples[frame * channels + channel]
                out[frame] = sum / channels
            } else {
                val mono = if (channels == 1) {
                    samples[frame]
                } else {
                    var sum = 0f
                    for (channel in 0 until channels) sum += samples[frame * channels + channel]
                    sum / channels
                }
                out[frame * 2] = if (channels == 1) samples[frame] else samples[frame * channels]
                out[frame * 2 + 1] = if (channels == 1) mono else samples[frame * channels + 1]
            }
        }
        return PcmAudio(sampleRate, target, out)
    }

    /**
     * A view of this audio with [frames] taken from the head, sharing the same buffer.
     *
     * Used by the trim stage, which shifts the tail down and then hands back a shorter view rather
     * than a fresh allocation.
     */
    fun withFrameCount(frames: Int): PcmAudio =
        PcmAudio(sampleRate, channels, samples, frames.coerceIn(0, frameCount))

    /**
     * Linear-interpolating resampler. Used to normalise every source to one rate before pressing,
     * and by the wow-and-flutter stage, which is a time-varying version of the same thing.
     */
    fun resampleTo(targetRate: Int): PcmAudio {
        if (targetRate == sampleRate) return this
        return resampleWith(sampleRate.toDouble() / targetRate.toDouble()) { 1.0 }
    }

    /**
     * Resamples while a rate multiplier drifts over time.
     *
     * [rateAt] returns how fast the *source* should be read at a given output frame: 1.0 is nominal,
     * 1.005 reads slightly fast, which lowers the pitch. This is the mechanism behind wow (slow,
     * sub-1 Hz drift) and flutter (fast, several Hz).
     */
    fun resampleWith(baseRatio: Double, rateAt: (Double) -> Double): PcmAudio {
        if (frameCount == 0) return PcmAudio(sampleRate, channels, FloatArray(0))
        val outputFrames = kotlin.math.max(1, (frameCount / baseRatio).toInt())
        val out = FloatArray(outputFrames * channels)
        var position = 0.0
        for (frame in 0 until outputFrames) {
            position += baseRatio * rateAt(frame.toDouble())
            if (position >= frameCount - 1) {
                // The source ran out: keep the frames actually produced, not the whole buffer.
                return PcmAudio(sampleRate, channels, out, frame)
            }
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            for (channel in 0 until channels) {
                val a = samples[index * channels + channel]
                val b = samples[(index + 1) * channels + channel]
                out[frame * channels + channel] = a + (b - a) * fraction
            }
        }
        return PcmAudio(sampleRate, channels, out)
    }

    companion object {
        const val STUDIO_SAMPLE_RATE = 44_100

        fun silence(sampleRate: Int, channels: Int, frameCount: Int): PcmAudio =
            PcmAudio(sampleRate, channels, FloatArray(frameCount * channels))
    }
}

/**
 * WAV reading and writing.
 *
 * The reader accepts what a record actually arrives as: 8-bit unsigned, 16-bit, 24-bit and 32-bit
 * signed PCM, and 32-bit float, mono or stereo, including the `WAVE_FORMAT_EXTENSIBLE` header that
 * most DAWs write. Files with more than two channels are folded to stereo. Anything it cannot make
 * sense of returns null — the studio reports "that file could not be read" rather than crashing.
 *
 * The writer emits the canonical 44-byte header followed by 16-bit PCM, which every player on every
 * platform reads.
 */
object WavIO {

    private const val RIFF = 0x52494646 // "RIFF"
    private const val WAVE = 0x57415645 // "WAVE"
    private const val FMT = 0x666D7420 // "fmt "
    private const val DATA = 0x64617461 // "data"
    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    /** Reads a WAV file, or returns null when the file is not a WAV this reader understands. */
    fun read(file: File): PcmAudio? = try {
        file.inputStream().buffered().use { read(it) }
    } catch (error: IOException) {
        null
    }

    fun read(stream: InputStream): PcmAudio? {
        val header = ByteArray(12)
        if (!readFully(stream, header)) return null
        if (le32(header, 0) != RIFF || le32(header, 8) != WAVE) return null

        var format = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLength = 0

        // Walk the chunk table. Unknown chunks (LIST, fact, bext, cue, id3 ...) are skipped rather
        // than treated as damage: a file tagged by a music player is still a perfectly good WAV.
        val chunkHeader = ByteArray(8)
        while (readFully(stream, chunkHeader)) {
            val id = le32(chunkHeader, 0)
            val size = le32(chunkHeader, 4)
            if (size < 0) return null
            when (id) {
                FMT -> {
                    val fmt = ByteArray(minOf(size, 40))
                    if (!readFully(stream, fmt)) return null
                    if (size > fmt.size) skip(stream, (size - fmt.size).toLong())
                    format = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4)
                    bitsPerSample = le16(fmt, 14)
                    if (format == FORMAT_EXTENSIBLE && fmt.size >= 26) {
                        // The real format is the first two bytes of the sub-format GUID.
                        format = le16(fmt, 24)
                    }
                }

                DATA -> {
                    dataOffset = 0 // the stream is positioned here; the bytes follow
                    dataLength = size
                    break
                }

                else -> if (!skip(stream, (size + (size and 1)).toLong())) return null
            }
        }

        if (dataOffset < 0 || channels !in 1..8 || sampleRate <= 0) return null
        if (format != FORMAT_PCM && format != FORMAT_FLOAT) return null

        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) return null
        val bytesPerFrame = bytesPerSample * channels
        if (bytesPerFrame <= 0) return null

        val available = minOf(dataLength, Int.MAX_VALUE - bytesPerFrame).coerceAtLeast(0)
        val frames = available / bytesPerFrame
        if (frames <= 0) return null

        val raw = ByteArray(frames * bytesPerFrame)
        if (!readFully(stream, raw)) return null

        val folded = if (channels <= 2) channels else 2
        val out = FloatArray(frames * folded)
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val value = decodeSample(raw, (frame * channels + channel) * bytesPerSample, bitsPerSample, format)
                when {
                    channels == 1 -> out[frame] = value
                    channels == 2 -> out[frame * 2 + channel] = value
                    channel == 0 -> out[frame * 2] += value
                    channel == 1 -> out[frame * 2 + 1] += value
                    else -> {
                        // Fold surrounds into both sides at a third of their level.
                        out[frame * 2] += value / 3f
                        out[frame * 2 + 1] += value / 3f
                    }
                }
            }
        }
        PcmAudio(sampleRate, folded, out)
    }

    /** Writes 16-bit PCM. Returns false when the file could not be written. */
    fun write(file: File, audio: PcmAudio): Boolean = try {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { write(it, audio) }
        true
    } catch (error: IOException) {
        false
    }

    fun write(stream: OutputStream, audio: PcmAudio) {
        val channels = audio.channels
        val bitsPerSample = 16
        val bytesPerFrame = channels * bitsPerSample / 8
        val dataBytes = audio.frameCount * bytesPerFrame

        stream.write(wavHeader(channels, audio.sampleRate, bitsPerSample, dataBytes))

        // Clamp once, at the boundary: everything upstream works in floats precisely so the clipping
        // decision is made in exactly one place.
        val buffer = ByteArray(8192)
        var index = 0
        for (sample in audio.samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            val value = (clamped * 32767f).roundToInt()
            buffer[index++] = (value and 0xFF).toByte()
            buffer[index++] = ((value shr 8) and 0xFF).toByte()
            if (index == buffer.size) {
                stream.write(buffer)
                index = 0
            }
        }
        if (index > 0) stream.write(buffer, 0, index)
    }

    /**
     * The 44-byte canonical WAV header.
     *
     * Shared with the recorder, which streams: it writes this header with a placeholder size and
     * patches the two length fields once the recording stops, so an hour-long voice note never has
     * to be held in memory.
     */
    fun wavHeader(channels: Int, sampleRate: Int, bitsPerSample: Int, dataBytes: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)
        writeAscii(header, 0, "RIFF")
        le32(header, 4, 36 + dataBytes)
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        le32(header, 16, 16)
        le16(header, 20, FORMAT_PCM)
        le16(header, 22, channels)
        le32(header, 24, sampleRate)
        le32(header, 28, byteRate)
        le16(header, 32, blockAlign)
        le16(header, 34, bitsPerSample)
        writeAscii(header, 36, "data")
        le32(header, 40, dataBytes)
        return header
    }

    /**
     * RMS envelope for the waveform display: [buckets] values in 0..1, normalised to the loudest
     * bucket so a quiet recording still shows its shape.
     */
    fun envelope(audio: PcmAudio, buckets: Int = 128): FloatArray {
        if (buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        if (audio.frameCount == 0) return out

        val framesPerBucket = audio.frameCount.toDouble() / buckets
        for (bucket in 0 until buckets) {
            val start = (bucket * framesPerBucket).toInt()
            val end = (((bucket + 1) * framesPerBucket).toInt()).coerceAtMost(audio.frameCount)
            if (end <= start) continue
            var sum = 0.0
            var count = 0
            for (frame in start until end) {
                for (channel in 0 until audio.channels) {
                    val sample = audio.samples[frame * audio.channels + channel].toDouble()
                    sum += sample * sample
                    count++
                }
            }
            out[bucket] = if (count > 0) sqrt(sum / count).toFloat() else 0f
        }

        var peak = 0f
        for (value in out) if (value > peak) peak = value
        if (peak > 1e-5f) {
            for (index in out.indices) out[index] = (out[index] / peak).coerceIn(0f, 1f)
        }
        return out
    }

    // ------------------------------------------------------------------ little-endian plumbing

    private fun decodeSample(raw: ByteArray, offset: Int, bitsPerSample: Int, format: Int): Float = when {
        format == FORMAT_FLOAT && bitsPerSample == 32 -> Float.fromBits(le32(raw, offset))
        bitsPerSample == 8 -> // 8-bit WAV is unsigned
            ((raw[offset].toInt() and 0xFF) - 128) / 128f

        bitsPerSample == 16 -> le16s(raw, offset) / 32768f
        bitsPerSample == 24 -> {
            val low = raw[offset].toInt() and 0xFF
            val mid = raw[offset + 1].toInt() and 0xFF
            val high = raw[offset + 2].toInt() // sign-extended
            (low or (mid shl 8) or (high shl 16)) / 8_388_608f
        }

        bitsPerSample == 32 -> le32(raw, offset).toFloat() / 2_147_483_648f
        else -> 0f
    }

    private fun readFully(stream: InputStream, target: ByteArray): Boolean {
        var read = 0
        while (read < target.size) {
            val count = stream.read(target, read, target.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    private fun skip(stream: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                // Not every stream can skip; fall back to reading and discarding.
                if (stream.read() < 0) return false
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return true
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le16s(bytes: ByteArray, offset: Int): Float {
        val value = le16(bytes, offset)
        return (if (value >= 0x8000) value - 0x10000 else value) / 32768f
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun le32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun le16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeAscii(bytes: ByteArray, offset: Int, text: String) {
        for (index in text.indices) bytes[offset + index] = text[index].code.toByte()
    }

    /** True when the stream simply ran out; used by the reader to tell damage from a short file. */
    internal fun isTruncated(error: IOException): Boolean = error is EOFException
}
