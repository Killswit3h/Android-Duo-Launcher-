package com.jake.duolauncher.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * The kinds of Liquid Glass surface, which differ in how much they sit "on top of" the wallpaper
 * and therefore in fill weight, blur radius and rim strength (FR-2).
 */
enum class GlassLevel(
    /** Fill opacity when the Glass slider is at 0 (Clear). */
    internal val clearAlpha: Float,
    /** Backdrop blur radius for this level. */
    val blurRadius: Dp,
    /** Strength of the specular top rim. */
    internal val rimStrength: Float,
) {
    /** Dock rail, page indicator, status cluster: thin chrome sitting directly on the wallpaper. */
    BAR(clearAlpha = 0.08f, blurRadius = 24.dp, rimStrength = 0.55f),

    /** Folder panels, sheets, App Library, Search: large surfaces that own the screen. */
    PANEL(clearAlpha = 0.10f, blurRadius = 32.dp, rimStrength = 0.50f),

    /** Context menus: small, high-contrast, must stay readable over anything. */
    MENU(clearAlpha = 0.14f, blurRadius = 40.dp, rimStrength = 0.60f),

    /** Today View widget backings: the lightest glass, so widget content leads. */
    WIDGET(clearAlpha = 0.06f, blurRadius = 20.dp, rimStrength = 0.40f),
}

/** The Glass slider, 0 = Clear to 100 = Tinted (FR-5). */
val LocalGlassLevel = compositionLocalOf { DEFAULT_GLASS_LEVEL }

/** Accessibility: render glass as an opaque tinted fill with no blur (FR-6). */
val LocalReduceTransparency = compositionLocalOf { false }

/**
 * True when the wallpaper behind glass is bright enough that labels need the dim layer to reach
 * 4.5:1 (FR-7, NFR-A2). Supplied by the wallpaper track from the wallpaper's luminance; see
 * [isBrightBackdrop].
 */
val LocalBrightBackdrop = compositionLocalOf { false }

/**
 * A Liquid Glass surface: translucent fill over the shared blurred backdrop, a specular highlight
 * along the top rim, a darkened edge below, and — over bright wallpaper at low Glass values — a
 * dim layer under the content so text stays legible.
 *
 * Signature frozen by the build plan's integration contract.
 */
@Composable
fun GlassSurface(
    level: GlassLevel,
    shape: Shape = DuoTokens.radius.card,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = currentDuoColors()
    val glass = LocalGlassLevel.current
    val reduceTransparency = LocalReduceTransparency.current
    val brightBackdrop = LocalBrightBackdrop.current
    val backdrop = LocalDuoBackdrop.current

    val fillAlpha = glassFillAlpha(level, glass, reduceTransparency)
    val dimAlpha = glassDimAlpha(glass, brightBackdrop)
    val rimAlpha = glassRimAlpha(level, glass)
    val blurring = backdrop.blurSupported && !reduceTransparency

    Box(
        modifier = modifier
            .clip(shape)
            .then(if (blurring) Modifier.duoBackdropEffect(backdrop, shape, level.blurRadius) else Modifier)
            .background(colors.glassTint.copy(alpha = fillAlpha))
            .then(
                if (dimAlpha > 0f) Modifier.background(colors.scrim.copy(alpha = dimAlpha)) else Modifier,
            )
            .glassRim(shape, colors.specular, colors.edge, rimAlpha),
        content = content,
    )
}

/**
 * One border draws both halves of the glass edge: the specular highlight catching light along the
 * top, fading out, then the darkened edge grounding the bottom (FR-2).
 */
private fun Modifier.glassRim(shape: Shape, specular: Color, edge: Color, rimAlpha: Float): Modifier =
    border(
        width = RIM_WIDTH,
        brush = Brush.verticalGradient(
            0.0f to specular.copy(alpha = rimAlpha),
            0.35f to specular.copy(alpha = rimAlpha * RIM_FADE),
            0.65f to edge.copy(alpha = rimAlpha * EDGE_FADE),
            1.0f to edge.copy(alpha = rimAlpha * EDGE_STRENGTH),
        ),
        shape = shape,
    )

// ---------------------------------------------------------------------------
// Pure curves, unit tested
// ---------------------------------------------------------------------------

/**
 * Fill opacity for a surface at a given Glass slider value (FR-5).
 *
 * Continuous and monotonic from near-clear at 0 to a solid tint at 100, so dragging the slider
 * never flickers. Reduce transparency pins it opaque (FR-6).
 */
fun glassFillAlpha(level: GlassLevel, glass: Int, reduceTransparency: Boolean): Float {
    if (reduceTransparency) return 1f
    return lerp(level.clearAlpha, 1f, glassFraction(glass))
}

/**
 * Opacity of the dim layer drawn under content (FR-7).
 *
 * Only engages below Glass 30 and only over a bright backdrop, ramping to at most 35% at Glass 0
 * so labels keep 4.5:1 contrast (NFR-A2).
 */
fun glassDimAlpha(glass: Int, brightBackdrop: Boolean): Float {
    if (!brightBackdrop) return 0f
    val clamped = glass.coerceIn(0, 100)
    if (clamped >= DIM_ENGAGES_BELOW) return 0f
    return MAX_DIM_ALPHA * (DIM_ENGAGES_BELOW - clamped) / DIM_ENGAGES_BELOW
}

/**
 * Strength of the specular rim. The rim is what reads as "glass", so it is strongest when the
 * surface is clear and eases back as the fill becomes solid and the effect is carried by tint.
 */
fun glassRimAlpha(level: GlassLevel, glass: Int): Float =
    level.rimStrength * lerp(1f, RIM_AT_FULL_TINT, glassFraction(glass))

private fun glassFraction(glass: Int): Float = glass.coerceIn(0, 100) / 100f

/** Glass slider default: halfway between Clear and Tinted. */
const val DEFAULT_GLASS_LEVEL = 50

private const val DIM_ENGAGES_BELOW = 30
private const val MAX_DIM_ALPHA = 0.35f
private const val RIM_AT_FULL_TINT = 0.5f
private const val RIM_FADE = 0.15f
private const val EDGE_FADE = 0.15f
private const val EDGE_STRENGTH = 0.45f
private val RIM_WIDTH = 1.dp
