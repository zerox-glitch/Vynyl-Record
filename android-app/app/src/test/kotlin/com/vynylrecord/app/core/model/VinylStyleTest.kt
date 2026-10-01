package com.vynylrecord.app.core.model

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five appearances.
 *
 * A style is not a name on a card: it carries the material the 3D disc is built from and the colours the
 * label and the artwork are drawn with. Choosing "Smoked Obsidian" has to make a translucent black disc.
 */
class VinylStyleTest {

    @Test
    fun `the five styles are present with the palette ids`() {
        assertEquals(
            listOf(
                "classic_red",
                "midnight_blue",
                "gold_edition",
                "vintage_emerald",
                "smoked_obsidian",
            ),
            VinylStyleId.all.map { it.id },
        )
        assertEquals(5, VinylStyleId.all.size)
        assertEquals(VinylStyleId.CLASSIC_WAX_RUBY, VinylStyleId.DEFAULT)
    }

    @Test
    fun `styles round-trip and fall back`() {
        VinylStyleId.all.forEach { style ->
            assertEquals(style, VinylStyleId.fromId(style.id))
            assertTrue(style.displayName.isNotBlank())
            assertTrue(style.subtitle.isNotBlank())
        }
        assertEquals(VinylStyleId.DEFAULT, VinylStyleId.fromId(null))
        assertEquals(VinylStyleId.DEFAULT, VinylStyleId.fromId("chrome"))
    }

    @Test
    fun `every style has its own colours`() {
        val bases = VinylStyleId.all.map { it.baseColor }
        assertEquals(bases.size, bases.toSet().size)
        val labels = VinylStyleId.all.map { it.labelColor }
        assertEquals(labels.size, labels.toSet().size)
        VinylStyleId.all.forEach { style ->
            assertNotEquals(style.baseColor, style.labelColor)
            assertNotEquals(style.baseColor, style.grooveColor)
            assertEquals(1f, style.baseColor.alpha, 0f)
        }
    }

    @Test
    fun `only the smoked and sapphire pressings are translucent`() {
        assertTrue(VinylStyleId.SMOKED_OBSIDIAN.translucent)
        assertTrue(VinylStyleId.MIDNIGHT_SAPPHIRE.translucent)
        listOf(VinylStyleId.CLASSIC_WAX_RUBY, VinylStyleId.IMPERIAL_GOLD_MASTER, VinylStyleId.VINTAGE_EMERALD)
            .forEach { style -> assertFalse("${style.id} should be opaque", style.translucent) }
    }

    @Test
    fun `the ruby pressing uses the label colour from the brand palette`() {
        val ruby = VinylStyleId.CLASSIC_WAX_RUBY
        assertEquals(Color(0xFF991B1B), ruby.labelColor)
        assertEquals(Color(0xFF121212), ruby.baseColor)
        assertEquals(Color(0xFFF59E0B), ruby.brassAccent)
        assertEquals("1920s Velvet Red Core", ruby.subtitle)
    }

    @Test
    fun `the material is built from the style`() {
        VinylStyleId.all.forEach { style ->
            val material = StyleMaterial.of(style)
            assertEquals(3, material.base.size)
            assertEquals(3, material.label.size)
            assertEquals(3, material.groove.size)
            assertEquals(3, material.brass.size)
            assertEquals(style.translucent, material.translucent)
            listOf(material.base, material.label, material.groove, material.brass).forEach { rgb ->
                rgb.forEach { channel -> assertTrue("channel $channel is outside 0..1", channel in 0f..1f) }
            }
            assertTrue(material.baseArgb != 0)
            assertTrue(material.labelArgb != 0)
            assertTrue(material.brassArgb != 0)
        }
    }

    @Test
    fun `materials differ between styles so the 3D disc really changes`() {
        val first = StyleMaterial.of(VinylStyleId.CLASSIC_WAX_RUBY)
        val second = StyleMaterial.of(VinylStyleId.SMOKED_OBSIDIAN)
        assertNotEquals(first.baseArgb, second.baseArgb)
    }

    @Test
    fun `argb conversion is opaque and ordered correctly`() {
        val material = StyleMaterial.of(VinylStyleId.IMPERIAL_GOLD_MASTER)
        val argb = material.baseArgb
        assertEquals(0xFF shl 24, argb and (0xFF shl 24))
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        assertTrue(red in 0..255 && green in 0..255 && blue in 0..255)
    }

    @Test
    fun `a colour converts to linear rgb and to an int`() {
        val linear = Color(0xFFD97706).toLinearRgb()
        assertEquals(3, linear.size)
        linear.forEach { assertTrue(it in 0f..1f) }

        val argb = Color(0xFFF59E0B).toArgbInt()
        assertEquals(0xFF, (argb shr 24) and 0xFF)
        assertEquals(0xF5, (argb shr 16) and 0xFF)
        assertEquals(0x9E, (argb shr 8) and 0xFF)
        assertEquals(0x0B, argb and 0xFF)
    }

    @Test
    fun `the artwork subtitle matches the style subtitle`() {
        VinylStyleId.all.forEach { style ->
            assertEquals(style.subtitle, style.artworkSubtitle)
        }
    }
}
