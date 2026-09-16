package com.jake.duolauncher.design

import android.os.Build
import android.provider.Settings
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.jake.duolauncher.LocalDuoPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Every color Duo draws (FR-1). This file and [DuoTokens] are the only places a color literal is
 * allowed to appear; screens read semantic roles from [duoColors].
 *
 * The non-accent roles are derived from the app's existing `DuoPalette`, so adopting these tokens
 * does not change how the launcher looks.
 */
@Immutable
data class DuoColors(
    /** Primary foreground, matching the existing palette's ink. */
    val ink: Color,
    /** The tint a glass surface fills with, matching the existing palette's glass. */
    val glassTint: Color,
    /** Resolved accent (FR-8). */
    val accent: Color,
    /** Primary label. */
    val label1: Color,
    /** Secondary label: captions, supporting rows. */
    val label2: Color,
    /** Tertiary label: placeholders, disabled text. */
    val label3: Color,
    /** Hairline between rows and sections. */
    val separator: Color,
    // Additive roles beyond the frozen list, so that glass rendering needs no literals of its own.
    /** Specular top-rim highlight on a glass surface (FR-2). */
    val specular: Color,
    /** Darkened outer edge on a glass surface (FR-2). */
    val edge: Color,
    /** Scrim used for the under-text dim layer (FR-7) and the dark-mode wallpaper dim (FR-9). */
    val scrim: Color,
    val dark: Boolean,
)

/**
 * An explicit color override. Null means "derive from the palette the app is already themed with",
 * which is what [currentDuoColors] does, so glass works before a Duo theme is wired up.
 */
val LocalDuoColors = staticCompositionLocalOf<DuoColors?> { null }

/** The colors a surface should draw with: the provided override, else the palette-derived set. */
@Composable
fun currentDuoColors(): DuoColors = LocalDuoColors.current ?: duoColors()

/**
 * Semantic colors for the current appearance.
 *
 * @param accent which accent the user picked (FR-8). Defaults to [DuoAccent.Auto].
 * @param dark dark appearance; defaults to the palette the app is already themed with.
 * @param wallpaperSeed dominant color of the Duo wallpaper, used for [DuoAccent.Auto] when
 *   Android dynamic colors are unavailable. Supplied by the wallpaper track through
 *   [LocalDuoWallpaperSeed]; never computed on the main thread here.
 */
@Composable
fun duoColors(
    accent: DuoAccent = DuoAccent.Auto,
    dark: Boolean = LocalDuoPalette.current.dark,
    wallpaperSeed: Color? = LocalDuoWallpaperSeed.current,
): DuoColors {
    val palette = LocalDuoPalette.current
    val dynamic = dynamicAccentOrNull(dark)
    val resolvedAccent = resolveAccent(accent, dark, dynamic, wallpaperSeed)
    return remember(palette, dark, resolvedAccent) {
        val ink = palette.ink
        DuoColors(
            ink = ink,
            glassTint = palette.glass,
            accent = resolvedAccent,
            label1 = ink,
            label2 = ink.copy(alpha = LABEL2_ALPHA),
            label3 = ink.copy(alpha = LABEL3_ALPHA),
            separator = ink.copy(alpha = SEPARATOR_ALPHA),
            specular = if (dark) SpecularDark else SpecularLight,
            edge = if (dark) EdgeDark else EdgeLight,
            scrim = Scrim,
            dark = dark,
        )
    }
}

/** The dominant color of the current Duo wallpaper, or null. Owned by the wallpaper track (F3). */
val LocalDuoWallpaperSeed = staticCompositionLocalOf<Color?> { null }

// ---------------------------------------------------------------------------
// Accent (FR-8)
// ---------------------------------------------------------------------------

sealed interface DuoAccent {
    /** Android dynamic color where available, else derived from the Duo wallpaper. */
    data object Auto : DuoAccent
    data class Preset(val preset: DuoAccentPreset) : DuoAccent
    /** Custom hue picker, 0..360 degrees. */
    data class CustomHue(val degrees: Float) : DuoAccent
}

/** The eight presets offered alongside Automatic and Custom. */
enum class DuoAccentPreset(internal val light: Color, internal val dark: Color) {
    BLUE(Color(0xFF2F6F8F), Color(0xFF8CC3DC)),
    INDIGO(Color(0xFF4B4F9B), Color(0xFFA7ABE8)),
    PURPLE(Color(0xFF7A4B93), Color(0xFFD1A8E2)),
    PINK(Color(0xFFA8456A), Color(0xFFF0A0BB)),
    ORANGE(Color(0xFFB2631F), Color(0xFFF2B27A)),
    YELLOW(Color(0xFF8A6B12), Color(0xFFE6C766)),
    GREEN(Color(0xFF3B7444), Color(0xFF97CFA0)),
    TEAL(Color(0xFF2C6F6B), Color(0xFF8FCCC7));

    fun color(dark: Boolean): Color = if (dark) this.dark else this.light
}

/**
 * Resolves a user accent choice to a drawable color. Pure, so it is unit tested.
 *
 * @param dynamic Android dynamic color primary, or null where unavailable.
 * @param wallpaperSeed dominant wallpaper color, or null.
 */
fun resolveAccent(
    accent: DuoAccent,
    dark: Boolean,
    dynamic: Color?,
    wallpaperSeed: Color?,
): Color = when (accent) {
    is DuoAccent.Preset -> accent.preset.color(dark)
    is DuoAccent.CustomHue -> accentForHue(accent.degrees, dark)
    DuoAccent.Auto -> dynamic
        ?: wallpaperSeed?.let { accentFromSeed(it, dark) }
        ?: DuoAccentPreset.BLUE.color(dark)
}

/** An accent at [degrees] hue, at a lightness that stays legible in the given appearance. Pure. */
fun accentForHue(degrees: Float, dark: Boolean): Color {
    val hue = ((degrees % 360f) + 360f) % 360f
    return Color.hsl(
        hue = hue,
        saturation = ACCENT_SATURATION,
        lightness = if (dark) ACCENT_LIGHTNESS_DARK else ACCENT_LIGHTNESS_LIGHT,
    )
}

/** Takes the hue of a wallpaper's dominant color and restyles it as an accent. Pure. */
fun accentFromSeed(seed: Color, dark: Boolean): Color = accentForHue(hueOf(seed), dark)

/** Hue in degrees (0..360) of a color; 0 for greys. Pure. */
fun hueOf(color: Color): Float {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maximum = max(r, max(g, b))
    val minimum = min(r, min(g, b))
    val delta = maximum - minimum
    if (delta < 1e-6f) return 0f
    val hue = when (maximum) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return if (hue < 0f) hue + 360f else hue
}

/** Relative luminance (WCAG). Used to decide when a backdrop counts as bright (FR-7). Pure. */
fun relativeLuminance(color: Color): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

/** True when text over this backdrop needs the FR-7 dim layer to reach 4.5:1. Pure. */
fun isBrightBackdrop(color: Color): Boolean = relativeLuminance(color) > BRIGHT_BACKDROP_LUMINANCE

@Composable
private fun dynamicAccentOrNull(dark: Boolean): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return remember(context, dark) {
        runCatching {
            if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
        }.getOrNull()
    }
}

/**
 * FR-11: reads the system animator duration scale. Returns true when motion should play.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        motionEnabled(abs(scale))
    }
}

// Literals live here and nowhere else.
private const val LABEL2_ALPHA = 0.62f
private const val LABEL3_ALPHA = 0.38f
private const val SEPARATOR_ALPHA = 0.16f
private const val ACCENT_SATURATION = 0.52f
private const val ACCENT_LIGHTNESS_LIGHT = 0.38f
private const val ACCENT_LIGHTNESS_DARK = 0.70f
private const val BRIGHT_BACKDROP_LUMINANCE = 0.45f

private val SpecularLight = Color(0xFFFFFFFF)
private val SpecularDark = Color(0xFFDCEAF0)
private val EdgeLight = Color(0xFF1A2A33)
private val EdgeDark = Color(0xFF000000)
private val Scrim = Color(0xFF000000)

/**
 * Stand-in "photograph" for previews, so glass can be eyeballed over a busy, bright-to-dark
 * backdrop. Fixture art, not a UI color role.
 */
internal val DuoSampleBackdrop: List<Color> = listOf(
    Color(0xFFF6D6A8),
    Color(0xFFE79B6B),
    Color(0xFF9C5F7A),
    Color(0xFF3F4C7E),
    Color(0xFF16233D),
)

internal val DuoSampleBackdropHighlight: Color = Color(0xFFFFF3D6)

/** Used for previews and as a safe default before a theme provides real colors. */
val DuoColorsFallback = DuoColors(
    ink = Color(0xFF243A46),
    glassTint = Color(0xFFE8EFF2),
    accent = DuoAccentPreset.BLUE.light,
    label1 = Color(0xFF243A46),
    label2 = Color(0xFF243A46).copy(alpha = LABEL2_ALPHA),
    label3 = Color(0xFF243A46).copy(alpha = LABEL3_ALPHA),
    separator = Color(0xFF243A46).copy(alpha = SEPARATOR_ALPHA),
    specular = SpecularLight,
    edge = EdgeLight,
    scrim = Scrim,
    dark = false,
)
