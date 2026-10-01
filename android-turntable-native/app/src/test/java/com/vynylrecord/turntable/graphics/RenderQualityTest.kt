package com.vynylrecord.turntable.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quality selection and the device-capability default. */
class RenderQualityTest {

    private fun capabilities(
        maxTexture: Int = 8192,
        maxSamples: Int = 4,
        lowRam: Boolean = false,
    ) = GlCapabilities(
        esVersion = 0x30000,
        renderer = "test",
        vendor = "test",
        maxTextureSize = maxTexture,
        maxSamples = maxSamples,
        isLowRamDevice = lowRam,
    )

    @Test
    fun `a low memory device gets the lightest preset`() {
        assertEquals(RenderQuality.LOW, RenderQuality.recommendFor(capabilities(lowRam = true)))
    }

    @Test
    fun `a device without multisampling is capped at medium`() {
        assertEquals(RenderQuality.MEDIUM, RenderQuality.recommendFor(capabilities(maxSamples = 0)))
    }

    @Test
    fun `a small texture limit drops to the lightest preset`() {
        assertEquals(RenderQuality.LOW, RenderQuality.recommendFor(capabilities(maxTexture = 1024)))
    }

    @Test
    fun `a capable device gets the full preset`() {
        assertEquals(RenderQuality.HIGH, RenderQuality.recommendFor(capabilities()))
    }

    @Test
    fun `unknown devices fall back to medium`() {
        assertEquals(RenderQuality.MEDIUM, RenderQuality.recommendFor(null))
    }

    @Test
    fun `requested samples never exceed what the driver reports`() {
        assertEquals(0, RenderQuality.clampSamples(RenderQuality.HIGH, capabilities(maxSamples = 0)))
        assertEquals(2, RenderQuality.clampSamples(RenderQuality.HIGH, capabilities(maxSamples = 2)))
        assertEquals(4, RenderQuality.clampSamples(RenderQuality.HIGH, capabilities(maxSamples = 8)))
    }

    @Test
    fun `the ladder gets more expensive as it goes up`() {
        assertTrue(RenderQuality.LOW.renderScale < RenderQuality.MEDIUM.renderScale)
        assertTrue(RenderQuality.MEDIUM.renderScale < RenderQuality.HIGH.renderScale)
        assertTrue(RenderQuality.LOW.maxRenderSize < RenderQuality.HIGH.maxRenderSize)
        assertTrue(RenderQuality.LOW.labelTextureSize < RenderQuality.HIGH.labelTextureSize)
    }

    @Test
    fun `quality names round trip`() {
        for (quality in RenderQuality.entries) {
            assertEquals(quality, RenderQuality.fromNameOrDefault(quality.name))
        }
        assertEquals(RenderQuality.DEFAULT, RenderQuality.fromNameOrDefault("nonsense"))
        assertEquals(RenderQuality.DEFAULT, RenderQuality.fromNameOrDefault(null))
    }
}
