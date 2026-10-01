package com.vynylrecord.app.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Streaming blocks: sources, sinks, buffers and the loop that sits under a voice. */
class PcmTest {

    private val sampleRate = 44_100

    /** A ramp where every frame is identifiable, so a copy error cannot hide. */
    private fun ramp(frames: Int): FloatArray {
        val block = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            block[frame * 2] = frame.toFloat()
            block[frame * 2 + 1] = -frame.toFloat()
        }
        return block
    }

    @Test
    fun `a buffer source reports its length and then stops`() {
        val source = BufferSource(ramp(100), sampleRate)
        assertEquals(100, source.frameCount)

        val out = FloatArray(64 * 2)
        assertEquals(64, source.read(out, 0, 64))
        assertEquals(0f, out[0], 0f)
        assertEquals(-63f, out[127], 0f)

        val tail = FloatArray(64 * 2)
        assertEquals(36, source.read(tail, 0, 64))
        assertEquals(64f, tail[0], 0f)
        assertEquals(0, source.read(tail, 0, 64))

        source.rewind()
        val again = FloatArray(8 * 2)
        assertEquals(8, source.read(again, 0, 8))
        assertEquals(0f, again[0], 0f)
    }

    @Test
    fun `a buffer source reads into an offset`() {
        val source = BufferSource(ramp(10), sampleRate)
        val out = FloatArray(40)
        assertEquals(4, source.read(out, 16, 4))
        assertEquals(0f, out[16], 0f)
        assertEquals(3f, out[22], 0f)
    }

    @Test
    fun `the interleaved buffer reports frames, peak and duration`() {
        val buffer = FloatPcmBuffer(floatArrayOf(0f, 0f, 0.5f, -0.25f, 1f, -1f), sampleRate)
        assertEquals(3, buffer.frameCount)
        assertEquals(2, buffer.channels)
        assertEquals(1f, buffer.peak, 1e-6f)
        assertEquals(68L, buffer.durationMs)

        val silent = FloatPcmBuffer.silence(4_410, sampleRate)
        assertEquals(4_410, silent.frameCount)
        assertEquals(100L, silent.durationMs)
        assertEquals(0f, silent.peak, 0f)
    }

    @Test
    fun `a loop source fills a request longer than the loop`() {
        val inner = BufferSource(ramp(50), sampleRate)
        val loop = LoopSource(inner, loopFrames = 50, crossfadeFrames = 0)
        val out = FloatArray(120 * 2)
        var read = 0
        while (read < 120) {
            val got = loop.read(out, read * 2, 120 - read)
            if (got <= 0) break
            read += got
        }
        assertEquals(120, read)
        // Frame 50 is frame 0 of the next pass, and the ends must meet without a gap.
        assertEquals(0f, out[100], 0f)
        assertEquals(0f, out[0], 0f)
        assertTrue(out.all { it.isFinite() })
        loop.close()
    }

    @Test
    fun `a cross-faded loop has no seam`() {
        val frames = 400
        val tone = FloatArray(frames * 2) { i ->
            val frame = i / 2
            (0.5f * kotlin.math.sin(2.0 * Math.PI * frame / 40.0)).toFloat()
        }
        val loop = LoopSource(BufferSource(tone, sampleRate), loopFrames = frames, crossfadeFrames = 100)
        val out = FloatArray(1_200 * 2)
        var read = 0
        while (read < 1_200) {
            val got = loop.read(out, read * 2, 1_200 - read)
            if (got <= 0) break
            read += got
        }
        assertEquals(1_200, read)
        // The cross-fade exists so the sample right after the seam does not jump by the full signal swing.
        val seamIndex = 400 * 2
        val jump = kotlin.math.abs(out[seamIndex] - out[seamIndex - 2])
        assertTrue("seam jumped by $jump", jump < 0.5f)
        loop.close()
    }

    @Test
    fun `the wav writer streams frames and finalises the header`() {
        val file = Files.createTempFile("vynyl-stream", ".wav").toFile()
        try {
            val writer = WavCodec.Writer(file, sampleRate, channels = 2, bitsPerSample = 16)
            val block = FloatArray(1_000 * 2) { 0.25f }
            writer.write(block, 0, 1_000)
            assertEquals(44L + 1_000L * 4L, writer.bytesWritten)
            writer.write(block, 0, 500)
            writer.finish()

            assertEquals(44L + 1_500L * 4L, file.length())
            val header = WavCodec.readHeader(file.inputStream())
            assertNotNull(header)
            assertEquals(sampleRate, header!!.sampleRate)
            assertEquals(2, header.channels)
            assertEquals(16, header.bitsPerSample)
            assertEquals(1_500, header.frameCount)
            assertEquals(34L, header.durationMs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a finished wav reads back with the right values`() {
        val file = Files.createTempFile("vynyl-roundtrip", ".wav").toFile()
        try {
            val source = FloatArray(2_048 * 2) { index ->
                if (index % 2 == 0) 0.5f else -0.5f
            }
            WavCodec.write(file, FloatPcmBuffer(source, sampleRate, 2))
            val read = WavCodec.read(file)
            assertNotNull(read)
            assertEquals(2_048, read!!.frameCount)
            assertEquals(sampleRate, read.sampleRate)
            assertEquals(2, read.channels)
            assertEquals(0.5f, read.peak, 1e-3f)
            assertEquals(source.size, read.samples.size)
            assertTrue(WavCodec.maxDifference(source, read.samples) < 1e-3f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a streaming reader can be read in blocks`() {
        val file = Files.createTempFile("vynyl-reader", ".wav").toFile()
        try {
            val frames = 5_000
            val source = FloatArray(frames * 2) { index -> kotlin.math.sin(index / 3.0).toFloat() * 0.4f }
            WavCodec.write(file, FloatPcmBuffer(source, sampleRate, 2))

            WavCodec.Reader(file).use { reader ->
                assertEquals(sampleRate, reader.sampleRate)
                assertEquals(frames, reader.frameCount)
                val out = FloatArray(512 * 2)
                var total = 0
                while (true) {
                    val got = reader.read(out, 0, 512)
                    if (got <= 0) break
                    total += got
                }
                assertEquals(frames, total)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `truncated and foreign files do not crash the reader`() {
        val truncated = Files.createTempFile("vynyl-trunc", ".wav").toFile()
        try {
            truncated.writeBytes(ByteArray(40))
            assertEquals(null, WavCodec.readHeader(truncated.inputStream()))
            assertEquals(null, WavCodec.read(truncated))
        } finally {
            truncated.delete()
        }

        val foreign = Files.createTempFile("vynyl-notwav", ".wav").toFile()
        try {
            foreign.writeBytes("this is not a waveform at all".toByteArray())
            assertEquals(null, WavCodec.readHeader(foreign.inputStream()))
            assertFalse(foreign.length() > 1_000L)
        } finally {
            foreign.delete()
        }
    }

    @Test
    fun `the sink contract is honoured by the file writer`() {
        val file = File(Files.createTempDirectory("vynyl-sink").toFile(), "nested/stream.wav")
        try {
            val sink: PcmSink = WavCodec.Writer(file, sampleRate)
            assertTrue("the writer did not create its parent directory", file.isFile)
            sink.write(FloatArray(2 * 2) { 0.1f }, 0, 2)
            sink.close()
            assertTrue(file.length() > 44L)
        } finally {
            file.parentFile?.parentFile?.deleteRecursively()
        }
    }
}
