package com.jake.duolauncher

/**
 * Grid-change reflow (B2; FR-31, FR-33, FR-34).
 *
 * ## The one rule
 *
 * **Nothing is ever deleted.** Every app, shortcut, folder and widget that was placed before a grid
 * change is still placed after it. An item that no longer fits where it was moves to the next free
 * cell, and if the existing pages are full a new page is appended to hold it. The count of what
 * moved is returned so the caller can show "Moved N items to a new page" with Undo (error table,
 * FR-33); undo itself is the model's existing layout-undo, which restores the whole previous
 * layout including its grid.
 *
 * ## Order of operations
 *
 * Widgets are reflowed first because they reserve rectangles that cells cannot occupy. Only then
 * are apps placed, so an app can never be assigned a cell a widget is about to claim.
 */

/**
 * What a widget's provider will allow, in cells (FR-34).
 *
 * Supplied by the caller because the provider's real limits come from `AppWidgetProviderInfo` via
 * [widgetSpanConstraints], which needs display metrics this pure layer does not have. The default
 * is "no provider opinion", which is also the correct behaviour for Duo's own built-in widgets.
 */
data class WidgetSpanLimits(
    val minSpanX: Int = 1,
    val minSpanY: Int = 1,
    val maxSpanX: Int = Int.MAX_VALUE,
    val maxSpanY: Int = Int.MAX_VALUE,
)

/** The result of a grid change: the new layout plus what had to be disturbed to get there. */
data class ReflowOutcome(
    val layout: DuoLayout,
    /** Apps, shortcuts and folders that could not stay in their old cell. */
    val movedItems: Int = 0,
    /** Widgets that kept their position but had to shrink. */
    val clampedWidgets: Int = 0,
    /** Widgets that had to be relocated, including onto an appended page. */
    val movedWidgets: Int = 0,
) {
    /** What the snackbar counts (FR-33). */
    val movedCount: Int get() = movedItems + movedWidgets
}

/**
 * Re-lays [layout] onto [target], preserving every placement.
 *
 * Items whose old row and column still exist keep them, so a grid change that only adds space
 * leaves the screen looking untouched. Everything else is re-placed in a stable order — by page,
 * then by cell — so the result is deterministic and an undo/redo round trip is exact.
 */
fun reflowLayout(
    layout: DuoLayout,
    target: GridSpec,
    providerLimits: (Int) -> WidgetSpanLimits = { WidgetSpanLimits() },
): ReflowOutcome {
    val to = target.sanitized()
    val from = layout.grid
    if (from == to) return ReflowOutcome(layout)

    val reflowedWidgets = reflowWidgets(layout, from, to, providerLimits)
    val reserved = reflowedWidgets.placements
        .flatMapTo(mutableSetOf()) { it.coveredIndices(to) }

    // Ordinary pages.
    val occupied = mutableMapOf<Int, String>()
    val displaced = mutableListOf<String>()
    val pageCount = maxOf(1, homePageCount(layout.slots.size, from))
    for (page in 0 until pageCount) {
        for (local in 0 until from.cells) {
            val id = layout.slots.getOrNull(page * from.cells + local) ?: continue
            val row = local / from.columns
            val column = local % from.columns
            val index = if (row < to.rows && column < to.columns) {
                page * to.cells + (row * to.columns + column)
            } else {
                -1
            }
            if (index >= 0 && index !in reserved && index !in occupied) occupied[index] = id else displaced += id
        }
    }

    // The leading workspace keeps its own page and is remapped the same way.
    val leading = MutableList<String?>(to.cells) { null }
    val displacedLeading = mutableListOf<String>()
    layout.leadingSlots.forEachIndexed { local, id ->
        if (id == null) return@forEachIndexed
        val row = local / from.columns
        val column = local % from.columns
        val cell = if (row < to.rows && column < to.columns) row * to.columns + column else -1
        val blocked = cell >= 0 && homeCellIndex(-1, cell, to) in reserved
        if (cell >= 0 && !blocked && leading[cell] == null) leading[cell] = id else displacedLeading += id
    }
    // A displaced leading item stays on the leading page while it has room; it only falls through
    // to the ordinary pages when that page is genuinely full, and it is never dropped.
    displacedLeading.forEach { id ->
        val free = (0 until to.cells).firstOrNull {
            leading[it] == null && homeCellIndex(-1, it, to) !in reserved
        }
        if (free != null) leading[free] = id else displaced += id
    }

    // Re-place everything that lost its cell, filling gaps before appending a page.
    // An item that merely shifted because the row stride changed is not counted: what FR-33's
    // snackbar reports is the items that lost their cell, which is exactly `displaced`.
    val movedItems = displaced.size
    var scan = 0
    displaced.forEach { id ->
        while (scan in reserved || occupied.containsKey(scan)) scan++
        occupied[scan] = id
        scan++
    }

    val highest = (occupied.keys.maxOrNull() ?: -1)
    val slots = MutableList<String?>(highest + 1) { null }
    occupied.forEach { (index, id) -> slots[index] = id }

    return ReflowOutcome(
        layout = layout.copy(
            grid = to,
            slots = slots.dropLastWhile { it == null },
            leadingSlots = leading,
            widgetPlacements = reflowedWidgets.placements,
        ).withPageIds(),
        movedItems = movedItems,
        clampedWidgets = reflowedWidgets.clamped,
        movedWidgets = reflowedWidgets.moved,
    )
}

private class ReflowedWidgets(
    val placements: List<WidgetPlacement>,
    val clamped: Int,
    val moved: Int,
)

/**
 * FR-34: a widget that no longer fits shrinks to the largest span its provider allows that does
 * fit. If no allowed span fits where it is, it is relocated — to a free rectangle on its own page
 * if there is one, otherwise onto a new page appended after the last.
 */
private fun reflowWidgets(
    layout: DuoLayout,
    from: GridSpec,
    to: GridSpec,
    providerLimits: (Int) -> WidgetSpanLimits,
): ReflowedWidgets {
    val placed = mutableListOf<WidgetPlacement>()
    var clamped = 0
    var moved = 0
    // A stable order keeps the outcome deterministic regardless of how the list was built.
    val ordered = layout.widgetPlacements.sortedWith(compareBy({ it.page }, { it.row }, { it.column }, { it.slot }))
    val pageCount = maxOf(
        homePageCount(layout.slots.size, from),
        layout.widgetPlacements.filter { it.page >= 0 }.maxOfOrNull { it.page + 1 } ?: 1,
    )
    var appendPage = pageCount

    ordered.forEach { placement ->
        val limits = providerLimits(placement.slot)
        val maxX = minOf(placement.spanX, to.columns, limits.maxSpanX).coerceAtLeast(1)
        val maxY = minOf(placement.spanY, to.rows, limits.maxSpanY).coerceAtLeast(1)
        val minX = limits.minSpanX.coerceIn(1, to.columns)
        val minY = limits.minSpanY.coerceIn(1, to.rows)

        val keptSpan = placement.spanX == maxX && placement.spanY == maxY
        // Try the widget's own position first, largest allowed span downwards.
        val atHome = largestFitting(
            page = placement.page, column = placement.column, row = placement.row,
            maxX = maxX, maxY = maxY, minX = minX, minY = minY, grid = to, taken = placed,
        )
        if (atHome != null) {
            val next = placement.copy(spanX = atHome.first, spanY = atHome.second)
            if (!keptSpan || next.spanX != placement.spanX || next.spanY != placement.spanY) clamped++
            placed += next
            return@forEach
        }

        // Then anywhere on its own page, then on a freshly appended page.
        val relocated = firstFreeRect(placement.page, maxX, maxY, minX, minY, to, placed)
            ?: run {
                val page = appendPage++
                firstFreeRect(page, maxX, maxY, minX, minY, to, placed)
            }
        if (relocated != null) {
            val next = placement.copy(
                page = relocated.page, column = relocated.column, row = relocated.row,
                spanX = relocated.spanX, spanY = relocated.spanY,
            )
            if (next.spanX != placement.spanX || next.spanY != placement.spanY) clamped++
            moved++
            placed += next
        } else {
            // Nothing fits anywhere, which can only happen if the provider's minimum exceeds the
            // whole grid. Keep the placement rather than delete the user's widget: it is clamped to
            // the grid and parked on its own appended page.
            val page = appendPage++
            placed += placement.copy(page = page, column = 0, row = 0, spanX = maxX, spanY = maxY)
            moved++
        }
    }
    return ReflowedWidgets(placed.sortedBy { it.slot }, clamped, moved)
}

/** The largest allowed span that fits at an exact position without overlapping what is placed. */
private fun largestFitting(
    page: Int, column: Int, row: Int,
    maxX: Int, maxY: Int, minX: Int, minY: Int,
    grid: GridSpec, taken: List<WidgetPlacement>,
): Pair<Int, Int>? {
    if (column >= grid.columns || row >= grid.rows) return null
    for (spanX in maxX downTo minX) {
        for (spanY in maxY downTo minY) {
            if (column + spanX > grid.columns || row + spanY > grid.rows) continue
            val candidate = WidgetPlacement(0, 0, page, column, row, spanX, spanY)
            if (taken.none { rectOverlaps(it, candidate) }) return spanX to spanY
        }
    }
    return null
}

private fun firstFreeRect(
    page: Int, maxX: Int, maxY: Int, minX: Int, minY: Int,
    grid: GridSpec, taken: List<WidgetPlacement>,
): WidgetPlacement? {
    for (row in 0 until grid.rows) {
        for (column in 0 until grid.columns) {
            val span = largestFitting(page, column, row, maxX, maxY, minX, minY, grid, taken) ?: continue
            return WidgetPlacement(0, 0, page, column, row, span.first, span.second)
        }
    }
    return null
}

private fun rectOverlaps(a: WidgetPlacement, b: WidgetPlacement) = a.page == b.page &&
    a.column < b.column + b.spanX && b.column < a.column + a.spanX &&
    a.row < b.row + b.spanY && b.row < a.row + a.spanY
