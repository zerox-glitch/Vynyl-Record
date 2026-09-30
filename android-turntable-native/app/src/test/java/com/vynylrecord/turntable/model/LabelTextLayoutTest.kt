package com.vynylrecord.turntable.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Label typography runs on a bitmap, so the layout rules have to be provable without a device. The
 * "measure" function below is a fixed-width stand-in for the canvas: ten units per character.
 */
class LabelTextLayoutTest {

    private val measure: (String) -> Float = { text -> text.length * 10f }

    @Test
    fun shortTitlesAreLeftAlone() {
        assertEquals("Golden Hour", LabelTextLayout.ellipsize("Golden Hour", 500f, measure))
    }

    @Test
    fun longTitlesAreEllipsisedInsideTheBudget() {
        val budget = 120f
        val result = LabelTextLayout.ellipsize("A Very Long Record Title Indeed", budget, measure)
        assertTrue("the ellipsis must be visible", result.endsWith(LabelTextLayout.ELLIPSIS))
        assertTrue("the result must fit: '$result'", measure(result) <= budget)
        assertTrue("the result must keep as much text as fits", result.length >= 8)
    }

    @Test
    fun wrappingHonoursTheLineLimit() {
        val lines = LabelTextLayout.wrap(
            text = "Golden Hour Sessions Recorded Live On A Warm Evening In September",
            maxWidth = 120f,
            maxLines = 2,
            measure = measure,
        )
        assertTrue("no more than two lines", lines.size <= 2)
        for (line in lines) assertTrue("'$line' must fit", measure(line) <= 120f)
        assertTrue("the overflow must be marked", lines.last().endsWith(LabelTextLayout.ELLIPSIS))
    }

    @Test
    fun anUnbreakableWordIsHardBroken() {
        val lines = LabelTextLayout.wrap(
            text = "Supercalifragilisticexpialidocious",
            maxWidth = 80f,
            maxLines = 6,
            measure = measure,
        )
        assertTrue(lines.isNotEmpty())
        for (line in lines) assertTrue("'$line' must fit the budget", measure(line) <= 80f)
    }

    @Test
    fun emptyInputProducesNoLines() {
        assertTrue(LabelTextLayout.wrap("   ", 100f, 3, measure).isEmpty())
        assertEquals("", LabelTextLayout.ellipsize("", 100f, measure))
    }

    @Test
    fun balancingEvensOutATwoLineTitle() {
        val wrapped = listOf("Golden Hour Sessions", "Live")
        val balanced = LabelTextLayout.balance(wrapped, 400f, measure)
        assertEquals(wrapped.size, balanced.size)
        val before = kotlin.math.abs(measure(wrapped[0]) - measure(wrapped[1]))
        val after = kotlin.math.abs(measure(balanced[0]) - measure(balanced[1]))
        assertTrue("balance should even the lines out ($before -> $after)", after <= before)
        for (line in balanced) assertTrue(measure(line) <= 400f)
    }

    @Test
    fun titleLayoutStaysInsideTheSideBudget() {
        val budget = 300f
        val lines = LabelTextLayout.layoutTitle(
            title = "Midnight Drive Home Through The Rain",
            maxWidth = budget,
            maxLines = 2,
            measure = measure,
        )
        assertTrue(lines.size in 1..2)
        for (line in lines) assertTrue("'$line' must fit", measure(line) <= budget)
    }

    @Test
    fun metadataIsNormalisedAndComparable() {
        val metadata = RecordMetadata(
            title = "  Golden Hour  ",
            recipient = " You ",
            sender = " Vynyl Record ",
            side = RecordSide.A,
            date = "   ",
            catalogue = " VYN 001 ",
        ).normalized()

        assertEquals("Golden Hour", metadata.title)
        assertEquals("You", metadata.recipient)
        assertEquals("Vynyl Record", metadata.sender)
        assertEquals(null, metadata.date)
        assertEquals("VYN 001", metadata.catalogue)
        assertTrue(metadata.rendersSameLabelAs(metadata.copy()))
        assertTrue(!metadata.rendersSameLabelAs(metadata.copy(title = "Something Else")))
        assertTrue(!metadata.rendersSameLabelAs(null))
        assertEquals(RecordSide.B, RecordSide.A.flipped())
        assertEquals(RecordSide.A, RecordSide.B.flipped())
        assertEquals("for You", metadata.dedicationLine())
        assertEquals("from Vynyl Record", metadata.signatureLine())
    }

    @Test
    fun thePrintedSideMatchesTheLabelSpec() {
        assertEquals("SIDE A", LabelRendererSpec.sideLabel(RecordSide.A))
        assertEquals("SIDE B", LabelRendererSpec.sideLabel(RecordSide.B))
        assertEquals("A", RecordSide.A.shortName)
        assertEquals("B", RecordSide.B.shortName)
    }

    @Test
    fun theDefaultMetadataIsPrintable() {
        val default = RecordMetadata.DEFAULT
        assertTrue(default.title.isNotBlank())
        assertTrue(default.recipient.isNotBlank())
        assertTrue(default.sender.isNotBlank())
        assertTrue(default.rendersSameLabelAs(RecordMetadata.DEFAULT))
    }
}
