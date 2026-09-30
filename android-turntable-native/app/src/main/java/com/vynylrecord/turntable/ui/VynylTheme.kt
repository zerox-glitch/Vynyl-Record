package com.vynylrecord.turntable.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The visual identity, as one place to change it.
 *
 * Dark analog: near-black background, warm stone panel, amber controls, brass borders, ruby accents.
 * Headings are serif, controls are sans - the label on the record and the on-screen UI share the
 * same pairing so the 3D scene and the panel look like one object.
 */
object VynylColors {
    val Background = Color(0xFF0C0A09)
    val Panel = Color(0xFF1C1917)
    val PanelRaised = Color(0xFF292524)
    val Amber = Color(0xFFD97706)
    val AmberBright = Color(0xFFF59E0B)
    val Cream = Color(0xFFFEF3C7)
    val Brass = Color(0xFFB45309)
    val Ruby = Color(0xFF991B1B)
    val Outline = Color(0xFF44403C)
    val Muted = Color(0xFFA8A29E)
}

/** Extra colours that Material3's scheme has no slot for. */
data class VynylExtraColors(
    val brass: Color = VynylColors.Brass,
    val amberBright: Color = VynylColors.AmberBright,
    val ruby: Color = VynylColors.Ruby,
    val panelRaised: Color = VynylColors.PanelRaised,
    val muted: Color = VynylColors.Muted,
)

private val scheme = darkColorScheme(
    primary = VynylColors.Amber,
    onPrimary = VynylColors.Background,
    primaryContainer = VynylColors.Brass,
    onPrimaryContainer = VynylColors.Cream,
    secondary = VynylColors.Brass,
    onSecondary = VynylColors.Cream,
    tertiary = VynylColors.AmberBright,
    onTertiary = VynylColors.Background,
    background = VynylColors.Background,
    onBackground = VynylColors.Cream,
    surface = VynylColors.Panel,
    onSurface = VynylColors.Cream,
    surfaceVariant = VynylColors.PanelRaised,
    onSurfaceVariant = VynylColors.Muted,
    outline = VynylColors.Outline,
    outlineVariant = VynylColors.Outline,
    error = VynylColors.Ruby,
    onError = VynylColors.Cream,
    scrim = Color(0xCC0C0A09),
)

/** Serif for headings, sans for controls, both slightly tightened for a printed feel. */
private val typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        titleMedium = titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = labelLarge.copy(fontFamily = FontFamily.SansSerif, letterSpacing = 0.4.sp),
        labelMedium = labelMedium.copy(fontFamily = FontFamily.SansSerif, letterSpacing = 0.6.sp),
        labelSmall = labelSmall.copy(fontFamily = FontFamily.SansSerif, letterSpacing = 0.8.sp),
    )
}

/** Tabular-ish style for the timecode readout. */
val TimecodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.5.sp,
)

@Composable
fun VynylTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") VynylColors.Background
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content,
    )
}

/** Brass border width used by every framed panel so the metal line stays consistent. */
val BrassBorderWidth = 1.dp
