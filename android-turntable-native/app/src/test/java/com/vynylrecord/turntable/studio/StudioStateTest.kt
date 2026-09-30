package com.vynylrecord.turntable.studio

import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.VinylStyle
import com.vynylrecord.turntable.vault.Press
import com.vynylrecord.turntable.vault.PressRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Studio's own rules: when a side may be pressed, and what the screen says while it is being cut.
 *
 * These are the gates that decide whether a record can be made at all, so they are worth pinning down
 * separately from the composables that draw them.
 */
class StudioStateTest {

    private val ready = StudioUiState(
        title = "Late Night Letter",
        recipient = "Mum",
        sender = "You",
        source = SourceInfo(
            kind = SourceKind.RECORDING,
            displayName = "capture.wav",
            durationMs = 42_000L,
            sampleRate = 44_100,
            channels = 1,
            peakDecibels = -3.2f,
            waveform = listOf(0f, 0.5f, 1f, 0.5f),
        ),
    )

    @Test
    fun `a side cannot be pressed without a master`() {
        assertFalse("no master", ready.copy(source = null).canPress)
        assertTrue("with everything filled in it can be pressed", ready.canPress)
    }

    @Test
    fun `a labelled side needs a title and someone to dedicate it to`() {
        assertFalse("no title", ready.copy(title = "").canPress)
        assertFalse("a blank title is no title", ready.copy(title = "   ").canPress)
        assertFalse("no recipient", ready.copy(recipient = "").canPress)
        assertFalse("no sender", ready.copy(sender = "").canPress)
        assertFalse(ready.copy(title = "", recipient = "").dedicationComplete)
        assertTrue(ready.dedicationComplete)
    }

    @Test
    fun `a side cannot be pressed while it is being pressed and a recording cannot be pressed mid-capture`() {
        assertFalse("already pressing", ready.copy(press = PressProgress(active = true)).canPress)
        assertFalse("still recording", ready.copy(isRecording = true).canPress)
    }

    @Test
    fun `the steps run in order and stop at both ends`() {
        assertEquals(StudioStage.CAPTURE, StudioStage.DEDICATE.previous())
        assertEquals(StudioStage.DEDICATE, StudioStage.CAPTURE.next())
        assertEquals(StudioStage.ATMOSPHERE, StudioStage.ATMOSPHERE.next())
        assertEquals(StudioStage.CAPTURE, StudioStage.CAPTURE.previous())
        assertTrue(StudioStage.CAPTURE.isFirst)
        assertTrue(StudioStage.ATMOSPHERE.isLast)
        assertEquals(4, StudioStage.ordered.size)
        assertTrue("every step states its promise", StudioStage.ordered.all { it.promise.isNotBlank() })
    }

    @Test
    fun `press progress reports a percentage a person can read`() {
        assertEquals(0, PressProgress(fraction = 0f).percent)
        assertEquals(37, PressProgress(fraction = 0.374f).percent)
        assertEquals(100, PressProgress(fraction = 1f).percent)
        assertEquals("out of range values must clamp", 100, PressProgress(fraction = 1.4f).percent)
        assertEquals("and so must negatives", 0, PressProgress(fraction = -0.2f).percent)
    }

    @Test
    fun `the catalogue number follows the vault`() {
        assertEquals("VYN 001", StudioUiState().suggestedCatalogue)
        assertEquals("VYN 004", StudioUiState(vault = List(3) { press("$it") }).suggestedCatalogue)
        assertEquals("VYN 011", StudioUiState(vault = List(10) { press("$it") }).suggestedCatalogue)
    }

    @Test
    fun `storage is reported in units that fit on a settings row`() {
        assertEquals("0 B", StudioUiState(vaultBytes = 0).vaultSizeLabel)
        assertEquals("900 B", StudioUiState(vaultBytes = 900).vaultSizeLabel)
        assertEquals("5 KB", StudioUiState(vaultBytes = 5 * 1024).vaultSizeLabel)
        assertEquals("2.5 MB", StudioUiState(vaultBytes = (2.5 * 1024 * 1024).toLong()).vaultSizeLabel)
    }

    @Test
    fun `the recording timecode reads like a clock`() {
        assertEquals("00:00", StudioUiState(recordingMs = 0).recordingLabel)
        assertEquals("00:09", StudioUiState(recordingMs = 9_400).recordingLabel)
        assertEquals("01:30", StudioUiState(recordingMs = 90_000).recordingLabel)
        assertEquals("12:05", StudioUiState(recordingMs = 725_000).recordingLabel)
    }

    @Test
    fun `a long master is flagged as more than one side`() {
        val long = ready.source!!.copy(durationMs = 7 * 60_000L, spansMultipleSides = true)
        assertTrue("a seven minute note is two sides", long.spansMultipleSides)
        assertEquals("7:00", long.durationLabel)
        assertEquals("mono", long.channelsLabel)
        assertEquals("stereo", ready.source!!.copy(channels = 2, displayName = "x").channelsLabel)
        assertFalse(ready.source!!.spansMultipleSides)
    }

    @Test
    fun `a fresh studio is on the first step with a house cut and no master`() {
        val fresh = StudioUiState()
        assertEquals(StudioStage.CAPTURE, fresh.stage)
        assertEquals(PressRecipe.Preset.HOUSE_CUT.recipe, fresh.recipe)
        assertEquals(PressRecipe.Preset.HOUSE_CUT, fresh.recipePreset)
        assertEquals(VinylStyle.DEFAULT, fresh.style)
        assertEquals(PlatterSpeed.THIRTY_THREE, fresh.speed)
        assertEquals(null, fresh.source)
        assertFalse(fresh.canPress)
    }

    private fun press(id: String) = Press(
        id = id,
        metadata = com.vynylrecord.turntable.model.RecordMetadata(
            title = "Side $id",
            recipient = "You",
            sender = "Me",
        ),
        style = VinylStyle.DEFAULT,
        speed = PlatterSpeed.THIRTY_THREE,
        recipe = PressRecipe.STUDIO,
        fileName = "side.wav",
        createdAtEpochMs = 0L,
        durationMs = 1_000L,
        byteCount = 1_000L,
        sourceLabel = "Studio",
    )
}
