package com.vynylrecord.app.core.data.json

import com.vynylrecord.app.core.model.NoiseFilterShape
import com.vynylrecord.app.core.model.VinylControl
import com.vynylrecord.app.core.model.VinylControls
import com.vynylrecord.app.core.model.VinylPresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a sound recipe survives a file.
 *
 * A record is re-rendered from the settings stored with it, and a `.vynyl` bundle carries those settings
 * to another device. If a value is lost in the round trip, the second press of the same record is a
 * different record — which is exactly the bug this file exists to catch.
 */
class RecipeJsonTest {

    @Test
    fun `every preset round-trips field for field`() {
        VinylPresetId.entries.forEach { preset ->
            val original = VinylControls.of(preset)
            val decoded = RecipeJson.decode(RecipeJson.encode(original), preset)
            assertEquals(preset.id, decoded.recipe, original.recipe)
            assertFalse("${preset.id} came back customized", decoded.isCustomized)
        }
    }

    @Test
    fun `manual moves survive the round trip`() {
        val moved = VinylControls.of(VinylPresetId.DUSTY_RECORD)
            .with(VinylControl.CRACKLE, 0.85f)
            .with(VinylControl.STEREO_WIDTH, 0.3f)
            .with(VinylControl.NEEDLE_INTRO, 0.5f)

        val decoded = RecipeJson.decode(RecipeJson.encode(moved), VinylPresetId.DUSTY_RECORD)
        assertTrue(decoded.isCustomized)
        assertEquals(moved.recipe, decoded.recipe)
        assertEquals(moved.movedControls, decoded.movedControls)
        assertEquals(0.85f, VinylControl.CRACKLE.read(decoded.recipe), 0.01f)
        assertEquals(0.3f, VinylControl.STEREO_WIDTH.read(decoded.recipe), 0.01f)
    }

    @Test
    fun `the document carries its own version`() {
        val document = JsonValue.parse(RecipeJson.encode(VinylControls.of(VinylPresetId.WARM_VINTAGE)))
        assertNotNull(document)
        val object1 = document!!.asObjOrNull()!!
        assertEquals(RecipeJson.VERSION, object1.int("version"))
        assertTrue(object1.int("version") >= 1)
    }

    @Test
    fun `the numeric values are stored as numbers rather than knob positions`() {
        val document = JsonValue.parse(RecipeJson.encode(VinylControls.of(VinylPresetId.RARE_ARCHIVAL)))
        assertNotNull(document)
        val recipe = document!!.asObjOrNull()!!
        // Someone opening a bundle by hand should read Hz and dB, not 0..1 sliders.
        assertEquals(9_800f, recipe.float("lowPassHz"), 1f)
        assertEquals(-24f, recipe.float("surfaceLevelDb"), 0.1f)
        assertEquals(48f, recipe.float("crackleDensityPerMin"), 0.1f)
        assertEquals("flat", recipe.string("surfaceFilter"))
        assertNull(recipe.obj("recipe"))
    }

    @Test
    fun `the motor rates survive the round trip`() {
        // Regression guard: the saved rates are per minute (2 700 for flutter is 45 Hz), so a decode
        // clamp written in seconds would quietly turn a turntable into a wobble.
        VinylPresetId.entries.forEach { preset ->
            val decoded = RecipeJson.decode(RecipeJson.encode(VinylControls.of(preset)), preset)
            assertEquals(preset.recipe.wowRatePerMin, decoded.recipe.wowRatePerMin, 1e-4f)
            assertEquals(preset.recipe.flutterRatePerMin, decoded.recipe.flutterRatePerMin, 1e-3f)
            assertEquals(preset.recipe.wowDepthCents, decoded.recipe.wowDepthCents, 1e-4f)
            assertEquals(preset.recipe.flutterDepthCents, decoded.recipe.flutterDepthCents, 1e-4f)
        }
        assertEquals(
            2_700f,
            RecipeJson.decode(
                RecipeJson.encode(VinylControls.of(VinylPresetId.WARM_VINTAGE)),
                VinylPresetId.WARM_VINTAGE,
            ).recipe.flutterRatePerMin,
            1e-3f,
        )
    }

    @Test
    fun `a hand-edited recipe is clamped into a range the dsp can run`() {
        val wild = """
            {"version":1,"recipe":{},"lowPassHz":40,"highPassHz":90000,"wowRatePerMin":900,
             "flutterRatePerMin":2,"stereoWidth":9,"needleIntroMs":99000,"musicLevel":5}
        """.trimIndent()
        val decoded = RecipeJson.decode(wild, VinylPresetId.CLEAN_VINYL).recipe
        assertTrue(decoded.lowPassHz >= 2_000f)
        assertTrue(decoded.highPassHz <= 1_000f)
        assertTrue(decoded.wowRatePerMin in 3f..18f)
        assertTrue(decoded.flutterRatePerMin in 1_800f..3_600f)
        assertTrue(decoded.stereoWidth <= 1.25f)
        assertTrue(decoded.needleIntroMs <= 2_000)
        assertTrue(decoded.musicLevel <= 0.8f)
    }

    @Test
    fun `garbage falls back to the preset instead of failing`() {
        listOf(null, "", "{}", "not json", "[1,2,3]", "{\"recipe\":null}").forEach { text ->
            val decoded = RecipeJson.decode(text, VinylPresetId.WARM_VINTAGE)
            assertEquals(VinylPresetId.WARM_VINTAGE.recipe, decoded.recipe)
            assertFalse(decoded.isCustomized)
        }
    }

    @Test
    fun `an unknown filter shape falls back to the default one`() {
        val damaged = """
            {"version":1,"recipe":{"surfaceFilter":"mystery-filter","lowPassHz":11000},"moved":[]}
        """.trimIndent()
        val decoded = RecipeJson.decode(damaged, VinylPresetId.CLEAN_VINYL)
        assertEquals(NoiseFilterShape.FLAT, decoded.recipe.surfaceFilter)
        assertEquals(11_000f, decoded.recipe.lowPassHz, 0.5f)
    }

    @Test
    fun `a missing boolean keeps the preset's answer`() {
        val damaged = """{"version":1,"recipe":{"lowPassHz":12000}}"""
        val decoded = RecipeJson.decode(damaged, VinylPresetId.DUSTY_RECORD)
        assertEquals(VinylPresetId.DUSTY_RECORD.recipe.wowEnabled, decoded.recipe.wowEnabled)
        assertEquals(VinylPresetId.DUSTY_RECORD.recipe.wowDepthCents, decoded.recipe.wowDepthCents, 1e-6f)
    }

    @Test
    fun `two records with the same settings encode identically`() {
        val one = VinylControls.of(VinylPresetId.OLD_FAMILY_RECORD).with(VinylControl.HISS, 0.4f)
        val two = VinylControls.of(VinylPresetId.OLD_FAMILY_RECORD).with(VinylControl.HISS, 0.4f)
        assertEquals(RecipeJson.encode(one), RecipeJson.encode(two))
    }

    @Test
    fun `a customized record differs from its preset in the document`() {
        val plain = RecipeJson.encode(VinylControls.of(VinylPresetId.CLEAN_VINYL))
        val moved = RecipeJson.encode(
            VinylControls.of(VinylPresetId.CLEAN_VINYL).with(VinylControl.CRACKLE, 0.9f),
        )
        assertTrue(plain != moved)
    }

    @Test
    fun `the moved list is readable by a person`() {
        val encoded = RecipeJson.encode(
            VinylControls.of(VinylPresetId.WARM_VINTAGE).with(VinylControl.CRACKLE, 0.5f),
        )
        assertTrue(encoded.contains("CRACKLE"))
    }
}
