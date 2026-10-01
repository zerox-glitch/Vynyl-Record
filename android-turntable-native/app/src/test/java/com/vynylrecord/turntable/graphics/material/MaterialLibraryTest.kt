package com.vynylrecord.turntable.graphics.material

import com.vynylrecord.turntable.graphics.geometry.MeshFactory.GeometryDetail
import com.vynylrecord.turntable.graphics.geometry.TurntableBuilder
import com.vynylrecord.turntable.model.TurntableSpec
import com.vynylrecord.turntable.model.VinylStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every style has to be a complete material set: a style that forgets to configure the record would
 * show up as a black disc, and a missing kind would crash the renderer on the first frame.
 */
class MaterialLibraryTest {

    @Test
    fun `every style builds a full material set`() {
        for (style in VinylStyle.entries) {
            val materials = MaterialLibrary.buildSet(style)
            assertEquals(
                "style ${style.name} is missing materials",
                MaterialLibrary.Kind.entries.size,
                materials.size,
            )
            for (kind in MaterialLibrary.Kind.entries) {
                assertTrue("style ${style.name} is missing $kind", materials.containsKey(kind))
            }
        }
    }

    @Test
    fun `the record material follows the style`() {
        val ruby = MaterialLibrary.buildSet(VinylStyle.CLASSIC_WAX_RUBY)
        val obsidian = MaterialLibrary.buildSet(VinylStyle.SMOKED_OBSIDIAN)

        val rubyRecord = ruby.getValue(MaterialLibrary.Kind.VINYL_RECORD)
        val obsidianRecord = obsidian.getValue(MaterialLibrary.Kind.VINYL_RECORD)

        assertNotEquals(
            "two styles must not look identical",
            rubyRecord.albedo.joinToString(),
            obsidianRecord.albedo.joinToString(),
        )
        assertEquals(1f, rubyRecord.opacity.value, 1e-4f)
        assertTrue("smoked vinyl is translucent", obsidianRecord.opacity.value < 1f)
        assertTrue(obsidianRecord.isTransparent)
        assertEquals(VinylStyle.SMOKED_OBSIDIAN.recordRoughness, obsidianRecord.roughness.value, 1e-5f)
    }

    @Test
    fun `only the record uses the groove shader`() {
        val materials = MaterialLibrary.buildSet(VinylStyle.DEFAULT)
        assertEquals(true, materials.getValue(MaterialLibrary.Kind.VINYL_RECORD).grooveMode.value)
        assertEquals(true, materials.getValue(MaterialLibrary.Kind.PAPER_LABEL).useLabelTexture.value)
        for (kind in MaterialLibrary.Kind.entries) {
            if (kind == MaterialLibrary.Kind.VINYL_RECORD) continue
            assertEquals("$kind must not synthesise grooves", false, materials.getValue(kind).grooveMode.value)
        }
    }

    @Test
    fun `applying a style mutates the material in place`() {
        val record = MaterialLibrary.buildSet(VinylStyle.CLASSIC_WAX_RUBY).getValue(MaterialLibrary.Kind.VINYL_RECORD)
        val before = record.albedo.copyOf()

        record.applyVinylStyle(VinylStyle.IMPERIAL_GOLD_MASTER)
        assertTrue(
            "the albedo must change with the style",
            kotlin.math.abs(before[0] - record.albedo[0]) > 1e-6f,
        )
        assertEquals(VinylStyle.IMPERIAL_GOLD_MASTER.recordRoughness, record.roughness.value, 1e-5f)
        assertEquals(true, record.grooveMode.value)
        assertTrue("the groove depth stays metric", record.grooveDepth.value > 0f)
        assertTrue("the groove pitch stays metric", record.groovePitch.value > 0f)
    }

    @Test
    fun `the disc radial range matches the record surface mesh`() {
        val record = MaterialLibrary.buildSet(VinylStyle.DEFAULT).getValue(MaterialLibrary.Kind.VINYL_RECORD)

        // The shader turns the record top's normalised V coordinate back into a physical radius, so
        // this range has to describe the annulus that surface was actually built from: the spindle
        // hole out to the start of the edge bevel, not out to the full disc radius. Being 2.5 mm
        // out here stretches and offsets every groove on the pressing.
        val innerRadius = TurntableSpec.SPINDLE_RADIUS + 0.0002f
        val outerRadius = TurntableSpec.RECORD_RADIUS - TurntableSpec.RECORD_EDGE_BEVEL_WIDTH
        assertEquals("the shader recovers metric radii from this range", innerRadius, record.discRadialRange[0], 1e-5f)
        assertEquals("the shader recovers metric radii from this range", outerRadius, record.discRadialRange[1], 1e-5f)

        val surface = TurntableBuilder.build(GeometryDetail.LOW).recordTop
        assertEquals(
            "the range must match the mesh the shader samples it against",
            outerRadius,
            surface.bounds.maxX - TurntableSpec.PLATTER_CENTER_X,
            1e-4f,
        )
    }

    @Test
    fun `translucent styles are flagged so the renderer can sort them`() {
        val translucent = VinylStyle.entries.filter { it.isTranslucent }
        assertTrue("the catalogue must offer translucent pressings", translucent.isNotEmpty())
        assertTrue(translucent.contains(VinylStyle.SMOKED_OBSIDIAN))
        assertTrue(translucent.contains(VinylStyle.MIDNIGHT_SAPPHIRE))
    }
}
