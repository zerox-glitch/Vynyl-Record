package com.vynylrecord.app.core.render

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vynylrecord.app.core.audio.AudioDecoder
import com.vynylrecord.app.core.audio.dsp.FloatPcmBuffer
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.audio.render.ArtworkRenderer
import com.vynylrecord.app.core.audio.render.RenderPipeline
import com.vynylrecord.app.core.audio.render.RenderLimits
import com.vynylrecord.app.core.audio.render.RenderPreflight
import com.vynylrecord.app.core.audio.render.Waveform
import com.vynylrecord.app.core.data.db.VynylDatabase
import com.vynylrecord.app.core.data.repository.RecordRepository
import com.vynylrecord.app.core.model.AudioAsset
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderStage
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylControls
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.storage.FileStore
import com.vynylrecord.app.core.storage.RecordImport
import com.vynylrecord.app.core.storage.StorageLayout
import com.vynylrecord.app.core.storage.VynylBundle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * The press, end to end, on a device.
 *
 * Everything else in the audio test suite checks one stage or one file format. This checks the promise
 * the app makes to the user: *press this, and you get a record that plays*. It runs the real pipeline —
 * the real DSP chain, the real WAV writer, a real decode-back validation, the platform canvas for the
 * artwork, and MediaCodec for the M4A — and then imports the result back into a second library through a
 * `.vynyl` bundle. Nothing is stubbed, because the failures this is looking for are exactly the ones that
 * only appear when the pieces are put together.
 */
@RunWith(AndroidJUnit4::class)
class RenderPipelineInstrumentedTest {

    private lateinit var context: Context
    private lateinit var database: VynylDatabase
    private lateinit var files: FileStore
    private lateinit var repository: RecordRepository
    private lateinit var pipeline: RenderPipeline

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, VynylDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = FileStore(context)
        repository = RecordRepository(database.records(), files)
        pipeline = RenderPipeline(context, files)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Three seconds of speech-shaped audio at 44.1 kHz, written as the capture the studio would hold. */
    private fun capture(seconds: Int = 3, name: String = "capture.wav"): File {
        val file = File(files.cacheRoot, name)
        val frames = seconds * 44_100
        val samples = FloatArray(frames * 2) { index ->
            val frame = index / 2
            val t = frame.toDouble() / 44_100.0
            val syllable = if ((t * 2.5).toInt() % 5 == 4) 0.05 else 1.0
            val voice = sin(2.0 * Math.PI * 190.0 * t) * 0.6 + sin(2.0 * Math.PI * 900.0 * t) * 0.25
            (voice * syllable * 0.42).toFloat()
        }
        WavCodec.write(file, FloatPcmBuffer(samples, 44_100, 2))
        return file
    }

    private suspend fun draft(preset: VinylPresetId = VinylPresetId.WARM_VINTAGE): Record =
        repository.createDraft(
            capturedSource = capture(),
            title = "For Ammi",
            preset = preset,
            occasion = Occasion.BIRTHDAY,
            senderName = "Zain",
        )

    @Test
    fun a_press_produces_a_master_that_plays() = runBlocking {
        val record = draft()
        val stages = LinkedHashSet<RenderStage>()
        val fractions = ArrayList<Float>()

        val outcome = pipeline.render(
            request = RenderPipeline.Request(record = record, jobId = "job-1"),
            onProgress = { stage, fraction ->
                stages += stage
                fractions += fraction
            },
        )

        // The record that comes back is the record that was pressed.
        assertEquals(record.id, outcome.record.id)
        assertEquals(RenderState.COMPLETED, outcome.record.renderState)
        assertEquals(1f, outcome.record.renderProgress, 0f)
        assertEquals("For Ammi", outcome.record.title)

        // A master exists, at the canonical path, with the right shape.
        val master = files.masterFile(record.id)
        assertTrue(master.isFile)
        assertEquals(master.absolutePath, outcome.masterFile.absolutePath)
        assertTrue("the master is implausibly small: ${master.length()}", master.length() > 200_000L)
        assertEquals(master.length(), outcome.record.sizeBytes)

        val header = master.inputStream().buffered().use { WavCodec.readHeader(it) }
        assertNotNull(header)
        assertEquals(44_100, header!!.sampleRate)
        assertEquals(2, header.channels)
        assertEquals(16, header.bitsPerSample)

        // The duration is the capture plus the needle lead-in the recipe asks for.
        val expectedMs = 3_000L + record.controls.recipe.needleIntroMs
        assertTrue(
            "the master is ${outcome.durationMs} ms but should be about $expectedMs ms",
            kotlin.math.abs(outcome.durationMs - expectedMs) < 250L,
        )
        assertEquals(outcome.durationMs, outcome.record.durationMilliseconds)

        // Re-opened the way a player would: right length, real audio, nothing over full scale.
        val report = pipeline.validate(master, outcome.durationMs, outcome.measurements.peakDb)
        assertTrue("the validator rejected the master it just wrote: ${report.message}", report.ok)
        assertTrue(outcome.measurements.peakDb in -3f..-0.5f)
        assertTrue(outcome.measurements.rmsDb < outcome.measurements.peakDb)
        assertTrue(outcome.measurements.summary.isNotBlank())

        // The artwork and the waveform are part of a finished record, not an afterthought.
        val artwork = File(outcome.artworkFile!!.absolutePath)
        assertEquals(ArtworkRenderer.FILE_NAME, artwork.name)
        assertTrue(artwork.isFile)
        assertTrue("the cover is too small to be a cover", artwork.length() > 10_000L)
        assertEquals("PNG", artwork.inputStream().use { stream ->
            val magic = ByteArray(8)
            stream.read(magic)
            if (magic[1] == 'P'.code.toByte() && magic[2] == 'N'.code.toByte() && magic[3] == 'G'.code.toByte()) "PNG" else "not PNG"
        })

        assertFalse(outcome.waveform.isEmpty)
        assertEquals(Waveform.DETAILED_POINTS, outcome.waveform.peaks.size)
        assertEquals(outcome.durationMs, outcome.waveform.durationMs)
        assertEquals("the library card wants 96 peaks", 96, outcome.record.waveformPeaks.size)
        assertTrue("every peak is scaled 0..1", outcome.record.waveformPeaks.all { it in 0f..1f })
        assertEquals(1f, outcome.record.waveformPeaks.max(), 1e-6f)
        assertTrue(files.waveformFile(record.id).isFile)

        // The scratch directory is gone: a finished press leaves no rubbish behind.
        assertFalse(files.renderDirectory("job-1").exists())

        // The nine stages were reported in order, and the last one was the end.
        assertEquals(
            listOf(
                RenderStage.PREPARING, RenderStage.DECODING, RenderStage.MIXING, RenderStage.CHARACTER,
                RenderStage.MASTERING, RenderStage.ENCODING, RenderStage.WAVEFORM, RenderStage.ARTWORK,
                RenderStage.COMPLETE,
            ),
            stages.toList(),
        )
        assertTrue(fractions.all { it in 0f..1f })
        assertEquals(1f, fractions.last(), 1e-6f)

        // Only now, with the file on disk and verified, does the row become a record.
        val committed = repository.commitRender(outcome)
        assertTrue(committed.isReady)
        val stored = repository.find(record.id)!!
        assertEquals(RenderState.COMPLETED, stored.renderState)
        assertEquals(master.absolutePath, stored.masterPath)
        assertTrue(repository.resolveMaster(stored)!!.isFile)
        assertEquals(96, stored.waveformPeaks.size)
        assertEquals(1, repository.count())
    }

    @Test
    fun the_master_encodes_to_an_m4a_a_player_can_read() = runBlocking {
        val record = draft()
        val outcome = pipeline.render(RenderPipeline.Request(record = record, jobId = "job-2"))

        val target = File(files.cacheRoot, "export-${record.id}.m4a")
        var progress = 0f
        val encoded = pipeline.encodeM4a(outcome.masterFile, target, onProgress = { progress = it })

        assertNotNull("the M4A encoder produced nothing", encoded)
        assertTrue("the M4A is a header and no audio: ${target.length()} bytes", target.length() > 20_000L)
        assertEquals(1f, progress, 1e-6f)

        // Read it back the way the player and the sharing UI do.
        val track = AudioDecoder.openTrack(context, target)
        assertNotNull("the encoded file is not a track anything can open", track)
        val opened = track!!
        try {
            assertTrue("unexpected codec ${opened.mime}", opened.mime.startsWith("audio/"))
            val durationMs = opened.durationUs / 1000L
            assertTrue(
                "the M4A is ${durationMs} ms but the master is ${outcome.durationMs} ms",
                kotlin.math.abs(durationMs - outcome.durationMs) < 500L,
            )
        } finally {
            opened.extractor.release()
        }

        // A WAV export is a byte copy, which is what makes it exact.
        val wavTarget = File(files.cacheRoot, "export-${record.id}.wav")
        assertTrue(pipeline.exportWav(outcome.masterFile, wavTarget))
        assertEquals(outcome.masterFile.length(), wavTarget.length())
        assertEquals(
            outcome.masterFile.readBytes().toList().take(4_096),
            wavTarget.readBytes().toList().take(4_096),
        )
    }

    @Test
    fun cancelling_the_press_leaves_no_master_and_an_unchanged_record() = runBlocking {
        val record = draft()
        assertThrows(RenderPipeline.RenderException::class.java) {
            pipeline.render(
                request = RenderPipeline.Request(record = record, jobId = "job-cancelled"),
                isCancelled = { true },
            )
        }

        assertFalse("a cancelled press left a master", files.masterFile(record.id).exists())
        assertFalse(files.renderDirectory("job-cancelled").exists())
        val stored = repository.find(record.id)!!
        assertEquals(RenderState.DRAFT, stored.renderState)
        assertNull(stored.masterPath)
        assertFalse(stored.isReady)
        // The recording is still there, which is the whole point of cancelling rather than deleting.
        assertTrue(files.sourceFile(record.id).isFile)
    }

    @Test
    fun a_cancelled_press_can_be_started_again() = runBlocking {
        val record = draft()
        assertThrows(RenderPipeline.RenderException::class.java) {
            pipeline.render(
                request = RenderPipeline.Request(record = record, jobId = "job-a"),
                isCancelled = { true },
            )
        }

        val second = pipeline.render(RenderPipeline.Request(record = record, jobId = "job-b"))
        assertTrue(second.masterFile.isFile)
        assertFalse(files.renderDirectory("job-a").exists())
        assertFalse(files.renderDirectory("job-b").exists())
        val expectedMs = 3_000L + record.controls.recipe.needleIntroMs
        assertTrue(
            "the second press is ${second.durationMs} ms but should be about $expectedMs ms",
            kotlin.math.abs(second.durationMs - expectedMs) < 250L,
        )
    }

    @Test
    fun every_preset_presses_and_every_press_is_a_different_disc() = runBlocking {
        val spectrum = LinkedHashMap<String, Pair<String, Long>>()
        VinylPresetId.entries.forEach { preset ->
            val record = draft(preset)
            val outcome = pipeline.render(RenderPipeline.Request(record = record, jobId = "job-${preset.id}"))
            assertTrue(outcome.masterFile.isFile)
            assertTrue(
                "${preset.id} produced a master that does not verify",
                pipeline.validate(outcome.masterFile, outcome.durationMs, outcome.measurements.peakDb).ok,
            )
            // The press is deterministic: the same record, pressed again, is the same file.
            val again = pipeline.render(RenderPipeline.Request(record = record, jobId = "job-${preset.id}-b"))
            assertEquals(
                "a re-press of ${preset.id} produced different audio",
                outcome.masterFile.readBytes().toList(),
                again.masterFile.readBytes().toList(),
            )
            spectrum[preset.id] = outcome.diagnostics to outcome.masterFile.length()
        }
        assertEquals(VinylPresetId.entries.size, spectrum.size)
    }

    @Test
    fun a_pressed_record_survives_a_bundle_round_trip() = runBlocking {
        val record = draft(VinylPresetId.DUSTY_RECORD)
        val outcome = pipeline.render(RenderPipeline.Request(record = record, jobId = "job-bundle"))
        val committed = repository.commitRender(outcome)
        val masterBytes = outcome.masterFile.readBytes().toList()

        val bundle = File(files.cacheRoot, "For Ammi.vynyl")
        val written = VynylBundle.write(
            target = bundle,
            record = committed,
            appVersion = "1.0.0",
            masterFile = outcome.masterFile,
            sourceFile = files.sourceFile(record.id),
            artworkFile = outcome.artworkFile,
            waveformFile = files.waveformFile(record.id),
            includeSource = true,
        )
        assertTrue("the bundle was not written: $written", written is VynylBundle.BundleResult.Written)
        assertTrue(bundle.length() > 200_000L)

        // The original is gone — a fresh install, or another phone.
        assertTrue(repository.delete(record.id))
        assertFalse(files.recordDirectory(record.id).exists())

        val staging = File(files.cacheRoot, "import-${System.nanoTime()}")
        val read = VynylBundle.read(bundle, staging)
        assertTrue("the bundle was not read: $read", read is VynylBundle.BundleResult.Read)
        val contents = (read as VynylBundle.BundleResult.Read).contents
        assertEquals("For Ammi", contents.manifest.record.title)
        assertTrue(contents.manifest.includesSource)

        val restored = RecordImport.commit(repository, files, contents)
        assertNotNull(restored)
        val imported = repository.find(restored!!.id)!!
        assertTrue("the imported record does not claim to be ready", imported.isReady)
        assertEquals("For Ammi", imported.title)
        assertEquals(VinylPresetId.DUSTY_RECORD, imported.presetId)

        // The audio that came back is the audio that went in, and it still plays.
        val restoredMaster = repository.resolveMaster(imported)!!
        assertEquals(masterBytes, restoredMaster.readBytes().toList())
        assertTrue(pipeline.validate(restoredMaster, imported.durationMilliseconds, -1f).ok)
        assertTrue(repository.resolveSource(imported)!!.isFile)
        assertEquals(96, imported.waveformPeaks.size)
        assertTrue(repository.resolveArtwork(imported)!!.isFile)
        assertEquals(1, repository.count())
    }

    @Test
    fun preflight_catches_an_impossible_recipe_before_the_press_starts() {
        val recipe = VinylPresetId.CLEAN_VINYL.recipe.copy(highPassHz = 12_000f, lowPassHz = 4_000f)
        val broken = Record(
            title = "Crossed over",
            controls = VinylControls(recipe = recipe),
            durationMilliseconds = 30_000L,
        )

        val findings = RenderPreflight.check(broken, null)
        assertTrue(
            "a recipe whose filters cross over was not refused: $findings",
            findings.any { it.level == RenderPreflight.Finding.Level.ERROR },
        )

        val tooLong = broken.copy(
            controls = VinylControls.of(VinylPresetId.CLEAN_VINYL),
            durationMilliseconds = RenderLimits.MAX_RECORD_MS + 1L,
        )
        assertTrue(
            RenderPreflight.check(tooLong, null)
                .any { it.level == RenderPreflight.Finding.Level.ERROR },
        )

        // A quiet, ordinary problem is a warning rather than a refusal.
        val switchedOff = AudioAsset(title = "Rain", enabled = false)
        assertTrue(
            RenderPreflight.check(
                broken.copy(controls = VinylControls.of(VinylPresetId.CLEAN_VINYL), durationMilliseconds = 30_000L),
                switchedOff,
            ).any { it.level == RenderPreflight.Finding.Level.WARNING },
        )

        // And a record with nothing wrong with it produces nothing at all.
        val healthy = Record(title = "Fine", durationMilliseconds = 30_000L)
        assertTrue(RenderPreflight.check(healthy, null).isEmpty())
        assertEquals(StorageLayout.SOURCE_NAME, files.sourceFile("x").name)
    }
}
