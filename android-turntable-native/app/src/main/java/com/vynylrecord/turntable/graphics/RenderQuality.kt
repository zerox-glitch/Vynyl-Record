package com.vynylrecord.turntable.graphics

import com.vynylrecord.turntable.graphics.geometry.MeshFactory.GeometryDetail

/**
 * Renderer quality ladder.
 *
 * Each step changes real work, not just a label: geometry budgets, offscreen resolution, MSAA
 * sample count, whether the analytic contact shadows and the procedural groove relief run at all,
 * anti-aliasing strength and film grain.
 */
enum class RenderQuality(
    val displayName: String,
    val geometry: GeometryDetail,
    /** Fraction of the surface size the scene renders at before the composite upscales it. */
    val renderScale: Float,
    /** Hard cap on the longest render-target edge, in pixels. */
    val maxRenderSize: Int,
    /** Requested MSAA samples for the offscreen scene target (0 disables MSAA). */
    val msaaSamples: Int,
    /** Analytic soft contact shadows in the material shader. */
    val contactShadows: Boolean,
    /** Procedural groove relief strength passed to the shader. */
    val grooveDetail: Float,
    /** FXAA-style edge resolve strength in the composite pass. */
    val edgeResolve: Float,
    val grain: Boolean,
    val labelTextureSize: Int,
) {
    LOW(
        displayName = "Low",
        geometry = GeometryDetail.LOW,
        renderScale = 0.50f,
        maxRenderSize = 720,
        msaaSamples = 0,
        contactShadows = false,
        grooveDetail = 0f,
        edgeResolve = 0.75f,
        grain = false,
        labelTextureSize = 512,
    ),
    MEDIUM(
        displayName = "Medium",
        geometry = GeometryDetail.MEDIUM,
        renderScale = 0.72f,
        maxRenderSize = 1080,
        msaaSamples = 2,
        contactShadows = true,
        grooveDetail = 0.75f,
        edgeResolve = 0.5f,
        grain = true,
        labelTextureSize = 768,
    ),
    HIGH(
        displayName = "High",
        geometry = GeometryDetail.HIGH,
        renderScale = 1.0f,
        maxRenderSize = 1440,
        msaaSamples = 4,
        contactShadows = true,
        grooveDetail = 1f,
        edgeResolve = 0.32f,
        grain = true,
        labelTextureSize = 1024,
    );

    fun next(): RenderQuality = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT: RenderQuality = MEDIUM

        fun fromNameOrDefault(name: String?): RenderQuality =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        /** Safe default for the device, chosen from the live GL context where available. */
        fun recommendFor(capabilities: GlCapabilities?): RenderQuality {
            if (capabilities == null) return DEFAULT
            if (capabilities.isLowRamDevice) return LOW
            if (capabilities.maxTextureSize < 2048) return LOW
            if (capabilities.maxSamples < 4) return MEDIUM
            return HIGH
        }

        /** Applies the device's maximum sample count without exceeding the level's request. */
        fun clampSamples(quality: RenderQuality, capabilities: GlCapabilities?): Int {
            val maximum = capabilities?.maxSamples ?: quality.msaaSamples
            return quality.msaaSamples.coerceAtMost(maximum).coerceAtLeast(0)
        }
    }
}
