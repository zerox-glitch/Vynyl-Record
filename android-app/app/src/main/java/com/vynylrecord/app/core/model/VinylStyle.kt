package com.vynylrecord.app.core.model

import androidx.compose.ui.graphics.Color

/**
 * The appearance of the pressing.
 *
 * Colours are the web application's `VINYL_STYLES` verbatim. They are stored on the record as [id] and
 * re-resolved at render time, so a style can be retuned later without rewriting anyone's library — and
 * so a `.vynyl` bundle stays readable. Every field here reaches the real GL material, the generated
 * label bitmap and the exported artwork: the style is not a name on a card.
 *
 * The `isPremium` flag from the web constants is deliberately dropped. There is no account, no store
 * and no paywall in this app — every style is simply available.
 */
enum class VinylStyleId(
    val id: String,
    val displayName: String,
    val subtitle: String,
    /** The disc itself. */
    val baseColor: Color,
    /** The printed centre label. */
    val labelColor: Color,
    /** The groove tint, used for the procedural groove relief and the vinyl sheen. */
    val grooveColor: Color,
    /** Rings, spindle and the small brass details. */
    val brassAccent: Color,
    /** True for pressings that read as translucent (smoked / deep sapphire). */
    val translucent: Boolean = false,
) {
    CLASSIC_WAX_RUBY(
        id = "classic_red",
        displayName = "Classic Wax Ruby",
        subtitle = "1920s Velvet Red Core",
        baseColor = Color(0xFF121212),
        labelColor = Color(0xFF991B1B),
        grooveColor = Color(0xFF1E1E1E),
        brassAccent = Color(0xFFF59E0B),
    ),
    MIDNIGHT_SAPPHIRE(
        id = "midnight_blue",
        displayName = "Midnight Sapphire",
        subtitle = "1940s Blue Note Jazz",
        baseColor = Color(0xFF0A0E1A),
        labelColor = Color(0xFF1E3A8A),
        grooveColor = Color(0xFF172554),
        brassAccent = Color(0xFF38BDF8),
        translucent = true,
    ),
    IMPERIAL_GOLD_MASTER(
        id = "gold_edition",
        displayName = "Imperial Gold Master",
        subtitle = "Presidential Master Edition",
        baseColor = Color(0xFF1C1917),
        labelColor = Color(0xFFB45309),
        grooveColor = Color(0xFF78350F),
        brassAccent = Color(0xFFFBBF24),
    ),
    VINTAGE_EMERALD(
        id = "vintage_emerald",
        displayName = "Vintage Emerald",
        subtitle = "Art Deco Forest Wax",
        baseColor = Color(0xFF052E16),
        labelColor = Color(0xFF166534),
        grooveColor = Color(0xFF14532D),
        brassAccent = Color(0xFF4ADE80),
    ),
    SMOKED_OBSIDIAN(
        id = "smoked_obsidian",
        displayName = "Smoked Obsidian",
        subtitle = "Modern Audiophile Carbon",
        baseColor = Color(0xFF09090B),
        labelColor = Color(0xFF27272A),
        grooveColor = Color(0xFF18181B),
        brassAccent = Color(0xFFE4E4E7),
        translucent = true,
    ),
    ;

    /** What the exported artwork prints under the title. */
    val artworkSubtitle: String get() = subtitle

    companion object {
        val all: List<VinylStyleId> = entries.toList()

        val DEFAULT: VinylStyleId = CLASSIC_WAX_RUBY

        fun fromId(id: String?): VinylStyleId =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * A style flattened into the linear RGB the renderer and the artwork canvas want.
 *
 * Compose colours are sRGB with an alpha channel; the GL scene works in linear-ish floats and the
 * artwork renderer wants plain ARGB ints. Doing the conversion once here keeps the renderer free of
 * colour-space code, which is where that kind of bug usually hides.
 */
data class StyleMaterial(
    val base: FloatArray,
    val label: FloatArray,
    val groove: FloatArray,
    val brass: FloatArray,
    val translucent: Boolean,
) {
    val labelArgb: Int get() = argb(label, 1f)
    val baseArgb: Int get() = argb(base, 1f)
    val brassArgb: Int get() = argb(brass, 1f)

    private fun argb(rgb: FloatArray, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24
        val r = (rgb[0].coerceIn(0f, 1f) * 255f).toInt() shl 16
        val g = (rgb[1].coerceIn(0f, 1f) * 255f).toInt() shl 8
        val b = (rgb[2].coerceIn(0f, 1f) * 255f).toInt()
        return a or r or g or b
    }

    companion object {
        fun of(style: VinylStyleId): StyleMaterial = StyleMaterial(
            base = style.baseColor.toLinearRgb(),
            label = style.labelColor.toLinearRgb(),
            groove = style.grooveColor.toLinearRgb(),
            brass = style.brassAccent.toLinearRgb(),
            translucent = style.translucent,
        )
    }
}

/** sRGB byte -> 0..1 float. The renderer does its own gamma work in the fragment shader. */
fun Color.toLinearRgb(): FloatArray = floatArrayOf(red, green, blue)

/** Packs to the ARGB int Android's Canvas and Bitmap calls want. */
fun Color.toArgbInt(): Int {
    val a = (alpha * 255f).toInt() shl 24
    val r = (red * 255f).toInt() shl 16
    val g = (green * 255f).toInt() shl 8
    val b = (blue * 255f).toInt()
    return a or r or g or b
}
