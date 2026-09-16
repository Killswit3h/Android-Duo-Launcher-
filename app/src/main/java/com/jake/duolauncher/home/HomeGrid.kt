@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.DEFAULT_GRID
import com.jake.duolauncher.DropTarget
import com.jake.duolauncher.FolderEntry
import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.HomeDragState
import com.jake.duolauncher.HomeGeometry
import com.jake.duolauncher.WidgetController
import com.jake.duolauncher.WidgetPlacement
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.dropRegion
import com.jake.duolauncher.homeCellIndex
import com.jake.duolauncher.homeCellLocal
import kotlin.math.roundToInt

/**
 * One Home page's grid (FR-31, FR-42).
 *
 * The grid is whatever [grid] says — 4 to 8 columns by 4 to 8 rows, per layout — rather than the
 * 4x6 this used to hard-code. Everything that addresses a cell goes through [grid], so a page on the
 * inner display and a page on the cover can be different shapes at the same time.
 *
 * While the device is half-opened with a vertical fold, the columns open a gutter at the boundary
 * nearest the hinge (see [hingeColumnGutter]) instead of being redistributed: the icons stay in the
 * order the user put them in, and no cell ends up under the fold.
 */
@Composable
internal fun SharedHomeGrid(
    page: Int,
    savedSlots: List<String?>,
    savedLeadingSlots: List<String?>,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    widgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    geometry: HomeGeometry,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    folders: List<FolderEntry>,
    grid: GridSpec = DEFAULT_GRID,
    /** FR-45: Edit mode jiggles every placement and shows its − badge. */
    editing: Boolean = false,
    /** FR-46: removes an app, shortcut or folder from Home. Null hides the − badges. */
    onRemoveItem: ((String) -> Unit)? = null,
    /** FR-46: removes a widget, after the caller has confirmed releasing its binding. */
    onRemoveWidget: ((Int) -> Unit)? = null,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit,
) {
    val colors = currentDuoColors()
    val rowHeight = geometry.rowHeight
    val iconSize = geometry.iconSize
    val cells = grid.cells
    val columns = grid.columns
    val pageStart = homeCellIndex(page, 0, grid)
    val pageRange = pageStart until pageStart + cells
    fun savedAt(index: Int) = if (page == -1) savedLeadingSlots.getOrNull(homeCellLocal(index, grid)) else savedSlots.getOrNull(index)
    fun previewAt(index: Int) = if (page == -1) previewLeadingSlots.getOrNull(homeCellLocal(index, grid)) else previewSlots.getOrNull(index)
    fun savedIndexOf(id: String) = if (page == -1) savedLeadingSlots.indexOf(id).takeIf { it >= 0 }?.let { homeCellIndex(-1, it, grid) }
        else savedSlots.indexOf(id).takeIf { it >= 0 }
    fun previewIndexOf(id: String) = if (page == -1) previewLeadingSlots.indexOf(id).takeIf { it >= 0 }?.let { homeCellIndex(-1, it, grid) }
        else previewSlots.indexOf(id).takeIf { it >= 0 }
    val draggedId = drag.source?.appId
    val homeTarget = (target as? DropTarget.Home)?.index
    val source = drag.source?.target as? DropTarget.Home
    val draggedPreviewIndex = draggedId?.let(::previewIndexOf) ?: -1
    val hiddenIndex = when {
        !drag.active || !drag.moved -> null
        homeTarget != null -> draggedPreviewIndex.takeIf { it >= 0 }
        source != null && target !is DropTarget.Dock -> draggedPreviewIndex.takeIf { it >= 0 }
        else -> null
    }
    val dimDragged = drag.active && !drag.moved && source != null
    val pending = widgets.pendingPlacement?.takeIf { it.page == page }
    val pendingIsReplacement = pending != null && widgetPlacements.any { it.slot == pending.slot }
    val pageWidgets = widgetPlacements.filter { it.page == page } + listOfNotNull(pending?.takeUnless { pendingIsReplacement })
    val renderedRows = maxOf(grid.rows, pageWidgets.maxOfOrNull { it.row + it.spanY } ?: grid.rows)
    val topPitch = (geometry.widgetHeight + WIDGET_BAND_GAP) / WIDGET_BAND_ROWS
    fun rowTop(row: Int) = if (row <= WIDGET_BAND_ROWS) row * topPitch
        else geometry.widgetHeight + WIDGET_BAND_GAP + (row - WIDGET_BAND_ROWS) * rowHeight

    // FR-42: the fold, rebased onto this grid's own left edge. Measured rather than passed in, so
    // every caller of the grid gets the behaviour without having to plumb window coordinates.
    val hinge = LocalHomeHinge.current
    var gridLeftInWindow by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(rowTop(renderedRows).dp)
            .onGloballyPositioned { gridLeftInWindow = it.boundsInWindow().left },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val gutter = remember(hinge, gridLeftInWindow, widthPx, columns) {
            hingeColumnGutter(gridLeftInWindow, widthPx, columns, hinge)
        }
        val cellWidthPx = gutter?.cellWidthPx ?: (widthPx / columns)
        val cellWidth = with(density) { cellWidthPx.toDp() }
        fun columnLeftPx(column: Int) = gutter?.columnLeftPx(column) ?: (column * cellWidthPx)
        fun columnLeft(column: Int) = with(density) { columnLeftPx(column).toDp() }

        repeat(cells) { localIndex ->
            val globalIndex = pageStart + localIndex
            val cell = DropTarget.Home(globalIndex)
            val savedId = savedAt(globalIndex)
            val savedApp = appsById[savedId]
            val savedFolder = folders.firstOrNull { it.id == savedId }
            val previewId = previewAt(globalIndex)
            val highlighted = drag.active && target == cell
            val gap = hiddenIndex == globalIndex
            val row = localIndex / columns
            val cellHeight = rowTop(row + 1) - rowTop(row)
            Box(Modifier.offset(x = columnLeft(localIndex % columns), y = rowTop(row).dp)
                .width(cellWidth).height(cellHeight.dp).testTag("home-cell-$globalIndex")
                .dropRegion(drag, cell, savedApp?.id ?: savedFolder?.id, page)
                .combinedClickable(onClick = { savedFolder?.let { onFolder(it.id) } },
                    onLongClick = { if (savedId == null && !drag.active) onEmptyWidget(globalIndex) })
                .background(if (highlighted) colors.glassTint.copy(alpha = DROP_FILL) else Color.Transparent,
                    DuoTokens.radius.tile)
                .border(if (highlighted) 2.dp else 0.dp,
                    if (highlighted) colors.specular.copy(alpha = DROP_EDGE) else Color.Transparent,
                    DuoTokens.radius.tile),
                contentAlignment = Alignment.TopCenter) {
                if (drag.active && drag.source?.appId != null && (gap || previewId == null)) SlotPlaceholder(
                    size = iconSize, emphasized = gap,
                    modifier = Modifier.testTag(if (gap) "drag-gap-home-$globalIndex" else "empty-home-slot-$globalIndex"))
            }
        }

        val ids = (if (page == -1) savedLeadingSlots + previewLeadingSlots
            else savedSlots.slicePage(pageRange) + previewSlots.slicePage(pageRange)).filterNotNull().distinct()
        ids.forEach { id ->
            val savedIndex = savedIndexOf(id) ?: -1
            val previewIndex = previewIndexOf(id) ?: -1
            val renderIndex = previewIndex.takeIf { it in pageRange } ?: savedIndex.takeIf { it in pageRange } ?: return@forEach
            val app = appsById[id] ?: return@forEach
            key(id) {
                val localIndex = renderIndex - pageStart
                val row = localIndex / columns
                val animatedOffset by animateIntOffsetAsState(
                    IntOffset(columnLeftPx(localIndex % columns).roundToInt(),
                        with(density) { rowTop(row).dp.toPx() }.roundToInt()),
                    label = "home insertion $id",
                )
                val visible = previewIndex in pageRange && renderIndex != hiddenIndex
                val opacity by animateFloatAsState(
                    if (dimDragged && id == draggedId) .28f else 1f,
                    label = "home insertion visibility $id",
                )
                Box(Modifier.offset { animatedOffset }.width(cellWidth).height(rowHeight.dp)
                    .alpha(opacity).testTag("home-app-$id"), contentAlignment = Alignment.TopCenter) {
                    if (visible) AppTile(app, iconSize,
                        editing = editing,
                        onRemove = onRemoveItem?.let { remove -> { remove(id) } },
                        onClick = { onLaunch(app, it) }, onLongClick = { onActions(app) })
                }
            }
        }
        folders.forEach { folder ->
            val savedIndex = savedIndexOf(folder.id) ?: -1
            val previewIndex = previewIndexOf(folder.id) ?: -1
            val renderIndex = previewIndex.takeIf { it in pageRange } ?: savedIndex.takeIf { it in pageRange } ?: return@forEach
            val localIndex = renderIndex - pageStart
            val row = localIndex / columns
            val x = columnLeft(localIndex % columns)
            val y = rowTop(row).dp
            FolderTile(folder, appsById, iconSize, drag, page,
                Modifier.offset(x = x, y = y).width(cellWidth).height(rowHeight.dp)
                    .testTag("home-folder-${folder.id}"),
                editing = editing,
                onRemove = onRemoveItem?.let { remove -> { remove(folder.id) } },
                onClick = { onFolder(folder.id) })
        }
        pageWidgets.forEach { placement ->
            key("widget-${placement.slot}") {
                val x = columnLeft(placement.column) + WIDGET_INSET
                // Spanning across the fold gutter keeps the widget's own edges on the grid lines,
                // so a widget either sits beside the fold or bridges it deliberately.
                val spanWidthPx = columnLeftPx(placement.column + placement.spanX) - columnLeftPx(placement.column)
                val width = (with(density) { spanWidthPx.toDp() } - WIDGET_INSET * 2).coerceAtLeast(1.dp)
                val y = rowTop(placement.row)
                val height = (rowTop(placement.row + placement.spanY) - y - WIDGET_BAND_GAP).coerceAtLeast(48f)
                if (placement == pending) GlassSurface(
                    level = GlassLevel.WIDGET, shape = DuoTokens.radius.widget,
                    modifier = Modifier.offset(x = x, y = y.dp).width(width).height(height.dp)
                        .testTag("widget-pending-${placement.slot}").semantics(mergeDescendants = true) {
                            contentDescription = "Pending ${widgets.pendingProvider?.shortClassName ?: "widget"}"
                        }) {
                    Column(Modifier.padding(DuoTokens.space.md), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(DuoTokens.space.sm))
                        Text("Finish widget setup", style = DuoTokens.type.footnote, color = colors.label1)
                    }
                } else Box(Modifier.offset(x = x, y = y.dp).width(width).height(height.dp)) {
                    MovableWidget(placement.id, placement.slot, widgets, drag, target,
                        Modifier.jiggle(editing, "widget-${placement.slot}"), page = page) { onWidget(placement.slot) }
                    if (editing && onRemoveWidget != null) {
                        RemoveBadge(
                            kind = RemovableKind.WIDGET,
                            label = remember(placement.id, widgets) { widgetLabel(placement.id, widgets) },
                            tag = "remove-widget-${placement.slot}",
                            onRemove = { onRemoveWidget(placement.slot) },
                        )
                    }
                }
            }
        }
    }
}

internal fun <T> List<T>.slicePage(range: IntRange): List<T> =
    if (isEmpty() || range.first >= size) emptyList() else subList(range.first, minOf(range.last + 1, size))

/** The top band is two rows tall and holds the page's tall widgets, whatever the grid's row count. */
private const val WIDGET_BAND_ROWS = 2
private const val WIDGET_BAND_GAP = 18f
private val WIDGET_INSET = 5.dp
private const val DROP_FILL = 0.25f
private const val DROP_EDGE = 0.8f
