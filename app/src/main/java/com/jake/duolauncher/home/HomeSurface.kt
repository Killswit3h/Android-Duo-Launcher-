package com.jake.duolauncher.home

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jake.duolauncher.DEFAULT_GRID
import com.jake.duolauncher.DockSide
import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.MAX_DOCK_CAPACITY
import com.jake.duolauncher.MIN_DOCK_CAPACITY
import com.jake.duolauncher.postures.DuoPosture
import com.jake.duolauncher.postures.FoldOrientation
import com.jake.duolauncher.postures.HingeAvoidance
import com.jake.duolauncher.postures.HingeRegions
import com.jake.duolauncher.postures.PostureRect
import com.jake.duolauncher.postures.WindowPostureProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// The Home surface's configuration (FR-31, FR-37, FR-38, FR-41, FR-47, FR-49)
// ---------------------------------------------------------------------------

/**
 * Everything the Home surface needs to know that the *layout store* owns: the grid it draws, which
 * edge the dock rail lives on, how many dock slots there are, whether the layout is locked, whether
 * the Duo status cluster replaces the vertical rail, and which pages the user hid.
 *
 * This exists so that the Compose layer takes these as one explicit input rather than reaching into
 * the persisted model from a dozen places. [homeSurfaceConfigOf] is the single adapter from
 * `LauncherState`; every composable below takes a [HomeSurfaceConfig] (or its individual fields)
 * with a working default, so previews, tests and a half-wired model all render something sane.
 */
@Immutable
data class HomeSurfaceConfig(
    /** FR-31: the active layout's grid, 4–8 columns by 4–8 rows. */
    val grid: GridSpec = DEFAULT_GRID,
    /** FR-37: which screen edge the dock rail, status cluster and page controls sit on. */
    val dockSide: DockSide = DockSide.RIGHT,
    /** FR-38: 3 to 6 dock slots. */
    val dockCapacity: Int = DEFAULT_DOCK_SLOTS,
    /** FR-49: blocks drag, remove and Edit mode. */
    val lockLayout: Boolean = false,
    /** FR-41: the circular corner cluster replaces the vertical status rail. */
    val duoStatus: Boolean = true,
    /** FR-47: page ids the user hid in Page overview. Their contents are kept. */
    val hiddenPageIds: Set<Int> = emptySet(),
    /** One stable id per page, index-aligned with the page number (FR-47). */
    val pageIds: List<Int> = emptyList(),
) {
    val grid1: GridSpec get() = grid.sanitized()

    /** FR-37: the status cluster and page controls mirror to the dock's edge. */
    val dockOnRight: Boolean get() = dockSide == DockSide.RIGHT
}

/** The dock capacity a fresh install gets, and the fallback when nothing has been configured. */
const val DEFAULT_DOCK_SLOTS = 4

/**
 * The Home surface configuration described by [state].
 *
 * Deliberately the *only* place the Compose layer reads these fields, so that a rename on the
 * schema-9 side breaks one function rather than every Home file.
 */
fun homeSurfaceConfigOf(state: LauncherState): HomeSurfaceConfig {
    val dock = state.layoutSet.dock
    val layout = state.layoutSet.layout(state.layoutSet.targetFor(state.expandedActive))
    return HomeSurfaceConfig(
        grid = state.grid.sanitized(),
        dockSide = dock.side,
        dockCapacity = sanitizedDockCapacity(dock.capacity),
        lockLayout = state.settings.lockLayout,
        duoStatus = state.settings.duoStatus,
        hiddenPageIds = layout.hiddenPageIds,
        pageIds = layout.pageIds,
    )
}

// ---------------------------------------------------------------------------
// Dock rules (FR-38, FR-39, FR-40) — pure, so they are unit tested
// ---------------------------------------------------------------------------

/** FR-38: dock capacity is 3 to 6, whatever a stale file or a bad caller asks for. */
fun sanitizedDockCapacity(capacity: Int): Int = capacity.coerceIn(MIN_DOCK_CAPACITY, MAX_DOCK_CAPACITY)

/**
 * [items] as exactly [capacity] slots: padded with empty slots when the stored dock is short, and
 * trimmed when the capacity was lowered. Trimming never happens silently on a filled slot — the
 * reflow track moves those out first — so this only ever drops trailing empties in practice.
 */
fun dockSlots(items: List<String?>, capacity: Int): List<String?> {
    val size = sanitizedDockCapacity(capacity)
    return List(size) { index -> items.getOrNull(index) }
}

/**
 * FR-40: whether a drop of [id] fits the dock as it stands.
 *
 * Moving an item that is already in the dock always fits, because it vacates its own slot. Anything
 * else needs a free slot within the capacity. A false answer is what raises
 * [DOCK_FULL_MESSAGE]; it is never silently swallowed.
 */
fun dockAcceptsDrop(items: List<String?>, id: String, capacity: Int): Boolean {
    if (id.isBlank()) return false
    val slots = dockSlots(items, capacity)
    return id in slots || slots.any { it == null }
}

/** The rejection the dock has always shown, kept word for word (FR-40). */
const val DOCK_FULL_MESSAGE = "Dock full • Move an app out first"

/** FR-49's refusal, shown where Edit mode would otherwise have opened. */
const val LAYOUT_LOCKED_MESSAGE = "Home layout is locked"

// ---------------------------------------------------------------------------
// Fold posture (FR-42, FR-43)
// ---------------------------------------------------------------------------

/**
 * The strip of the window that must stay clear of content, in **window** pixels (FR-42).
 *
 * Window coordinates rather than any one composable's local space, because the Home surface, the
 * dock, folders, menus and sheets are all positioned in different parents and each converts with
 * its own `boundsInWindow()`.
 */
@Immutable
data class HingeBand(val leftPx: Float, val rightPx: Float, val topPx: Float, val bottomPx: Float, val vertical: Boolean) {
    val widthPx: Float get() = (rightPx - leftPx).coerceAtLeast(0f)
    val heightPx: Float get() = (bottomPx - topPx).coerceAtLeast(0f)
    val centerXPx: Float get() = (leftPx + rightPx) / 2f
}

/**
 * The hinge band the Home surface is currently avoiding, or null when there is nothing to avoid.
 *
 * Null is the honest value for a flat display, an unknown posture and a horizontal fold in a
 * surface that only splits horizontally, which is exactly the width-only fallback the error table
 * asks for.
 */
val LocalHomeHinge = staticCompositionLocalOf<HingeBand?> { null }

/**
 * The launcher's fold posture, collected while this composition is started.
 *
 * `WindowPostureProvider` is a lifecycle observer, so it is registered and unregistered here rather
 * than being built once in the activity: Home is the only surface that consumes it, and a provider
 * that outlives the surface would keep a `WindowInfoTracker` collector alive for nothing.
 */
@Composable
fun rememberDuoPosture(activity: android.app.Activity): State<DuoPosture> {
    val provider = remember(activity) { WindowPostureProvider(activity) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(provider, lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(provider)
        onDispose { lifecycleOwner.lifecycle.removeObserver(provider) }
    }
    return provider.posture.collectAsState()
}

/**
 * [posture] as the band to keep clear, in window pixels, or null when nothing has to be avoided.
 *
 * Pure, so every case is a unit test: only a half-opened fold produces a band, and the band is the
 * reported hinge bounds grown by [HingeAvoidance.MARGIN_DP] on each side of the fold.
 */
fun hingeBandOf(posture: DuoPosture, density: Float): HingeBand? {
    val half = posture as? DuoPosture.HalfOpened ?: return null
    val margin = HingeAvoidance.marginPx(density)
    val bounds = half.hingeBoundsPx
    return when (half.orientation) {
        FoldOrientation.VERTICAL -> HingeBand(
            leftPx = (bounds.left - margin).toFloat(),
            rightPx = (bounds.right + margin).toFloat(),
            topPx = bounds.top.toFloat(),
            bottomPx = bounds.bottom.toFloat(),
            vertical = true,
        )
        FoldOrientation.HORIZONTAL -> HingeBand(
            leftPx = bounds.left.toFloat(),
            rightPx = bounds.right.toFloat(),
            topPx = (bounds.top - margin).toFloat(),
            bottomPx = (bounds.bottom + margin).toFloat(),
            vertical = false,
        )
    }
}

/**
 * Where a grid of [columns] columns has to open a gutter so that no cell lands under the fold.
 *
 * This is Apple's rule for the inner display — prefer an even column count so the grid divides
 * cleanly at the fold, and make a small adjustment rather than rearranging content — expressed as
 * arithmetic. The band is absorbed into the column boundary nearest its centre, so the columns stay
 * in their original order and only become a little narrower.
 *
 * Returns null when there is nothing to avoid, when the band misses the grid entirely, or when the
 * grid would be left with less than [MIN_CELL_FRACTION] of its natural cell width, in which case
 * overlapping the fold is the lesser evil (the same judgement `HingeAvoidance.avoidable` makes).
 */
fun hingeColumnGutter(
    gridLeftPx: Float,
    gridWidthPx: Float,
    columns: Int,
    band: HingeBand?,
): HingeGutter? {
    if (band == null || !band.vertical || columns <= 0 || gridWidthPx <= 0f) return null
    val gridRight = gridLeftPx + gridWidthPx
    if (band.rightPx <= gridLeftPx || band.leftPx >= gridRight) return null
    val gutter = band.widthPx.coerceAtMost(gridWidthPx)
    val remaining = gridWidthPx - gutter
    val naturalCell = gridWidthPx / columns
    if (remaining / columns < naturalCell * MIN_CELL_FRACTION) return null
    // The boundary the band is closest to, keeping at least one column on each side where the
    // band actually crosses the grid rather than clipping its edge.
    val boundary = (((band.centerXPx - gridLeftPx) / gridWidthPx) * columns)
        .roundToInt().coerceIn(0, columns)
    return HingeGutter(afterColumn = boundary, widthPx = gutter, cellWidthPx = remaining / columns)
}

/**
 * A gutter opened inside a grid so the fold falls between two columns.
 *
 * @param afterColumn how many columns sit before the gutter. 0 or [GridSpec.columns] means the
 *   whole grid shifts to one side of the fold instead of being split.
 * @param cellWidthPx the narrowed column width that makes room for the gutter.
 */
@Immutable
data class HingeGutter(val afterColumn: Int, val widthPx: Float, val cellWidthPx: Float) {
    /** The left edge of [column], relative to the grid's own left edge. */
    fun columnLeftPx(column: Int): Float =
        column * cellWidthPx + if (column >= afterColumn) widthPx else 0f
}

/** Below this fraction of its natural width a column is too narrow to hold an icon and a label. */
private const val MIN_CELL_FRACTION = 0.72f

/**
 * [content] moved clear of [band], within [container] — the single-rectangle case that folders,
 * menus and sheets need (FR-42).
 *
 * Everything here is in the same coordinate space; the caller picks which (window for an overlay,
 * local for a child). Content that already clears the fold is returned untouched.
 */
fun avoidHinge(container: Rect, content: Rect, band: HingeBand?): Rect {
    if (band == null) return content
    val containerRect = container.toPostureRect()
    val hinge = PostureRect(
        left = band.leftPx.roundToInt(),
        top = band.topPx.roundToInt(),
        right = band.rightPx.roundToInt(),
        bottom = band.bottomPx.roundToInt(),
    )
    val regions = HingeAvoidance.regions(
        container = containerRect,
        hinge = hinge,
        orientation = if (band.vertical) FoldOrientation.VERTICAL else FoldOrientation.HORIZONTAL,
        // The band already carries the 16dp margin, so it is not added twice here.
        marginPx = 0,
    )
    return HingeAvoidance.avoid(content.toPostureRect(), regions).toComposeRect()
}

/** The roomiest area left once [band] is excluded from [container] — where a panel should open. */
fun largestRegion(container: Rect, band: HingeBand?): Rect {
    if (band == null) return container
    val regions: HingeRegions = HingeAvoidance.regions(
        container = container.toPostureRect(),
        hinge = PostureRect(
            band.leftPx.roundToInt(), band.topPx.roundToInt(),
            band.rightPx.roundToInt(), band.bottomPx.roundToInt(),
        ),
        orientation = if (band.vertical) FoldOrientation.VERTICAL else FoldOrientation.HORIZONTAL,
        marginPx = 0,
    )
    return if (regions.avoidable) regions.largest.toComposeRect() else container
}

private fun Rect.toPostureRect() =
    PostureRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

private fun PostureRect.toComposeRect() =
    Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

/**
 * Padding that keeps this composable's content out of the fold (FR-42).
 *
 * The observer sits outside the padding, so it always measures the full area the parent gave and
 * the padding it computes cannot feed back into its own input. With no fold, or a fold this surface
 * does not cross, it adds nothing at all.
 */
@Composable
fun Modifier.hingeClearance(): Modifier {
    val band = LocalHomeHinge.current
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val measured = this.onGloballyPositioned { bounds = it.boundsInWindow() }
    if (band == null || bounds.width <= 0f || bounds.height <= 0f) return measured
    val local = band.relativeTo(bounds)
    val region = largestRegion(Rect(0f, 0f, bounds.width, bounds.height), local)
    if (region.width >= bounds.width && region.height >= bounds.height) return measured
    return measured.padding(
        start = with(density) { region.left.coerceAtLeast(0f).toDp() },
        top = with(density) { region.top.coerceAtLeast(0f).toDp() },
        end = with(density) { (bounds.width - region.right).coerceAtLeast(0f).toDp() },
        bottom = with(density) { (bounds.height - region.bottom).coerceAtLeast(0f).toDp() },
    )
}

/** [band] expressed relative to a composable whose window bounds are [bounds]. */
fun HingeBand.relativeTo(bounds: Rect): HingeBand = HingeBand(
    leftPx = leftPx - bounds.left,
    rightPx = rightPx - bounds.left,
    topPx = topPx - bounds.top,
    bottomPx = bottomPx - bounds.top,
    vertical = vertical,
)

// ---------------------------------------------------------------------------
// Mirroring (FR-37)
// ---------------------------------------------------------------------------

/** The gap between the rail — dock, status cluster, controls — and the screen edge it sits on. */
val RAIL_EDGE = 12.dp

/** The corner the dock rail, status cluster and page controls occupy, for the given side. */
fun topRailAlignment(side: DockSide): Alignment =
    if (side == DockSide.RIGHT) Alignment.TopEnd else Alignment.TopStart

fun bottomRailAlignment(side: DockSide): Alignment =
    if (side == DockSide.RIGHT) Alignment.BottomEnd else Alignment.BottomStart

/** The page area sits opposite the rail, so it is aligned to the other edge. */
fun pagerAlignment(side: DockSide): Alignment =
    if (side == DockSide.RIGHT) Alignment.TopStart else Alignment.TopEnd

/** The page indicator mirrors with the rail: it stays in the page area, on the rail's own side. */
fun pageIndicatorAlignment(side: DockSide): Alignment =
    if (side == DockSide.RIGHT) Alignment.BottomStart else Alignment.BottomEnd

/** Whether the two sides describe the same edge — the check a mirroring test actually wants. */
internal fun Density.pxOf(dp: Float): Float = dp * density

internal fun approximately(a: Float, b: Float, tolerance: Float = 0.5f): Boolean = abs(a - b) <= tolerance
