package com.vynylrecord.app.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The four sample formats and the extensible header, written by hand here.
 *
 * The app's own writer is exercised in [PcmTest]; this file is about the files the *world* hands over —
 * an editor's 24-bit export, a phone's mono 8-bit memo, a float WAV out of a DAW — because an import that
 * cannot read those is not an import.
 */
class WavCodecTest {

    private val sampleRate = 44_100

    private fun header(
        channels: Int,
        bits: Int,
        format: Int,
        dataBytes: Int,
        extra: ByteArray = ByteArray(0),
    ): ByteArray {
        val fmtSize = if (extra.isEmpty()) 16 else 16 + extra.size
        val buffer = ByteBuffer.allocate(12 + 8 + fmtSize + 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(4 + 8 + fmtSize + 8 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(fmtSize)
        buffer.putShort(format.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * bits / 8)
        buffer.putShort((channels * bits / 8).toShort())
        buffer.putShort(bits.toShort())
        if (extra.isNotEmpty()) buffer.put(extra)
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        return buffer.array()
    }

    private fun wav(channels: Int, bits: Int, samples: IntArray): ByteArray {
        val bytesPerSample = bits / 8
        val dataBytes = samples.size * bytesPerSample
        val out = ByteArrayOutputStream()
        out.write(header(channels, bits, format = 1, dataBytes = dataBytes))
        samples.forEach { sample ->
            when (bytesPerSample) {
                1 -> out.write(sample and 0xFF)
                2 -> {
                    out.write(sample and 0xFF)
                    out.write((sample shr 8) and 0xFF)
                }
                3 -> {
                    out.write(sample and 0xFF)
                    out.write((sample shr 8) and 0xFF)
                    out.write((sample shr 16) and 0xFF)
                }
                else -> {
                    out.write(sample and 0xFF)
                    out.write((sample shr 8) and 0xFF)
                    out.write((sample shr 16) and 0xFF)
                    out.write((sample shr 24) and 0xFF)
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a 16-bit stereo file reads back as expected`() {
        val bytes = wav(channels = 2, bits = 16, samples = intArrayOf(32_767, -32_768, 16_384, -16_384))
        val read = WavCodec.read(ByteArrayInputStream(bytes))
        assertNotNull(read)
        assertEquals(2, read!!.channels)
        assertEquals(2, read.frameCount)
        assertEquals(sampleRate, read.sampleRate)
        assertEquals(1f, read.peak, 1e-3f)
        assertEquals(0.9999f, read.samples[0], 1e-3f)
        assertEquals(-1f, read.samples[1], 1e-3f)
        assertEquals(0.5f, read.samples[2], 1e-3f)
    }

    @Test
    fun `8-bit unsigned samples are centred`() {
        // 128 is silence in 8-bit unsigned PCM; 255 is full scale positive.
        val bytes = wav(channels = 1, bits = 8, samples = intArrayOf(128, 255, 0))
        val header = WavCodec.readHeader(ByteArrayInputStream(bytes))
        assertNotNull(header)
        assertEquals(8, header!!.bitsPerSample)
        assertEquals(1, header.channels)
        assertEquals(3, header.frameCount)

        val read = WavCodec.read(ByteArrayInputStream(bytes))
        assertNotNull(read)
        // Mono folds into a stereo pair, so every sample appears twice.
        assertEquals(2, read!!.channels)
        assertEquals(0f, read.samples[0], 0.01f)
        assertTrue(read.samples[2] > 0.9f)
    }

    @Test
    fun `24-bit samples keep their resolution`() {
        val bytes = wav(channels = 2, bits = 24, samples = intArrayOf(0x400000, -0x400000, 0x7FFFFF, 0))
        val header = WavCodec.readHeader(ByteArrayInputStream(bytes))
        assertNotNull(header)
        assertEquals(24, header!!.bitsPerSample)
        assertEquals(6, header.bytesPerFrame)

        val read = WavCodec.read(ByteArrayInputStream(bytes))
        assertNotNull(read)
        assertEquals(0.5f, read!!.samples[0], 0.01f)
        assertEquals(-0.5f, read.samples[1], 0.01f)
        assertTrue(read.samples[2] > 0.99f)
    }

    @Test
    fun `32-bit pcm and float are told apart`() {
        val pcm = wav(channels = 2, bits = 32, samples = intArrayOf(1_073_741_824, -1_073_741_824, 0, 0))
        val pcmRead = WavCodec.read(ByteArrayInputStream(pcm))
        assertNotNull(pcmRead)
        assertEquals(0.5f, pcmRead!!.samples[0], 0.01f)
        assertEquals(-0.5f, pcmRead.samples[1], 0.01f)

        val floats = floatArrayOf(0.25f, -0.75f)
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buffer.putFloat(it) }
        val floatBytes = ByteArrayOutputStream().apply {
            write(header(channels = 2, bits = 32, format = 3, dataBytes = 8))
            write(buffer.array())
        }.toByteArray()

        val floatRead = WavCodec.read(ByteArrayInputStream(floatBytes))
        assertNotNull(floatRead)
        assertEquals(0.25f, floatRead!!.samples[0], 1e-4f)
        assertEquals(-0.75f, floatRead.samples[1], 1e-4f)
    }

    @Test
    fun `a 40-byte extensible fmt chunk is understood`() {
        // 22 bytes of extension: cbSize, valid bits, channel mask, and the sub-format GUID.
        val extra = ByteArray(22).apply {
            this[0] = 22
            this[2] = 24
            // The last 16 bytes carry the PCM sub-format GUID.
            this[6] = 0x01
        }
        val bytes = ByteArrayOutputStream().apply {
            write(header(channels = 2, bits = 24, format = 0xFFFE, dataBytes = 12, extra = extra))
            write(byteArrayOf(0, 0, 0x40, 0, 0, 0, 0, 0, 0x80, 0, 0))
        }.toByteArray()

        val header = WavCodec.readHeader(ByteArrayInputStream(bytes))
        assertNotNull(header)
        // 12 bytes of RIFF header + 8 chunk header + the 40-byte fmt chunk + 8 bytes of data header.
        assertEquals(68L, header!!.dataOffset)
        assertEquals(2, header.channels)
        assertEquals(24, header.bitsPerSample)
        assertEquals(2, header.frameCount)

        val read = WavCodec.read(ByteArrayInputStream(bytes))
        assertNotNull(read)
        assertEquals(0.5f, read!!.samples[0], 0.01f)
    }

    @Test
    fun `an unknown chunk before the audio is skipped`() {
        val out = ByteArrayOutputStream()
        val audio = wav(channels = 2, bits = 16, samples = intArrayOf(0, 0, 1_000, 1_000))
        out.write(audio, 0, 12)
        // LIST chunk with 6 bytes of payload.
        out.write("LIST".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(6).array())
        out.write("INFOxy".toByteArray())
        out.write(audio, 12, audio.size - 12)

        val read = WavCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(read)
        assertEquals(2, read!!.frameCount)
        assertEquals(sampleRate, read.sampleRate)
    }

    @Test
    fun `a header with a bogus size is refused rather than trusted`() {
        val bytes = header(channels = 2, bits = 16, format = 1, dataBytes = 4_000_000)
        val short = ByteArrayOutputStream().apply {
            write(bytes)
            write(ByteArray(64))
        }.toByteArray()

        val read = WavCodec.readHeader(ByteArrayInputStream(short))
        assertNotNull(read)
        // The reader clamps to what is really there instead of reading past the end of the stream.
        assertTrue(read!!.dataBytes > 0)

        assertNull(WavCodec.readHeader(ByteArrayInputStream(ByteArray(12))))
        assertNull(WavCodec.readHeader(ByteArrayInputStream("RIFFxxxxWAVEfmt ".toByteArray())))
    }

    @Test
    fun `a mono file is widened into both channels`() {
        val bytes = wav(channels = 1, bits = 16, samples = intArrayOf(16_384, -16_384))
        val read = WavCodec.read(ByteArrayInputStream(bytes))
        assertNotNull(read)
        assertEquals(2, read!!.channels)
        assertEquals(2, read.frameCount)
        // One microphone is one channel wide; a record is not. The mono sample must appear on both sides
        // rather than importing a voice memo that only plays in one ear.
        assertEquals(read.samples[0], read.samples[1], 0f)
        assertEquals(read.samples[2], read.samples[3], 0f)
        assertEquals(0.5f, read.samples[0], 0.01f)
        assertEquals(-0.5f, read.samples[2], 0.01f)
    }

    @Test
    fun `a mono file reads as stereo through the streaming reader`() {
        val file = java.nio.file.Files.createTempFile("vynyl-mono", ".wav").toFile()
        try {
            file.writeBytes(wav(channels = 1, bits = 16, samples = intArrayOf(8_192, 8_192, 8_192, 8_192)))
            WavCodec.Reader(file).use { reader ->
                assertEquals(1, reader.sourceChannels)
                assertEquals(4, reader.frameCount)
                val block = FloatArray(8 * 2)
                assertEquals(4, reader.read(block, 0, 8))
                assertEquals(0.25f, block[0], 0.01f)
                assertEquals(0.25f, block[1], 0.01f)
                assertEquals(block[0], block[1], 0f)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `multichannel audio is folded to stereo`() {
        val channels = 6
        val frames = 4
        val interleaved = IntArray(frames * channels) { index -> if (index % channels == 0) 16_384 else 0 }
        val bytes = ByteArrayOutputStream().apply {
            write(header(channels = channels, bits = 16, dataBytes = interleaved.size * 2))
            interleaved.forEach { sample ->
                write(sample and 0xFF)
                write((sample shr 8) and 0xFF)
            }
        }.toByteArray()

        val read = WavCodec.read(ByteArrayInputStream(bytes))
        assertNotNull(read)
        assertEquals(2, read!!.channels)
        assertEquals(frames, read.frameCount)
        assertEquals(0.5f, read.samples[0], 0.02f)
    }

    @Test
    fun `silence stays silent and does not produce nan`() {
        val bytes = wav(channels = 2, bits = 16, samples = IntArray(2_000))
        val read = WavCodec.read(ByteArrayInputStream(bytes))
        assertNotNull(read)
        assertEquals(0f, read!!.peak, 0f)
        assertTrue(read.samples.none { it.isNaN() || it.isInfinite() })
        assertFalse(read.frameCount <= 0)
    }
}
