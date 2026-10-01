package com.vynylrecord.turntable.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalogue: five styles, each a complete and distinct colourway. */
class VinylStyleTest {

    @Test
    fun `the catalogue has five distinct styles`() {
        val styles = VinylStyle.entries
        assertEquals(5, styles.size)
        assertEquals(styles.size, styles.map { it.displayName }.toSet().size)
        assertEquals(styles.size, styles.map { it.palette.labelPrimary }.toSet().size)
    }

    @Test
    fun `cycling wraps around in both directions`() {
        val first = VinylStyle.entries.first()
        val last = VinylStyle.entries.last()
        assertEquals(last, first.previous())
        assertEquals(first, last.next())
        for (style in VinylStyle.entries) {
            assertEquals(style, style.next().previous())
        }
    }

    @Test
    fun `lookup by name is safe`() {
        assertEquals(VinylStyle.MIDNIGHT_SAPPHIRE, VinylStyle.fromNameOrDefault("MIDNIGHT_SAPPHIRE"))
        assertEquals(VinylStyle.DEFAULT, VinylStyle.fromNameOrDefault("not-a-style"))
        assertEquals(VinylStyle.DEFAULT, VinylStyle.fromNameOrDefault(null))
        assertEquals(null, VinylStyle.fromNameOrNull("not-a-style"))
    }

    @Test
    fun `palettes are fully specified`() {
        for (style in VinylStyle.entries) {
            val palette = style.palette
            val colours = listOf(
                palette.labelPrimary, palette.labelSecondary, palette.labelInk,
                palette.labelRing, palette.accent, palette.backdropTop,
                palette.backdropMid, palette.backdropBottom,
            )
            for (colour in colours) {
                assertEquals("alpha must be opaque in ${style.name}", 0xFF, (colour ushr 24) and 0xFF)
            }
            assertNotEquals(palette.labelPrimary, palette.labelInk)
            assertNotEquals(palette.backdropTop, palette.backdropBottom)
        }
    }

    @Test
    fun `style parameters stay in a physical range`() {
        for (style in VinylStyle.entries) {
            assertTrue("${style.name} opacity", style.recordOpacity in 0.4f..1f)
            assertTrue("${style.name} roughness", style.recordRoughness in 0.02f..0.5f)
            assertTrue("${style.name} groove strength", style.grooveStrength in 0f..1f)
            assertTrue("${style.name} groove modulation", style.grooveModulation in 0f..1f)
            assertTrue("${style.name} accent strength", style.accentStrength in 0f..1f)
        }
    }

    @Test
    fun `colour packing converts to linear light`() {
        val out = FloatArray(3)
        ColorPacking.toLinearRgb(0xFFFFFFFF.toInt(), out)
        assertEquals(1f, out[0], 1e-4f)
        assertEquals(1f, out[1], 1e-4f)
        assertEquals(1f, out[2], 1e-4f)

        ColorPacking.toLinearRgb(0xFF000000.toInt(), out)
        assertEquals(0f, out[0], 1e-6f)

        // Mid grey is darker in linear light than in sRGB, which is the whole point of converting.
        ColorPacking.toLinearRgb(0xFF808080.toInt(), out)
        assertTrue(out[0] in 0.20f..0.30f)
    }

    @Test
    fun `colour interpolation is monotonic`() {
        val from = 0xFF000000.toInt()
        val to = 0xFFFFFFFF.toInt()
        var previous = -1
        var t = 0f
        while (t <= 1f) {
            val value = ColorPacking.lerpArgb(from, to, t)
            val red = (value shr 16) and 0xFF
            assertTrue("interpolation must not go backwards", red >= previous)
            previous = red
            t += 0.1f
        }
        assertEquals(0xFF, (ColorPacking.lerpArgb(from, to, 1f) shr 16) and 0xFF)
    }
}
