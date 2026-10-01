package com.vynylrecord.app.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vynylrecord.app.core.audio.dsp.FloatPcmBuffer
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.data.db.VynylDatabase
import com.vynylrecord.app.core.data.repository.AssetRepository
import com.vynylrecord.app.core.data.repository.RecordRepository
import com.vynylrecord.app.core.model.AudioAsset
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.sin

/**
 * The whole shelf, in one file — and back again.
 *
 * A backup is the only thing standing between a phone that dies and a recording that no longer exists, so
 * these tests do the thing the user does: press a couple of records, back everything up, delete it all, and
 * restore. They check the archive's shape, that the audio inside it is the audio that went in, that
 * restoring twice is additive rather than destructive, and that a hostile archive cannot write outside the
 * staging directory.
 */
@RunWith(AndroidJUnit4::class)
class BackupArchiveTest {

    private lateinit var context: Context
    private lateinit var database: VynylDatabase
    private lateinit var files: FileStore
    private lateinit var records: RecordRepository
    private lateinit var assets: AssetRepository

    private val created = mutableListOf<String>()
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, VynylDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = FileStore(context)
        records = RecordRepository(database.records(), files)
        assets = AssetRepository(context, database.audioAssets(), files)
    }

    @After
    fun tearDown() {
        created.forEach { id -> runCatching { files.deleteRecordFiles(id) } }
        tempFiles.forEach { file -> runCatching { file.delete() } }
        runCatching { files.renderDirectory("backup-test").deleteRecursively() }
        database.close()
    }

    // ---------------------------------------------------------------- fixtures

    private fun completedRecord(title: String, preset: VinylPresetId, seconds: Int = 2): Record = runBlocking {
        val frames = seconds * SAMPLE_RATE
        val audio = FloatPcmBuffer(
            FloatArray(frames * 2) { index ->
                (0.3f * sin(2.0 * Math.PI * 300.0 * (index / 2) / SAMPLE_RATE)).toFloat()
            },
            SAMPLE_RATE,
            2,
        )
        val capture = File(files.cacheRoot, "backup-capture-${System.nanoTime()}.wav")
        WavCodec.write(capture, audio)
        val draft = records.createDraft(
            capture,
            title,
            preset = preset,
            style = VinylStyleId.VINTAGE_EMERALD,
        )
        created += draft.id
        WavCodec.write(files.masterFile(draft.id), audio)
        records.save(
            draft.copy(
                masterPath = files.masterFile(draft.id).absolutePath,
                renderState = RenderState.COMPLETED,
                renderProgress = 1f,
                renderStageLabel = "Complete",
                durationMilliseconds = seconds * 1000L,
                dedication = "Keep this one.",
                deterministicSeed = 11L,
            ),
        )
    }

    /** A user's own bed, stored the way the Sound Lab stores one: PCM in files/assets. */
    private suspend fun importedAsset(title: String): AudioAsset {
        val source = File(files.cacheRoot, "bed-${System.nanoTime()}.wav")
        val frames = SAMPLE_RATE
        WavCodec.write(
            source,
            FloatPcmBuffer(
                FloatArray(frames * 2) { index -> (0.25f * sin(2.0 * Math.PI * 174.0 * (index / 2) / SAMPLE_RATE)).toFloat() },
                SAMPLE_RATE,
                2,
            ),
        )
        val id = "asset-${System.nanoTime()}"
        assertTrue(assets.importRestoredAsset(id, source, "wav", title = title, volume = 0.3f))
        return assets.find(id) ?: error("the asset was not stored")
    }

    private fun archive(name: String = "shelf.vynylbak"): File =
        File(context.cacheDir, name).also { tempFiles += it }

    private suspend fun backUp(target: File, includeSources: Boolean = false): BackupArchive.Report? =
        BackupArchive.backup(
            target = target,
            records = records,
            assets = assets,
            files = files,
            appVersion = "1.0.0",
            recordList = records.records.first(),
            assetList = assets.assets.first(),
            includeSources = includeSources,
        )

    // ---------------------------------------------------------------- the archive

    @Test
    fun a_backup_is_a_zip_with_an_index_and_one_bundle_per_record() = runBlocking {
        val first = completedRecord("First Voice", VinylPresetId.WARM_VINTAGE)
        val second = completedRecord("Second Voice", VinylPresetId.DUSTY_RECORD)
        val bed = importedAsset("Kitchen Radio")

        val target = archive()
        val report = backUp(target)
        assertNotNull("the backup did not produce a report", report)
        assertEquals(2, report!!.records)
        assertEquals(1, report.assets)
        assertTrue("the backup has no audio in it", report.audioBytes > 200_000L)
        assertEquals(target.absolutePath, report.path)
        assertTrue(target.isFile)

        ZipFile(target).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue("no index: $names", names.any { it == BackupArchive.INDEX })
            assertTrue(
                "a record was not packed: $names",
                names.any { it == "records/${first.id}.${BackupArchive.EXTENSION}" },
            )
            assertTrue(
                "the second record was not packed: $names",
                names.any { it == "records/${second.id}.${BackupArchive.EXTENSION}" },
            )
            assertTrue("the user's own bed was not packed: $names", names.any { it.startsWith("assets/") && it.contains(bed.id) })
            // The sources were not asked for, so they are not there.
            assertFalse("sources were packed when they were not requested: $names", names.any { it.contains("source") })

            val index = zip.getInputStream(zip.getEntry(BackupArchive.INDEX)).readBytes().decodeToString()
            assertTrue("the index does not identify itself: $index", index.contains(BackupArchive.KIND))
            assertTrue(index.contains(first.displayTitle))
            assertTrue(index.contains(VinylPresetId.DUSTY_RECORD.id))
            assertTrue("the asset's title is not in the index", index.contains("Kitchen Radio"))
        }
    }

    @Test
    fun a_backup_can_carry_the_original_recordings() = runBlocking {
        completedRecord("With the Original", VinylPresetId.CLEAN_VINYL)
        val without = archive("without.vynylbak")
        backUp(without, includeSources = false)
        val with = archive("with.vynylbak")
        backUp(with, includeSources = true)
        assertTrue(
            "including the source did not add anything: ${with.length()} against ${without.length()}",
            with.length() > without.length(),
        )
    }

    // ---------------------------------------------------------------- the round trip

    @Test
    fun a_restore_brings_the_records_and_their_audio_back() = runBlocking {
        val record = completedRecord("Grandmother's Kitchen", VinylPresetId.OLD_FAMILY_RECORD)
        val bed = importedAsset("Rain on the Window")
        val target = archive()
        backUp(target)

        // Take the shelf away, the way a lost phone does.
        assertTrue(records.delete(record.id))
        assertTrue(assets.delete(bed.id))
        assertEquals(0, records.count())

        val report = BackupArchive.restore(target, records, assets, files)
        assertEquals("the record did not come back", 1, report.recordsImported)
        assertEquals(1, report.assetsImported)
        assertTrue("restoring reported failures: ${report.failures}", report.failures.isEmpty())
        assertTrue("the summary is empty", report.summary.isNotBlank())

        val restored = records.find(record.id)
        assertNotNull("the restored record is not in the library", restored)
        assertEquals(record.displayTitle, restored!!.displayTitle)
        assertEquals(VinylPresetId.OLD_FAMILY_RECORD, restored.presetId)
        assertEquals(VinylStyleId.VINTAGE_EMERALD, restored.styledId)
        assertEquals(record.durationMilliseconds, restored.durationMilliseconds)
        assertEquals("Keep this one.", restored.dedication)
        assertTrue("a restored record must be playable", restored.isReady)

        val master = records.resolveMaster(restored)
        assertNotNull(master)
        val decoded = WavCodec.read(master!!)
        assertNotNull("the restored master is not decodable", decoded)
        assertEquals("the audio did not survive the round trip", 2 * SAMPLE_RATE, decoded!!.frameCount)

        val restoredBed = assets.find(bed.id)
        assertNotNull("the bed did not come back", restoredBed)
        assertEquals("Rain on the Window", restoredBed!!.title)
        assertEquals(0.3f, restoredBed.defaultVolume, 1e-3f)
        assertFalse("a restored asset is the user's, not the app's", restoredBed.isBundled)
        assertTrue("the restored bed has no audio file", File(restoredBed.sourcePath!!).isFile)
    }

    @Test
    fun restoring_twice_keeps_the_first_copy_and_adds_nothing() = runBlocking {
        val record = completedRecord("Only Once", VinylPresetId.IMPERIAL_GOLD_MASTER)
        val target = archive()
        backUp(target)

        // The record is still here: a backup taken on another device is being restored on this one.
        val first = BackupArchive.restore(target, records, assets, files)
        assertEquals(0, first.recordsImported)
        assertEquals(1, first.recordsSkipped)
        assertEquals(1, records.count())

        // And the record on the device is untouched: restoring never overwrites what is already here.
        val stored = records.find(record.id)
        assertNotNull(stored)
        assertEquals(record.updatedAt, stored!!.updatedAt)
        assertEquals(1, records.count())
    }

    @Test
    fun a_backup_of_an_empty_shelf_is_still_a_valid_backup() = runBlocking {
        val target = archive("empty.vynylbak")
        val report = backUp(target)
        assertNotNull(report)
        assertEquals(0, report!!.records)
        assertEquals(0, report.assets)
        ZipFile(target).use { zip ->
            assertNotNull(zip.getEntry(BackupArchive.INDEX))
        }
        val restored = BackupArchive.restore(target, records, assets, files)
        assertEquals(0, restored.recordsImported)
        assertTrue(restored.failures.isEmpty())
    }

    // ---------------------------------------------------------------- hostile input

    @Test
    fun a_file_that_is_not_a_backup_is_refused_with_a_sentence() = runBlocking {
        val notAnArchive = archive("notes.vynylbak")
        notAnArchive.writeText("this is not a backup, it is a shopping list")

        val report = BackupArchive.restore(notAnArchive, records, assets, files)
        assertEquals(0, report.recordsImported)
        assertTrue("a text file was accepted as a backup", report.failures.isNotEmpty())
        assertEquals(0, records.count())
    }

    @Test
    fun a_zip_that_is_not_a_vynyl_backup_is_refused() = runBlocking {
        val wrongKind = archive("someone-elses.zip")
        ZipOutputStream(wrongKind.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.INDEX))
            zip.write("""{"kind":"something.else","records":[]}""".toByteArray())
            zip.closeEntry()
        }
        val report = BackupArchive.restore(wrongKind, records, assets, files)
        assertEquals(0, report.recordsImported)
        assertTrue("a foreign index was accepted", report.failures.isNotEmpty())
    }

    @Test
    fun an_archive_cannot_write_outside_the_staging_directory() = runBlocking {
        completedRecord("Honest Record", VinylPresetId.WARM_VINTAGE)
        val target = archive()
        backUp(target)

        // Re-pack the archive with an entry that tries to escape upwards, the classic zip-slip.
        val hostile = archive("hostile.vynylbak")
        ZipFile(target).use { source ->
            ZipOutputStream(hostile.outputStream()).use { out ->
                source.entries().toList().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    out.putNextEntry(ZipEntry(entry.name))
                    source.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry("../../escaped.txt"))
                out.write("this should never be written".toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("records/../escaped-again.vynylbak"))
                out.write("neither should this".toByteArray())
                out.closeEntry()
            }
        }

        val report = BackupArchive.restore(hostile, records, assets, files)
        assertEquals("the honest record still had to be restored", 1, report.recordsImported)
        assertTrue("the traversal entries were not reported", report.failures.isNotEmpty())

        // Nothing was written anywhere but the staging directory: no file with a name from the hostile
        // entries exists under the app's own storage or cache.
        val strays = listOf(context.filesDir, context.cacheDir, context.dataDir)
            .flatMap { root -> root.walkTopDown().take(4_000).filter { it.isFile }.toList() }
            .filter { it.name.contains("escaped") }
        assertTrue("a traversal entry escaped the staging directory: $strays", strays.isEmpty())
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
