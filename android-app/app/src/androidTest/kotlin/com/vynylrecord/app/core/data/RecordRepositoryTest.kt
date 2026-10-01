package com.vynylrecord.app.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vynylrecord.app.core.audio.dsp.FloatPcmBuffer
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.data.db.VynylDatabase
import com.vynylrecord.app.core.data.repository.RecordRepository
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderStage
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylControls
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.storage.FileStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * The repository, on a device.
 *
 * This is where the file layer and the database layer meet, and the rule they meet on is the one that
 * protects a memory the user only has once: **audio first, row second**. These tests check the order of
 * those two writes, the startup sweep that repairs a process kill, and that deleting a record deletes its
 * audio rather than leaving it behind.
 */
@RunWith(AndroidJUnit4::class)
class RecordRepositoryTest {

    private lateinit var database: VynylDatabase
    private lateinit var repository: RecordRepository
    private lateinit var files: FileStore

    private val created = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VynylDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = FileStore(context)
        repository = RecordRepository(database.records(), files)
    }

    @After
    fun tearDown() {
        created.forEach { id -> runCatching { files.deleteRecordFiles(id) } }
        database.close()
    }

    /** A real 44.1 kHz stereo WAV, the shape a capture arrives in. */
    private fun fixture(name: String, seconds: Int = 1): File {
        val file = File(files.cacheRoot, name)
        val frames = seconds * 44_100
        val samples = FloatArray(frames * 2) { index ->
            val frame = index / 2
            (0.35f * sin(2.0 * Math.PI * 220.0 * frame / 44_100.0)).toFloat()
        }
        WavCodec.write(file, FloatPcmBuffer(samples, 44_100, 2))
        return file
    }

    private suspend fun draft(): Record {
        val record = repository.createDraft(
            capturedSource = fixture("take-${System.nanoTime()}.wav"),
            title = "For Ammi",
            preset = VinylPresetId.DUSTY_RECORD,
            style = VinylStyleId.CLASSIC_WAX_RUBY,
            occasion = Occasion.BIRTHDAY,
            senderName = "Zain",
            defaultMusicLevel = 0.22f,
        )
        created += record.id
        return record
    }

    @Test
    fun a_draft_has_its_recording_before_anything_else_exists() = runBlocking {
        val record = draft()

        val source = files.sourceFile(record.id)
        assertTrue("the capture was not moved into the record directory", source.isFile)
        assertEquals(176_444L, source.length())
        assertEquals(source.absolutePath, record.sourcePath)

        // The duration comes from the file, not from a parameter.
        assertEquals(durationMs(source), record.durationMilliseconds)
        assertEquals(record.durationMilliseconds, record.sourceDurationMilliseconds)

        // A draft is a record whose audio is already safe, in the database as well as on disk.
        val stored = repository.find(record.id)
        assertNotNull(stored)
        assertEquals(record.id, stored!!.id)
        assertEquals(RenderState.DRAFT, stored.renderState)
        assertEquals("For Ammi", stored.title)
        assertEquals(Occasion.BIRTHDAY, stored.occasion)
        assertEquals("Zain", stored.senderName)
        assertEquals(VinylPresetId.DUSTY_RECORD, stored.presetId)
        assertEquals(VinylStyleId.CLASSIC_WAX_RUBY, stored.styledId)
        assertEquals(VinylControls.of(VinylPresetId.DUSTY_RECORD), stored.controls)
        assertEquals(0.22f, stored.backgroundVolume, 1e-6f)
        assertNotEquals(0L, stored.deterministicSeed)
        // The same record always presses to the same disc, so the seed is a function of the row.
        assertEquals(record.deterministicSeed, stored.deterministicSeed)
        assertFalse(stored.isReady)
        assertNull(stored.masterPath)
        assertNull(repository.resolveMaster(stored))
        assertEquals(source.absolutePath, repository.resolveSource(stored)?.absolutePath)
    }

    @Test
    fun the_press_states_move_forward_and_keep_the_source() = runBlocking {
        val record = draft()

        repository.markQueued(record.id, "job-1")
        var current = repository.find(record.id)!!
        assertEquals(RenderState.QUEUED, current.renderState)
        assertEquals("job-1", current.renderJobId)

        repository.markRendering(record.id, "job-1", RenderStage.CHARACTER, 0.5f)
        current = repository.find(record.id)!!
        assertEquals(RenderState.RENDERING, current.renderState)
        assertEquals(RenderStage.CHARACTER.label, current.renderStageLabel)
        assertEquals(0.5f, current.renderProgress, 1e-6f)
        // A press in flight still has its source: the render reads it, it does not consume it.
        assertTrue(files.sourceFile(record.id).isFile)

        repository.markFailed(record.id, "Not enough space")
        current = repository.find(record.id)!!
        assertEquals(RenderState.FAILED, current.renderState)
        assertEquals("Not enough space", current.renderError)
        assertNull(current.renderJobId)
        assertTrue("the source was lost when the press failed", files.sourceFile(record.id).isFile)

        // A failure is not the end: the same source can be queued again.
        repository.markQueued(record.id, "job-2")
        current = repository.find(record.id)!!
        assertEquals(RenderState.QUEUED, current.renderState)
        assertNull(current.renderError)
        assertEquals("job-2", current.renderJobId)
    }

    @Test
    fun a_cancelled_press_keeps_everything_it_needs_to_try_again() = runBlocking {
        val record = draft()
        repository.markQueued(record.id, "job-1")
        repository.markCancelled(record.id)

        val canceled = repository.find(record.id)!!
        assertEquals(RenderState.CANCELED, canceled.renderState)
        assertNull(canceled.renderJobId)
        assertTrue(canceled.renderState.isTerminal)
        assertEquals("source.wav", files.sourceFile(record.id).name)
        assertTrue(files.sourceFile(record.id).isFile)
    }

    @Test
    fun duplicating_a_record_gives_a_second_draft_with_its_own_audio() = runBlocking {
        val original = draft()
        val copy = repository.duplicate(original.id)
        assertNotNull(copy)
        created += copy!!.id

        assertNotEquals(original.id, copy.id)
        assertEquals("For Ammi (copy)", copy.title)
        assertFalse(copy.favourite)
        assertEquals(0, copy.playCount)
        assertEquals(RenderState.DRAFT, copy.renderState)
        assertNotEquals(original.deterministicSeed, copy.deterministicSeed)

        // Two files, not two references to one file: deleting the copy must not touch the original.
        val originalSource = files.sourceFile(original.id)
        val copySource = files.sourceFile(copy.id)
        assertTrue(copySource.isFile)
        assertNotEquals(originalSource.absolutePath, copySource.absolutePath)
        assertEquals(originalSource.readBytes().toList(), copySource.readBytes().toList())

        assertTrue(repository.delete(copy.id))
        assertFalse(files.recordDirectory(copy.id).exists())
        assertTrue("deleting the copy took the original's recording", originalSource.isFile)
        assertNotNull(repository.find(original.id))
    }

    @Test
    fun a_finished_record_can_be_copied_even_without_its_source() = runBlocking {
        val record = draft()
        // Stand in for a record restored from a bundle that skipped the source: a master, no source.
        val master = files.masterFile(record.id)
        files.sourceFile(record.id).copyTo(master, overwrite = true)
        val finished = repository.save(
            record.copy(
                masterPath = master.absolutePath,
                durationMilliseconds = 60_000L,
                sizeBytes = master.length(),
                renderState = RenderState.COMPLETED,
                renderProgress = 1f,
                renderStageLabel = RenderStage.COMPLETE.label,
            ),
        )
        files.sourceFile(record.id).delete()

        val copy = repository.duplicate(finished.id)
        assertNotNull(copy)
        created += copy!!.id
        // There is nothing to re-press from, but the record the user made is still playable.
        assertEquals(RenderState.COMPLETED, copy.renderState)
        assertEquals(finished.masterPath, copy.masterPath)
        assertEquals(finished.sizeBytes, copy.sizeBytes)
        assertTrue(copy.isReady)
        assertTrue(repository.resolveMaster(copy)!!.isFile)
    }

    @Test
    fun the_startup_sweep_catches_a_record_whose_master_vanished() = runBlocking {
        val record = draft()
        val master = files.masterFile(record.id)
        files.sourceFile(record.id).copyTo(master, overwrite = true)
        repository.save(
            record.copy(
                masterPath = master.absolutePath,
                durationMilliseconds = 60_000L,
                sizeBytes = master.length(),
                renderState = RenderState.COMPLETED,
                renderProgress = 1f,
                renderStageLabel = RenderStage.COMPLETE.label,
            ),
        )
        // Something outside the app removed the file — a user with a file manager, or a failed restore.
        assertTrue(master.delete())

        val report = repository.reconcileOnStartup()
        assertEquals(1, report.missingMasters)
        assertEquals(0, report.strandedRenders)
        assertFalse(report.isQuiet)

        val repaired = repository.find(record.id)!!
        assertEquals(RenderState.FAILED, repaired.renderState)
        assertFalse("a record with no master still claimed to be ready", repaired.isReady)
        assertTrue(
            "the user was not told what happened: ${repaired.renderError}",
            repaired.renderError.orEmpty().contains("Press it again"),
        )
    }

    @Test
    fun the_startup_sweep_resets_a_press_a_killed_process_left_behind() = runBlocking {
        val record = draft()
        repository.markQueued(record.id, "job-killed")

        val report = repository.reconcileOnStartup()
        assertEquals(1, report.strandedRenders)
        assertEquals(0, report.missingMasters)

        val repaired = repository.find(record.id)!!
        assertEquals(RenderState.FAILED, repaired.renderState)
        assertEquals("Press interrupted", repaired.renderStageLabel)
        assertNull(repaired.renderJobId)
        assertTrue(repaired.renderError.orEmpty().contains("interrupted"))
        // The source is why the retry is possible at all.
        assertTrue(files.sourceFile(record.id).isFile)

        // And a record that is simply finished is left exactly as it was.
        val quiet = repository.reconcileOnStartup()
        assertTrue(quiet.isQuiet)
    }

    @Test
    fun deleting_a_record_takes_its_audio_and_its_row() = runBlocking {
        val record = draft()
        val directory = files.recordDirectory(record.id)
        assertTrue(directory.isDirectory)

        assertTrue(repository.delete(record.id))
        assertFalse(directory.exists())
        assertNull(repository.find(record.id))
        // Pressing delete again is harmless: the row is already gone and there is nothing to remove.
        assertTrue(repository.delete(record.id))
        assertNull(repository.find(record.id))
    }

    @Test
    fun orphaned_audio_is_found_and_can_be_swept() = runBlocking {
        val record = draft()
        val known = files.recordDirectory(record.id)
        // A directory with no row: what a crash between "write the audio" and "write the row" leaves.
        val orphan = files.recordDirectory("orphan-${System.nanoTime()}")
        orphan.mkdirs()
        File(orphan, "source.wav").writeBytes(ByteArray(2_000))

        val found = repository.orphanedRecordDirectories().map { it.name }
        assertTrue("the orphan was not found: $found", found.contains(orphan.name))
        assertFalse("a known record was reported as orphaned", found.contains(known.name))

        assertTrue(repository.deleteOrphanedDirectories() >= 1)
        assertFalse(orphan.exists())
        assertTrue("the sweep took a real record's audio", known.isDirectory)
    }

    @Test
    fun favouriting_is_a_switch() = runBlocking {
        val record = draft()
        assertEquals(true, repository.toggleFavourite(record.id))
        assertTrue(repository.find(record.id)!!.favourite)
        assertEquals(false, repository.toggleFavourite(record.id))
        assertFalse(repository.find(record.id)!!.favourite)
        repository.setFavourite(record.id, true)
        assertTrue(repository.find(record.id)!!.favourite)
    }

    @Test
    fun the_shelf_totals_come_from_the_rows() = runBlocking {
        val record = draft()
        assertEquals(0, repository.count())
        assertEquals(0L, repository.totalDurationMs())

        val master = files.masterFile(record.id)
        files.sourceFile(record.id).copyTo(master, overwrite = true)
        repository.save(
            record.copy(
                masterPath = master.absolutePath,
                durationMilliseconds = 30_000L,
                sizeBytes = master.length(),
                renderState = RenderState.COMPLETED,
            ),
        )

        assertEquals(1, repository.count())
        assertEquals(30_000L, repository.totalDurationMs())
        assertEquals(master.length(), repository.totalBytes())

        // A draft is not counted: the header says how much has been pressed, not how much exists.
        draft()
        assertEquals(1, repository.count())
    }

    @Test
    fun a_record_can_be_re_rendered_from_its_previous_master() = runBlocking {
        val record = draft()
        assertNull(repository.backupMasterForRerender(record.id))

        val master = files.masterFile(record.id)
        files.sourceFile(record.id).copyTo(master, overwrite = true)
        val backup = repository.backupMasterForRerender(record.id)
        assertNotNull(backup)
        assertEquals("master.previous.wav", backup!!.name)
        assertTrue(backup.isFile)
        assertEquals(master.length(), backup.length())
        // The current master is untouched, so a failed re-press still leaves a playable record.
        assertTrue(master.isFile)
    }

    private fun durationMs(file: File): Long = WavCodec.Reader(file).use { reader ->
        reader.frameCount * 1000L / reader.sampleRate
    }
}
