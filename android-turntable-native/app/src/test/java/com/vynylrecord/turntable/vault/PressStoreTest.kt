package com.vynylrecord.turntable.vault

import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.RecordSide
import com.vynylrecord.turntable.model.VinylStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import java.nio.file.Files

/**
 * The vault, tested against a real directory.
 *
 * The rules that matter here are the failure rules: a vault that cannot survive one damaged record, or
 * that lets a description point the app at a file outside its own folder, is worse than no vault at
 * all. Both are covered below with files written by hand.
 */
class PressStoreTest {

    private fun newStore(): Pair<PressStore, File> {
        val root = Files.createTempDirectory("vynyl-vault").toFile()
        return PressStore(File(root, "presses")) to root
    }

    private fun metadata(title: String = "Late Night Letter") = RecordMetadata(
        title = title,
        recipient = "Mum",
        sender = "You",
        side = RecordSide.A,
        date = "30 Sep 2026",
        catalogue = "VYN 001",
    )

    private fun store(store: PressStore, pressed: Press, bytes: Int = 1_024): Press {
        val file = File(store.directoryFor(pressed.id), pressed.fileName)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes) { (it and 0xFF).toByte() })
        return store.attachAudio(pressed, file, durationMs = 12_000L)
    }

    private fun allocate(
        store: PressStore,
        title: String = "Late Night Letter",
        now: Long = 1_000L,
    ): Press = store.allocate(
        metadata = metadata(title),
        style = VinylStyle.CLASSIC_WAX_RUBY,
        speed = PlatterSpeed.THIRTY_THREE,
        recipe = PressRecipe.STUDIO,
        sourceLabel = "Recorded in Studio",
        nowEpochMs = now,
    )

    @Test
    fun `a pressed side is listed with everything it was pressed with`() {
        val (store, _) = newStore()
        assertTrue(store.ensureReady())

        val allocated = allocate(store)
        val saved = store(store, allocated)

        val listed = store.list()
        assertEquals(1, listed.size)
        val press = listed.single()
        assertEquals(saved.id, press.id)
        assertEquals("Late Night Letter", press.metadata.title)
        assertEquals("Mum", press.metadata.recipient)
        assertEquals(RecordSide.A, press.metadata.side)
        assertEquals(VinylStyle.CLASSIC_WAX_RUBY, press.style)
        assertEquals(PlatterSpeed.THIRTY_THREE, press.speed)
        assertEquals(12_000L, press.durationMs)
        assertEquals(1_024L, press.byteCount)
        assertEquals("Recorded in Studio", press.sourceLabel)
        assertEquals(PressRecipe.STUDIO.crackle, press.recipe.crackle, 1e-6f)
        assertEquals(PressRecipe.STUDIO.trimSilence, press.recipe.trimSilence)
        assertTrue(store.audioFile(press).isFile)
    }

    @Test
    fun `the vault lists the newest side first`() {
        val (store, _) = newStore()
        store.ensureReady()
        store(store, allocate(store, title = "Older", now = 1_000L))
        store(store, allocate(store, title = "Newer", now = 5_000L))
        store(store, allocate(store, title = "Oldest", now = 500L))

        assertEquals(listOf("Newer", "Older", "Oldest"), store.list().map { it.metadata.title })
        assertEquals(3, store.count())
    }

    @Test
    fun `a recipe survives the round trip exactly`() {
        val (store, _) = newStore()
        store.ensureReady()
        val custom = PressRecipe(
            crackle = 0.123f,
            surfaceNoise = 0.456f,
            wowFlutter = 0.789f,
            warmth = 0.234f,
            roomTone = 0.567f,
            hiss = 0.891f,
            saturation = 0.345f,
            trimSilence = false,
            normalize = false,
        )
        val allocated = store.allocate(
            metadata = metadata(),
            style = VinylStyle.MIDNIGHT_SAPPHIRE,
            speed = PlatterSpeed.FORTY_FIVE,
            recipe = custom,
            sourceLabel = "Imported: letter.mp3",
        )
        store(store, allocated)

        val press = store.list().single()
        with(press.recipe) {
            assertEquals(0.123f, crackle, 1e-4f)
            assertEquals(0.456f, surfaceNoise, 1e-4f)
            assertEquals(0.789f, wowFlutter, 1e-4f)
            assertEquals(0.234f, warmth, 1e-4f)
            assertEquals(0.567f, roomTone, 1e-4f)
            assertEquals(0.891f, hiss, 1e-4f)
            assertEquals(0.345f, saturation, 1e-4f)
            assertFalse(trimSilence)
            assertFalse(normalize)
        }
        assertEquals(PlatterSpeed.FORTY_FIVE, press.speed)
        assertEquals("Imported: letter.mp3", press.sourceLabel)
    }

    @Test
    fun `one damaged record does not take the vault down with it`() {
        val (store, _) = newStore()
        store.ensureReady()
        val good = store(store, allocate(store, title = "Survivor", now = 9_000L))

        // A directory with no description at all.
        File(store.directoryFor("vyn-broken-1")).mkdirs()
        // A description that is not a properties file.
        val garbage = File(store.directoryFor("vyn-broken-2")).apply { mkdirs() }
        File(garbage, Press.PROPERTIES_FILE).writeText("this is not a description\u0000")
        // A description whose audio has been deleted.
        val orphan = store.allocate(
            metadata = metadata("Orphan"),
            style = VinylStyle.DEFAULT,
            speed = PlatterSpeed.THIRTY_THREE,
            recipe = PressRecipe.STUDIO,
            sourceLabel = "Studio",
        )
        File(store.directoryFor(orphan.id)).mkdirs()
        File(store.directoryFor(orphan.id), Press.PROPERTIES_FILE).writeText(
            orphan.toProperties().let { properties -> java.io.StringWriter().also { properties.store(it, null) }.toString() },
        )

        val listed = store.list()
        assertEquals("only the readable record may be listed", 1, listed.size)
        assertEquals(good.id, listed.single().id)
    }

    @Test
    fun `a description cannot point outside its own folder`() {
        val (store, _) = newStore()
        store.ensureReady()
        val allocated = allocate(store)
        val directory = store.directoryFor(allocated.id).apply { mkdirs() }
        // A crafted record that tries to read something else on disk.
        val escaped = allocated.copy(fileName = "../../../secrets.txt")
        val writer = java.io.StringWriter()
        escaped.toProperties().store(writer, null)
        File(directory, Press.PROPERTIES_FILE).writeText(writer.toString())

        assertTrue("a traversal must not be listed", store.list().isEmpty())
    }

    @Test
    fun `a record from a newer version is skipped rather than misread`() {
        val (store, _) = newStore()
        store.ensureReady()
        val allocated = allocate(store)
        store(store, allocated)

        val properties = Properties().apply {
            File(store.directoryFor(allocated.id), Press.PROPERTIES_FILE).inputStream().use(::load)
        }
        properties.setProperty("version", (Press.VERSION + 5).toString())
        val writer = java.io.StringWriter()
        properties.store(writer, null)
        File(store.directoryFor(allocated.id), Press.PROPERTIES_FILE).writeText(writer.toString())

        assertTrue("a future format must be ignored, not guessed at", store.list().isEmpty())
    }

    @Test
    fun `renaming reprints the label and leaves the audio alone`() {
        val (store, _) = newStore()
        store.ensureReady()
        val press = store(store, allocate(store))
        val audioSizeBefore = store.audioFile(press).length()

        val renamed = store.rename(press, press.metadata.copy(title = "Morning Post", recipient = "Dad"))

        assertEquals("Morning Post", renamed.metadata.title)
        assertEquals("Dad", renamed.metadata.recipient)
        assertEquals("the audio must be untouched", audioSizeBefore, store.audioFile(press).length())
        assertEquals("Morning Post", store.list().single().metadata.title)
    }

    @Test
    fun `deleting a side removes its file and its folder`() {
        val (store, _) = newStore()
        store.ensureReady()
        val keep = store(store, allocate(store, title = "Keep", now = 2_000L))
        val drop = store(store, allocate(store, title = "Drop", now = 1_000L))

        assertTrue(store.delete(drop))

        assertEquals(1, store.list().size)
        assertEquals(keep.id, store.list().single().id)
        assertFalse("the folder must be gone", store.directoryFor(drop.id).exists())
        assertNull("it must not be findable", store.find(drop.id))
        assertNotNull(store.find(keep.id))
    }

    @Test
    fun `the vault reports its own size and can be emptied`() {
        val (store, _) = newStore()
        store.ensureReady()
        store(store, allocate(store, title = "One", now = 1_000L), bytes = 2_000)
        store(store, allocate(store, title = "Two", now = 2_000L), bytes = 3_000)

        val total = store.totalBytes()
        assertTrue("the total must count the audio ($total)", total >= 5_000L)
        assertTrue("and the descriptions", total > 5_000L)
        assertEquals(2, store.count())

        assertTrue(store.clear())
        assertTrue(store.list().isEmpty())
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun `allocating a side twice never collides`() {
        val (store, _) = newStore()
        val first = allocate(store)
        val second = allocate(store)
        assertNotNull(first.id)
        assertNotNull(second.id)
        assertFalse("ids must differ", first.id == second.id)
        assertTrue("the id must be usable as a folder name", first.id.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(first.fileName.endsWith(".wav"))
    }

    @Test
    fun `the description keeps the label's own text`() {
        val (store, _) = newStore()
        store.ensureReady()
        val press = store(store, allocate(store))
        val loaded = store.list().single()

        assertEquals(press.metadata.dedicationLine(), loaded.metadata.dedicationLine())
        assertEquals(press.metadata.signatureLine(), loaded.metadata.signatureLine())
        assertEquals("30 Sep 2026", loaded.metadata.date)
        assertEquals("VYN 001", loaded.metadata.catalogue)
    }
}
