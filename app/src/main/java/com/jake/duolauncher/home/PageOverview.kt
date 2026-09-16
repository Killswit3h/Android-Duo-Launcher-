package com.jake.duolauncher.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.HomeLayout
import com.jake.duolauncher.coveredIndices
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors

// ---------------------------------------------------------------------------
// Page overview rules (FR-47) — pure, so they are unit tested
// ---------------------------------------------------------------------------

/**
 * One Home page as the overview describes it.
 *
 * @param page the page number the pager uses.
 * @param pageId the page's stable id, which is what hiding and reordering are stored against, so
 *   that moving a page does not silently hide a different one.
 */
@Immutable
data class HomePageSummary(
    val page: Int,
    val pageId: Int,
    val items: Int,
    val widgets: Int,
    val hidden: Boolean,
) {
    /** FR-47: only an empty page can be deleted, because deleting is otherwise destructive. */
    val isEmpty: Boolean get() = items == 0 && widgets == 0
}

/**
 * Every Home page, with what is on it.
 *
 * [pageIds] is index-aligned with the page number and may be short or empty on a layout that has
 * not been given stable ids yet, in which case the page number stands in for its own id. That keeps
 * the overview working on a half-migrated layout instead of collapsing every page onto id 0.
 */
fun homePageSummaries(
    layout: HomeLayout,
    pageIds: List<Int> = emptyList(),
    hiddenPageIds: Set<Int> = emptySet(),
): List<HomePageSummary> = (0 until layout.pageCount).map { page ->
    val id = pageIds.getOrNull(page) ?: page
    HomePageSummary(
        page = page,
        pageId = id,
        items = layout.slotsForPage(page).count { it != null },
        widgets = layout.widgetPlacements.count { it.page == page },
        hidden = id in hiddenPageIds,
    )
}

/** Which cells of [page] are occupied, by an item or by a widget's footprint. */
fun pageOccupancy(layout: HomeLayout, page: Int): List<Boolean> {
    val grid = layout.grid
    val covered = layout.widgetPlacements
        .filter { it.page == page }
        .flatMapTo(mutableSetOf()) { it.coveredIndices(grid) }
    val slots = layout.slotsForPage(page)
    return List(grid.cells) { local ->
        slots.getOrNull(local) != null || com.jake.duolauncher.homeCellIndex(page, local, grid) in covered
    }
}

/**
 * [order] with the page at [from] moved to [to] (FR-47).
 *
 * Out-of-range indices are clamped rather than throwing, because the buttons that drive this are at
 * the ends of the row and a double tap must not crash Home.
 */
fun movePage(order: List<Int>, from: Int, to: Int): List<Int> {
    if (order.size < 2) return order
    val source = from.coerceIn(0, order.lastIndex)
    val destination = to.coerceIn(0, order.lastIndex)
    if (source == destination) return order
    return order.toMutableList().apply { add(destination, removeAt(source)) }
}

/**
 * [hidden] with [pageId] hidden or shown (FR-47).
 *
 * Hiding the last visible page is refused: Home would have nothing to show, and the user would have
 * no page indicator left to tap to get back. Showing is always allowed.
 */
fun toggleHiddenPage(hidden: Set<Int>, pageId: Int, allPageIds: List<Int>): Set<Int> {
    if (pageId in hidden) return hidden - pageId
    val remaining = allPageIds.count { it != pageId && it !in hidden }
    if (remaining == 0) return hidden
    return hidden + pageId
}

/** The pages a swipe actually visits, in order — hidden pages are skipped, not deleted (AC-38). */
fun visiblePageOrder(order: List<Int>, hidden: Set<Int>): List<Int> = order.filterNot { it in hidden }

/**
 * The page *numbers* whose stable ids the user hid (AC-38).
 *
 * Hiding is stored against ids so that a reorder never re-points it, but the pager addresses pages
 * by number, so this is the translation between the two. A page with no id yet cannot have been
 * hidden, so it is never reported.
 */
fun hiddenPageNumbers(pageIds: List<Int>, hidden: Set<Int>, pageCount: Int): Set<Int> {
    if (hidden.isEmpty()) return emptySet()
    return (0 until pageCount).filterTo(mutableSetOf()) { page ->
        pageIds.getOrNull(page)?.let { it in hidden } == true
    }
}

/**
 * Where a swipe that came to rest on a hidden page should settle instead (AC-38).
 *
 * It keeps going the way the user was travelling, so swiping forward past a hidden page lands on the
 * page after it, exactly as if the hidden page were not there. Only when every page that way is
 * hidden does it turn back. Null only if *every* page is hidden, which [toggleHiddenPage] prevents.
 *
 * [pageCount] should include any trailing page that can never be hidden — the App Library — so that
 * swiping forward past a hidden last Home page reaches the library rather than bouncing back.
 */
fun nextVisiblePage(page: Int, forward: Boolean, pageCount: Int, hidden: Set<Int>): Int? {
    val ahead = if (forward) (page + 1 until pageCount) else (page - 1 downTo 0)
    val behind = if (forward) (page - 1 downTo 0) else (page + 1 until pageCount)
    return ahead.firstOrNull { it !in hidden } ?: behind.firstOrNull { it !in hidden }
}

/** FR-47: an empty page can be deleted, as long as it is not the only page left. */
fun canDeletePage(summary: HomePageSummary, totalPages: Int): Boolean =
    summary.isEmpty && totalPages > 1

// ---------------------------------------------------------------------------
// The overview itself (FR-47)
// ---------------------------------------------------------------------------

/**
 * The Page overview (FR-47): every Home page as a card, with hide/show, reorder and delete.
 *
 * Reordering is by explicit move-left / move-right buttons rather than by drag. A drag would be
 * prettier, but these cards are small, they sit inside a surface that already owns a horizontal
 * gesture, and a screen-reader user cannot drag at all; the buttons are reachable by every input.
 */
@Composable
fun HomePageOverview(
    summaries: List<HomePageSummary>,
    grid: GridSpec,
    occupancy: (Int) -> List<Boolean>,
    onMove: (from: Int, to: Int) -> Unit,
    onToggleHidden: (pageId: Int) -> Unit,
    onDelete: (pageId: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    BackHandler { onDismiss() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("page-overview-scrim")
            .background(colors.scrim.copy(alpha = OVERVIEW_SCRIM))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Close page overview",
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            level = GlassLevel.PANEL,
            shape = DuoTokens.radius.sheet,
            modifier = Modifier
                .padding(DuoTokens.space.lg)
                .testTag("page-overview")
                .semantics { contentDescription = "Home pages" }
                // Taps inside the panel must not reach the dismissing scrim underneath.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(Modifier.padding(DuoTokens.space.lg)) {
                Text(
                    text = "Home pages",
                    style = DuoTokens.type.title3,
                    color = colors.label1,
                    modifier = Modifier.padding(bottom = DuoTokens.space.sm),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
                ) {
                    summaries.forEachIndexed { index, summary ->
                        PageCard(
                            summary = summary,
                            index = index,
                            total = summaries.size,
                            grid = grid,
                            occupied = occupancy(summary.page),
                            onMove = onMove,
                            onToggleHidden = { onToggleHidden(summary.pageId) },
                            onDelete = { onDelete(summary.pageId) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = DuoTokens.space.md),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = TOUCH, minHeight = TOUCH)
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .testTag("page-overview-done")
                            .padding(horizontal = DuoTokens.space.md),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Done", style = DuoTokens.type.headline, color = colors.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageCard(
    summary: HomePageSummary,
    index: Int,
    total: Int,
    grid: GridSpec,
    occupied: List<Boolean>,
    onMove: (from: Int, to: Int) -> Unit,
    onToggleHidden: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = currentDuoColors()
    val pageLabel = "Page ${summary.page + 1}"
    Column(
        modifier = Modifier.testTag("page-card-${summary.pageId}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassSurface(
            level = GlassLevel.WIDGET,
            shape = DuoTokens.radius.card,
            modifier = Modifier
                .width(CARD_WIDTH)
                .height(CARD_HEIGHT)
                .alpha(if (summary.hidden) HIDDEN_ALPHA else 1f)
                .semantics {
                    contentDescription = "$pageLabel, ${summary.items} items, " +
                        "${summary.widgets} widgets" + if (summary.hidden) ", hidden" else ""
                },
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(DuoTokens.space.sm),
                verticalArrangement = Arrangement.spacedBy(DOT_GAP),
            ) {
                repeat(grid.rows) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DOT_GAP),
                    ) {
                        repeat(grid.columns) { column ->
                            val filled = occupied.getOrNull(row * grid.columns + column) == true
                            Box(
                                Modifier
                                    .size(DOT)
                                    .background(
                                        if (filled) colors.label1 else colors.label1.copy(alpha = EMPTY_DOT_ALPHA),
                                        CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = pageLabel,
            style = DuoTokens.type.caption1,
            color = colors.label2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = DuoTokens.space.xs),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                label = "Move $pageLabel left",
                tag = "page-move-left-${summary.pageId}",
                enabled = index > 0,
                onClick = { onMove(index, index - 1) },
            )
            CardButton(
                icon = if (summary.hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                label = if (summary.hidden) "Show $pageLabel" else "Hide $pageLabel",
                tag = "page-hide-${summary.pageId}",
                enabled = true,
                onClick = onToggleHidden,
            )
            CardButton(
                icon = Icons.Rounded.DeleteOutline,
                label = "Delete $pageLabel",
                tag = "page-delete-${summary.pageId}",
                enabled = canDeletePage(summary, total),
                onClick = onDelete,
            )
            CardButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                label = "Move $pageLabel right",
                tag = "page-move-right-${summary.pageId}",
                enabled = index < total - 1,
                onClick = { onMove(index, index + 1) },
            )
        }
    }
}

@Composable
private fun CardButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = currentDuoColors()
    Box(
        modifier = Modifier
            .size(TOUCH)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colors.label1 else colors.label3,
            modifier = Modifier.size(ICON),
        )
    }
}

private val CARD_WIDTH = 96.dp
private val CARD_HEIGHT = 132.dp
private val TOUCH = 48.dp
private val ICON = 20.dp
private val DOT = 6.dp
private val DOT_GAP = 3.dp
private const val OVERVIEW_SCRIM = 0.42f
private const val HIDDEN_ALPHA = 0.4f
private const val EMPTY_DOT_ALPHA = 0.22f
