package com.vynylrecord.app.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design system's contract.
 *
 * The palette and the type scale are not decoration: they are the thing that makes this app feel like one
 * product rather than a stack of screens, and they are specified exactly. A colour that drifts by one hex
 * digit is invisible in review and visible in a screenshot, so the values are pinned here and any change to
 * them has to be deliberate.
 */
class VynylThemeTest {

    /** Compose stores a colour's channels as floats; the palette is specified as 32-bit ARGB. */
    private fun hex(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    private fun assertHex(expected: Long, color: Color, name: String) {
        assertEquals("$name is #${hex(color).toString(16)}", expected, hex(color))
    }

    @Test
    fun `the palette is exactly the specified one`() {
        assertHex(0xFF0C0A09, VynylColors.Obsidian, "Obsidian")
        assertHex(0xFF1C1917, VynylColors.DeepStone, "Deep stone")
        assertHex(0xFF211E1A, VynylColors.Panel, "Panel")
        assertHex(0xFFD97706, VynylColors.Amber, "Amber")
        assertHex(0xFFF59E0B, VynylColors.AmberBright, "Bright amber")
        assertHex(0xFFB45309, VynylColors.Brass, "Brass")
        assertHex(0xFFFEF3C7, VynylColors.Cream, "Cream")
        assertHex(0xFFFFF7E6, VynylColors.WarmPaper, "Warm paper")
        assertHex(0xFF991B1B, VynylColors.Ruby, "Ruby")
        assertHex(0xFFA8A29E, VynylColors.Muted, "Muted")
        assertHex(0xFFF87171, VynylColors.Error, "Error")
        assertHex(0xFF34D399, VynylColors.Success, "Success")
    }

    @Test
    fun `the palette stays in the warm family it promises`() {
        val palette = listOf(
            VynylColors.Obsidian, VynylColors.DeepStone, VynylColors.Panel, VynylColors.Amber,
            VynylColors.AmberBright, VynylColors.Brass, VynylColors.Cream, VynylColors.WarmPaper,
            VynylColors.Ruby, VynylColors.Muted, VynylColors.Error, VynylColors.Success,
        )
        val opaque = palette.filter { it.alpha > 0.99f }
        assertEquals(12, opaque.size)
        // No purple Material, no neon, no glassmorphism: every colour is either neutral, amber/brass,
        // cream, or a status colour.
        opaque.forEach { color ->
            assertTrue("a palette colour is invisible", color.alpha > 0.9f)
        }
        // Red and green are for state, not for surfaces: the ground is near-black.
        assertTrue(VynylColors.Obsidian.red < 0.1f)
        assertTrue(VynylColors.Obsidian.green < 0.1f)
        assertTrue(VynylColors.Obsidian.blue < 0.1f)

        // The three accents are ordered from deep brass to bright amber, which is how they are used.
        assertTrue(VynylColors.Brass.red < VynylColors.Amber.red)
        assertTrue(VynylColors.Amber.red < VynylColors.AmberBright.red)
        assertNotEquals(VynylColors.Amber, VynylColors.AmberBright)
        assertNotEquals(VynylColors.Amber, VynylColors.Brass)
    }

    @Test
    fun `cream text passes on obsidian and muted stays legible`() {
        // WCAG contrast, computed the way the guidelines define it: relative luminance then a ratio.
        fun luminance(color: Color): Double {
            fun channel(value: Float): Double {
                val v = value.toDouble()
                return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }

        fun ratio(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            val lighter = maxOf(la, lb)
            val darker = minOf(la, lb)
            return (lighter + 0.05) / (darker + 0.05)
        }

        val onObsidian = ratio(VynylColors.Cream, VynylColors.Obsidian)
        assertTrue("cream on obsidian is only ${"%.1f".format(onObsidian)}:1", onObsidian > 12.0)
        val mutedOnObsidian = ratio(VynylColors.Muted, VynylColors.Obsidian)
        assertTrue("muted on obsidian is only ${"%.1f".format(mutedOnObsidian)}:1", mutedOnObsidian >= 4.5)
        val amberOnObsidian = ratio(VynylColors.AmberBright, VynylColors.Obsidian)
        assertTrue("bright amber on obsidian is only ${"%.1f".format(amberOnObsidian)}:1", amberOnObsidian >= 4.5)

        // Cream text on the amber accent would fail, which is why the primary button uses obsidian text.
        val amberButtonText = ratio(VynylColors.Obsidian, VynylColors.Amber)
        assertTrue("dark text on amber is only ${"%.1f".format(amberButtonText)}:1", amberButtonText >= 4.5)

        val errorOnObsidian = ratio(VynylColors.Error, VynylColors.Obsidian)
        assertTrue("the error colour is hard to read: ${"%.1f".format(errorOnObsidian)}:1", errorOnObsidian >= 4.5)
        val successOnObsidian = ratio(VynylColors.Success, VynylColors.Obsidian)
        assertTrue("the success colour is hard to read: ${"%.1f".format(successOnObsidian)}:1", successOnObsidian >= 4.5)
    }

    @Test
    fun `headings are serif and durations are mono`() {
        val serif = androidx.compose.ui.text.font.FontFamily.Serif
        val mono = androidx.compose.ui.text.font.FontFamily.Monospace
        val sans = androidx.compose.ui.text.font.FontFamily.SansSerif

        assertEquals(serif, VynylType.display.fontFamily)
        assertEquals(serif, VynylType.title.fontFamily)
        assertEquals(serif, VynylType.headline.fontFamily)
        assertEquals(mono, VynylType.mono.fontFamily)
        assertEquals(mono, VynylType.monoLarge.fontFamily)
        assertEquals(mono, VynylType.monoSmall.fontFamily)
        assertEquals(sans, VynylType.body.fontFamily)
        assertEquals(sans, VynylType.bodyLarge.fontFamily)

        // A reading size the user can scale, and a hierarchy that is strictly decreasing.
        assertTrue(VynylType.display.fontSize.value > VynylType.title.fontSize.value)
        assertTrue(VynylType.title.fontSize.value > VynylType.headline.fontSize.value)
        assertTrue(VynylType.headline.fontSize.value > VynylType.bodyLarge.fontSize.value)
        assertTrue(VynylType.bodyLarge.fontSize.value > VynylType.body.fontSize.value)
        assertTrue(VynylType.body.fontSize.value > VynylType.caption.fontSize.value)
        assertTrue("body text is too small to read comfortably", VynylType.body.fontSize.value >= 15f)
        assertTrue(VynylType.monoLarge.fontSize.value > VynylType.mono.fontSize.value)
    }

    @Test
    fun `borders, scrims and overlays are translucent brass over obsidian`() {
        // A brass seam, not a line: it has to let the surface through.
        assertTrue(VynylColors.Border.alpha in 0.05f..0.4f)
        assertTrue(VynylColors.BorderStrong.alpha > VynylColors.Border.alpha)
        assertTrue(VynylColors.BorderStrong.alpha < 1f)
        assertEquals("the border is brass at low alpha", VynylColors.Brass.copy(alpha = VynylColors.Border.alpha), VynylColors.Border)

        assertTrue("a scrim that is not mostly opaque hides nothing", VynylColors.Scrim.alpha > 0.6f)
        assertTrue(VynylColors.PanelOverlay.alpha > 0.6f)
        assertEquals(VynylColors.Scrim.red, VynylColors.Obsidian.red, 0.001f)
        assertEquals(VynylColors.PanelOverlay.red, VynylColors.Panel.red, 0.001f)
    }
}
