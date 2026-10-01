package com.vynylrecord.app.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * The storage layout.
 *
 * Every path in the app comes out of [StorageLayout], which makes this the one place where a bug can put
 * a record somewhere nothing else looks — or, worse, outside the app's own directory. The interesting
 * cases are the hostile ids: an id from a `.vynyl` bundle is attacker-controlled, and it is joined
 * straight onto `files/records/`.
 */
class StorageLayoutTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val root get() = temporary.root
    private val cache get() = File(root, "cache").apply { mkdirs() }

    private fun write(file: File, bytes: Int) {
        StorageLayout.ensureParent(file)
        file.writeBytes(ByteArray(bytes) { 0x41 })
    }

    @Test
    fun `a record keeps its four files together`() {
        val id = "3f2b8c1e-0000-4444-8888-abcdefabcdef"
        val directory = StorageLayout.recordDirectory(root, id)

        assertEquals(File(StorageLayout.recordsRoot(root), id), directory)
        assertEquals(File(directory, StorageLayout.SOURCE_NAME), StorageLayout.sourceFile(root, id))
        assertEquals(File(directory, StorageLayout.MASTER_NAME), StorageLayout.masterFile(root, id))
        assertEquals(File(directory, StorageLayout.ARTWORK_NAME), StorageLayout.artworkFile(root, id))
        assertEquals(File(directory, StorageLayout.WAVEFORM_NAME), StorageLayout.waveformFile(root, id))
        // All four files share one directory, so deleting a record is one delete.
        assertEquals(StorageLayout.RECORDS, directory.parentFile?.name)
    }

    @Test
    fun `an asset keeps its own extension`() {
        val wav = StorageLayout.assetFile(root, "music-1")
        assertEquals("music-1.wav", wav.name)
        assertEquals(StorageLayout.ASSETS, wav.parentFile?.name)
        assertEquals("music-1.m4a", StorageLayout.assetFile(root, "music-1", "m4a").name)
    }

    @Test
    fun `an in-flight render lives in the cache, not in files`() {
        val job = StorageLayout.renderDirectory(cache, "job-9")
        assertEquals(File(File(cache, StorageLayout.RENDER), "job-9"), job)
        assertEquals(File(job, "master.wav"), StorageLayout.renderMaster(cache, "job-9"))
        // A render's scratch must never sit under files/: a cache wipe has to be able to remove it.
        assertFalse(job.path.startsWith(StorageLayout.recordsRoot(root).path))
    }

    @Test
    fun `the staging roots are all under the cache`() {
        listOf(
            StorageLayout.stagingRoot(cache),
            StorageLayout.decodedRoot(cache),
            StorageLayout.sharedRoot(cache),
        ).forEach { directory ->
            assertEquals(cache, directory.parentFile)
        }
        assertNotEquals(StorageLayout.stagingRoot(cache), StorageLayout.decodedRoot(cache))
    }

    @Test
    fun `an id cannot climb out of its directory`() {
        val hostile = listOf(
            "../outside",
            "../../../../etc/passwd",
            "..",
            ".",
            "/",
            "/etc/passwd",
            "a/../../b",
            "..\\..\\windows",
            "%2e%2e%2f%2e%2e%2f",
            "\u0000",
            " ",
            "",
        )
        val records = StorageLayout.recordsRoot(root).canonicalPath
        hostile.forEach { id ->
            val safe = StorageLayout.safeId(id)
            assertTrue("safeId($id) was empty", safe.isNotEmpty())
            assertFalse("safeId($id) kept a separator", safe.contains('/') || safe.contains('\\'))
            assertFalse("safeId($id) kept a dot", safe.contains(".."))
            val directory = StorageLayout.recordDirectory(root, id)
            assertTrue(
                "an id of '$id' escaped the records directory: ${directory.path}",
                directory.canonicalPath.startsWith(records + File.separator),
            )
            assertEquals(directory, File(records, safe))
        }
    }

    @Test
    fun `the ids the app generates are left alone`() {
        val id = UUID.randomUUID().toString()
        assertEquals(id, StorageLayout.safeId(id))
        // Ids are file names, so length is capped rather than trusted.
        val long = "a".repeat(300)
        assertEquals(64, StorageLayout.safeId(long).length)
        assertEquals("unnamed", StorageLayout.safeId("../../"))
        assertEquals("2f2e2e2f", StorageLayout.safeId("%2f%2e%2e%2f"))
    }

    @Test
    fun `ensureParent builds the tree a path needs`() {
        val file = File(root, "records/a/b/c/source.wav")
        assertFalse(file.parentFile!!.exists())
        assertEquals(file, StorageLayout.ensureParent(file))
        assertTrue(file.parentFile!!.isDirectory)
        // Calling it twice is not an error, and a file at the root needs no parent.
        StorageLayout.ensureParent(file)
        StorageLayout.ensureParent(File(root, "top.wav"))
    }

    @Test
    fun `sizeOf measures a tree exactly`() {
        val record = StorageLayout.recordDirectory(root, "r1")
        write(StorageLayout.sourceFile(root, "r1"), 1_000)
        write(StorageLayout.masterFile(root, "r1"), 4_000)
        write(StorageLayout.artworkFile(root, "r1"), 250)
        // A wave file left over from an older build is still this record's bytes.
        write(File(record, "waveform.json"), 50)

        assertEquals(5_300L, StorageLayout.sizeOf(record))
        assertEquals(5_300L, StorageLayout.recordBytes(root, "r1"))
        assertEquals(5_300L, StorageLayout.sizeOf(StorageLayout.recordsRoot(root)))
        assertEquals(0L, StorageLayout.sizeOf(File(root, "nothing-here")))
        assertEquals(1_000L, StorageLayout.sizeOf(StorageLayout.sourceFile(root, "r1")))
    }

    @Test
    fun `deleteTree frees exactly what it removes`() {
        write(File(root, "doomed/a/one.bin"), 700)
        write(File(root, "doomed/a/b/two.bin"), 300)
        val doomed = File(root, "doomed")

        val freed = StorageLayout.deleteTree(doomed)
        assertEquals(1_000L, freed)
        assertFalse(doomed.exists())
        // Deleting something that is already gone is not a failure, and frees nothing.
        assertEquals(0L, StorageLayout.deleteTree(doomed))
    }

    @Test
    fun `the cache is measured in two parts`() {
        write(StorageLayout.renderMaster(cache, "job-1"), 2_000)
        write(StorageLayout.renderMaster(cache, "job-2"), 500)
        write(File(StorageLayout.stagingRoot(cache), "bed.wav"), 400)
        write(File(StorageLayout.decodedRoot(cache), "bed.pcm"), 100)
        write(File(StorageLayout.sharedRoot(cache), "For Ammi - Side A.wav"), 300)

        assertEquals(2_500L, StorageLayout.renderCacheBytes(cache))
        assertEquals(500L, StorageLayout.assetCacheBytes(cache))
        // The two panels on the settings screen must not double-count each other.
        assertNotEquals(StorageLayout.renderCacheBytes(cache), StorageLayout.assetCacheBytes(cache))
    }

    @Test
    fun `clearing the cache spares the exports`() {
        val export = File(StorageLayout.exportsRoot(root), "For Ammi - Side A.wav")
        write(export, 9_000)
        write(StorageLayout.renderMaster(cache, "job-1"), 2_000)
        write(File(StorageLayout.stagingRoot(cache), "bed.wav"), 400)
        write(File(StorageLayout.decodedRoot(cache), "bed.pcm"), 100)
        write(File(StorageLayout.sharedRoot(cache), "copy.wav"), 300)

        val freed = StorageLayout.clearCache(cache)
        assertEquals(2_800L, freed)
        assertFalse(File(cache, StorageLayout.RENDER).exists())
        assertFalse(StorageLayout.stagingRoot(cache).exists())
        assertFalse(StorageLayout.decodedRoot(cache).exists())
        assertFalse(StorageLayout.sharedRoot(cache).exists())
        assertTrue("the cache wipe took the user's export with it", export.isFile)
        assertEquals(9_000L, export.length())
        // An export is not under any of the cache roots by construction.
        assertFalse(export.path.startsWith(cache.path))

        // A second press on the button frees nothing and reports so honestly.
        assertEquals(0L, StorageLayout.clearCache(cache))
    }

    @Test
    fun `a record's bytes are its own`() {
        write(StorageLayout.masterFile(root, "r1"), 1_500)
        write(StorageLayout.masterFile(root, "r2"), 275)
        assertEquals(1_500L, StorageLayout.recordBytes(root, "r1"))
        assertEquals(275L, StorageLayout.recordBytes(root, "r2"))
        assertEquals(0L, StorageLayout.recordBytes(root, "never-rendered"))
        assertEquals(1_775L, StorageLayout.sizeOf(StorageLayout.recordsRoot(root)))
    }
}
