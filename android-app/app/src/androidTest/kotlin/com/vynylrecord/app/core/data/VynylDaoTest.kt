package com.vynylrecord.app.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vynylrecord.app.core.data.db.AudioAssetDao
import com.vynylrecord.app.core.data.db.AudioAssetEntity
import com.vynylrecord.app.core.data.db.RecordDao
import com.vynylrecord.app.core.data.db.RecordEntity
import com.vynylrecord.app.core.data.db.VynylDatabase
import com.vynylrecord.app.core.model.AudioAsset
import com.vynylrecord.app.core.model.AssetCategory
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderStage
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylControls
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database, on a device.
 *
 * Room's generated code is the part of this app that cannot be checked on a desktop JVM: the SQL is
 * validated at build time by KSP, but the behaviour — that an upsert replaces, that a render status
 * update does not disturb the label, that the aggregate queries that feed the Vault header count what
 * they say they count — only exists once a real SQLite is underneath it.
 */
@RunWith(AndroidJUnit4::class)
class VynylDaoTest {

    private lateinit var database: VynylDatabase
    private lateinit var records: RecordDao
    private lateinit var assets: AudioAssetDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VynylDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        records = database.records()
        assets = database.assets()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun record(
        id: String,
        title: String = "For Ammi",
        state: RenderState = RenderState.DRAFT,
        updatedAt: Long = 1_000L,
        durationMs: Long = 65_000L,
        sizeBytes: Long = 0L,
        peaks: List<Float> = emptyList(),
    ): Record = Record(
        id = id,
        title = title,
        recipientName = "Ammi",
        senderName = "Zain",
        dedication = "Press this and think of me.",
        occasion = Occasion.BIRTHDAY,
        occasionDate = "14 February 2026",
        sideALabel = "Dear Ammi",
        sideBLabel = "The long way home",
        sourcePath = "source.wav",
        masterPath = if (state == RenderState.COMPLETED) "master.wav" else null,
        coverArtworkPath = if (state == RenderState.COMPLETED) "cover.png" else null,
        waveformPath = if (state == RenderState.COMPLETED) "waveform.json" else null,
        waveformPeaks = peaks,
        durationMilliseconds = durationMs,
        sourceDurationMilliseconds = durationMs,
        presetId = VinylPresetId.DUSTY_RECORD,
        controls = VinylControls.of(VinylPresetId.DUSTY_RECORD),
        styledId = VinylStyleId.MIDNIGHT_SAPPHIRE,
        backgroundAssetId = "asset-ambient",
        backgroundVolume = 0.24f,
        backgroundTrimStartMs = 1_500L,
        backgroundTrimEndMs = 42_000L,
        deterministicSeed = 8_675_309L,
        renderState = state,
        renderProgress = if (state == RenderState.COMPLETED) 1f else 0.4f,
        renderStageLabel = if (state == RenderState.COMPLETED) RenderStage.COMPLETE.label else "Mixing",
        renderJobId = if (state.isRunning) "job-1" else null,
        renderError = if (state == RenderState.FAILED) "no space left" else null,
        sizeBytes = sizeBytes,
        favourite = true,
        lastPlayedAt = 1_700_000_000_000L,
        playCount = 3,
        createdAt = 500L,
        updatedAt = updatedAt,
    )

    private fun asset(
        id: String,
        title: String = "Rain on a window",
        bundled: Boolean = false,
        category: AssetCategory = AssetCategory.BACKGROUND_MUSIC,
    ): AudioAsset = AudioAsset(
        id = id,
        title = title,
        category = category,
        sourcePath = if (bundled) null else "assets/$id.wav",
        bundledResourceName = if (bundled) "audio/rain.mp3" else null,
        isBundled = bundled,
        enabled = true,
        defaultVolume = 0.3f,
        durationMilliseconds = 12_000L,
        sizeBytes = 640_000L,
        createdAt = 42L,
    )

    @Test
    fun a_record_round_trips_through_the_database() = runBlocking {
        val original = record("r1", state = RenderState.COMPLETED, peaks = listOf(0.1f, 0.25f, 1f, 0f))
        records.upsert(RecordEntity.from(original))

        val stored = records.findById("r1")
        assertNotNull(stored)
        val back = stored!!.toModel()

        // Every column the entity carries has to survive: the model is what the whole app reads.
        assertEquals(original.copy(durationMilliseconds = 65_000L), back)
        assertEquals(listOf(0.1f, 0.25f, 1f, 0f), back.waveformPeaks)
        assertEquals(VinylPresetId.DUSTY_RECORD, back.presetId)
        assertEquals(VinylStyleId.MIDNIGHT_SAPPHIRE, back.styledId)
        assertEquals(Occasion.BIRTHDAY, back.occasion)
        assertEquals(RenderState.COMPLETED, back.renderState)
        assertEquals(8_675_309L, back.deterministicSeed)
        assertEquals("Dear Ammi", back.sideALabel)
        assertTrue(back.isReady)
    }

    @Test
    fun an_upsert_replaces_the_row_it_is_given() = runBlocking {
        records.upsert(RecordEntity.from(record("r1", title = "First take", updatedAt = 10L)))
        records.upsert(RecordEntity.from(record("r1", title = "Second take", updatedAt = 20L)))

        assertEquals(1, records.all().size)
        assertEquals("Second take", records.findById("r1")?.title)
        // insertIfAbsent is the opposite contract, and is used by the restore path.
        assertEquals(-1L, records.insertIfAbsent(RecordEntity.from(record("r1", title = "Third take"))))
        assertEquals("Second take", records.findById("r1")?.title)
    }

    @Test
    fun the_vault_queries_see_what_was_written() = runBlocking {
        records.upsert(RecordEntity.from(record("a", title = "Alpha", state = RenderState.COMPLETED, updatedAt = 1L, durationMs = 60_000L, sizeBytes = 1_000_000L)))
        records.upsert(RecordEntity.from(record("b", title = "Beta", state = RenderState.COMPLETED, updatedAt = 3L, durationMs = 90_000L, sizeBytes = 2_000_000L)))
        records.upsert(RecordEntity.from(record("c", title = "Gamma", state = RenderState.DRAFT, updatedAt = 2L, durationMs = 5_000L, sizeBytes = 0L)))

        val listed = records.observeAll().first()
        assertEquals(3, listed.size)
        // Newest first: updatedAt descending.
        assertEquals(listOf("b", "c", "a"), listed.map { it.id })

        assertEquals("c", records.observeById("c").first()?.id)
        assertNull(records.observeById("missing").first())

        // The header numbers only count records that actually finished.
        assertEquals(2, records.countCompleted())
        assertEquals(150_000L, records.totalRecordedMilliseconds())
        assertEquals(3_000_000L, records.totalBytes())
    }

    @Test
    fun only_work_in_flight_is_queued_or_rendering() = runBlocking {
        records.upsert(RecordEntity.from(record("queued", state = RenderState.QUEUED, updatedAt = 3L)))
        records.upsert(RecordEntity.from(record("rendering", state = RenderState.RENDERING, updatedAt = 1L)))
        records.upsert(RecordEntity.from(record("done", state = RenderState.COMPLETED, updatedAt = 2L)))
        records.upsert(RecordEntity.from(record("failed", state = RenderState.FAILED)))
        records.upsert(RecordEntity.from(record("canceled", state = RenderState.CANCELED)))

        assertEquals(listOf("rendering", "queued"), records.findActiveRenders().map { it.id })
        assertEquals(2, records.observeActiveRenders().first().size)
    }

    @Test
    fun a_render_walks_forward_without_touching_the_label() = runBlocking {
        records.upsert(RecordEntity.from(record("r1", state = RenderState.DRAFT)))

        records.updateRenderStatus(
            id = "r1", state = RenderState.QUEUED.id, progress = 0.02f,
            stageLabel = RenderStage.PREPARING.label, jobId = "job-7", error = null, updatedAt = 11L,
        )
        records.updateRenderStatus(
            id = "r1", state = RenderState.RENDERING.id, progress = 0.6f,
            stageLabel = RenderStage.CHARACTER.label, jobId = "job-7", error = null, updatedAt = 12L,
        )
        val running = records.findById("r1")!!
        assertEquals(RenderState.RENDERING.id, running.renderState)
        assertEquals(0.6f, running.renderProgress, 1e-6f)
        assertEquals("job-7", running.renderJobId)
        assertEquals("For Ammi", running.title)
        assertEquals("Ammi", running.recipientName)
        assertEquals(0.24f, running.backgroundVolume, 1e-6f)

        // A failure keeps the message a person can act on.
        records.updateRenderStatus(
            id = "r1", state = RenderState.FAILED.id, progress = 0.6f,
            stageLabel = RenderStage.ENCODING.label, jobId = null, error = "Not enough space", updatedAt = 13L,
        )
        val failed = records.findById("r1")!!
        assertEquals("Not enough space", failed.renderError)
        assertNull(failed.renderJobId)
        assertTrue(records.findActiveRenders().isEmpty())
    }

    @Test
    fun a_finished_press_gets_its_paths_and_measurements() = runBlocking {
        records.upsert(RecordEntity.from(record("r1", state = RenderState.RENDERING, sizeBytes = 0L, durationMs = 0L)))
        records.updateMaster(
            id = "r1",
            masterPath = "master.wav",
            artworkPath = "cover.png",
            waveformPath = "waveform.json",
            waveformSamples = RecordEntity.encodePeaks(listOf(0.5f, 1f, 0.25f)),
            durationMs = 61_500L,
            sizeBytes = 5_400_000L,
            updatedAt = 99L,
        )
        records.updateRenderStatus(
            id = "r1", state = RenderState.COMPLETED.id, progress = 1f,
            stageLabel = RenderStage.COMPLETE.label, jobId = null, error = null, updatedAt = 99L,
        )

        val done = records.findById("r1")!!.toModel()
        assertEquals("master.wav", done.masterPath)
        assertEquals("cover.png", done.coverArtworkPath)
        assertEquals(61_500L, done.durationMilliseconds)
        assertEquals(5_400_000L, done.sizeBytes)
        assertEquals(listOf(0.5f, 1f, 0.25f), done.waveformPeaks)
        assertTrue(done.isReady)
        assertEquals(1, records.countCompleted())
    }

    @Test
    fun playing_a_record_is_recorded() = runBlocking {
        records.upsert(RecordEntity.from(record("r1")))
        assertEquals(0, records.findById("r1")!!.playCount)

        records.markPlayed("r1", 1_800_000_000_000L)
        records.markPlayed("r1", 1_800_000_000_500L)

        val played = records.findById("r1")!!
        assertEquals(2, played.playCount)
        assertEquals(1_800_000_000_500L, played.lastPlayedAt)
    }

    @Test
    fun favouriting_is_a_single_column_update() = runBlocking {
        records.upsert(RecordEntity.from(record("r1")))
        records.updateFavourite("r1", false, 77L)
        val row = records.findById("r1")!!
        assertEquals(false, row.favourite)
        assertEquals(77L, row.updatedAt)
        // The rest of the label is untouched by a star.
        assertEquals("For Ammi", row.title)
        assertEquals("source.wav", row.sourceAudioPath)
    }

    @Test
    fun deleting_a_record_leaves_nothing_behind() = runBlocking {
        val row = RecordEntity.from(record("r1", state = RenderState.COMPLETED))
        records.upsert(row)
        records.delete(row)
        assertNull(records.findById("r1"))
        assertTrue(records.all().isEmpty())
        // Deleting a row that is already gone is not an error.
        records.deleteById("r1")
        assertNull(records.findById("r1"))
    }

    @Test
    fun a_bundled_asset_can_be_turned_off_but_not_deleted() = runBlocking {
        assets.upsert(AudioAssetEntity.from(asset("bundled-rain", bundled = true)))
        assets.upsert(AudioAssetEntity.from(asset("imported-1", title = "My tape", bundled = false)))

        assertEquals(2, assets.all().size)
        assertEquals(1, assets.countBundled())
        assertEquals("bundled-rain", assets.findByResource("audio/rain.mp3")?.id)

        // The Sound Lab's promise: a bundled asset is a switch, never a delete.
        assertEquals(0, assets.deleteImported("bundled-rain"))
        assertEquals(1, assets.countBundled())
        assertNotNull(assets.findById("bundled-rain"))

        assets.setEnabled("bundled-rain", false)
        assertEquals(false, assets.findById("bundled-rain")!!.enabled)

        assets.updateTrim("bundled-rain", 2_000L, 9_000L, 0.55f)
        val trimmed = assets.findById("bundled-rain")!!.toModel()
        assertEquals(2_000L, trimmed.trimStartMilliseconds)
        assertEquals(9_000L, trimmed.trimEndMilliseconds)
        assertEquals(0.55f, trimmed.defaultVolume, 1e-6f)

        assets.updateMeasured("bundled-rain", 12_000L, 512_000L)
        assertEquals(512_000L, assets.findById("bundled-rain")!!.sizeBytes)

        // An imported one can go, and taking it away does not touch the bundled row.
        assertEquals(1, assets.deleteImported("imported-1"))
        assertNull(assets.findById("imported-1"))
        assertEquals(1, assets.all().size)
    }

    @Test
    fun the_sound_lab_lists_by_category() = runBlocking {
        assets.upsertAll(
            listOf(
                AudioAssetEntity.from(asset("music-1", category = AssetCategory.BACKGROUND_MUSIC)),
                AudioAssetEntity.from(asset("music-2", title = "A second bed", category = AssetCategory.BACKGROUND_MUSIC)),
                AudioAssetEntity.from(asset("texture-1", title = "Dust", category = AssetCategory.SURFACE_TEXTURE)),
                AudioAssetEntity.from(asset("effect-1", title = "Needle drop", category = AssetCategory.SOUND_EFFECT)),
            ),
        )

        assertEquals(4, assets.all().size)
        assertEquals(2, assets.observeByCategory(AssetCategory.BACKGROUND_MUSIC.id).first().size)
        assertEquals(1, assets.observeByCategory(AssetCategory.SURFACE_TEXTURE.id).first().size)
        assertEquals(1, assets.observeByCategory(AssetCategory.SOUND_EFFECT.id).first().size)
        assertTrue(assets.observeByCategory("not-a-category").first().isEmpty())
    }

    @Test
    fun a_restore_can_be_applied_twice_without_duplicating() = runBlocking {
        val restored = listOf(
            RecordEntity.from(record("r1", title = "From the archive", updatedAt = 5L)),
            RecordEntity.from(record("r2", title = "And this one", updatedAt = 6L)),
        )
        // The restore path is deliberately additive and idempotent: skipping rows that already exist is
        // what makes a second restore of the same backup harmless.
        restored.forEach { records.insertIfAbsent(it) }
        restored.forEach { records.insertIfAbsent(it) }
        assertEquals(2, records.all().size)

        records.upsert(RecordEntity.from(record("r1", title = "Edited after the restore", updatedAt = 9L)))
        restored.forEach { records.insertIfAbsent(it) }
        assertEquals(2, records.all().size)
        assertEquals("Edited after the restore", records.findById("r1")?.title)
    }
}
