package com.vynylrecord.turntable.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recipe is the vocabulary of the Sound Lab, so its own rules have to hold: every preset is a
 * legal recipe, every recipe can describe itself in words, and the preset a hand-tuned side matches is
 * the one the Studio highlights.
 */
class PressRecipeTest {

    @Test
    fun `every preset is a legal recipe`() {
        for (preset in PressRecipe.Preset.entries) {
            val recipe = preset.recipe
            assertTrue("${preset.name} crackle", recipe.crackle in 0f..1f)
            assertTrue("${preset.name} noise", recipe.surfaceNoise in 0f..1f)
            assertTrue("${preset.name} wow", recipe.wowFlutter in 0f..1f)
            assertTrue("${preset.name} warmth", recipe.warmth in 0f..1f)
            assertTrue("${preset.name} room", recipe.roomTone in 0f..1f)
            assertTrue("${preset.name} hiss", recipe.hiss in 0f..1f)
            assertTrue("${preset.name} drive", recipe.saturation in 0f..1f)
            assertTrue("${preset.name} needs a name", preset.displayName.isNotBlank())
            assertTrue("${preset.name} needs a description", preset.blurb.isNotBlank())
        }
    }

    @Test
    fun `out of range values are refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) { PressRecipe(crackle = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { PressRecipe(wowFlutter = -0.01f) }
        assertThrows(IllegalArgumentException::class.java) { PressRecipe(warmth = 4f) }
    }

    @Test
    fun `a preset is recognised as itself and a master is recognised as a master`() {
        for (preset in PressRecipe.Preset.entries) {
            assertEquals(
                "${preset.name} should map back to itself",
                preset,
                PressRecipe.closestPreset(preset.recipe),
            )
        }
        assertTrue("the clean preset must be the flat one", PressRecipe.Preset.CLEAN_MASTER.recipe.isMaster)
        assertFalse("the house cut has a character", PressRecipe.Preset.HOUSE_CUT.recipe.isMaster)
        assertEquals(PressRecipe.STUDIO, PressRecipe.Preset.HOUSE_CUT.recipe)
    }

    @Test
    fun `a hand-tuned recipe maps to the nearest preset`() {
        val nearlyHouse = PressRecipe.STUDIO.copy(crackle = PressRecipe.STUDIO.crackle + 0.03f)
        assertEquals(PressRecipe.Preset.HOUSE_CUT, PressRecipe.closestPreset(nearlyHouse))

        val nearlyAttic = PressRecipe.Preset.ATTIC_FIND.recipe.copy(wowFlutter = 0.5f)
        assertEquals(PressRecipe.Preset.ATTIC_FIND, PressRecipe.closestPreset(nearlyAttic))
    }

    @Test
    fun `character rises with the recipe and describe always says something`() {
        assertTrue("a master has no character", PressRecipe.MASTER.character < 0.1f)
        assertTrue("an attic find has plenty", PressRecipe.Preset.ATTIC_FIND.recipe.character > 0.4f)
        assertTrue(
            "character must be a fraction",
            PressRecipe.Preset.ATTIC_FIND.recipe.character in 0f..1f,
        )

        assertTrue(PressRecipe.MASTER.describe().contains("clean"))
        assertTrue(PressRecipe.Preset.ATTIC_FIND.recipe.describe().contains("crackled"))
        assertTrue(PressRecipe.Preset.HOUSE_CUT.recipe.describe().contains("warm"))
        for (preset in PressRecipe.Preset.entries) {
            val words = preset.recipe.describe().split(", ")
            assertTrue("${preset.name} describes itself with too many words", words.size <= 6)
            assertTrue("${preset.name} has an empty word", words.all { it.isNotBlank() })
        }
    }

    @Test
    fun `the catalogue covers clean, warm and worn records`() {
        val names = PressRecipe.Preset.entries.map { it.displayName }
        assertEquals("preset names must be unique", names.size, names.toSet().size)
        assertTrue("there must be a flat option", PressRecipe.all.any { it.recipe.isMaster })
        assertTrue(
            "there must be a heavily worn option",
            PressRecipe.all.any { it.recipe.crackle > 0.6f && it.recipe.surfaceNoise > 0.5f },
        )
    }
}
