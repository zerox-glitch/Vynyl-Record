package com.vynylrecord.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record itself: the states a press moves through, and the lines a person reads.
 *
 * The labels are not cosmetic. `displayTitle`, `dedicationLine` and `durationLabel` are what the library
 * row, the exported artwork, the label texture and the share sheet's file name are all built from, so a
 * change here shows up in four places at once.
 */
class RecordModelTest {

    private fun record(
        id: String = "3f2b8c1e-0000-4444-8888-abcdefabcdef",
        durationMs: Long = 65_000L,
        state: RenderState = RenderState.DRAFT,
        masterPath: String? = null,
    ) = Record(
        id = id,
        title = "For Ammi",
        recipientName = "Ammi",
        senderName = "Zain",
        dedication = "Press this and think of me.",
        occasion = Occasion.WEDDING,
        occasionDate = "14 February 2026",
        durationMilliseconds = durationMs,
        renderState = state,
        masterPath = masterPath,
    )

    @Test
    fun `a render moves through six states and only forward`() {
        assertEquals(6, RenderState.entries.size)
        assertEquals(
            listOf("draft", "queued", "rendering", "completed", "failed", "canceled"),
            RenderState.entries.map { it.id },
        )
        RenderState.entries.forEach { state ->
            assertTrue("${state.id} has no label", state.label.isNotBlank())
            assertTrue(state.progress in 0f..1f)
        }
        // Only the end of a press is terminal, and only the middle of one is running.
        assertTrue(RenderState.COMPLETED.isTerminal)
        assertTrue(RenderState.FAILED.isTerminal)
        assertTrue(RenderState.CANCELED.isTerminal)
        assertFalse(RenderState.DRAFT.isTerminal)
        assertFalse(RenderState.QUEUED.isTerminal)
        assertFalse(RenderState.RENDERING.isTerminal)
        assertTrue(RenderState.QUEUED.isRunning)
        assertTrue(RenderState.RENDERING.isRunning)
        assertFalse(RenderState.DRAFT.isRunning)
        assertFalse(RenderState.COMPLETED.isRunning)
        assertEquals(1f, RenderState.COMPLETED.progress, 0f)

        RenderState.entries.forEach { state -> assertEquals(state, RenderState.fromId(state.id)) }
        assertEquals(RenderState.DRAFT, RenderState.fromId("not-a-state"))
        assertEquals(RenderState.DRAFT, RenderState.fromId(null))
    }

    @Test
    fun `a press runs nine named stages that only ever move forward`() {
        assertEquals(9, RenderStage.entries.size)
        assertEquals(RenderStage.entries.toList(), RenderStage.ordered)
        assertEquals(
            listOf(
                "Preparing source", "Decoding", "Mixing atmosphere", "Adding vinyl character", "Mastering",
                "Encoding", "Generating waveform", "Generating artwork", "Complete",
            ),
            RenderStage.ordered.map { it.label },
        )
        assertEquals(0, RenderStage.PREPARING.index)
        assertEquals(8, RenderStage.COMPLETE.index)
        assertEquals(1f, RenderStage.COMPLETE.upTo, 0f)

        // The stage boundaries cover 0..1 with no gaps and no overlaps.
        RenderStage.ordered.zipWithNext().forEach { (earlier, later) ->
            assertTrue("${earlier.label} does not come before ${later.label}", earlier.upTo < later.upTo)
            assertTrue(later.upTo - earlier.upTo < 0.5f)
        }
        assertEquals(0.04f, RenderStage.PREPARING.upTo, 0f)

        // Whatever fraction the worker reports, there is a stage to show for it — and it never goes back.
        var previous = RenderStage.PREPARING
        var step = 0f
        while (step <= 1f) {
            val stage = RenderStage.forFraction(step)
            assertTrue(
                "progress went backwards at $step: ${previous.label} then ${stage.label}",
                stage.index >= previous.index,
            )
            previous = stage
            step += 0.005f
        }
        assertEquals(RenderStage.PREPARING, RenderStage.forFraction(0f))
        assertEquals(RenderStage.PREPARING, RenderStage.forFraction(-1f))
        assertEquals(RenderStage.COMPLETE, RenderStage.forFraction(1f))
        assertEquals(RenderStage.COMPLETE, RenderStage.forFraction(4f))
        assertEquals(RenderStage.CHARACTER, RenderStage.forFraction(0.5f))
        assertEquals(RenderStage.ARTWORK, RenderStage.forFraction(0.98f))
    }

    @Test
    fun `the occasions are a real list, not a placeholder`() {
        assertTrue(Occasion.entries.size >= 10)
        val ids = Occasion.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Occasion.entries.forEach { occasion ->
            assertTrue(occasion.label.isNotBlank())
            assertTrue("${occasion.id} has no writing prompt", occasion.prompt.isNotBlank())
            assertEquals(occasion, Occasion.fromId(occasion.id))
        }
        assertEquals(Occasion.SOMETHING_ELSE, Occasion.fromId(null))
        assertEquals(Occasion.SOMETHING_ELSE, Occasion.fromId("not-an-occasion"))
        assertEquals(Occasion.SOMETHING_ELSE, Occasion.DEFAULT)
        assertTrue(Occasion.entries.map { it.label }.contains("Wedding"))
        assertTrue(Occasion.entries.map { it.label }.contains("Memorial"))
    }

    @Test
    fun `the label lines fall back the way the design says they do`() {
        val full = record()
        assertEquals("For Ammi", full.displayTitle)
        assertEquals("Press this and think of me.", full.dedicationLine)
        assertEquals("from Zain", full.signatureLine)
        assertEquals("Wedding · 14 February 2026", full.subtitleLine)

        // Untitled, no dedication: the shelf says something honest rather than showing an empty row.
        val bare = Record(title = "", recipientName = "", senderName = "", occasion = Occasion.BIRTHDAY)
        assertEquals("Untitled record", bare.displayTitle)
        assertEquals("Birthday", bare.dedicationLine)
        assertEquals("Birthday", bare.subtitleLine)
        assertEquals("", bare.signatureLine)

        // A recipient but no dedication.
        assertEquals("For Ammi", bare.copy(recipientName = "Ammi").dedicationLine)
        // A dedication wins over both.
        assertEquals("Listen to this", bare.copy(recipientName = "Ammi", dedication = "Listen to this").dedicationLine)
        // An occasion with a date reads on one line.
        assertEquals("Birthday · 4 July 2026", bare.copy(occasionDate = "4 July 2026").subtitleLine)
    }

    @Test
    fun `durations read as a record sleeve does`() {
        assertEquals("0:00", record(durationMs = 0L).durationLabel)
        assertEquals("-0:00", record(durationMs = 0L).remainingLabel)
        assertEquals("1:05", record(durationMs = 65_000L).durationLabel)
        assertEquals("-1:05", record(durationMs = 65_000L).remainingLabel)
        assertEquals("3:09", record(durationMs = 189_400L).durationLabel)
        assertEquals("10:00", record(durationMs = 600_000L).durationLabel)
        // Milliseconds are truncated, not rounded up: a record never claims to be longer than it is.
        assertEquals("0:59", record(durationMs = 59_999L).durationLabel)
        // A negative duration cannot happen, and if it did the row must not show "-0:-1".
        assertEquals("-0:00", record(durationMs = -500L).remainingLabel)
    }

    @Test
    fun `sizes read the way a storage panel would say them`() {
        assertEquals("0 B", Record.formatBytes(0L))
        assertEquals("512 B", Record.formatBytes(512L))
        assertEquals("1 KB", Record.formatBytes(1_024L))
        assertEquals("2 KB", Record.formatBytes(2_048L))
        assertEquals("1.5 MB", Record.formatBytes(1_572_864L))
        assertEquals("2.00 GB", Record.formatBytes(2_147_483_648L))
        assertEquals("5.4 MB", record().copy(sizeBytes = 5_662_310L).sizeLabel)
    }

    @Test
    fun `a record is ready only when the master is really there`() {
        assertFalse(record().isReady)
        assertFalse(record(state = RenderState.QUEUED).isReady)
        // A completed row with no path is still not playable, and must not pretend otherwise.
        assertFalse(record(state = RenderState.COMPLETED).isReady)
        assertTrue(record(state = RenderState.COMPLETED, masterPath = "master.wav").isReady)
        assertFalse(record(state = RenderState.FAILED, masterPath = "master.wav").isReady)
    }

    @Test
    fun `the seed is a function of the record and the preset, so a re-press is identical`() {
        val a = Seed.derive("record-a", "warm_vintage")
        val b = Seed.derive("record-a", "warm_vintage")
        assertEquals(a, b)
        assertNotEquals(0L, a)
        // The same voice under a different preset is a different disc…
        assertNotEquals(a, Seed.derive("record-a", "rare_archival"))
        // …and two records of the same voice never share a pattern of pops.
        assertNotEquals(a, Seed.derive("record-b", "warm_vintage"))
        // The hash stays inside 32 bits, which is what the noise generator consumes.
        assertTrue(a in 0L..0xFFFFFFFFL)
        assertTrue(Seed.derive("", "").let { it == 0L || it in 0L..0xFFFFFFFFL })
    }

    @Test
    fun `a record without a stored seed derives one on demand`() {
        val zero = record().copy(deterministicSeed = 0L)
        assertEquals(Seed.derive(zero.id, zero.presetId.id), zero.seedOrDefault)
        assertEquals(zero.seedOrDefault, zero.seedOrDefault)
        val stored = zero.copy(deterministicSeed = 42L)
        assertEquals(42L, stored.seedOrDefault)
        // The stored seed is the one that matters: a record pressed once keeps its crackle for ever.
        val mixed = zero.copy(presetId = VinylPresetId.RARE_ARCHIVAL, deterministicSeed = 42L)
        assertEquals(42L, mixed.seedOrDefault)
    }

    @Test
    fun `a fresh record is a usable, empty draft`() {
        val fresh = Record()
        assertTrue(fresh.id.isNotBlank())
        assertNull(fresh.sourcePath)
        assertNull(fresh.masterPath)
        assertEquals(0L, fresh.durationMilliseconds)
        assertEquals(RenderState.DRAFT, fresh.renderState)
        assertFalse(fresh.isReady)
        assertFalse(fresh.favourite)
        assertEquals(0, fresh.playCount)
        assertEquals(VinylPresetId.DEFAULT, fresh.presetId)
        assertEquals(VinylStyleId.DEFAULT, fresh.styledId)
        assertEquals(Occasion.SOMETHING_ELSE, fresh.occasion)
        assertEquals(0.18f, fresh.backgroundVolume, 1e-6f)
        assertEquals("Side A", fresh.sideALabel)
        assertEquals("Side B", fresh.sideBLabel)
        assertTrue(fresh.waveformPeaks.isEmpty())
        assertTrue(fresh.createdAt > 0L)
        assertEquals(fresh.createdAt, fresh.updatedAt)
    }
}
