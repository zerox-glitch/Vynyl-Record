package com.vynylrecord.turntable.model

/**
 * Colourways for the record itself.
 *
 * A style is not a UI theme: it drives the record's real material (albedo, roughness,
 * metallic, translucency), the paper label palette used by the procedural Canvas label
 * renderer, and the accent colours the studio environment and indicator lamp pick up.
 * [com.vynylrecord.turntable.graphics.material.MaterialLibrary] turns each style into the
 * full set of GPU material uniforms.
 */
enum class VinylStyle(
    val displayName: String,
    val palette: VinylPalette,
    /** Base albedo linear-ish tint of the vinyl compound. */
    val recordAlbedo: Int,
    /** 1.0 = opaque pressing, below 1.0 = translucent (smoked / sapphire) vinyl. */
    val recordOpacity: Float,
    /** Micro-surface roughness of the pressing. Lower = glossier. */
    val recordRoughness: Float,
    /** Groove relief scale, 0..1. */
    val grooveStrength: Float,
    /** Lateral signal wobble baked into the groove profile, 0..1. */
    val grooveModulation: Float,
    /** Strength of the amber marker printed on the label. */
    val accentStrength: Float,
) {

    /** Near-black grooves, ruby label, warm amber accents. */
    CLASSIC_WAX_RUBY(
        displayName = "Classic Wax Ruby",
        palette = VinylPalette(
            labelPrimary = 0xFF4C0519.toInt(),
            labelSecondary = 0xFF991B1B.toInt(),
            labelInk = 0xFFFEF3C7.toInt(),
            labelRing = 0xFFD97706.toInt(),
            accent = 0xFFF59E0B.toInt(),
            backdropTop = 0xFFF6E4D2.toInt(),
            backdropMid = 0xFFCBA391.toInt(),
            backdropBottom = 0xFF3A2A23.toInt(),
        ),
        recordAlbedo = 0xFF0B0A0C.toInt(),
        recordOpacity = 1f,
        recordRoughness = 0.11f,
        grooveStrength = 1f,
        grooveModulation = 0.85f,
        accentStrength = 1f,
    ),

    /** Deep blue translucent tint, sapphire label, cool highlights. */
    MIDNIGHT_SAPPHIRE(
        displayName = "Midnight Sapphire",
        palette = VinylPalette(
            labelPrimary = 0xFF102A63.toInt(),
            labelSecondary = 0xFF1D4ED8.toInt(),
            labelInk = 0xFFE0F2FE.toInt(),
            labelRing = 0xFF60A5FA.toInt(),
            accent = 0xFF38BDF8.toInt(),
            backdropTop = 0xFFDCE6F4.toInt(),
            backdropMid = 0xFF8FA2C4.toInt(),
            backdropBottom = 0xFF1B2436.toInt(),
        ),
        recordAlbedo = 0xFF070B18.toInt(),
        recordOpacity = 0.88f,
        recordRoughness = 0.09f,
        grooveStrength = 0.95f,
        grooveModulation = 0.8f,
        accentStrength = 0.85f,
    ),

    /** Dark warm-gold disc with restrained metallic label detailing. */
    IMPERIAL_GOLD_MASTER(
        displayName = "Imperial Gold Master",
        palette = VinylPalette(
            labelPrimary = 0xFF3A2A05.toInt(),
            labelSecondary = 0xFF78350F.toInt(),
            labelInk = 0xFFFDE68A.toInt(),
            labelRing = 0xFFFBBF24.toInt(),
            accent = 0xFFFBBF24.toInt(),
            backdropTop = 0xFFF7E9CE.toInt(),
            backdropMid = 0xFFC9A46A.toInt(),
            backdropBottom = 0xFF31240F.toInt(),
        ),
        recordAlbedo = 0xFF1A1207.toInt(),
        recordOpacity = 1f,
        recordRoughness = 0.14f,
        grooveStrength = 1f,
        grooveModulation = 0.9f,
        accentStrength = 1f,
    ),

    /** Dark emerald vinyl, forest label, brass accents. */
    VINTAGE_EMERALD(
        displayName = "Vintage Emerald",
        palette = VinylPalette(
            labelPrimary = 0xFF052E23.toInt(),
            labelSecondary = 0xFF047857.toInt(),
            labelInk = 0xFFECFDF5.toInt(),
            labelRing = 0xFFB45309.toInt(),
            accent = 0xFFB45309.toInt(),
            backdropTop = 0xFFEFE6D2.toInt(),
            backdropMid = 0xFFA8B29A.toInt(),
            backdropBottom = 0xFF22301F.toInt(),
        ),
        recordAlbedo = 0xFF04150F.toInt(),
        recordOpacity = 1f,
        recordRoughness = 0.12f,
        grooveStrength = 1f,
        grooveModulation = 0.82f,
        accentStrength = 0.95f,
    ),

    /** Partially transparent smoky black, charcoal label, pale silver highlights. */
    SMOKED_OBSIDIAN(
        displayName = "Smoked Obsidian",
        palette = VinylPalette(
            labelPrimary = 0xFF18181B.toInt(),
            labelSecondary = 0xFF3F3F46.toInt(),
            labelInk = 0xFFE4E4E7.toInt(),
            labelRing = 0xFFA1A1AA.toInt(),
            accent = 0xFFA1A1AA.toInt(),
            backdropTop = 0xFFE8E4E0.toInt(),
            backdropMid = 0xFF9C9490.toInt(),
            backdropBottom = 0xFF1A1918.toInt(),
        ),
        recordAlbedo = 0xFF101012.toInt(),
        recordOpacity = 0.72f,
        recordRoughness = 0.10f,
        grooveStrength = 0.9f,
        grooveModulation = 0.7f,
        accentStrength = 0.6f,
    );

    val isTranslucent: Boolean get() = recordOpacity < 0.995f

    /** Next style in declaration order, wrapping around. */
    fun next(): VinylStyle = entries[(ordinal + 1) % entries.size]

    /** Previous style in declaration order, wrapping around. */
    fun previous(): VinylStyle = entries[(ordinal + entries.size - 1) % entries.size]

    companion object {
        val DEFAULT: VinylStyle = CLASSIC_WAX_RUBY

        /** Safe lookup for persisted / restored state. */
        fun fromNameOrNull(name: String?): VinylStyle? =
            entries.firstOrNull { it.name == name }

        fun fromNameOrDefault(name: String?): VinylStyle = fromNameOrNull(name) ?: DEFAULT
    }
}

/** Palette for the procedurally drawn paper label and the environment accents. */
data class VinylPalette(
    /** Deepest label background. */
    val labelPrimary: Int,
    /** Secondary band / decorative plate colour. */
    val labelSecondary: Int,
    /** Typography colour. */
    val labelInk: Int,
    /** Decorative ring colour. */
    val labelRing: Int,
    /** Scene accent: indicator lamp, groove sweeps, glow tint. */
    val accent: Int,
    /** Studio backdrop gradient, top (warm, bright). */
    val backdropTop: Int,
    /** Studio backdrop gradient, middle (dusty rose). */
    val backdropMid: Int,
    /** Studio backdrop gradient, bottom (deep stone). */
    val backdropBottom: Int,
)

/**
 * Convenience packer so callers can turn a palette entry into normalised RGB without
 * touching android.graphics (keeps this file usable from plain JVM unit tests).
 */
object ColorPacking {

    fun red(argb: Int): Float = ((argb shr 16) and 0xFF) / 255f

    fun green(argb: Int): Float = ((argb shr 8) and 0xFF) / 255f

    fun blue(argb: Int): Float = (argb and 0xFF) / 255f

    fun alpha(argb: Int): Float = (((argb ushr 24) and 0xFF) / 255f)

    fun toRgbArray(argb: Int, out: FloatArray, offset: Int = 0) {
        out[offset] = red(argb)
        out[offset + 1] = green(argb)
        out[offset + 2] = blue(argb)
    }

    /** sRGB-encoded byte channel to linear light. */
    fun channelToLinear(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f else Math.pow(((channel + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    /** Packed colour to linear-light RGB, which is what the shader maths expects. */
    fun toLinearRgb(argb: Int, out: FloatArray, offset: Int = 0, gain: Float = 1f) {
        out[offset] = channelToLinear(red(argb)) * gain
        out[offset + 1] = channelToLinear(green(argb)) * gain
        out[offset + 2] = channelToLinear(blue(argb)) * gain
    }

    fun lerpArgb(from: Int, to: Int, t: Float): Int {
        val clamped = MathUtils.clamp01(t)
        fun mix(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * clamped).toInt().coerceIn(0, 255)
        }
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
