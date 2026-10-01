package com.vynylrecord.app.core.audio.dsp

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RIFF/WAVE reading and writing.
 *
 * The app decodes to WAV and renders to WAV for one reason: it is the only audio format a phone can
 * write without a licensed encoder, it is lossless, and every tool on earth can open it. The AAC/M4A
 * export is produced separately by MediaCodec for the files that leave the app.
 *
 * The reader handles the four sample formats an editor or a phone recorder will actually hand over —
 * 8-bit unsigned, 16-bit, 24-bit and 32-bit signed PCM, plus 32- and 64-bit float — and the
 * `WAVE_FORMAT_EXTENSIBLE` header that newer tools write. Anything above two channels is folded to
 * stereo the way the web renderer's chain does it, and a mono file is widened to two so that every
 * reader downstream sees interleaved stereo and none of them has to special-case a one-channel stream. Unknown chunks are skipped rather than treated as
 * damage: a file tagged by a music player is still a perfectly good recording.
 */
object WavCodec {

    private const val RIFF = 0x52494646
    private const val WAVE = 0x57415645
    private const val FMT = 0x666D7420
    private const val DATA = 0x64617461
    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    /** What a header actually said. */
    data class Header(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val isFloat: Boolean,
        /** Byte offset of the first audio byte in the stream. */
        val dataOffset: Long,
        val dataBytes: Long,
    ) {
        val bytesPerFrame: Int get() = (bitsPerSample / 8) * channels
        val frameCount: Int get() = if (bytesPerFrame <= 0) 0 else (dataBytes / bytesPerFrame).toInt()
        val durationMs: Long get() = if (sampleRate <= 0) 0L else frameCount * 1000L / sampleRate
    }

    /**
     * Reads just the header, without touching the audio.
     *
     * This is what makes a long import cheap: the duration and the format are known before a single
     * sample is decoded, so a progress bar can be honest and a buffer can be sized.
     */
    @Throws(IOException::class)
    fun readHeader(stream: InputStream): Header? {
        val riff = ByteArray(12)
        if (!readFully(stream, riff)) return null
        if (le32(riff, 0) != RIFF || le32(riff, 8) != WAVE) return null

        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataBytes = 0L
        var dataOffset = -1L
        var consumed = 12L

        val chunkHeader = ByteArray(8)
        while (readFully(stream, chunkHeader)) {
            consumed += 8
            val id = le32(chunkHeader, 0)
            val size = le32(chunkHeader, 4)
            if (size < 0) return null
            when (id) {
                FMT -> {
                    val keep = minOf(size, 40)
                    val fmt = ByteArray(keep)
                    if (!readFully(stream, fmt)) return null
                    if (size > keep && !skip(stream, (size - keep).toLong())) return null
                    consumed += size + (size and 1)
                    format = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4)
                    bits = le16(fmt, 14)
                    if (format == FORMAT_EXTENSIBLE && fmt.size >= 26) {
                        // The real format is the first two bytes of the sub-format GUID.
                        format = le16(fmt, 24)
                    }
                }

                DATA -> {
                    dataOffset = consumed
                    dataBytes = size.toLong() and 0xFFFFFFFFL
                    break
                }

                else -> {
                    if (!skip(stream, size.toLong() + (size and 1))) return null
                    consumed += size + (size and 1)
                }
            }
        }

        if (dataOffset < 0 || channels !in 1..8 || sampleRate <= 0) return null
        if (format != FORMAT_PCM && format != FORMAT_FLOAT) return null
        if (bits !in intArrayOf(8, 16, 24, 32, 64)) return null
        return Header(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bits,
            isFloat = format == FORMAT_FLOAT,
            dataOffset = dataOffset,
            dataBytes = dataBytes,
        )
    }

    /** Reads a whole WAV into memory. Only for short files — see [stream]. */
    @Throws(IOException::class)
    fun read(file: File): FloatPcmBuffer? = file.inputStream().buffered().use { read(it) }

    @Throws(IOException::class)
    fun read(stream: InputStream): FloatPcmBuffer? {
        val header = readHeader(stream) ?: return null
        val frames = header.frameCount
        if (frames <= 0) return null
        // Everything downstream — the master chain, the waveform extractor, the player — speaks
        // interleaved stereo, so a mono file is widened here rather than being threaded through as a
        // one-float-per-frame stream that half the callers would misread.
        val folded = 2
        val out = FloatArray(frames * folded)
        val bytesPerSample = header.bitsPerSample / 8
        val raw = ByteArray(minOf(header.bytesPerFrame * 4_096, maxOf(header.bytesPerFrame, 1) * frames))
        var frame = 0
        val blockFrames = raw.size / header.bytesPerFrame
        while (frame < frames) {
            val want = minOf(blockFrames, frames - frame)
            if (!readFully(stream, raw, want * header.bytesPerFrame)) break
            decodeFrames(raw, header, frame, want, folded, out)
            frame += want
        }
        return FloatPcmBuffer(out, header.sampleRate, folded)
    }

    /**
     * A streaming reader: header first, then blocks of interleaved stereo float.
     *
     * This is what the render pipeline reads the voice through, so a long recording never has to exist
     * in memory in one piece.
     */
    class Reader(private val file: File) : PcmSource {

        private var stream: BufferedInputStream
        private val header: Header
        private val raw: ByteArray
        private val folded: Int

        override val sampleRate: Int get() = header.sampleRate
        override val frameCount: Int get() = header.frameCount

        /** The source's own channel count, before folding. */
        val sourceChannels: Int get() = header.channels

        private var frameCursor = 0

        init {
            val opened = BufferedInputStream(file.inputStream(), 64 * 1024)
            val parsed = readHeader(opened)
            if (parsed == null) {
                opened.close()
                throw IOException("not a WAV file: ${file.name}")
            }
            header = parsed
            stream = opened
            folded = 2
            // 8 192 frames is about 64 KB of raw 16-bit stereo: big enough to amortise the read call,
            // small enough that the block cost stays flat on a low-end device.
            raw = ByteArray(8_192 * header.bytesPerFrame)
        }

        override fun read(out: FloatArray, offset: Int, frames: Int): Int {
            val remaining = header.frameCount - frameCursor
            if (remaining <= 0) return 0
            val want = minOf(frames, remaining, raw.size / header.bytesPerFrame)
            if (want <= 0) return 0
            if (!readFully(stream, raw, want * header.bytesPerFrame)) return 0
            decodeFrames(raw, header, 0, want, folded, out, offset)
            frameCursor += want
            return want
        }

        /** Back to the start, for a loop or a second pass. */
        /**
         * Reads the whole file to find its highest sample, then rewinds.
         *
         * Used to prove that a master is neither silent nor clipped. The scan is one pass over a file the
         * process just wrote, so the pages are still in the cache and it costs milliseconds; that is a
         * much better trade than trusting a number the renderer reported about itself.
         */
        fun measurePeak(): Float {
            val block = FloatArray(8_192 * folded)
            var peak = 0f
            while (true) {
                val read = read(block, 0, block.size / folded)
                if (read <= 0) break
                for (index in 0 until read * folded) {
                    val magnitude = kotlin.math.abs(block[index])
                    if (magnitude > peak) peak = magnitude
                }
            }
            rewind()
            return peak
        }

        fun rewind() {
            runCatching { stream.close() }
            val reopened = BufferedInputStream(file.inputStream(), 64 * 1024)
            val parsed = readHeader(reopened)
            if (parsed == null) {
                reopened.close()
                throw IOException("not a WAV file: ${file.name}")
            }
            stream = reopened
            frameCursor = 0
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }

    private fun decodeFrames(
        raw: ByteArray,
        header: Header,
        startFrame: Int,
        frames: Int,
        folded: Int,
        out: FloatArray,
        outOffset: Int = 0,
    ) {
        val bytesPerSample = header.bitsPerSample / 8
        val bytesPerFrame = header.bytesPerFrame
        val channels = header.channels
        for (index in 0 until frames) {
            val frameBase = (startFrame + index) * bytesPerFrame
            val outBase = outOffset + index * folded
            for (channel in 0 until channels) {
                val value = decodeSample(
                    raw,
                    frameBase + channel * bytesPerSample,
                    header.bitsPerSample,
                    header.isFloat,
                )
                when {
                    // A single microphone is one channel wide; the record is not. Duplicating it keeps the
                    // voice centred instead of leaving one side of the master silent.
                    channels == 1 -> {
                        out[outBase] = value
                        out[outBase + 1] = value
                    }

                    channels == 2 -> out[outBase + channel] = value
                    channel == 0 -> out[outBase] += value
                    channel == 1 -> out[outBase + 1] += value
                    else -> {
                        // Surrounds fold into both sides at a third of their level, as the web
                        // renderer's chain does, so a 5.1 import does not clip on the fold.
                        out[outBase] += value / 3f
                        out[outBase + 1] += value / 3f
                    }
                }
            }
        }
    }

    private fun decodeSample(raw: ByteArray, offset: Int, bits: Int, isFloat: Boolean): Float = when {
        isFloat && bits == 32 -> Float.fromBits(le32(raw, offset))
        isFloat && bits == 64 -> le64(raw, offset).toDouble().toFloat()
        isFloat && bits == 16 -> halfToFloat(le16(raw, offset).toShort())
        bits == 8 -> ((raw[offset].toInt() and 0xFF) - 128) / 128f
        bits == 16 -> le16s(raw, offset) / 32_768f
        bits == 24 -> {
            val low = raw[offset].toInt() and 0xFF
            val mid = raw[offset + 1].toInt() and 0xFF
            val high = raw[offset + 2].toInt()
            (low or (mid shl 8) or (high shl 16)) / 8_388_608f
        }

        bits == 32 -> le32(raw, offset).toFloat() / 2_147_483_648f
        else -> 0f
    }

    /** IEEE 754 half precision, which some recorders write for low-bandwidth voice. */
    private fun halfToFloat(bits: Short): Float {
        val half = bits.toInt() and 0xFFFF
        val sign = half ushr 15
        val exponent = (half ushr 10) and 0x1F
        val mantissa = half and 0x3FF
        val value = when (exponent) {
            0 -> mantissa * 5.960_464_5e-8f
            31 -> if (mantissa == 0) 1f else Float.NaN
            else -> (mantissa + 1024) * (2f.pow(exponent - 25))
        }
        return if (sign == 1) -value else value
    }

    private fun Float.pow(exponent: Int): Float = Math.pow(this.toDouble(), exponent.toDouble()).toFloat()

    /**
     * A streaming 16-bit WAV writer.
     *
     * Writes a placeholder header and patches the two length fields on [finish]. That is what makes an
     * interrupted render leave a *valid* file — playable up to the last flushed frame — instead of a
     * file with a zero-byte data chunk that no player will open. The same trick is used by the recorder.
     */
    class Writer(
        private val file: File,
        override val sampleRate: Int,
        private val channels: Int = 2,
        private val bitsPerSample: Int = 16,
    ) : PcmSink {

        private val randomAccess: RandomAccessFile
        private var framesWritten = 0L
        private var finished = false
        private val scratch: ByteArray

        init {
            file.parentFile?.mkdirs()
            randomAccess = RandomAccessFile(file, "rw")
            randomAccess.setLength(0)
            randomAccess.write(placeholderHeader(sampleRate, channels, bitsPerSample))
            scratch = ByteArray(16_384)
        }

        override fun write(block: FloatArray, offset: Int, frames: Int) {
            val bytesPerSample = bitsPerSample / 8
            var index = 0
            val total = frames * channels
            while (index < total) {
                val chunk = minOf(scratch.size / bytesPerSample, total - index)
                var byteCursor = 0
                for (sample in index until index + chunk) {
                    val value = DspMath.sanitize(block[offset + sample]).coerceIn(-1f, 1f)
                    val quantised = (value * 32_767f).roundToInt().coerceIn(-32_768, 32_767)
                    scratch[byteCursor++] = (quantised and 0xFF).toByte()
                    scratch[byteCursor++] = ((quantised shr 8) and 0xFF).toByte()
                }
                randomAccess.write(scratch, 0, byteCursor)
                index += chunk
            }
            framesWritten += frames
        }

        /** The file's current length, for the low-storage check before a long render. */
        val bytesWritten: Long get() = 44L + framesWritten * channels * (bitsPerSample / 8)

        override fun finish() {
            if (finished) return
            finished = true
            val dataBytes = framesWritten * channels * (bitsPerSample / 8)
            randomAccess.seek(0)
            randomAccess.write(finalHeader(sampleRate, channels, bitsPerSample, dataBytes))
            randomAccess.close()
        }

        override fun close() {
            if (!finished) finish()
        }
    }

    /** Writes a whole buffer. Convenient for tests, the artwork path and the export of a short cut. */
    @Throws(IOException::class)
    fun write(file: File, buffer: FloatPcmBuffer) {
        file.outputStream().buffered().use { stream -> write(stream, buffer) }
    }

    @Throws(IOException::class)
    fun write(stream: OutputStream, buffer: FloatPcmBuffer) {
        val channels = buffer.channels
        val dataBytes = buffer.frameCount * channels * 2
        stream.write(finalHeader(buffer.sampleRate, channels, 16, dataBytes.toLong()))
        val scratch = ByteArray(16_384)
        var index = 0
        val total = buffer.frameCount * channels
        while (index < total) {
            val chunk = minOf(scratch.size / 2, total - index)
            var cursor = 0
            for (sample in index until index + chunk) {
                val value = buffer.samples[sample].coerceIn(-1f, 1f)
                val quantised = (value * 32_767f).roundToInt().coerceIn(-32_768, 32_767)
                scratch[cursor++] = (quantised and 0xFF).toByte()
                scratch[cursor++] = ((quantised shr 8) and 0xFF).toByte()
            }
            stream.write(scratch, 0, cursor)
            index += chunk
        }
    }

    private fun placeholderHeader(sampleRate: Int, channels: Int, bits: Int): ByteArray =
        header(sampleRate, channels, bits, 0L)

    private fun finalHeader(sampleRate: Int, channels: Int, bits: Int, dataBytes: Long): ByteArray =
        header(sampleRate, channels, bits, dataBytes)

    /**
     * A canonical 44-byte PCM header.
     *
     * The RIFF and data sizes are 32-bit fields, which caps a WAV at 4 GB. A record that long would be
     * 22 hours, so the cap is documented rather than worked around: [dataBytes] is clamped and the
     * caller is expected to have refused the render long before that.
     */
    private fun header(sampleRate: Int, channels: Int, bits: Int, dataBytes: Long): ByteArray {
        val bytes = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val blockAlign = channels * bits / 8
        val clamped = dataBytes.coerceIn(0L, 0xFFFF_FFF0L)
        bytes.put("RIFF".toByteArray())
        bytes.putInt((36L + clamped).toInt())
        bytes.put("WAVE".toByteArray())
        bytes.put("fmt ".toByteArray())
        bytes.putInt(16)
        bytes.putShort(1)                                   // PCM
        bytes.putShort(channels.toShort())
        bytes.putInt(sampleRate)
        bytes.putInt(sampleRate * blockAlign)               // byte rate
        bytes.putShort(blockAlign.toShort())
        bytes.putShort(bits.toShort())
        bytes.put("data".toByteArray())
        bytes.putInt(clamped.toInt())
        return bytes.array()
    }

    // ---------------------------------------------------------------- little-endian helpers

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le16s(bytes: ByteArray, offset: Int): Float {
        val raw = le16(bytes, offset)
        return (if (raw >= 0x8000) raw - 0x10000 else raw).toFloat()
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun le64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun readFully(stream: InputStream, target: ByteArray, length: Int = target.size): Boolean {
        var read = 0
        while (read < length) {
            val count = stream.read(target, read, length - read)
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
                if (stream.read() < 0) return false
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return true
    }

    /** Absolute sample distance between two buffers — used by the tests. */
    fun maxDifference(a: FloatArray, b: FloatArray): Float {
        var worst = 0f
        val count = minOf(a.size, b.size)
        for (index in 0 until count) {
            val difference = abs(a[index] - b[index])
            if (difference > worst) worst = difference
        }
        return worst
    }
}
