package com.jake.duolauncher.design

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI

/**
 * The single source of truth for Duo's geometry, rhythm and motion (FR-1).
 *
 * Colors live in [DuoColors], text styles in [DuoTypography]. Screens never inline a radius,
 * a spacing value, a spring or a color literal; they read them from here.
 */
object DuoTokens {
    val radius: Radii = Radii()
    val space: Spacing = Spacing()
    val motion: Motion = Motion()
    val type: DuoTypography get() = InterDuoTypography
}

// ---------------------------------------------------------------------------
// Radii and concentric corners (FR-1)
// ---------------------------------------------------------------------------

/**
 * A corner radius that is also a [Shape], so a token can be used directly wherever Compose wants
 * a shape (`GlassSurface(shape = DuoTokens.radius.card)`) and wherever the raw [Dp] is needed for
 * concentric math (`DuoTokens.radius.card.dp`).
 */
@Immutable
class DuoRadius internal constructor(val dp: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        RoundedCornerShape(dp).createOutline(size, layoutDirection, density)

    /** Concentric inset: a child nested [padding] inside this corner keeps the curves parallel. */
    operator fun minus(padding: Dp): DuoRadius = DuoRadius(concentricRadius(dp, padding))

    /** Concentric outset: the parent corner that would enclose this one with [padding] to spare. */
    operator fun plus(padding: Dp): DuoRadius = DuoRadius(dp + padding)

    override fun equals(other: Any?): Boolean = other is DuoRadius && other.dp == dp
    override fun hashCode(): Int = dp.hashCode()
    override fun toString(): String = "DuoRadius(${dp.value}dp)"
}

@Immutable
class Radii internal constructor() {
    /** App icon squircle, for a nominal 60dp icon. Scale with [iconRadiusFor] for other sizes. */
    val icon: DuoRadius = DuoRadius(ICON_RADIUS)
    val tile: DuoRadius = DuoRadius(20.dp)
    val card: DuoRadius = DuoRadius(22.dp)
    val sheet: DuoRadius = DuoRadius(28.dp)
    val dock: DuoRadius = DuoRadius(32.dp)
    val widget: DuoRadius = DuoRadius(24.dp)
    val folder: DuoRadius = DuoRadius(36.dp)

    /** Smallest radius a concentric child is allowed to collapse to. */
    val minimum: Dp = 4.dp

    /** Icon corner radius for an arbitrary icon size, keeping the squircle proportion constant. */
    fun iconRadiusFor(iconSize: Dp): DuoRadius = DuoRadius(iconSize * ICON_RADIUS_RATIO)

    private companion object {
        /** iOS-style superellipse proportion: the corner is ~22.4% of the icon's edge. */
        const val ICON_RADIUS_RATIO = 0.2237f
        val ICON_RADIUS: Dp = 60.dp * ICON_RADIUS_RATIO
    }
}

/**
 * The concentric-rectangle rule: a child nested [padding] inside a parent of [parent] radius keeps
 * its curve parallel to the parent's when its own radius is the parent's minus that padding.
 *
 * Pure, so it is unit tested.
 */
fun concentricRadius(parent: Dp, padding: Dp, minimum: Dp = 0.dp): Dp =
    maxOf(minimum, parent - padding)

/** [concentricRadius] as a shape, for `ConcentricRectangle(DuoTokens.radius.sheet, space.lg)`. */
@Suppress("FunctionName")
fun ConcentricRectangle(parent: Dp, padding: Dp, minimum: Dp = DuoTokens.radius.minimum): DuoRadius =
    DuoRadius(concentricRadius(parent, padding, minimum))

@Suppress("FunctionName")
fun ConcentricRectangle(parent: DuoRadius, padding: Dp, minimum: Dp = DuoTokens.radius.minimum): DuoRadius =
    ConcentricRectangle(parent.dp, padding, minimum)

// ---------------------------------------------------------------------------
// Spacing (FR-1)
// ---------------------------------------------------------------------------

/** The 2 / 4 / 8 / 12 / 16 / 20 / 24 / 32 scale. Nothing in the UI invents a value between steps. */
@Immutable
class Spacing internal constructor() {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp

    /** The scale in order, for generated layouts and for tests that assert it stays monotonic. */
    val steps: List<Dp> = listOf(xxs, xs, sm, md, lg, xl, xxl, xxxl)
}

// ---------------------------------------------------------------------------
// Motion (FR-10, FR-11)
// ---------------------------------------------------------------------------

/**
 * Spring specs for folders, menus, sheets, App Library, Search and icon press.
 *
 * Specified the way designers talk about springs: a damping ratio and a *response* (the period of
 * the undamped spring, in seconds). FR-10 requires damping 0.8-0.9 and a 0.35-0.5s response.
 */
@Immutable
class Motion internal constructor() {
    val standardDamping: Float = 0.85f
    val standardResponse: Float = 0.42f
    val snappyDamping: Float = 0.80f
    val snappyResponse: Float = 0.35f
    val gentleDamping: Float = 0.90f
    val gentleResponse: Float = 0.50f

    /** Icon press scale (FR-10). */
    val pressedScale: Float = 0.9f

    val springStandard: SpringSpec<Float> = standard()
    val springSnappy: SpringSpec<Float> = snappy()
    val springGentle: SpringSpec<Float> = gentle()

    /** Folders, sheets, menus: the default for anything that opens or closes. */
    fun <T> standard(): SpringSpec<T> =
        spring(dampingRatio = standardDamping, stiffness = springStiffness(standardResponse))

    /** Icon press and release, drag pickup: short travel that must feel immediate. */
    fun <T> snappy(): SpringSpec<T> =
        spring(dampingRatio = snappyDamping, stiffness = springStiffness(snappyResponse))

    /** Large surfaces (App Library, Search) where a fast spring would read as a snap. */
    fun <T> gentle(): SpringSpec<T> =
        spring(dampingRatio = gentleDamping, stiffness = springStiffness(gentleResponse))
}

/**
 * Converts a spring *response* (seconds) to the Compose stiffness that produces it:
 * `stiffness = (2 * PI / response)^2`. Pure, so it is unit tested.
 */
fun springStiffness(responseSeconds: Float): Float {
    require(responseSeconds > 0f) { "Spring response must be positive." }
    val omega = (2.0 * PI / responseSeconds)
    return (omega * omega).toFloat()
}

/**
 * FR-11: while the system animator duration scale is 0, state changes apply without animation.
 * Pure, so it is unit tested; [rememberMotionEnabled] reads the real setting.
 */
fun motionEnabled(animatorDurationScale: Float): Boolean = animatorDurationScale > 0f
