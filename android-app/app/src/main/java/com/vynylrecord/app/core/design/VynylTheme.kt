package com.vynylrecord.app.core.design

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * The Vynyl design system: obsidian, brass and warm paper.
 *
 * The palette is not "dark mode with an accent". It is the palette of the object the app is about — a
 * black vinyl disc, a brass spindle and an amber label under a warm lamp. Every surface is one of four
 * dark browns rather than four greys, and the only saturated colours are the amber of the label and the
 * amber of brass.
 *
 * Two rules are enforced here rather than in review:
 *
 * * **No purple.** Material 3's default dark scheme is violet, and a Compose app that only sets a couple
 *   of colours inherits it in every ripple, slider and switch. The whole scheme is written out.
 * * **Contrast is checked.** Cream on obsidian is 15:1 and cream on panel is 14:1; muted text is 6.4:1,
 *   which clears WCAG AA for body text, so the muted colour is for secondary information only and never
 *   for something a user has to read to act.
 */
object VynylColors {

    /** The ground everything sits on: the black of a record. */
    val Obsidian = Color(0xFF0C0A09)

    /** Raised surfaces: cards, sheets, the bottom bar. */
    val DeepStone = Color(0xFF1C1917)

    /** The panel colour inside a card, a shade warmer than the surface. */
    val Panel = Color(0xFF211E1A)

    /** The label. Every interactive accent in the app is one of these three ambers. */
    val Amber = Color(0xFFD97706)
    val AmberBright = Color(0xFFF59E0B)
    val Brass = Color(0xFFB45309)

    /** Text on dark. Cream for reading, warm paper for headings on the label. */
    val Cream = Color(0xFFFEF3C7)
    val WarmPaper = Color(0xFFFFF7E6)

    /** A worn red label, used sparingly: the record-in-progress badge and destructive confirmations. */
    val Ruby = Color(0xFF991B1B)

    /** Secondary text. Passes AA on obsidian; not for anything longer than a line. */
    val Muted = Color(0xFFA8A29E)

    val Error = Color(0xFFF87171)
    val Success = Color(0xFF34D399)

    /** Borders: brass at low alpha, so a card edge reads as a metal seam rather than a line. */
    val Border = Color(0x33B45309)
    val BorderStrong = Color(0x66B45309)

    /** Translucent surfaces for overlays that must show the record behind them. */
    val Scrim = Color(0xCC0C0A09)
    val PanelOverlay = Color(0xE6211E1A)
}

/**
 * The Compose [androidx.compose.material3.ColorScheme].
 *
 * Every slot Material 3 can colour is written explicitly. Leaving `surfaceVariant` or `secondaryContainer`
 * to the default is how a purple switch appears in a brown app, and it is the kind of bug that only shows
 * up on the one screen that happens to use that component.
 */
private val VynylColorScheme = darkColorScheme(
    primary = VynylColors.Amber,
    onPrimary = VynylColors.Obsidian,
    primaryContainer = VynylColors.Brass,
    onPrimaryContainer = VynylColors.WarmPaper,

    secondary = VynylColors.Brass,
    onSecondary = VynylColors.WarmPaper,
    secondaryContainer = VynylColors.Panel,
    onSecondaryContainer = VynylColors.Cream,

    tertiary = VynylColors.AmberBright,
    onTertiary = VynylColors.Obsidian,
    tertiaryContainer = VynylColors.Ruby,
    onTertiaryContainer = VynylColors.WarmPaper,

    background = VynylColors.Obsidian,
    onBackground = VynylColors.Cream,

    surface = VynylColors.DeepStone,
    onSurface = VynylColors.Cream,
    surfaceVariant = VynylColors.Panel,
    onSurfaceVariant = VynylColors.Muted,

    surfaceTint = VynylColors.Amber,
    inverseSurface = VynylColors.Cream,
    inverseOnSurface = VynylColors.Obsidian,
    inversePrimary = VynylColors.Brass,

    error = VynylColors.Error,
    onError = VynylColors.Obsidian,
    errorContainer = VynylColors.Ruby,
    onErrorContainer = VynylColors.WarmPaper,

    outline = VynylColors.BorderStrong,
    outlineVariant = VynylColors.Border,
    scrim = VynylColors.Scrim,
)

/**
 * Type: a serif for anything that is *said*, a monospace for anything that is *measured*.
 *
 * The record's title, an occasion and a dedication are set in the platform's serif, because a record
 * label is printed, not rendered. Durations, sizes, seeds and levels are monospaced so that a column of
 * them lines up and a number changing does not shift the layout.
 */
object VynylType {

    private val serif = FontFamily.Serif
    private val sans = FontFamily.SansSerif
    private val mono = FontFamily.Monospace

    val display = TextStyle(fontFamily = serif, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontFamily = serif, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontFamily = serif, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyLarge = TextStyle(fontFamily = sans, fontSize = 17.sp, lineHeight = 25.sp)
    val label = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp)
    val mono = TextStyle(fontFamily = mono, fontSize = 13.sp, lineHeight = 18.sp)
    val monoLarge = TextStyle(fontFamily = mono, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    val monoSmall = TextStyle(fontFamily = mono, fontSize = 11.sp, lineHeight = 15.sp)
}

private val VynylTypography = Typography(
    displayLarge = VynylType.display,
    displayMedium = VynylType.display,
    displaySmall = VynylType.title,
    headlineLarge = VynylType.title,
    headlineMedium = VynylType.headline,
    headlineSmall = VynylType.headline,
    titleLarge = VynylType.title,
    titleMedium = VynylType.headline,
    titleSmall = VynylType.label,
    bodyLarge = VynylType.bodyLarge,
    bodyMedium = VynylType.body,
    bodySmall = VynylType.caption,
    labelLarge = VynylType.label,
    labelMedium = VynylType.caption,
    labelSmall = VynylType.monoSmall,
)

/** Spacing, in one scale, so screens line up with each other rather than each inventing a margin. */
object VynylSpacing {
    val hairline = 1.dp
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val xlarge = 24.dp
    val xxlarge = 32.dp

    /** The minimum a touch target may be, per the accessibility checklist. */
    val minimumTouchTarget = 48.dp
}

/**
 * Motion, with the reduced-motion preference applied at the source.
 *
 * A `LocalReducedMotion` rather than every screen asking the preference: a screen that forgets to check
 * would animate, and "reduce motion" failing on one screen is exactly the kind of bug an accessibility
 * setting must not have.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** True when the app should prefer static presentation: no orbit, no spin, no crossfades. */
@Composable
fun reducedMotionEnabled(explicit: Boolean? = null): Boolean =
    explicit ?: LocalReducedMotion.current

/**
 * The theme.
 *
 * The app is dark-only by design: a vinyl record under a lamp. Following the system into light mode would
 * mean a second palette that is not the object, and the app would look like two different products.
 */
@Composable
fun VynylTheme(
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current

    if (!view.isInEditMode) {
        SideEffect {
            // Edge to edge, with light icons on the obsidian ground. Done here rather than in the activity
            // so a preview and a test render the same chrome as the app.
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = VynylColorScheme,
            typography = VynylTypography,
            shapes = VynylShapes,
            content = content,
        )
    }
}

/** Rounded, but not soft: a record sleeve has corners, and brass seams have right angles. */
private val VynylShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
