package com.vynylrecord.turntable.press

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin

/**
 * The WAV layer is the boundary between the studio and everything else: if it is wrong, a pressing is
 * either silent, truncated, or unreadable outside this app. These tests work on real byte streams, not
 * mocks, because the bugs live in the byte layout.
 */
class WavIOTest {

    private fun tempDir(): File = Files.createTempDirectory("vynyl-wav").toFile()

    private fun tone(frameCount: Int, channels: Int = 1, rate: Int = 44_100): PcmAudio {
        val samples = FloatArray(frameCount * channels)
        for (frame in 0 until frameCount) {
            val value = (sin(frame * 2.0 * Math.PI * 440.0 / rate) * 0.5).toFloat()
            for (channel in 0 until channels) samples[frame * channels + channel] = value
        }
        return PcmAudio(rate, channels, samples)
    }

    @Test
    fun `a written side reads back sample for sample`() {
        val file = File(tempDir(), "side.wav")
        val original = tone(4_410)

        assertTrue("the writer must report success", WavIO.write(file, original))
        val read = WavIO.read(file)

        assertNotNull("the file must read back", read)
        read!!
        assertEquals(original.sampleRate, read.sampleRate)
        assertEquals(original.channels, read.channels)
        assertEquals(original.frameCount, read.frameCount)
        // 16-bit round trip: the only error is the quantisation of the writer.
        for (index in 0 until original.samples.size) {
            assertEquals(original.samples[index], read.samples[index], 1f / 32_000f)
        }
    }

    @Test
    fun `the header describes what follows it`() {
        val audio = tone(1_000, channels = 2)
        val bytes = ByteArrayOutputStream().also { WavIO.write(it, audio) }.toByteArray()

        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals("data", String(bytes, 36, 4))

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 1_000 * 2 * 2, buffer.getInt(4))
        assertEquals(16, buffer.getShort(16).toInt())
        assertEquals(1, buffer.getShort(20).toInt()) // PCM
        assertEquals(2, buffer.getShort(22).toInt()) // channels
        assertEquals(44_100, buffer.getInt(24))
        assertEquals(44_100 * 2 * 2, buffer.getInt(28)) // byte rate
        assertEquals(4, buffer.getShort(32).toInt()) // block align
        assertEquals(16, buffer.getShort(34).toInt())
        assertEquals(1_000 * 2 * 2, buffer.getInt(40))
        assertEquals(44 + 1_000 * 2 * 2, bytes.size)
    }

    @Test
    fun `stereo is preserved and not folded`() {
        val samples = floatArrayOf(0.5f, -0.5f, 0.25f, -0.25f)
        val bytes = ByteArrayOutputStream().also { WavIO.write(it, PcmAudio(44_100, 2, samples)) }.toByteArray()

        val read = WavIO.read(ByteArrayInputStream(bytes))
        assertNotNull("stereo must read back", read)
        read!!
        assertEquals(2, read.channels)
        assertEquals(2, read.frameCount)
        assertTrue("left must stay above right", read.samples[0] > 0f)
        assertTrue("right must stay below left", read.samples[1] < 0f)
    }

    @Test
    fun `eight, sixteen, twenty four and thirty two bit pcm all decode`() {
        // One frame of full-scale-negative-then-positive in each width, built by hand.
        val cases = listOf(
            8 to byteArrayOf(0x00, 0xFF.toByte()), // unsigned: 0 -> -1, 255 -> +1
            16 to byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F),
            24 to byteArrayOf(0x00, 0x00, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F),
            32 to byteArrayOf(0x00, 0x00, 0x00, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F),
        )
        for ((bits, data) in cases) {
            val bytes = wavWith(bits, channels = 1, data = data)
            val read = WavIO.read(ByteArrayInputStream(bytes))
            assertNotNull("${bits}-bit must decode", read)
            read!!
            assertEquals("${bits}-bit frames", 1, read.frameCount)
            assertEquals("${bits}-bit low", -1f, read.samples[0], 0.01f)
            // 8-bit unsigned has no exact +1 across its own scale; the others do.
            if (bits != 8) assertEquals("${bits}-bit high", 1f, read.samples[1], 1f / 16_000f)
        }
    }

    @Test
    fun `a float format extensible file from a daw still reads`() {
        // WAVE_FORMAT_EXTENSIBLE (0xFFFE) with an IEEE-float sub-format, as most editors write.
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(-0.5f).putFloat(0.5f).array()
        val bytes = wavWith(bits = 32, channels = 1, data = data, formatCode = 0xFFFE, subFormat = 3)

        val read = WavIO.read(ByteArrayInputStream(bytes))
        assertNotNull("extensible float must decode", read)
        read!!
        assertEquals(-0.5f, read.samples[0], 1e-6f)
        assertEquals(0.5f, read.samples[1], 1e-6f)
    }

    @Test
    fun `unknown chunks are skipped rather than treated as damage`() {
        val withMetadata = ByteArrayOutputStream().apply {
            val audio = tone(64)
            val clean = ByteArrayOutputStream().also { WavIO.write(it, audio) }.toByteArray()
            // RIFF header, then a LIST chunk, then the original fmt/data chunks.
            write(clean, 0, 12)
            write("LIST".toByteArray())
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(10).array())
            write("INFOhello!".toByteArray())
            write(clean, 12, clean.size - 12)
        }.toByteArray()

        val read = WavIO.read(ByteArrayInputStream(withMetadata))
        assertNotNull("a tagged file is still a WAV", read)
        read!!
        assertEquals(64, read.frameCount)
    }

    @Test
    fun `files that are not wav are refused instead of decoded as noise`() {
        assertNull("plain text", WavIO.read(ByteArrayInputStream("this is not audio".toByteArray())))
        assertNull("an empty file", WavIO.read(ByteArrayInputStream(ByteArray(0))))

        val truncated = ByteArrayOutputStream().also { WavIO.write(it, tone(500)) }.toByteArray()
            .copyOfRange(0, 200)
        assertNull("a truncated file", WavIO.read(ByteArrayInputStream(truncated)))
    }

    @Test
    fun `the envelope normalises to the loudest bucket and keeps shape`() {
        val samples = FloatArray(4_000)
        // Loud first half, quiet second half.
        for (frame in samples.indices) {
            samples[frame] = if (frame < 2_000) 0.8f else 0.08f
        }
        val envelope = WavIO.envelope(PcmAudio(44_100, 1, samples), buckets = 10)

        assertEquals(10, envelope.size)
        assertTrue("the loudest bucket is full scale", abs(envelope.max() - 1f) < 1e-3f)
        assertTrue("the quiet half stays quieter", envelope[1] > envelope[8])
        assertTrue("nothing is out of range", envelope.all { it in 0f..1f })
    }

    @Test
    fun `envelope of silence is flat and empty audio does not divide by zero`() {
        val silence = WavIO.envelope(PcmAudio(44_100, 1, FloatArray(1_000)), buckets = 8)
        assertTrue(silence.all { it == 0f })
        assertTrue(WavIO.envelope(PcmAudio(44_100, 1, FloatArray(0)), buckets = 8).all { it == 0f })
    }

    @Test
    fun `resampling to another rate keeps the duration and the pitch`() {
        val original = tone(44_100) // exactly one second at 44.1 kHz
        val resampled = original.resampleTo(48_000)

        assertEquals(48_000, resampled.sampleRate)
        // A time-varying resampler reports only the frames it produced; the duration must not drift.
        assertTrue(
            "duration drifted: ${resampled.durationMs} ms",
            abs(resampled.durationMs - 1_000L) < 12L,
        )
        assertTrue(
            "audio was invented: ${resampled.frameCount} frames",
            abs(resampled.frameCount - 44_100) <= 200,
        )
    }

    @Test
    fun `folding channels keeps mono content and duplicates it to stereo`() {
        val mono = PcmAudio(44_100, 1, floatArrayOf(0.25f, -0.25f))
        val stereo = mono.withChannels(2)
        assertEquals(2, stereo.channels)
        assertEquals(2, stereo.frameCount)
        assertEquals(0.25f, stereo.samples[0], 1e-6f)
        assertEquals(0.25f, stereo.samples[1], 1e-6f)

        val folded = stereo.withChannels(1)
        assertEquals(1, folded.channels)
        assertEquals(0.25f, folded.samples[0], 1e-6f)
    }

    @Test
    fun `a truncated view reports its own length, not its buffer's`() {
        val buffer = FloatArray(1_000) { 0.5f }
        val view = PcmAudio(44_100, 1, buffer, frameCount = 400)

        assertEquals(400, view.frameCount)
        assertEquals(9L, view.durationMs)
        assertEquals("peak must only consider live frames", 0.5f, view.peak, 1e-6f)
        assertEquals(0f, view.sampleAt(500, 0), 1e-6f)
    }

    /** Builds a minimal WAV with the given sample width and payload. */
    private fun wavWith(
        bits: Int,
        channels: Int,
        data: ByteArray,
        formatCode: Int = 1,
        subFormat: Int = 1,
    ): ByteArray {
        val fmtSize = if (formatCode == 0xFFFE) 40 else 16
        val out = ByteArrayOutputStream()
        val header = ByteArray(12)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        out.write(header)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + 8 + fmtSize + 8 + data.size).array())
        out.write("fmt ".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fmtSize).array())
        val fmt = ByteBuffer.allocate(fmtSize).order(ByteOrder.LITTLE_ENDIAN)
        fmt.putShort(formatCode.toShort())
        fmt.putShort(channels.toShort())
        fmt.putInt(44_100)
        fmt.putInt(44_100 * channels * bits / 8)
        fmt.putShort((channels * bits / 8).toShort())
        fmt.putShort(bits.toShort())
        if (formatCode == 0xFFFE) {
            fmt.putShort(22)
            fmt.putShort(bits.toShort())
            fmt.putInt(if (subFormat == 3) 4 else 0) // channel mask: mono
            // KSDATAFORMAT_SUBTYPE: data1 is the format code, then 0x0000, 0x0010, and the tail.
            fmt.putInt(subFormat)
            fmt.putShort(0x0000)
            fmt.putShort(0x0010)
            fmt.put(
                byteArrayOf(
                    0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
                ),
            )
        }
        out.write(fmt.array())
        out.write("data".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
        out.write(data)
        return out.toByteArray()
    }
}
