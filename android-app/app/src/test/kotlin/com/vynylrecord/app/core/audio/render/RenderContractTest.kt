package com.vynylrecord.app.core.audio.render

import com.vynylrecord.app.core.audio.dsp.BufferSource
import com.vynylrecord.app.core.audio.dsp.FloatPcmBuffer
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.model.VinylPresetId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * The contract between a source recording and a finished master.
 *
 * This is the acceptance test the app is built around: every preset is pressed from a set of awkward
 * fixtures — quiet speech, near-peak input, silence, mono, a second sample rate — and the master is then
 * validated the way the render worker validates it. Nothing here is simulated: the audio really is
 * processed block by block, written to a WAV, and read back.
 */
class RenderContractTest {

    private lateinit var workspace: File

    @Before
    fun setUp() {
        workspace = File.createTempFile("vynyl-render", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    /** Speech-shaped audio: two formants under a syllabic envelope, with real pauses between sentences. */
    private fun speech(seconds: Int, sampleRate: Int, amplitude: Float): FloatArray {
        val frames = seconds * sampleRate
        return FloatArray(frames * 2) { index ->
            val frame = index / 2
            val t = frame.toDouble() / sampleRate
            val syllable = if ((t * 2.5).toInt() % 5 == 4) 0.05 else 1.0
            val voice = sin(2.0 * Math.PI * 180.0 * t) * 0.55 +
                sin(2.0 * Math.PI * 720.0 * t) * 0.30 +
                sin(2.0 * Math.PI * 2_400.0 * t) * 0.15
            (voice * syllable * amplitude).toFloat()
        }
    }

    private fun music(seconds: Int, sampleRate: Int): FloatArray {
        val frames = seconds * sampleRate
        return FloatArray(frames * 2) { index ->
            val frame = index / 2
            val t = frame.toDouble() / sampleRate
            val value = if (index % 2 == 0) sin(2.0 * Math.PI * 130.8 * t) else sin(2.0 * Math.PI * 196.0 * t)
            (value * 0.45).toFloat()
        }
    }

    private fun source(samples: FloatArray, sampleRate: Int, channels: Int = 2): BufferSource =
        BufferSource(samples, sampleRate, channels)

    private class Press(
        val file: File,
        val result: MasterRenderer.Result,
    ) {
        val completed: MasterRenderer.Result.Completed
            get() = result as MasterRenderer.Result.Completed
    }

    private fun press(
        preset: VinylPresetId,
        voice: FloatArray,
        sampleRate: Int,
        seed: Long = 20260930L,
        music: FloatArray? = null,
        leadInMs: Int = 0,
    ): Press {
        val file = File(workspace, "${preset.id}-${System.nanoTime()}.wav")
        val renderer = MasterRenderer(sampleRate, blockFrames = 8_192)
        val leadInFrames = leadInMs * sampleRate / 1000
        val result = renderer.render(
            voice = source(voice, sampleRate),
            music = music?.let { source(it, sampleRate) },
            recipe = preset.recipe,
            seed = seed,
            sink = WavCodec.Writer(file, sampleRate, 2, 16),
            leadInFrames = leadInFrames,
        )
        return Press(file, result)
    }

    private fun samples(file: File): FloatArray {
        val buffer = WavCodec.read(file)
        assertNotNull("the master could not be decoded", buffer)
        return buffer!!.samples
    }

    @Test
    fun `every preset presses a decodable master of the right length`() {
        val seconds = 3
        val sampleRate = 44_100
        val voice = speech(seconds, sampleRate, 0.38f)
        VinylPresetId.entries.forEach { preset ->
            val pressed = press(preset, voice, sampleRate, leadInMs = 250)
            assertTrue("${preset.id} failed: ${pressed.result}", pressed.result is MasterRenderer.Result.Completed)

            val expectedFrames = seconds * sampleRate + 250 * sampleRate / 1000
            assertEquals("${preset.id} wrote the wrong number of frames", expectedFrames, pressed.completed.framesWritten)
            assertTrue("${preset.id} is empty", pressed.file.length() > 100_000L)

            val decoded = WavCodec.read(pressed.file)
            assertNotNull("${preset.id} produced a file no decoder can open", decoded)
            assertEquals(sampleRate, decoded!!.sampleRate)
            assertEquals(2, decoded.channels)
            assertEquals(
                "the container and the audio disagree about the length",
                expectedFrames,
                decoded.frameCount,
            )
            assertEquals(
                "the reported duration does not match the file",
                pressed.completed.durationMs,
                decoded.durationMs,
            )
        }
    }

    @Test
    fun `the master validator accepts what the renderer produces`() {
        val sampleRate = 44_100
        val seconds = 2
        VinylPresetId.entries.forEach { preset ->
            val pressed = press(preset, speech(seconds, sampleRate, 0.4f), sampleRate)
            val completed = pressed.completed
            val report = RenderValidator.validate(
                file = pressed.file,
                sizeBytes = pressed.file.length(),
                expectedDurationMs = completed.durationMs,
                measuredDurationMs = completed.durationMs,
                peak = completed.peak,
                decodable = true,
            )
            assertTrue("${preset.id} was rejected: ${report.message}", report.ok)
            assertEquals(completed.durationMs, report.measuredDurationMs)
        }
    }

    @Test
    fun `no preset produces a single clip or a non-finite sample`() {
        val sampleRate = 48_000
        VinylPresetId.entries.forEach { preset ->
            val pressed = press(preset, speech(2, sampleRate, 0.97f), sampleRate)
            val audio = samples(pressed.file)
            var peak = 0f
            audio.forEach { sample ->
                assertTrue("${preset.id} produced $sample", sample.isFinite())
                peak = maxOf(peak, abs(sample))
            }
            assertTrue("${preset.id} is silent", peak > 0.001f)
            assertTrue("${preset.id} clipped at $peak", peak <= preset.recipe.limiterCeil + 0.005f)
        }
    }

    @Test
    fun `a silent source still presses a record with texture`() {
        val sampleRate = 44_100
        val silence = FloatArray(2 * sampleRate * 2)
        VinylPresetId.entries.forEach { preset ->
            val pressed = press(preset, silence, sampleRate)
            val audio = samples(pressed.file)
            assertTrue("${preset.id} produced a digital black record", peak(audio) > 0.0005f)
            // The bed must stay a bed: an ambience that competes with a voice is not ambience.
            assertTrue("${preset.id} produced a loud bed", rms(audio) < 0.12f)
        }
    }

    @Test
    fun `quiet speech is lifted clear of the bed`() {
        val sampleRate = 44_100
        val seconds = 3
        val quiet = speech(seconds, sampleRate, 0.02f)
        // The same seed and the same length means the same bed, so the only difference between these two
        // files is the voice: comparing them is comparing the voice against the surface it sits on.
        val seed = 1234L
        val withVoice = samples(press(VinylPresetId.WARM_VINTAGE, quiet, sampleRate, seed = seed).file)
        val bedOnly = samples(
            press(VinylPresetId.WARM_VINTAGE, FloatArray(quiet.size), sampleRate, seed = seed).file,
        )

        val voiced = windowRms(withVoice, 0.5, 0.6, sampleRate)
        val bed = windowRms(bedOnly, 0.5, 0.6, sampleRate)
        assertTrue("the bed is silent, so this test proves nothing", bed > 1e-4f)
        assertTrue("a quiet voice was lost under the surface: $voiced against $bed", voiced > bed * 1.5f)

        // And a pause must fall back to the bed rather than staying loud.
        val pause = windowRms(withVoice, 1.65, 1.95, sampleRate)
        assertTrue("the pause did not drop back: $pause against $voiced", pause < voiced)
    }

    @Test
    fun `near-peak input is caught without being crushed`() {
        val sampleRate = 44_100
        val loud = speech(2, sampleRate, 0.99f)
        VinylPresetId.entries.forEach { preset ->
            val pressed = press(preset, loud, sampleRate)
            val audio = samples(pressed.file)
            assertTrue("${preset.id} exceeded its ceiling", peak(audio) <= preset.recipe.limiterCeil + 0.005f)
            // A limiter that leaves the record quiet is a limiter that failed.
            assertTrue("${preset.id} came out too quiet: ${peak(audio)}", peak(audio) > 0.3f)
        }
    }

    @Test
    fun `mono sources are pressed as stereo`() {
        val sampleRate = 44_100
        val monoFile = File(workspace, "mono.wav")
        val frames = sampleRate
        val mono = FloatArray(frames) { frame -> (0.4f * sin(2.0 * Math.PI * 220.0 * frame / sampleRate)).toFloat() }
        // A one-channel file, exactly as a phone memo arrives.
        WavCodec.write(monoFile, FloatPcmBuffer(mono, sampleRate, 1))

        WavCodec.Reader(monoFile).use { reader ->
            assertEquals(1, reader.sourceChannels)
            assertEquals(sampleRate, reader.sampleRate)
            val block = FloatArray(1024 * 2)
            val read = reader.read(block, 0, 1024)
            assertTrue(read > 0)
            assertEquals("mono was not widened to both channels", block[0], block[1], 0f)
        }

        val file = File(workspace, "mono-master.wav")
        val result = MasterRenderer(sampleRate).render(
            voice = WavCodec.Reader(monoFile),
            music = null,
            recipe = VinylPresetId.CLEAN_VINYL.recipe,
            seed = 7L,
            sink = WavCodec.Writer(file, sampleRate, 2, 16),
        )
        assertTrue(result is MasterRenderer.Result.Completed)
        val audio = samples(file)
        assertTrue(peak(audio) > 0.01f)
        // A voice recorded on one microphone must not end up in one ear: both sides carry it, and they
        // stay close enough that the mono source is still recognisably mono.
        assertTrue("the left channel is silent", channelRms(audio, 0) > 0.01f)
        assertTrue("the right channel is silent", channelRms(audio, 1) > 0.01f)
        assertTrue(
            "the two sides came out unrelated: ${correlationOfChannels(audio)}",
            correlationOfChannels(audio) > 0.6f,
        )
    }

    @Test
    fun `the same record pressed twice is the same file`() {
        val sampleRate = 44_100
        val voice = speech(2, sampleRate, 0.4f)
        val first = press(VinylPresetId.DUSTY_RECORD, voice, sampleRate, seed = 4242L)
        val second = press(VinylPresetId.DUSTY_RECORD, voice, sampleRate, seed = 4242L)
        assertEquals(first.completed.crackleCount, second.completed.crackleCount)
        assertEquals(first.completed.popCount, second.completed.popCount)
        assertEquals(first.completed.framesWritten, second.completed.framesWritten)
        assertEquals(
            "the two files differ in length",
            first.file.length(),
            second.file.length(),
        )
        assertEquals(
            "a rerender of the same record produced different audio",
            first.file.readBytes().toList(),
            second.file.readBytes().toList(),
        )
    }

    @Test
    fun `two records pressed from one voice are different discs`() {
        val sampleRate = 44_100
        val voice = speech(2, sampleRate, 0.4f)
        val one = press(VinylPresetId.WARM_VINTAGE, voice, sampleRate, seed = 1L)
        val two = press(VinylPresetId.WARM_VINTAGE, voice, sampleRate, seed = 2L)
        assertTrue(
            "two seeds produced identical files",
            one.file.readBytes().toList() != two.file.readBytes().toList(),
        )
    }

    @Test
    fun `the presets are measurably different from one another`() {
        val sampleRate = 44_100
        val voice = speech(2, sampleRate, 0.4f)
        val rendered = VinylPresetId.entries.associateWith { preset ->
            samples(press(preset, voice, sampleRate, seed = 99L).file)
        }
        val ids = rendered.keys.toList()
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val difference = difference(rendered.getValue(ids[i]), rendered.getValue(ids[j]))
                assertTrue("${ids[i]} and ${ids[j]} differed by only $difference", difference > 0.001f)
            }
        }
        // The archival pressing is the darkest: less high end than the clean one.
        assertTrue(
            "the archival press is not darker than the clean one",
            highFrequencyRatio(rendered.getValue(VinylPresetId.RARE_ARCHIVAL)) <
                highFrequencyRatio(rendered.getValue(VinylPresetId.CLEAN_VINYL)),
        )
    }

    @Test
    fun `a background bed is ducked under the voice and does not bury it`() {
        val sampleRate = 44_100
        val voice = speech(3, sampleRate, 0.4f)
        val bed = music(3, sampleRate)
        val withBed = samples(press(VinylPresetId.WARM_VINTAGE, voice, sampleRate, music = bed).file)
        val alone = samples(press(VinylPresetId.WARM_VINTAGE, voice, sampleRate).file)

        // During a pause the bed makes the record louder; during speech the duck keeps it close.
        val pauseWithBed = windowRms(withBed, 1.65, 1.95, sampleRate)
        val pauseAlone = windowRms(alone, 1.65, 1.95, sampleRate)
        assertTrue("the background never appeared", pauseWithBed > pauseAlone)
        val speechWithBed = windowRms(withBed, 0.5, 0.6, sampleRate)
        val speechAlone = windowRms(alone, 0.5, 0.6, sampleRate)
        assertTrue("the background buried the voice", speechWithBed < speechAlone * 1.6f)
    }

    @Test
    fun `a cancelled render leaves no finished master`() {
        val sampleRate = 44_100
        val file = File(workspace, "cancelled.wav")
        var blocks = 0
        val result = MasterRenderer(sampleRate).render(
            voice = source(speech(5, sampleRate, 0.4f), sampleRate),
            music = null,
            recipe = VinylPresetId.CLEAN_VINYL.recipe,
            seed = 5L,
            sink = WavCodec.Writer(file, sampleRate, 2, 16),
            onProgress = { blocks++ },
            isCancelled = { blocks >= 3 },
        )
        assertTrue("a cancelled render was reported as complete", result is MasterRenderer.Result.Cancelled)
        // The blocks that were written are real audio, but the writer was never finished, so the container
        // still says it holds nothing. That is what stops a half-pressed record being mistaken for a master.
        val header = WavCodec.readHeader(file.inputStream())
        assertNotNull(header)
        assertEquals("a cancelled render finalised its header", 0L, header!!.dataBytes)
        // The header is the length check the rest of the app uses, so this frame sees no record either.
        assertNull(WavCodec.read(file))
    }

    @Test
    fun `progress runs from zero to one`() {
        val sampleRate = 44_100
        val progress = ArrayList<Float>()
        val file = File(workspace, "progress.wav")
        MasterRenderer(sampleRate).render(
            voice = source(speech(2, sampleRate, 0.3f), sampleRate),
            music = null,
            recipe = VinylPresetId.DUSTY_RECORD.recipe,
            seed = 3L,
            sink = WavCodec.Writer(file, sampleRate, 2, 16),
            onProgress = { progress += it },
        )
        assertTrue("no progress was reported", progress.size > 4)
        assertTrue(progress.all { it in 0f..1f })
        assertEquals(1f, progress.last(), 1e-6f)
        progress.zipWithNext().forEach { (a, b) -> assertTrue("progress went backwards", b >= a) }
    }

    @Test
    fun `the validator refuses the masters that must never reach the vault`() {
        val good = File(workspace, "good.wav")
        WavCodec.write(good, FloatPcmBuffer(FloatArray(44_100 * 2) { 0.3f }, 44_100, 2))

        assertEquals(
            RenderValidator.Failure.MISSING,
            RenderValidator.validate(File(workspace, "missing.wav"), 0L, 1_000L, 1_000L, 0.5f, true).failure,
        )
        assertEquals(
            RenderValidator.Failure.TOO_SMALL,
            RenderValidator.validate(good, 100L, 1_000L, 1_000L, 0.5f, true).failure,
        )
        assertEquals(
            RenderValidator.Failure.UNDECODABLE,
            RenderValidator.validate(good, good.length(), 1_000L, 0L, 0.5f, false).failure,
        )
        assertEquals(
            RenderValidator.Failure.DURATION_MISMATCH,
            RenderValidator.validate(good, good.length(), 1_000L, 9_000L, 0.5f, true).failure,
        )
        assertEquals(
            RenderValidator.Failure.SILENT,
            RenderValidator.validate(good, good.length(), 1_000L, 1_000L, 0f, true).failure,
        )
        assertEquals(
            RenderValidator.Failure.CLIPPED,
            RenderValidator.validate(good, good.length(), 1_000L, 1_000L, 1.2f, true).failure,
        )
        assertTrue(RenderValidator.validate(good, good.length(), 1_000L, 1_000L, 0.9f, true).ok)
    }

    @Test
    fun `the render limits describe a real side of vinyl`() {
        assertEquals(600_000L, RenderLimits.MAX_RECORD_MS)
        assertTrue(RenderLimits.MIN_RECORD_MS < RenderLimits.MAX_RECORD_MS)
        // Ten minutes of 16-bit stereo at 44.1 kHz is about 106 MB: a plausible estimate, not a guess.
        val estimate = RenderLimits.estimateMasterBytes(600_000L)
        assertTrue("the estimate was $estimate", estimate in 100_000_000L..115_000_000L)
        assertEquals(44L + 44_100L * 4L, RenderLimits.estimateMasterBytes(1_000L))
    }

    private fun peak(audio: FloatArray): Float =
        audio.fold(0f) { best, sample -> maxOf(best, abs(sample)) }

    private fun rms(audio: FloatArray): Float {
        var sum = 0.0
        audio.forEach { sum += it.toDouble() * it }
        return kotlin.math.sqrt(sum / audio.size).toFloat()
    }

    private fun channelRms(audio: FloatArray, channel: Int): Float {
        var sum = 0.0
        var count = 0
        var frame = channel
        while (frame < audio.size) {
            val sample = audio[frame].toDouble()
            sum += sample * sample
            count++
            frame += 2
        }
        return if (count == 0) 0f else kotlin.math.sqrt(sum / count).toFloat()
    }

    private fun windowRms(audio: FloatArray, fromSeconds: Double, toSeconds: Double, sampleRate: Int): Float {
        val from = (fromSeconds * sampleRate).toInt().coerceIn(0, audio.size / 2 - 1)
        val to = (toSeconds * sampleRate).toInt().coerceIn(from + 1, audio.size / 2)
        var sum = 0.0
        var count = 0
        for (frame in from until to) {
            val sample = audio[frame * 2].toDouble()
            sum += sample * sample
            count++
        }
        return if (count == 0) 0f else kotlin.math.sqrt(sum / count).toFloat()
    }

    private fun difference(a: FloatArray, b: FloatArray): Float {
        val length = minOf(a.size, b.size)
        if (length == 0) return 0f
        var sum = 0.0
        for (index in 0 until length) {
            val delta = (a[index] - b[index]).toDouble()
            sum += delta * delta
        }
        return kotlin.math.sqrt(sum / length).toFloat()
    }

    private fun highFrequencyRatio(audio: FloatArray): Double {
        var high = 0.0
        var total = 0.0
        var previous = 0f
        for (index in audio.indices step 2) {
            val sample = audio[index]
            val delta = sample - previous
            previous = sample
            high += delta.toDouble() * delta
            total += sample.toDouble() * sample
        }
        return if (total <= 0.0) 0.0 else high / total
    }

    /** 1.0 when the two channels are identical, 0 when they are unrelated. */
    private fun correlationOfChannels(audio: FloatArray): Float {
        var dot = 0.0
        var left = 0.0
        var right = 0.0
        for (frame in 0 until audio.size / 2) {
            val l = audio[frame * 2].toDouble()
            val r = audio[frame * 2 + 1].toDouble()
            dot += l * r
            left += l * l
            right += r * r
        }
        if (left <= 0.0 || right <= 0.0) return 0f
        return (dot / kotlin.math.sqrt(left * right)).toFloat()
    }
}
