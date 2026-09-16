package com.jake.duolauncher.home

import android.content.Context
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.jake.duolauncher.DuoBadgeStyle
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.badges.BadgeCount
import com.jake.duolauncher.badges.DuoBadgeRepository
import com.jake.duolauncher.badges.DuoBadges
import com.jake.duolauncher.design.DEFAULT_GLASS_LEVEL
import com.jake.duolauncher.design.DuoColors
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.relativeLuminance
import com.jake.duolauncher.icons.DuoIconRenderer
import com.jake.duolauncher.icons.IconRenderer
import com.jake.duolauncher.icons.IconStyle
import com.jake.duolauncher.icons.LARGE_ICON_SCALE
import com.jake.duolauncher.icons.ProfileAppId
import com.jake.duolauncher.icons.iconSizePx

/**
 * How notification badges are drawn (FR-20). The data layer only ever reports a [BadgeCount]; this
 * is purely the presentation choice, so it lives with the UI rather than with the repository.
 */
enum class BadgeStyle {
    /** A plain dot: "this app has something new". */
    DOT,

    /** The count itself. */
    NUMBER,
}

/**
 * Everything about Home that the user can restyle: icon appearance and shape, icon size, labels,
 * badges and the glass slider.
 *
 * This is deliberately one parameter rather than a dozen. It is the single seam the schema-9
 * settings task writes to: it builds a [HomeAppearance] from the persisted settings block and hands
 * it to `LauncherScreen`, and every surface in `home/` follows without further plumbing. Until then
 * the defaults reproduce today's look, except that icons now come from the icon pipeline.
 */
@Immutable
data class HomeAppearance(
    /** Appearance, tint, shape and pack (FR-14 to FR-19). `dark` is resolved per-surface. */
    val iconStyle: IconStyle = IconStyle(),
    /** FR-17: hides labels and grows icons by 20%. */
    val largeIcons: Boolean = false,
    /** The existing **Show labels** switch. */
    val labels: Boolean = true,
    val badgeStyle: BadgeStyle = BadgeStyle.DOT,
    /** The **Badges** switch. Badges also need notification access, which the repository gates. */
    val badgesEnabled: Boolean = true,
    /** The Glass slider, 0 = Clear to 100 = Tinted (FR-5). */
    val glass: Int = DEFAULT_GLASS_LEVEL,
    /** Accessibility: opaque glass, no blur (FR-6). */
    val reduceTransparency: Boolean = false,
) {
    /**
     * Whether icon labels are drawn. Large icons hide labels, which is what makes the 20% growth
     * fit the same cell (FR-17).
     */
    val showLabels: Boolean get() = labels && !largeIcons

    /** The displayed size of an icon whose slider size is [baseDp] (FR-17). Pure. */
    fun iconSizeDp(baseDp: Float): Float = if (largeIcons) baseDp * LARGE_ICON_SCALE else baseDp
}

/**
 * The [HomeAppearance] described by the persisted settings (FR-5, FR-6, FR-14 to FR-17, FR-20).
 *
 * This is the adapter the schema-9 settings block was waiting for: without it every Home surface
 * renders the *defaults* no matter what the user chose, so the glass slider, icon appearance, icon
 * shape, tint, icon pack, Large icons and badge style all move in Settings and change nothing on
 * screen. Deliberately the only place these fields are read, so a rename breaks one function.
 */
fun homeAppearanceOf(state: LauncherState): HomeAppearance {
    val settings = state.settings
    return HomeAppearance(
        iconStyle = IconStyle(
            appearance = settings.iconAppearance,
            tint = settings.iconTint,
            tintIntensity = settings.iconTintIntensity,
            shape = settings.iconShape,
            pack = settings.iconPack,
        ),
        largeIcons = settings.largeIcons,
        labels = state.labels,
        badgeStyle = when (settings.badgeStyle) {
            DuoBadgeStyle.NUMBER -> BadgeStyle.NUMBER
            else -> BadgeStyle.DOT
        },
        // FR-20: the stored style carries the off switch as a third case, so "no badges" is a
        // style rather than a separate flag. Collapsing OFF into DOT would leave the toggle inert.
        badgesEnabled = settings.badgeStyle != DuoBadgeStyle.OFF,
        glass = settings.glassLevel,
        reduceTransparency = settings.reduceTransparency,
    )
}

/** The appearance every `home/` surface reads. Supplied by `LauncherScreen`. */
val LocalHomeAppearance = staticCompositionLocalOf { HomeAppearance() }

/** The current badge per app (FR-20). Collected once for the whole tree, never per tile. */
val LocalBadges = staticCompositionLocalOf<Map<ProfileAppId, BadgeCount>> { emptyMap() }

/** The icon pipeline every surface draws from (FR-12). */
val LocalIconRenderer = staticCompositionLocalOf<IconRenderer?> { null }

/**
 * The process-wide icon renderer.
 *
 * One cache for the whole launcher, so Home, the dock, folders and App Library share renders rather
 * than each holding their own copy of the same bitmap (NFR-P4).
 */
object DuoIcons {
    @Volatile
    private var instance: DuoIconRenderer? = null

    @Synchronized
    fun renderer(context: Context): DuoIconRenderer =
        instance ?: DuoIconRenderer(context.applicationContext).also { instance = it }
}

@Composable
internal fun rememberIconRenderer(): IconRenderer {
    val context = LocalContext.current
    return LocalIconRenderer.current ?: remember(context) { DuoIcons.renderer(context) }
}

@Composable
internal fun rememberBadgeRepository(): DuoBadgeRepository {
    val context = LocalContext.current
    return remember(context) { DuoBadges.repository(context) }
}

/**
 * The rendered icon for [appId] at [sizeDp], or null until the pipeline has one.
 *
 * The cache is consulted **synchronously** first, so a scrolling page of icons costs a map lookup
 * per tile and never blocks a frame (NFR-P4). Only a miss starts a render, and that happens off the
 * main thread inside the renderer.
 */
@Composable
internal fun rememberAppIcon(
    appId: ProfileAppId,
    sizeDp: Float,
    style: IconStyle,
    large: Boolean,
): ImageBitmap? {
    val renderer = rememberIconRenderer()
    val density = LocalDensity.current.density
    val sizePx = remember(sizeDp, density, large) { iconSizePx(sizeDp, density, large) }
    val icon = remember(renderer, appId, sizePx, style) {
        mutableStateOf(renderer.request(appId, sizePx, style))
    }
    LaunchedEffect(renderer, appId, sizePx, style) {
        if (icon.value == null) icon.value = renderer.load(appId, sizePx, style)
    }
    return icon.value
}

/** The icon style for the current appearance: dark follows the palette rather than the setting. */
@Composable
internal fun HomeAppearance.resolvedIconStyle(colors: DuoColors): IconStyle =
    remember(iconStyle, colors.dark) { iconStyle.copy(dark = colors.dark).normalized() }

// ---------------------------------------------------------------------------
// Badges (FR-20 to FR-22). Pure, so they are unit tested.
// ---------------------------------------------------------------------------

/** Above this the exact number stops being useful and stops fitting the badge. */
const val MAX_SHOWN_BADGE = 999

/**
 * The text a Number-style badge shows for [count], or null when nothing should be drawn.
 *
 * A count beyond [MAX_SHOWN_BADGE] reads as "999+" rather than growing the badge until it covers
 * the icon it belongs to.
 */
fun badgeText(count: Int): String? = when {
    count <= 0 -> null
    count > MAX_SHOWN_BADGE -> "$MAX_SHOWN_BADGE+"
    else -> count.toString()
}

/** The TalkBack description of a badge, or null when there is none (NFR-A1). */
fun badgeDescription(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 notification"
    else -> "$count notifications"
}

/**
 * Whether a badge is drawn at all: badges enabled, a positive count, and — because the repository
 * empties itself when access is revoked — a non-empty count is already proof of access (FR-23).
 */
fun showsBadge(enabled: Boolean, count: BadgeCount): Boolean = enabled && count.hasBadge

// ---------------------------------------------------------------------------
// Motion (FR-10, FR-11)
// ---------------------------------------------------------------------------

/**
 * [spec] while motion is enabled, and an immediate snap while the animator duration scale is 0
 * (FR-11). Every animated surface in `home/` goes through this rather than reading the setting
 * itself.
 */
fun <T> duoSpec(spec: FiniteAnimationSpec<T>, motionEnabled: Boolean): FiniteAnimationSpec<T> =
    if (motionEnabled) spec else snap()

/** The press scale an icon animates to (FR-10). */
fun pressScale(pressed: Boolean): Float = if (pressed) DuoTokens.motion.pressedScale else 1f

/**
 * A readable label over the accent, taken from the existing tokens rather than a new literal: the
 * specular highlight is the light one and the glass edge is the dark one.
 */
fun onAccentLabel(colors: DuoColors): Color =
    if (relativeLuminance(colors.accent) > ACCENT_IS_BRIGHT) colors.edge else colors.specular

private const val ACCENT_IS_BRIGHT = 0.4f
