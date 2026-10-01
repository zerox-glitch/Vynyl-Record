package com.vynylrecord.app.core.storage

import com.vynylrecord.app.core.audio.dsp.FloatPcmBuffer
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.data.json.JsonValue
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.VinylControl
import com.vynylrecord.app.core.model.VinylControls
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The `.vynyl` bundle: one portable file that carries a record to another device.
 *
 * This is the app's only import path, which makes it the only place where a file from outside the app is
 * trusted. The tests therefore spend as much time on the hostile cases — a climbing entry name, a wrong
 * checksum, a manifest from the future — as on the happy one.
 */
class VynylBundleTest {

    private lateinit var workspace: File

    @Before
    fun setUp() {
        workspace = File.createTempFile("vynyl-bundle", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun wav(name: String, seconds: Int = 1, amplitude: Float = 0.4f): File {
        val file = File(workspace, name)
        val frames = seconds * 44_100
        val samples = FloatArray(frames * 2) { index ->
            val sign = if (index % 2 == 0) 1f else -1f
            sign * amplitude * kotlin.math.sin(index / 7.0).toFloat()
        }
        WavCodec.write(file, FloatPcmBuffer(samples, 44_100, 2))
        return file
    }

    private fun sampleRecord(): Record = Record(
        title = "Sunday Kitchen",
        recipientName = "Amma",
        senderName = "Bilal",
        dedication = "Put this on while the tea brews.",
        occasion = Occasion.FAMILY,
        occasionDate = "12 March 2026",
        durationMilliseconds = 1_000,
        presetId = VinylPresetId.DUSTY_RECORD,
        controls = VinylControls.of(VinylPresetId.DUSTY_RECORD).with(VinylControl.CRACKLE, 0.7f),
        styledId = VinylStyleId.SMOKED_OBSIDIAN,
        backgroundVolume = 0.22f,
        backgroundTrimStartMs = 1_000,
        backgroundTrimEndMs = 9_000,
        deterministicSeed = 0x5EED1234L,
        masterPath = "/somewhere/master.wav",
    )

    private fun writeBundle(
        includeSource: Boolean = true,
        artwork: Boolean = true,
        waveform: Boolean = true,
        record: Record = sampleRecord(),
    ): File {
        val target = File(workspace, "record.vynyl")
        val result = VynylBundle.write(
            target = target,
            record = record,
            appVersion = "1.0.0",
            masterFile = wav("master.wav"),
            sourceFile = if (includeSource) wav("source.wav", amplitude = 0.2f) else null,
            artworkFile = if (artwork) File(workspace, "cover.png").apply { writeBytes(ByteArray(512) { 7 }) } else null,
            waveformFile = if (waveform) {
                File(workspace, "waveform.json").apply { writeText("{\"peaks\":[0.1,0.4,0.9]}") }
            } else {
                null
            },
            includeSource = includeSource,
        )
        assertTrue("writing the bundle failed: $result", result is VynylBundle.BundleResult.Written)
        return target
    }

    @Test
    fun `a written bundle contains the promised entries`() {
        val bundle = writeBundle()
        assertTrue(bundle.isFile)
        assertTrue(VynylBundle.looksLikeZip(bundle))

        ZipFile(bundle).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(VynylBundle.MANIFEST in names)
            assertTrue(VynylBundle.MASTER in names)
            assertTrue(VynylBundle.SOURCE in names)
            assertTrue(VynylBundle.ARTWORK in names)
            assertTrue(VynylBundle.WAVEFORM in names)
            assertTrue(VynylBundle.CHECKSUMS in names)
            assertEquals(6, names.size)
        }
    }

    @Test
    fun `a bundle without a source leaves it out`() {
        val bundle = writeBundle(includeSource = false)
        ZipFile(bundle).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertFalse(VynylBundle.SOURCE in names)
        }
    }

    @Test
    fun `a bundle round-trips every piece of metadata`() {
        val bundle = writeBundle()
        val into = File(workspace, "imported")
        val result = VynylBundle.read(bundle, into)
        assertTrue("read failed: $result", result is VynylBundle.BundleResult.Read)
        val contents = (result as VynylBundle.BundleResult.Read).contents

        assertEquals("Sunday Kitchen", contents.manifest.record.title)
        assertEquals("Amma", contents.manifest.record.recipientName)
        assertEquals("Bilal", contents.manifest.record.senderName)
        assertEquals("Put this on while the tea brews.", contents.manifest.record.dedication)
        assertEquals(Occasion.FAMILY, contents.manifest.record.occasion)
        assertEquals("12 March 2026", contents.manifest.record.occasionDate)
        assertEquals(VinylPresetId.DUSTY_RECORD, contents.manifest.record.presetId)
        assertEquals(VinylStyleId.SMOKED_OBSIDIAN, contents.manifest.record.styledId)
        assertEquals(0x5EED1234L, contents.manifest.record.deterministicSeed)
        assertEquals(0.22f, contents.manifest.record.backgroundVolume, 1e-6f)
        assertEquals(1_000L, contents.manifest.record.backgroundTrimStartMs)
        assertEquals(9_000L, contents.manifest.record.backgroundTrimEndMs)
        assertEquals(0.7f, VinylControl.CRACKLE.read(contents.manifest.record.controls.recipe), 0.02f)
        assertTrue(contents.manifest.record.controls.isCustomized)
        assertEquals("1.0.0", contents.manifest.appVersion)
        assertTrue(contents.manifest.includesSource)
        assertTrue(contents.bytes > 0L)
        assertEquals(VynylBundle.FORMAT_VERSION, contents.manifest.formatVersion)
    }

    @Test
    fun `the imported record points at the files that were extracted`() {
        val bundle = writeBundle()
        val into = File(workspace, "imported")
        val contents = (VynylBundle.read(bundle, into) as VynylBundle.BundleResult.Read).contents
        val record = contents.manifest.record

        assertNotNull(contents.master)
        assertTrue(contents.master!!.isFile)
        assertEquals(File(into, VynylBundle.MASTER).absolutePath, record.masterPath)
        assertTrue(File(record.masterPath!!).length() > 44L)
        assertNotNull(record.sourcePath)
        assertNotNull(record.coverArtworkPath)
        assertNotNull(record.waveformPath)
        assertEquals(com.vynylrecord.app.core.model.RenderState.COMPLETED, record.renderState)
    }

    @Test
    fun `entry names that could climb out of the directory are refused`() {
        listOf("../escape.wav", "a/../../b.wav", "/absolute.wav", "~home.wav", "c:evil.wav", "a\\b.wav", "").forEach { name ->
            assertFalse("$name was accepted", VynylBundle.isSafeEntryName(name))
        }
        listOf("manifest.json", "master.wav", "cover.png", "waveform.json", "checksums.txt").forEach { name ->
            assertTrue("$name was refused", VynylBundle.isSafeEntryName(name))
        }
        assertFalse(VynylBundle.isSafeEntryName("a".repeat(200)))
        assertFalse(VynylBundle.isSafeEntryName("nul\u0000.wav"))
    }

    @Test
    fun `a handmade zip with a climbing entry is rejected and leaves nothing behind`() {
        val hostile = File(workspace, "hostile.vynyl")
        ZipOutputStream(hostile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.wav"))
            zip.write(ByteArray(200))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(VynylBundle.MANIFEST))
            zip.write(manifestText(sampleRecord()).toByteArray())
            zip.closeEntry()
        }

        val into = File(workspace, "hostile-target")
        val result = VynylBundle.read(hostile, into)
        assertTrue(result is VynylBundle.BundleResult.Failed)
        assertTrue((result as VynylBundle.BundleResult.Failed).message.contains("unsafe", ignoreCase = true))
        assertFalse("a failed import left a directory behind", into.exists())
        assertFalse(File(workspace, "escape.wav").exists())
    }

    @Test
    fun `a bundle with a wrong checksum is refused`() {
        val damaged = File(workspace, "damaged.vynyl")
        ZipOutputStream(damaged.outputStream()).use { zip ->
            val master = File(workspace, "master.wav").readBytes()
            val wrongCrc = CRC32().apply { update("not this audio".toByteArray()) }.value
            zip.putNextEntry(ZipEntry(VynylBundle.MASTER))
            zip.write(master)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(VynylBundle.CHECKSUMS))
            zip.write("crc32:%08x:%d  %s\n".format(wrongCrc, master.size, VynylBundle.MASTER).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(VynylBundle.MANIFEST))
            zip.write(manifestText(sampleRecord()).toByteArray())
            zip.closeEntry()
        }

        val into = File(workspace, "damaged-target")
        val result = VynylBundle.read(damaged, into)
        assertTrue("a corrupted bundle was accepted", result is VynylBundle.BundleResult.Failed)
        assertFalse(into.exists())
    }

    @Test
    fun `a correct checksum verifies`() {
        val good = File(workspace, "good.vynyl")
        ZipOutputStream(good.outputStream()).use { zip ->
            val master = File(workspace, "master.wav").readBytes()
            val crc = CRC32().apply { update(master) }.value
            zip.putNextEntry(ZipEntry(VynylBundle.MASTER))
            zip.write(master)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(VynylBundle.CHECKSUMS))
            zip.write("crc32:%08x:%d  %s\n".format(crc, master.size, VynylBundle.MASTER).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(VynylBundle.MANIFEST))
            zip.write(manifestText(sampleRecord()).toByteArray())
            zip.closeEntry()
        }

        val result = VynylBundle.read(good, File(workspace, "good-target"))
        assertTrue(result is VynylBundle.BundleResult.Read)
    }

    @Test
    fun `a manifest from a newer build is refused rather than half-read`() {
        val future = File(workspace, "future.vynyl")
        val text = manifestText(record(), formatVersion = VynylBundle.FORMAT_VERSION + 1)
        ZipOutputStream(future.outputStream()).use { zip ->
            val master = File(workspace, "master.wav").readBytes()
            zip.putNextEntry(ZipEntry(VynylBundle.MASTER))
            zip.write(master)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(VynylBundle.MANIFEST))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
        val result = VynylBundle.read(future, File(workspace, "future-target"))
        assertTrue(result is VynylBundle.BundleResult.Failed)
        assertTrue((result as VynylBundle.BundleResult.Failed).message.contains("newer", ignoreCase = true))
    }

    @Test
    fun `a zip without a manifest or without audio is refused`() {
        val noManifest = File(workspace, "no-manifest.vynyl")
        ZipOutputStream(noManifest.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(VynylBundle.MASTER))
            zip.write(ByteArray(400) { 1 })
            zip.closeEntry()
        }
        val first = VynylBundle.read(noManifest, File(workspace, "no-manifest-target"))
        assertTrue(first is VynylBundle.BundleResult.Failed)

        val noAudio = File(workspace, "no-audio.vynyl")
        ZipOutputStream(noAudio.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(VynylBundle.MANIFEST))
            zip.write(manifestText(sampleRecord()).toByteArray())
            zip.closeEntry()
        }
        val second = VynylBundle.read(noAudio, File(workspace, "no-audio-target"))
        assertTrue(second is VynylBundle.BundleResult.Failed)
        assertTrue((second as VynylBundle.BundleResult.Failed).message.contains("audio", ignoreCase = true))
    }

    @Test
    fun `a file that is not a bundle at all is refused politely`() {
        val text = File(workspace, "notes.txt").apply { writeBytes(ByteArray(400) { 42 }) }
        val result = VynylBundle.read(text, File(workspace, "notes-target"))
        assertTrue(result is VynylBundle.BundleResult.Failed)
        assertTrue((result as VynylBundle.BundleResult.Failed).message.isNotBlank())
        assertFalse(VynylBundle.looksLikeZip(text))
    }

    @Test
    fun `writing without a master is refused with something a person can read`() {
        val result = VynylBundle.write(
            target = File(workspace, "empty.vynyl"),
            record = record(),
            appVersion = "1.0.0",
            masterFile = null,
            sourceFile = null,
            artworkFile = null,
            waveformFile = null,
            includeSource = false,
        )
        assertTrue(result is VynylBundle.BundleResult.Failed)
        assertTrue((result as VynylBundle.BundleResult.Failed).message.contains("Press", ignoreCase = true))
    }

    @Test
    fun `the bundle advertises its own mime type and extension`() {
        assertEquals("vynyl", VynylBundle.EXTENSION)
        assertEquals("application/vnd.vynyl.record", VynylBundle.mimeTypeForBundle())
        assertEquals(VynylBundle.MIME_TYPE, VynylBundle.mimeTypeForBundle())
    }

    @Test
    fun `the size caps are ordered so the total is the tighter bound`() {
        assertTrue(VynylBundle.MAX_ENTRY_BYTES < VynylBundle.MAX_TOTAL_BYTES)
        assertEquals(160L * 1024L * 1024L, VynylBundle.MAX_ENTRY_BYTES)
        assertEquals(400L * 1024L * 1024L, VynylBundle.MAX_TOTAL_BYTES)
    }

    @Test
    fun `a manifest without a record object is refused`() {
        val document = JsonValue.obj(
            "kind" to JsonValue.of(VynylBundle.KIND),
            "formatVersion" to JsonValue.of(VynylBundle.FORMAT_VERSION),
        )
        val bundle = File(workspace, "no-record.vynyl")
        ZipOutputStream(bundle.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(VynylBundle.MASTER))
            zip.write(File(workspace, "master.wav").readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(VynylBundle.MANIFEST))
            zip.write(document.write().toByteArray())
            zip.closeEntry()
        }
        val result = VynylBundle.read(bundle, File(workspace, "no-record-target"))
        assertTrue(result is VynylBundle.BundleResult.Failed)
        assertNull(File(workspace, "no-record-target").takeIf { it.exists() })
    }

    private fun manifestText(record: Record, formatVersion: Int = VynylBundle.FORMAT_VERSION): String {
        val document = VynylBundle.manifestFor(record, "1.0.0", includesSource = false).asObjOrNull()!!
        val rebuilt = JsonValue.obj(
            linkedMapOf(
                "kind" to JsonValue.of(VynylBundle.KIND),
                "formatVersion" to JsonValue.of(formatVersion),
                "appVersion" to JsonValue.of(document.string("appVersion", "1.0.0")),
                "exportedAt" to JsonValue.of(document.long("exportedAt", 0L)),
                "includesSource" to JsonValue.of(document.bool("includesSource", false)),
                "record" to document.obj("record")!!,
            ),
        )
        return rebuilt.write()
    }
}
