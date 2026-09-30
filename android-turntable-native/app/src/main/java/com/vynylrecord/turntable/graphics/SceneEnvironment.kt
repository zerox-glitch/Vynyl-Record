package com.vynylrecord.turntable.graphics

import com.vynylrecord.turntable.model.ColorPacking
import com.vynylrecord.turntable.model.TurntableSpec
import com.vynylrecord.turntable.model.VinylStyle

/**
 * Studio lighting rig and procedural backdrop, in one mutable holder.
 *
 * Deliberately uncompressed: the studio is *generated*, never loaded. The key light is a warm
 * directional source, the fill is an amber inverse-square point light, the rim is a cool
 * directional source weighted by grazing angle, and the ambient is a hemispheric term whose
 * two stops come from the same three-colour backdrop gradient the composite pass draws. Changing
 * vinyl style re-tints the backdrop, the bloom and the shadow tint without touching geometry.
 *
 * Backdrop colours are stored in **display space** (they are painted directly by the composite),
 * while the light colours are **linear light** because they multiply linear albedo.
 */
class SceneEnvironment {

    // ------------------------------------------------------------------ backdrop (display space)

    val backdropTop = floatArrayOf(0.96f, 0.89f, 0.82f)
    val backdropMid = floatArrayOf(0.80f, 0.64f, 0.57f)
    val backdropBottom = floatArrayOf(0.23f, 0.17f, 0.14f)
    val glowColor = floatArrayOf(1f, 0.66f, 0.24f)

    @JvmField var glowPositionX = -0.16f
    @JvmField var glowPositionY = 0.34f
    @JvmField var glowStrength = 0.42f
    @JvmField var grainStrength = 0.030f
    @JvmField var vignetteStrength = 0.42f
    @JvmField var horizon = 0.30f
    @JvmField var floorFade = 0.85f
    @JvmField var grainEnabled = 1
    @JvmField var animateBackdrop = 0

    // ------------------------------------------------------------------ lights (linear)

    val keyDirection = floatArrayOf(-0.512f, 0.782f, 0.356f)
    val keyColor = floatArrayOf(1.42f, 1.30f, 1.12f)
    val fillPosition = floatArrayOf(0.30f, 0.42f, 0.26f)
    val fillColor = floatArrayOf(1.05f, 0.62f, 0.26f)
    val rimDirection = floatArrayOf(0.40f, 0.34f, -0.85f)
    val rimColor = floatArrayOf(0.52f, 0.66f, 0.95f)
    val ambientFloor = floatArrayOf(0.030f, 0.020f, 0.016f)
    val ambientSky = floatArrayOf(0.085f, 0.076f, 0.078f)

    // ------------------------------------------------------------------ contact shadows

    /** `xy` = disc centre, `z` = radius, `w` = plane height. */
    val discShadow0 = floatArrayOf(TurntableSpec.PLATTER_CENTER_X, TurntableSpec.PLATTER_CENTER_Z, TurntableSpec.PLATTER_RADIUS + 0.005f, TurntableSpec.PLINTH_TOP)

    /** Second soft blob under the tonearm's pivot housing. */
    val discShadow1 = floatArrayOf(TurntableSpec.TONEARM_PIVOT_X, TurntableSpec.TONEARM_PIVOT_Z, 0.045f, TurntableSpec.PLINTH_TOP)

    /** `xy` = centre, `zw` = half extents of the plinth, casting onto the floor at y = 0. */
    val rectShadow = floatArrayOf(0f, 0f, TurntableSpec.PLINTH_WIDTH * 0.5f, TurntableSpec.PLINTH_DEPTH * 0.5f)

    @JvmField var shadowCeiling = TurntableSpec.PLINTH_TOP + 0.055f
    @JvmField var shadowStrength = 0.85f
    @JvmField var shadowBias = 0.0004f

    // ------------------------------------------------------------------ exposure

    @JvmField var exposure = 1.04f

    /** Re-tints the environment for a vinyl style. Called from the GL thread on demand. */
    fun applyVinylStyle(style: VinylStyle) {
        val palette = style.palette
        setDisplaySpace(backdropTop, palette.backdropTop)
        setDisplaySpace(backdropMid, palette.backdropMid)
        setDisplaySpace(backdropBottom, palette.backdropBottom)

        // Accent bloom picks up the style, desaturated a little so it stays tasteful.
        ColorPacking.toLinearRgb(palette.accent, glowColor)
        glowColor[0] = glowColor[0] * 0.72f + 0.28f
        glowColor[1] = glowColor[1] * 0.72f + 0.22f
        glowColor[2] = glowColor[2] * 0.72f + 0.18f
        // Our accent lookup returns linear light; the backdrop is composited in display space,
        // so re-encode the bloom the same way the scene pass does.
        for (i in 0..2) {
            glowColor[i] = Math.pow(glowColor[i].toDouble(), 1.0 / 2.2).toFloat()
        }

        // Fill light inherits a little of the accent so controls pick up the style's warmth.
        ColorPacking.toLinearRgb(palette.accent, fillColor)
        fillColor[0] = fillColor[0] * 0.55f + 0.45f
        fillColor[1] = fillColor[1] * 0.55f + 0.28f
        fillColor[2] = fillColor[2] * 0.55f + 0.12f
    }

    private fun setDisplaySpace(target: FloatArray, argb: Int) {
        target[0] = ColorPacking.red(argb)
        target[1] = ColorPacking.green(argb)
        target[2] = ColorPacking.blue(argb)
    }

    fun copyFrom(other: SceneEnvironment) {
        System.arraycopy(other.backdropTop, 0, backdropTop, 0, 3)
        System.arraycopy(other.backdropMid, 0, backdropMid, 0, 3)
        System.arraycopy(other.backdropBottom, 0, backdropBottom, 0, 3)
        System.arraycopy(other.glowColor, 0, glowColor, 0, 3)
    }
}
