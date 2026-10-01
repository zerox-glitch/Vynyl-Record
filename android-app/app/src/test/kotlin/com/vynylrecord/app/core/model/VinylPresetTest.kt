package com.vynylrecord.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five pressings.
 *
 * The presets are the product, not a cosmetic list: each one is a complete recipe the renderer runs, and
 * the gradient between them — a fresh pressing, a kept seventies record, an attic find, a family tape, an
 * archival transfer — is what the user is actually choosing. These tests pin the numbers that make that
 * gradient real, so a preset cannot quietly become the same sound as its neighbour.
 */
class VinylPresetTest {

    private val fieldOrder = listOf(
        VinylPresetId.CLEAN_VINYL,
        VinylPresetId.WARM_VINTAGE,
        VinylPresetId.DUSTY_RECORD,
        VinylPresetId.OLD_FAMILY_RECORD,
        VinylPresetId.RARE_ARCHIVAL,
    )

    @Test
    fun `there are five pressings and the catalogue is honest about them`() {
        assertEquals(5, VinylPresetId.entries.size)
        assertEquals(fieldOrder, VinylPresetId.all)
        VinylPresetId.entries.forEach { preset ->
            assertTrue("${preset.id} has no name", preset.displayName.isNotBlank())
            assertTrue("${preset.id} has no description", preset.description.isNotBlank())
            assertTrue("${preset.id} has no character line", preset.character.isNotBlank())
            assertTrue("${preset.id} has a lower-case id", preset.id.all { it.isLowerCase() || it == '_' })
        }
        // The ids are the web application's, because they are stored in `.vynyl` bundles and backups.
        assertEquals(
            listOf("clean_vinyl", "warm_vintage", "dusty_record", "old_family_record", "rare_archival"),
            VinylPresetId.entries.map { it.id },
        )
        assertEquals(VinylPresetId.WARM_VINTAGE, VinylPresetId.DEFAULT)
    }

    @Test
    fun `a preset is found by its stored id and falls back to the default`() {
        VinylPresetId.entries.forEach { preset ->
            assertEquals(preset, VinylPresetId.fromId(preset.id))
        }
        assertEquals(VinylPresetId.DEFAULT, VinylPresetId.fromId("no-such-preset"))
        assertEquals(VinylPresetId.DEFAULT, VinylPresetId.fromId(null))
        assertEquals(VinylPresetId.DEFAULT, VinylPresetId.fromId(""))

        // The four names a web-era database may still hold.
        assertEquals(VinylPresetId.CLEAN_VINYL, VinylPresetId.fromLegacyFilterId("clean"))
        assertEquals(VinylPresetId.WARM_VINTAGE, VinylPresetId.fromLegacyFilterId("gramophone"))
        assertEquals(VinylPresetId.OLD_FAMILY_RECORD, VinylPresetId.fromLegacyFilterId("radio"))
        assertEquals(VinylPresetId.DUSTY_RECORD, VinylPresetId.fromLegacyFilterId("tape"))
        assertNull(VinylPresetId.fromLegacyFilterId("cassette"))
        assertNull(VinylPresetId.fromLegacyFilterId(null))
    }

    @Test
    fun `the shelf gets darker from left to right`() {
        val lowPass = fieldOrder.map { it.recipe.lowPassHz }
        val highPass = fieldOrder.map { it.recipe.highPassHz }
        val drive = fieldOrder.map { it.recipe.saturationDrive }
        val surface = fieldOrder.map { it.recipe.surfaceLevelDb }
        val hiss = fieldOrder.map { it.recipe.hissLevelDb }
        val intro = fieldOrder.map { it.recipe.needleIntroMs }
        val ceiling = fieldOrder.map { it.recipe.limiterCeil }

        assertEquals(listOf(16_500f, 14_200f, 12_600f, 11_200f, 9_800f), lowPass)
        assertEquals(listOf(90f, 75f, 70f, 65f, 60f), highPass)
        assertEquals(listOf(0.08f, 0.28f, 0.45f, 0.55f, 0.65f), drive)
        assertEquals(listOf(-42f, -34f, -28f, -28f, -24f), surface)
        assertEquals(listOf(-52f, -44f, -38f, -34f, -30f), hiss)
        assertEquals(listOf(90, 140, 180, 240, 320), intro)
        assertEquals(listOf(0.96f, 0.94f, 0.94f, 0.93f, 0.92f), ceiling)

        // Decreasing means the highs are being taken away; increasing means more of what makes a record
        // sound worn. Both are strict, except where two presets deliberately share a value.
        lowPass.zipWithNext().forEach { (a, b) -> assertTrue("$a is not above $b", a > b) }
        highPass.zipWithNext().forEach { (a, b) -> assertTrue("$a is not above $b", a > b) }
        drive.zipWithNext().forEach { (a, b) -> assertTrue("$a is not below $b", a < b) }
        surface.zipWithNext().forEach { (a, b) -> assertTrue("$a is not below $b", a <= b) }
        hiss.zipWithNext().forEach { (a, b) -> assertTrue("$a is not below $b", a < b) }
        intro.zipWithNext().forEach { (a, b) -> assertTrue("$a is not below $b", a < b) }
        ceiling.zipWithNext().forEach { (a, b) -> assertTrue("$a is not below $b", a >= b) }
    }

    @Test
    fun `the transient layers escalate from a clean press to an archival transfer`() {
        val crackle = fieldOrder.map { it.recipe.crackleDensityPerMin }
        val pops = fieldOrder.map { it.recipe.popsDensityPerMin }
        val intensity = fieldOrder.map { it.recipe.crackleIntensity }

        assertEquals(listOf(10f, 22f, 38f, 28f, 48f), crackle)
        assertEquals(listOf(0.4f, 0.9f, 1.6f, 1.0f, 2.0f), pops)
        // The two ends of the shelf are unambiguous: the archival press cracks and pops most, the clean
        // one least.
        assertEquals(48f, crackle.max())
        assertEquals(10f, crackle.min())
        assertEquals(fieldOrder[4], fieldOrder[crackle.indexOf(48f)])
        assertTrue(crackle[2] > crackle[1])
        assertTrue(crackle[1] > crackle[0])
        assertTrue(crackle[3] in crackle[1]..crackle[2])
        assertTrue(pops[4] > pops[2])
        assertTrue(pops[2] > pops[1])
        assertTrue(pops[1] > pops[0])
        // Intensity is not a monotone shelf either — the family tape is quieter per tick than the attic
        // find — but the ends are what the user hears.
        assertTrue(intensity.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) > 0.01f })
        assertTrue(intensity.last() > intensity.first())
        assertTrue(intensity.all { it in 0.15f..0.85f })

        // Every preset runs a real bed and a real hiss layer: no preset is "off".
        fieldOrder.forEach { preset ->
            assertTrue(preset.recipe.surfaceEnabled)
            assertTrue("${preset.id} has no bed", preset.recipe.surfaceLevelDb < 0f)
            assertTrue("${preset.id} has no hiss", preset.recipe.hissLevelDb < preset.recipe.surfaceLevelDb)
            assertTrue(preset.recipe.crackleEnabled)
            assertTrue(preset.recipe.popsEnabled)
        }
        // …except that the shapes differ, which is what stops five presets sounding like one preset
        // played louder.
        assertEquals(
            3,
            fieldOrder.map { it.recipe.surfaceFilter }.distinct().size,
        )
        assertEquals(NoiseFilterShape.HIGHPASS_FLAT, VinylPresetId.CLEAN_VINYL.recipe.surfaceFilter)
        assertEquals(NoiseFilterShape.FLAT, VinylPresetId.RARE_ARCHIVAL.recipe.surfaceFilter)
    }

    @Test
    fun `every preset is deterministic and every preset is stereo`() {
        VinylPresetId.entries.forEach { preset ->
            assertTrue("${preset.id} shared its noise with other records", preset.recipe.seedFromRecordingId)
            assertTrue("${preset.id} is mono", preset.recipe.stereoEnabled)
            assertTrue(preset.recipe.stereoWidth in 0.5f..1.25f)
            assertTrue(preset.recipe.limiterCeil in 0.8f..1.0f)
            assertTrue(preset.recipe.masterGain > 0f)
            assertTrue(preset.recipe.musicLevel in 0f..0.8f)
            assertTrue(preset.recipe.needleIntroMs in 0..2_000)
            assertTrue(preset.recipe.highPassHz < preset.recipe.lowPassHz)
            assertTrue("${preset.id} has wow but no depth", preset.recipe.wowEnabled)
            assertTrue(preset.recipe.wowDepthCents > 0f)
            assertTrue(preset.recipe.wowRatePerMin in 3f..18f)
            assertTrue(preset.recipe.flutterEnabled)
            assertTrue(preset.recipe.flutterRatePerMin in 1_800f..3_600f)
        }
        // The stereo image narrows as the record gets older, which is what a worn pressing does.
        val widths = fieldOrder.map { it.recipe.stereoWidth }
        widths.zipWithNext().forEach { (a, b) -> assertTrue("$a is not wider than $b", a >= b) }
        assertEquals(listOf(1.00f, 0.95f, 0.92f, 0.88f, 0.85f), widths)
    }

    @Test
    fun `the derived badges are derived, not stored`() {
        VinylPresetId.entries.forEach { preset ->
            assertTrue(preset.warmth in 0f..1f)
            assertTrue(preset.texture in 0f..1f)
            assertTrue(preset.age in 0f..1f)
        }
        // Warmth and texture both climb from one end of the shelf to the other — not strictly, because the
        // family tape is warm and quiet where the archival transfer is warm and loud — and age, which
        // combines them, climbs too.
        val warmth = fieldOrder.map { it.warmth }
        assertTrue(warmth.first() < warmth[1])
        assertTrue(warmth[1] < warmth[2])
        assertTrue(warmth.last() > warmth.first())
        assertEquals(5, warmth.distinct().size)
        val texture = fieldOrder.map { it.texture }
        assertTrue(texture.first() < texture.last())
        assertTrue(fieldOrder.first().age < fieldOrder.last().age)

        // And the character line names what the recipe actually does.
        assertTrue(VinylPresetId.CLEAN_VINYL.character.contains("clean press"))
        assertTrue(VinylPresetId.RARE_ARCHIVAL.character.contains("crackle"))
        assertTrue(VinylPresetId.RARE_ARCHIVAL.character.contains("soft highs"))
        assertTrue(VinylPresetId.RARE_ARCHIVAL.character.contains("wandering wow"))
    }

    @Test
    fun `the dBFS helpers match the recipe's own values`() {
        VinylPresetId.entries.forEach { preset ->
            val recipe = preset.recipe
            assertEquals(Math.pow(10.0, (recipe.surfaceLevelDb / 20.0)).toFloat(), recipe.surfaceLinear, 1e-6f)
            assertEquals(Math.pow(10.0, (recipe.hissLevelDb / 20.0)).toFloat(), recipe.hissLinear, 1e-6f)
            assertTrue(recipe.surfaceLinear in 0.0005f..0.1f)
            assertTrue(recipe.hissLinear < recipe.surfaceLinear)
        }
        assertEquals(1f, VinylRecipe.dbToLinear(0f), 1e-6f)
        assertEquals(0.5f, VinylRecipe.dbToLinear(-6.0206f), 1e-3f)
        assertEquals(0.1f, VinylRecipe.dbToLinear(-20f), 1e-6f)
    }

    @Test
    fun `every knob maps a recipe value onto zero to one and back`() {
        assertEquals(14, VinylControl.entries.size)
        assertEquals(VinylControl.entries.toList(), VinylControl.ordered)
        VinylControl.entries.forEach { control ->
            assertTrue("${control.name} has no label", control.label.isNotBlank())
            assertTrue("${control.name} has no hint", control.hint.isNotBlank())

            // A preset's value sits inside the slider's range…
            VinylPresetId.entries.forEach { preset ->
                val value = control.read(preset.recipe)
                assertTrue("${control.name} read ${preset.id} as $value", value in -0.0001f..1.0001f)
                val displayed = control.display(preset.recipe)
                assertTrue("${control.name} displays nothing for ${preset.id}", displayed.isNotBlank())
            }

            // …and writing it back does not move the sound.
            val base = VinylPresetId.WARM_VINTAGE.recipe
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { value ->
                val written = control.write(base, value)
                val read = control.read(written)
                assertTrue(
                    "${control.name} lost a value of $value (read back as $read)",
                    kotlin.math.abs(read - value) < 0.02f,
                )
            }
            // Out-of-range input is clamped rather than propagated into the DSP.
            assertEquals(control.read(control.write(base, 4f)), control.read(control.write(base, 1f)), 1e-6f)
            assertEquals(control.read(control.write(base, -4f)), control.read(control.write(base, 0f)), 1e-6f)
        }
    }

    @Test
    fun `moving a knob makes a record customized and resetting a preset clears it`() {
        val preset = VinylPresetId.DUSTY_RECORD
        val stock = VinylControls.of(preset)
        assertFalse(stock.isCustomized)
        assertEquals(preset.recipe, stock.recipe)

        val moved = stock.with(VinylControl.SURFACE_LEVEL, 1f)
        assertTrue(moved.isCustomized)
        assertEquals(setOf(VinylControl.SURFACE_LEVEL), moved.movedControls)
        assertTrue(moved.recipe.surfaceLevelDb > stock.recipe.surfaceLevelDb)
        // Moving a knob also switches the layer on, so a slider at the top is audible rather than a no-op.
        assertTrue(moved.recipe.surfaceEnabled)

        val twice = moved.with(VinylControl.MUSIC_LEVEL, 0.1f)
        assertEquals(setOf(VinylControl.SURFACE_LEVEL, VinylControl.MUSIC_LEVEL), twice.movedControls)
        assertEquals(0.1f * 0.8f, twice.recipe.musicLevel, 1e-6f)

        // Moving the same knob again does not add it twice.
        val again = twice.with(VinylControl.SURFACE_LEVEL, 0.2f)
        assertEquals(2, again.movedControls.size)

        val reset = again.resetTo(preset)
        assertFalse(reset.isCustomized)
        assertEquals(preset.recipe, reset.recipe)
        assertEquals(stock, reset)
    }

    @Test
    fun `the knobs write the units the labels promise`() {
        val base = VinylPresetId.WARM_VINTAGE.recipe
        assertEquals(60f, VinylControl.CRACKLE.write(base, 1f).crackleDensityPerMin, 1e-4f)
        assertEquals(0f, VinylControl.CRACKLE.write(base, 0f).crackleDensityPerMin, 1e-4f)
        assertEquals(6f, VinylControl.POP_DENSITY.write(base, 1f).popsDensityPerMin, 1e-4f)
        assertEquals(-60f, VinylControl.SURFACE_LEVEL.write(base, 0f).surfaceLevelDb, 1e-4f)
        assertEquals(-18f, VinylControl.SURFACE_LEVEL.write(base, 1f).surfaceLevelDb, 1e-4f)
        assertEquals(50f, VinylControl.RUMBLE.write(base, 1f).rumbleHz, 1e-4f)
        assertEquals(140f, VinylControl.RUMBLE.write(base, 0f).rumbleHz, 1e-4f)
        assertEquals(30f, VinylControl.WOW.write(base, 1f).wowDepthCents, 1e-4f)
        assertEquals(6f, VinylControl.FLUTTER.write(base, 1f).flutterDepthCents, 1e-4f)
        assertEquals(1.25f, VinylControl.STEREO_WIDTH.write(base, 1f).stereoWidth, 1e-4f)
        assertEquals(0.5f, VinylControl.STEREO_WIDTH.write(base, 0f).stereoWidth, 1e-4f)
        assertEquals(800, VinylControl.NEEDLE_INTRO.write(base, 1f).needleIntroMs)
        assertEquals(0, VinylControl.NEEDLE_INTRO.write(base, 0f).needleIntroMs)

        // The displays read the way a person would say them.
        assertEquals("-34 dB", VinylControl.SURFACE_LEVEL.display(VinylPresetId.WARM_VINTAGE.recipe))
        assertEquals("22/min", VinylControl.CRACKLE.display(VinylPresetId.WARM_VINTAGE.recipe))
        assertEquals("140 ms", VinylControl.NEEDLE_INTRO.display(VinylPresetId.WARM_VINTAGE.recipe))
        assertEquals("0.95×", VinylControl.STEREO_WIDTH.display(VinylPresetId.WARM_VINTAGE.recipe))
        assertTrue(VinylControl.VOICE_PRESENCE.display(VinylPresetId.WARM_VINTAGE.recipe).endsWith("dB"))
        assertTrue(VinylControl.HISS.display(VinylPresetId.WARM_VINTAGE.recipe).endsWith("dB"))
        assertNotNull(VinylControl.MUSIC_LEVEL.display(VinylPresetId.WARM_VINTAGE.recipe))
    }
}
