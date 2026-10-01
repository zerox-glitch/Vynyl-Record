package com.vynylrecord.app.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vynylrecord.app.core.audio.dsp.FloatPcmBuffer
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.data.db.VynylDatabase
import com.vynylrecord.app.core.data.repository.AssetRepository
import com.vynylrecord.app.core.model.AssetCategory
import com.vynylrecord.app.core.model.AudioAsset
import com.vynylrecord.app.core.storage.FileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * The Sound Lab's catalogue, on a device.
 *
 * Two rules matter here and both are about ownership. The bundled beds belong to the app: they can be
 * switched off but never deleted, and an app update must not undo what the user decided about them. The
 * imported ones belong to the user: their audio is decoded into the app's own storage at import time so a
 * record never fails to press because the file it referenced was on a card that has been removed.
 */
@RunWith(AndroidJUnit4::class)
class AssetRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: VynylDatabase
    private lateinit var files: FileStore
    private lateinit var assets: AssetRepository

    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, VynylDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = FileStore(context)
        assets = AssetRepository(context, database.audioAssets(), files)
    }

    @After
    fun tearDown() {
        createdFiles.forEach { file -> runCatching { file.delete() } }
        database.close()
    }

    // ---------------------------------------------------------------- the shipped catalogue

    @Test
    fun the_bundled_catalogue_is_registered_once() = runBlocking {
        val added = assets.syncBundled()
        assertEquals(AudioAsset.BUNDLED.size, added)
        assertEquals(AudioAsset.BUNDLED.size, assets.assets.first().size)

        // A second launch must not duplicate anything.
        assertEquals("a second sync added rows again", 0, assets.syncBundled())
        assertEquals(AudioAsset.BUNDLED.size, assets.assets.first().size)
        assertEquals(AudioAsset.BUNDLED.size, assets.bundledCount())

        val stored = assets.assets.first()
        assertTrue("a bundled asset was not marked as bundled", stored.all { it.isBundled })
        assertTrue("a bundled asset has no resource", stored.all { !it.bundledResourceName.isNullOrBlank() })
        // The catalogue covers what the Studio offers: beds to mix, a texture, and the needle drop.
        assertTrue(AudioAsset.BUNDLED_MUSIC.size >= 4)
        assertTrue(stored.any { it.category == AssetCategory.SURFACE_TEXTURE })
        assertTrue(stored.any { it.category == AssetCategory.SOUND_EFFECT })
    }

    @Test
    fun every_bundled_asset_is_actually_in_the_apk() {
        // The one packaging mistake that would only show up on a user's phone: a catalogue entry with no
        // file behind it. Opening it through the asset manager is exactly what the bank does at runtime.
        assertEquals(AudioAsset.BUNDLED.size, AudioAsset.BUNDLED.map { it.id }.toSet().size)
        AudioAsset.BUNDLED.forEach { asset ->
            val resource = asset.bundledResourceName
            assertNotNull("${asset.id} has no resource name", resource)
            context.assets.open(resource!!).use { stream ->
                val header = ByteArray(3)
                assertEquals("$resource is too short to be audio", 3, stream.read(header))
                // Every bundled asset is MPEG audio: an ID3 tag or a frame sync, never an HTML error page.
                val isId3 = String(header, 0, 3) == "ID3"
                val isFrameSync = header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0
                assertTrue("$resource does not look like an MP3: ${header.joinToString()}", isId3 || isFrameSync)
                assertTrue("$resource is empty", stream.available() > 1_000)
            }
        }
    }

    @Test
    fun a_bundled_asset_is_measured_so_the_lab_can_show_its_length() = runBlocking {
        assets.syncBundled()
        // The catalogue ships with approximate lengths; measuring replaces them with what the decoder says.
        assets.measureMissingDurations()
        val measured = assets.assets.first().filter { it.durationMilliseconds > 0L }
        assertTrue("no bundled asset has a duration", measured.isNotEmpty())
        val needle = measured.first { it.category == AssetCategory.SOUND_EFFECT }
        assertTrue(
            "the needle drop is ${needle.durationMilliseconds}ms, which is not a needle drop",
            needle.durationMilliseconds in 200L..8_000L,
        )
    }

    @Test
    fun a_bundled_asset_can_be_switched_off_but_not_deleted() = runBlocking {
        assets.syncBundled()
        val asset = assets.assets.first().first()
        assertTrue(asset.enabled)

        assets.setEnabled(asset.id, false)
        val afterSwitchOff = assets.find(asset.id)
        assertNotNull("switching an asset off deleted it", afterSwitchOff)
        assertFalse(afterSwitchOff!!.enabled)
        assertFalse(
            "a switched-off bed is still offered to the Studio",
            assets.backgroundMusic.first().any { it.id == asset.id },
        )
        // It is still listed in the Sound Lab, where the user can switch it back on.
        assertTrue(assets.assetsIn(asset.category).first().any { it.id == asset.id })

        assertFalse("a bundled asset was deleted", assets.delete(asset.id))
        assertNotNull("the delete removed a bundled asset", assets.find(asset.id))
    }

    @Test
    fun an_app_update_keeps_what_the_user_decided_about_a_bed() = runBlocking {
        assets.syncBundled()
        val asset = assets.assets.first().first { it.title.isNotBlank() }
        assets.setEnabled(asset.id, false)
        assets.rename(asset.id, "My own name for it")
        assets.setTrim(asset.id, 1_000L, 4_000L, 0.42f)

        // A new build ships the same catalogue: the row is refreshed, the user's decisions are not.
        assets.syncBundled()
        val afterUpdate = assets.find(asset.id)
        assertNotNull(afterUpdate)
        assertFalse("the update switched a bed back on", afterUpdate!!.enabled)
        assertEquals("My own name for it", afterUpdate.title)
        assertEquals(1_000L, afterUpdate.trimStartMilliseconds)
        assertEquals(4_000L, afterUpdate.trimEndMilliseconds)
        assertEquals(0.42f, afterUpdate.defaultVolume, 1e-3f)
        assertEquals(AudioAsset.BUNDLED.size, assets.assets.first().size)
    }

    @Test
    fun the_trim_is_kept_inside_the_audio() = runBlocking {
        assets.syncBundled()
        assets.measureMissingDurations()
        val asset = assets.assets.first().first { it.durationMilliseconds > 1_000L }

        // A start beyond the end is pulled back, leaving at least the minimum window; the end follows.
        assets.setTrim(asset.id, asset.durationMilliseconds + 5_000L, 0L, 5f)
        val clamped = assets.find(asset.id)
        assertNotNull(clamped)
        assertTrue("the trim starts past the audio", clamped!!.trimStartMilliseconds < asset.durationMilliseconds)
        assertTrue(
            "the trim is not a usable window: ${clamped.trimStartMilliseconds}..${clamped.trimEndMilliseconds}",
            clamped.trimEndMilliseconds - clamped.trimStartMilliseconds >= 400L,
        )
        assertTrue("the volume was not clamped to a bed level", clamped.defaultVolume <= 0.8f)
        assertTrue("a bed cannot have a negative level", clamped.defaultVolume >= 0f)

        // An end before the start is treated as "play it to the end", which is what the Studio offers.
        assets.setTrim(asset.id, 1_000L, 500L, 0.2f)
        val toTheEnd = assets.find(asset.id)
        assertNotNull(toTheEnd)
        assertEquals(asset.durationMilliseconds, toTheEnd!!.trimEndMilliseconds)
    }

    @Test
    fun a_title_is_never_allowed_to_be_a_paragraph() = runBlocking {
        assets.syncBundled()
        val asset = assets.assets.first().first()
        assets.rename(asset.id, "   " + "Very Long ".repeat(20))
        val renamed = assets.find(asset.id)
        assertNotNull(renamed)
        assertTrue("the title is ${renamed!!.title.length} characters", renamed.title.length <= 60)
        assertFalse("the title kept its leading spaces", renamed.title.startsWith(" "))
        assertTrue(renamed.title.startsWith("Very Long"))
    }

    // ---------------------------------------------------------------- the user's own audio

    @Test
    fun an_imported_bed_lives_in_the_apps_own_storage() = runBlocking {
        val source = writeWav("imported-source.wav", seconds = 1)
        val id = "user-bed-1"
        assertTrue(assets.importRestoredAsset(id, source, "wav", title = "Auntie's Tape", volume = 0.25f))

        val stored = assets.find(id)
        assertNotNull(stored)
        assertFalse("an imported bed was marked bundled", stored!!.isBundled)
        assertNull(stored.bundledResourceName)
        assertEquals("an imported bed belongs in the Studio's bed list", AssetCategory.BACKGROUND_MUSIC, stored.category)

        val audio = File(stored.sourcePath!!)
        assertTrue("the audio was not copied into the app", audio.isFile)
        assertTrue(
            "the audio is not in the app's own storage: ${audio.absolutePath}",
            audio.absolutePath.startsWith(files.assetsRoot().absolutePath),
        )
        val decoded = WavCodec.read(audio)
        assertNotNull("the copied audio is not decodable", decoded)
        assertEquals(44_100, decoded!!.sampleRate)
        assertEquals(44_100, decoded.frameCount)

        // And it shows up in the Studio's list of beds, which is what makes it usable.
        assertTrue(assets.backgroundMusic.first().any { it.id == id })
        assertTrue("an imported bed is not counted as the user's", assets.importedBytes() > 0L)
    }

    @Test
    fun deleting_an_imported_bed_deletes_its_audio_too() = runBlocking {
        val source = writeWav("to-delete.wav", seconds = 1)
        val id = "user-bed-2"
        assertTrue(assets.importRestoredAsset(id, source, "wav", title = "Temporary"))
        val stored = assets.find(id)!!
        val audio = File(stored.sourcePath!!)
        assertTrue(audio.isFile)

        assertTrue(assets.delete(id))
        assertNull("the row survived a delete", assets.find(id))
        assertFalse("the audio was left behind", audio.exists())
        assertEquals(0L, assets.importedBytes())
    }

    @Test
    fun a_file_that_cannot_be_decoded_is_refused_rather_than_half_imported() = runBlocking {
        val junk = File(files.cacheRoot, "not-audio.mp3")
        junk.writeText("this is not audio, it is a note to self")
        createdFiles += junk
        assertFalse(
            "a text file was accepted as an audio asset",
            assets.importRestoredAsset("user-bed-3", junk, "mp3", title = "Nope"),
        )
        assertNull(assets.find("user-bed-3"))
        assertFalse(File(files.assetsRoot(), "user-bed-3.wav").exists())
    }

    @Test
    fun an_asset_id_can_never_be_a_path() = runBlocking {
        val source = writeWav("identity.wav", seconds = 1)
        // An id arrives from an archive, so it is untrusted: it becomes a name inside the assets directory
        // and nothing else.
        assertTrue(assets.importRestoredAsset("../../escapee", source, "wav", title = "Nope"))
        val stored = assets.assets.first().firstOrNull { it.title == "Nope" }
        assertNotNull(stored)
        assertFalse("the id kept a path separator", stored!!.id.contains('/') || stored.id.contains(".."))
        val audio = File(stored.sourcePath!!)
        assertTrue(
            "the audio was written outside the assets directory: ${audio.absolutePath}",
            audio.absolutePath.startsWith(files.assetsRoot().absolutePath),
        )
        assertTrue(assets.delete(stored.id))
    }

    @Test
    fun the_categories_the_app_uses_are_the_categories_it_stores() = runBlocking {
        assets.syncBundled()
        val stored = assets.assets.first()
        assertTrue("every category has a row", stored.map { it.category }.toSet().size >= 3)
        AssetCategory.entries.forEach { category ->
            assertEquals(
                "the $category filter does not agree with the list",
                stored.count { it.category == category },
                assets.assetsIn(category).first().size,
            )
        }
    }

    private fun writeWav(name: String, seconds: Int): File {
        val file = File(files.cacheRoot, name)
        val frames = seconds * 44_100
        WavCodec.write(
            file,
            FloatPcmBuffer(
                FloatArray(frames * 2) { index -> (0.3f * sin(2.0 * Math.PI * 196.0 * (index / 2) / 44_100)).toFloat() },
                44_100,
                2,
            ),
        )
        createdFiles += file
        return file
    }
}
