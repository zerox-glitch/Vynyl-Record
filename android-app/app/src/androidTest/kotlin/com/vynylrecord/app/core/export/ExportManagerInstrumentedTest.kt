package com.vynylrecord.app.core.export

import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vynylrecord.app.core.audio.dsp.FloatPcmBuffer
import com.vynylrecord.app.core.audio.dsp.WavCodec
import com.vynylrecord.app.core.audio.render.ArtworkRenderer
import com.vynylrecord.app.core.audio.render.RenderPipeline
import com.vynylrecord.app.core.data.db.VynylDatabase
import com.vynylrecord.app.core.data.prefs.AudioQuality
import com.vynylrecord.app.core.data.prefs.OutputFormat
import com.vynylrecord.app.core.data.repository.RecordRepository
import com.vynylrecord.app.core.model.Occasion
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.storage.FileStore
import com.vynylrecord.app.core.storage.RecordImport
import com.vynylrecord.app.core.storage.VynylBundle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * Exporting and sharing, on a device.
 *
 * This is the last step a user takes before the recording leaves the app: a file that has to open in
 * somebody else's phone, an artwork that has to be printable, and a bundle another install of Vynyl has to
 * be able to read back. The parts that cannot be checked on a desktop JVM — the media muxer, the platform
 * bitmap decoder, the FileProvider and the share contract — are exactly the parts checked here.
 */
@RunWith(AndroidJUnit4::class)
class ExportManagerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var database: VynylDatabase
    private lateinit var files: FileStore
    private lateinit var records: RecordRepository
    private lateinit var pipeline: RenderPipeline
    private lateinit var exports: ExportManager

    private var createdRecords = mutableListOf<String>()
    private var createdExports = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, VynylDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = FileStore(context)
        records = RecordRepository(database.records(), files)
        pipeline = RenderPipeline(context, files)
        exports = ExportManager(context, files, records, pipeline)
    }

    @After
    fun tearDown() {
        createdExports.forEach { it.delete() }
        createdRecords.forEach { id -> runCatching { files.deleteRecordFiles(id) } }
        database.close()
    }

    // ---------------------------------------------------------------- fixtures

    /** A finished record with a real 16-bit stereo master behind it, exactly as a press leaves one. */
    private fun completedRecord(
        title: String,
        seconds: Int = 2,
        preset: VinylPresetId = VinylPresetId.WARM_VINTAGE,
        style: VinylStyleId = VinylStyleId.DEFAULT,
    ): Record = runBlocking {
        val frames = seconds * SAMPLE_RATE
        val audio = FloatPcmBuffer(
            FloatArray(frames * 2) { index ->
                val frame = index / 2
                (0.35f * sin(2.0 * Math.PI * 220.0 * frame / SAMPLE_RATE)).toFloat()
            },
            SAMPLE_RATE,
            2,
        )
        val capture = File(files.cacheRoot, "capture-${System.nanoTime()}.wav")
        WavCodec.write(capture, audio)
        val draft = records.createDraft(capture, title, preset = preset, style = style)
        createdRecords += draft.id

        val master = files.masterFile(draft.id)
        WavCodec.write(master, audio)
        val finished = draft.copy(
            masterPath = master.absolutePath,
            durationMilliseconds = seconds * 1000L,
            renderState = RenderState.COMPLETED,
            renderProgress = 1f,
            renderStageLabel = "Complete",
            occasion = Occasion.BIRTHDAY,
            recipientName = "Amma",
            dedication = "For the mornings.",
            deterministicSeed = 7L,
        )
        records.save(finished)
    }

    /** A record whose press never produced a master, which is a state the app has to survive. */
    private fun recordWithoutMaster(title: String): Record = runBlocking {
        val capture = File(files.cacheRoot, "capture-${System.nanoTime()}.wav")
        WavCodec.write(
            capture,
            FloatPcmBuffer(FloatArray(SAMPLE_RATE * 2) { 0.2f }, SAMPLE_RATE, 2),
        )
        val draft = records.createDraft(capture, title)
        createdRecords += draft.id
        draft
    }

    private fun ready(result: ExportManager.Result): ExportManager.Result.Ready {
        assertTrue("expected a finished export, got $result", result is ExportManager.Result.Ready)
        return result as ExportManager.Result.Ready
    }

    private fun remember(result: ExportManager.Result.Ready): ExportManager.Result.Ready {
        createdExports += result.file
        return result
    }

    private fun hasAacEncoder(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            // A device whose encoder is software still counts, as long as it advertises the type: the
            // MediaCodec path is the same either way.
            info.isEncoder && !info.isAlias && info.supportedTypes.any { it.equals("audio/mp4a-latm", true) }
        }

    // ---------------------------------------------------------------- WAV

    @Test
    fun a_wav_export_is_a_real_file_the_rest_of_the_phone_can_open() {
        val record = completedRecord("Sunday Kitchen")
        val result = remember(ready(
            exports.exportAudio(record, OutputFormat.WAV, AudioQuality.HIGH, includeSource = false)
                .single(),
        ))

        assertEquals("audio/wav", result.mimeType)
        assertTrue("the export was not written", result.file.isFile)
        assertEquals(result.file.length(), result.bytes)
        assertTrue("the export is a header and nothing else", result.file.length() > 100_000L)
        // The name the user will see in a share sheet comes from the record, not from a job id.
        assertTrue("the export is not named after the record: ${result.file.name}", result.file.name.contains("Sunday"))

        val decoded = WavCodec.read(result.file)
        assertNotNull("the exported WAV does not decode", decoded)
        assertEquals(SAMPLE_RATE, decoded!!.sampleRate)
        assertEquals(2, decoded.channels)
        assertEquals(2 * SAMPLE_RATE, decoded.frameCount)
    }

    @Test
    fun every_export_is_handed_out_through_the_file_provider() {
        val record = completedRecord("The Long Way Home")
        val result = remember(ready(
            exports.exportAudio(record, OutputFormat.WAV, AudioQuality.HIGH, includeSource = false).single(),
        ))

        // A `file://` URI would be rejected by every modern Android release, so this is the difference
        // between sharing working and sharing throwing FileUriExposedException in front of the user.
        assertEquals("content", result.uri.scheme)
        assertEquals("${context.packageName}.fileprovider", result.uri.authority)
        assertTrue(
            "the shared URI is outside the provider's configured paths: ${result.uri}",
            result.uri.path.orEmpty().contains("exports") || result.uri.path.orEmpty().contains("shared"),
        )

        // And it is actually readable: opening it is what the receiving app will do.
        val stream = context.contentResolver.openInputStream(result.uri)
        assertNotNull("the shared URI could not be opened", stream)
        stream!!.use { input ->
            val header = ByteArray(12)
            assertEquals(12, input.read(header))
            assertEquals("RIFF", String(header, 0, 4))
            assertEquals("WAVE", String(header, 8, 4))
        }
    }

    @Test
    fun the_source_can_be_packed_beside_the_master_when_it_is_asked_for() {
        val record = completedRecord("Two Files")
        val withoutSource = exports.exportAudio(record, OutputFormat.WAV, AudioQuality.HIGH, includeSource = false)
        assertEquals(1, withoutSource.size)

        val withSource = exports.exportAudio(record, OutputFormat.WAV, AudioQuality.HIGH, includeSource = true)
        withSource.filterIsInstance<ExportManager.Result.Ready>().forEach { createdExports += it.file }
        assertEquals("the original recording was not included", 2, withSource.count { it is ExportManager.Result.Ready })
        assertTrue(
            "the two files are not distinguishable",
            withSource.filterIsInstance<ExportManager.Result.Ready>().map { it.file.name }.toSet().size == 2,
        )
    }

    @Test
    fun the_share_intent_carries_what_a_receiving_app_needs() {
        val record = completedRecord("For the Wall")
        val result = remember(ready(
            exports.exportAudio(record, OutputFormat.WAV, AudioQuality.HIGH, includeSource = false).single(),
        ))

        val intent = exports.shareIntent(result, record.displayTitle)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("audio/wav", intent.type)
        val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertEquals(result.uri, stream)
        assertTrue(
            "the receiving app was not granted read access",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertNotNull("there is no chooser to show", intent.selector)
    }

    @Test
    fun both_formats_are_offered_as_one_multi_file_share() {
        assumeTrue("this device has no AAC encoder", hasAacEncoder())
        val record = completedRecord("Side A and Side B")
        val results = exports.exportAudio(record, OutputFormat.BOTH, AudioQuality.HIGH, includeSource = false)
            .map { remember(ready(it)) }
        assertEquals("ask for both and both should arrive", 2, results.size)

        val many = exports.shareManyIntent(results, record.displayTitle)
        assertNotNull(many)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, many!!.action)
        val streams = many.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertEquals(2, streams?.size)
        assertTrue("the whole share was not granted", many.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun a_single_file_share_is_not_dressed_up_as_a_multiple() {
        val record = completedRecord("One File Only")
        val result = remember(ready(
            exports.exportAudio(record, OutputFormat.WAV, AudioQuality.HIGH, includeSource = false).single(),
        ))
        val intent = exports.shareManyIntent(listOf(result), record.displayTitle)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals(result.uri, intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }

    // ---------------------------------------------------------------- M4A

    @Test
    fun an_m4a_export_is_encoded_by_this_device_and_keeps_its_length() {
        assumeTrue("this device has no AAC encoder", hasAacEncoder())
        val record = completedRecord("M4A Please", seconds = 3)
        val result = remember(ready(
            exports.exportAudio(record, OutputFormat.M4A, AudioQuality.HIGH, includeSource = false).single(),
        ))

        assertEquals("audio/mp4", result.mimeType)
        assertTrue("the m4a is suspiciously small: ${result.file.length()}", result.file.length() > 4_000L)

        // Decoded through the platform extractor, which is the same route Media3 takes when it plays it.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, result.uri, null)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
            assertEquals(SAMPLE_RATE, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            // The muxer usually writes the duration; when a device's muxer does not, the file is still
            // perfectly playable and there is nothing to compare, so the check is skipped rather than
            // failed. Everything above it — one AAC track, the right rate, the right channel count — is
            // the part that has to hold.
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val reportedMs = format.getLong(MediaFormat.KEY_DURATION) / 1_000L
                assertTrue(
                    "the encode lost the length: ${reportedMs}ms against an expected 3000ms",
                    kotlin.math.abs(reportedMs - 3_000L) < 250L,
                )
            }
        } finally {
            extractor.release()
        }
    }

    @Test
    fun the_quality_setting_reaches_the_encoder() {
        assumeTrue("this device has no AAC encoder", hasAacEncoder())
        val record = completedRecord("Compact Please", seconds = 3)
        val compact = remember(ready(
            exports.exportAudio(record, OutputFormat.M4A, AudioQuality.STANDARD, includeSource = false).single(),
        ))
        val studio = remember(ready(
            exports.exportAudio(record, OutputFormat.M4A, AudioQuality.STUDIO, includeSource = false).single(),
        ))
        // 128 kbps against 256 kbps over the same three seconds: the difference is large enough that the
        // two files cannot be equal, which is what proves the setting is not decorative.
        assertTrue(
            "the two qualities produced comparable files: ${compact.bytes} against ${studio.bytes}",
            studio.bytes > compact.bytes * 1.2f,
        )
        assertTrue(compact.file.length() > 0L)
    }

    // ---------------------------------------------------------------- artwork and bundle

    @Test
    fun the_artwork_is_a_printable_png() {
        val record = completedRecord("The Sleeve")
        val result = remember(ready(exports.exportArtwork(record)))

        assertEquals("image/png", result.mimeType)
        assertEquals("png", result.file.extension)
        assertTrue("the cover is not a real image", result.file.length() > 5_000L)
        val bitmap = BitmapFactory.decodeFile(result.file.absolutePath)
        assertNotNull("the exported cover does not decode", bitmap)
        assertEquals(ArtworkRenderer.DEFAULT_SIZE, bitmap!!.width)
        assertEquals(ArtworkRenderer.DEFAULT_SIZE, bitmap.height)
        // A cover that is entirely one colour means the label, the title or the gradient never drew.
        assertTrue("the cover is blank", distinctColours(bitmap) > 4)
        bitmap.recycle()
    }

    @Test
    fun a_bundle_comes_back_into_the_app_through_the_importer() {
        val record = completedRecord("Letter to Saoirse", preset = VinylPresetId.RARE_ARCHIVAL)
        val result = remember(ready(
            exports.exportBundle(record, appVersion = "1.0.0", includeSource = true),
        ))

        assertEquals(VynylBundle.MIME_TYPE, result.mimeType)
        assertEquals(VynylBundle.EXTENSION, result.file.extension)
        assertTrue("the bundle is not a zip", VynylBundle.looksLikeZip(result.file))

        val staging = RecordImport.directoryFor(records, files)
        val read = VynylBundle.read(result.file, staging)
        assertTrue("the bundle could not be read back: $read", read is VynylBundle.BundleResult.Read)
        val contents = (read as VynylBundle.BundleResult.Read).contents
        assertEquals("the title did not survive the round trip", record.displayTitle, contents.manifest.record.displayTitle)
        assertEquals(record.id, contents.manifest.record.id)
        assertEquals(VinylPresetId.RARE_ARCHIVAL, contents.manifest.record.presetId)
        assertEquals(record.styledId, contents.manifest.record.styledId)
        assertEquals(appVersionOf(), contents.manifest.appVersion)
        assertTrue(contents.manifest.includesSource)
        assertNotNull("the master was not in the bundle", contents.master)
        assertNotNull("the source was not in the bundle", contents.source)

        // And importing it produces a record whose master is a real file the vault can play.
        val imported = runBlocking { RecordImport.commit(records, files, contents) }
        assertNotNull("the import refused a bundle this app wrote", imported)
        createdRecords += imported!!.id
        val master = records.resolveMaster(imported)
        assertNotNull(master)
        assertTrue(master!!.isFile)
        assertTrue("the imported master is empty", master.length() > 100_000L)
        assertEquals(record.durationMilliseconds, imported.durationMilliseconds)
        assertTrue(
            "an imported record should be ready to play",
            imported.renderState == RenderState.COMPLETED,
        )
    }

    @Test
    fun a_bundle_without_the_source_is_smaller_and_says_so() {
        val record = completedRecord("Master Only")
        val withoutSource = remember(ready(exports.exportBundle(record, "1.0.0", includeSource = false)))
        val staging = RecordImport.directoryFor(records, files)
        val read = VynylBundle.read(withoutSource.file, staging)
        assertTrue(read is VynylBundle.BundleResult.Read)
        val contents = (read as VynylBundle.BundleResult.Read).contents
        assertFalse(contents.manifest.includesSource)
        assertEquals(null, contents.source)
        assertNotNull(contents.master)
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun a_record_with_no_audio_fails_with_a_sentence_instead_of_a_crash() {
        val record = recordWithoutMaster("Never Pressed")
        val results = exports.exportAudio(record, OutputFormat.WAV, AudioQuality.HIGH, includeSource = false)
        val failure = results.filterIsInstance<ExportManager.Result.Failed>().firstOrNull()
        assertNotNull("exporting a record with no master should fail", failure)
        assertTrue(
            "the message does not explain itself: ${failure!!.message}",
            failure.message.contains("no audio", ignoreCase = true),
        )
        assertTrue(
            "the message is a stack trace, not a sentence",
            failure.message.length in 10..140,
        )
    }

    @Test
    fun exports_are_listed_and_can_be_deleted() {
        val record = completedRecord("Housekeeping")
        val result = remember(ready(
            exports.exportAudio(record, OutputFormat.WAV, AudioQuality.HIGH, includeSource = false).single(),
        ))
        assertTrue(
            "the export is not in the exports folder",
            exports.existingExports().any { it.absolutePath == result.file.absolutePath },
        )
        assertTrue(exports.deleteExport(result.file))
        assertFalse(result.file.exists())
    }

    @Test
    fun the_export_folder_is_the_apps_own_storage() {
        val record = completedRecord("Where Am I")
        val result = remember(ready(
            exports.exportAudio(record, OutputFormat.WAV, AudioQuality.HIGH, includeSource = false).single(),
        ))
        assertTrue(
            "exports must live in the app's own storage: ${result.file.absolutePath}",
            result.file.absolutePath.startsWith(files.exportsRoot().absolutePath),
        )
        // Nothing about an export depends on a storage permission: the file lives in the app's own
        // directory and leaves through a content URI the receiving app is granted.
        @Suppress("DEPRECATION")
        val requested = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .requestedPermissions ?: emptyArray()
        assertEquals(
            "an export must not need a storage permission: ${requested.toList()}",
            0,
            requested.count { it.contains("STORAGE") && !it.contains("MANAGE") },
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun appVersionOf(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"

    /** How many different colours the cover uses, sampled across a grid. */
    private fun distinctColours(bitmap: android.graphics.Bitmap): Int {
        val seen = HashSet<Int>()
        for (x in 0 until bitmap.width step bitmap.width / 32) {
            for (y in 0 until bitmap.height step bitmap.height / 32) {
                seen += bitmap.getPixel(x, y)
            }
        }
        return seen.size
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
