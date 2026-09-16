package com.jake.duolauncher

/**
 * The default grid, and the one every pre-schema-9 install used. These constants stay 4 and 6 so
 * that the Compose layer, the layout backup format and the existing test suite keep addressing the
 * grid exactly as before; a configurable grid arrives through [GridSpec] instead (FR-31).
 */
const val GRID_COLUMNS = 4
const val GRID_ROWS = 6
const val HOME_CELLS = GRID_COLUMNS * GRID_ROWS
const val EMPTY_WIDGET = -1
const val CLOCK_WIDGET = -2
const val DATE_WIDGET = -3
const val INFO_WIDGET = -4
const val NEEDS_BINDING_WIDGET = -5

/**
 * A Home grid size (FR-31: 4–8 columns and rows, set separately per layout).
 *
 * The grid travels with the layout rather than being a global constant, so placement, reflow and
 * validation all work against the grid the layout is actually stored with.
 */
data class GridSpec(val columns: Int, val rows: Int) {
    /** Cells per page, and therefore the stride of a flat cell address. */
    val cells: Int get() = columns * rows

    fun sanitized(): GridSpec = GridSpec(columns.coerceIn(4, 8), rows.coerceIn(4, 8))
}

/** 4×6: what every install had before schema 9, and what an upgrade migrates to (FR-35). */
val DEFAULT_GRID = GridSpec(GRID_COLUMNS, GRID_ROWS)

data class WidgetPlacement(val slot: Int, val id: Int, val page: Int, val column: Int, val row: Int, val spanX: Int, val spanY: Int)
data class WidgetRestore(val slot: Int, val providerComponent: String, val userSerial: Long, val title: String,
    val profileLabel: String, val isWork: Boolean = false, val sourceScope: String? = null)

val DEFAULT_WIDGET_PLACEMENTS = listOf(
    WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2),
    WidgetPlacement(1, DATE_WIDGET, 0, 2, 0, 2, 2),
)

/**
 * Cell addressing. Page `-1` is the leading workspace, which is why these use floor semantics
 * rather than truncating division. (Pager page `-1` separately means Discover; see
 * `docs/architecture.md`.)
 */
fun homeCellPage(index: Int, grid: GridSpec = DEFAULT_GRID) = Math.floorDiv(index, grid.cells)
fun homeCellLocal(index: Int, grid: GridSpec = DEFAULT_GRID) = Math.floorMod(index, grid.cells)
fun homeCellIndex(page: Int, local: Int, grid: GridSpec = DEFAULT_GRID): Int {
    require(page >= -1 && local in 0 until grid.cells)
    return page * grid.cells + local
}

private fun normalizedLeadingSlots(slots: List<String?>, grid: GridSpec) =
    normalizeHomeSlots(slots.take(grid.cells)).let { it + List(grid.cells - it.size) { null } }

/** Nulls are intentional empty home cells. Only unused trailing cells are removed. */
fun normalizeHomeSlots(slots: List<String?>): List<String?> {
    val seen = mutableSetOf<String>()
    return slots.map { it?.takeIf { id -> id.isNotBlank() && seen.add(id) } }.dropLastWhile { it == null }
}

fun reconcileHomeSlots(slots: List<String?>, installed: Set<String>) =
    normalizeHomeSlots(slots.map { it?.takeIf(installed::contains) })

/**
 * One layout's placements.
 *
 * [grid] is last and defaults to [DEFAULT_GRID] so every existing construction site keeps its
 * meaning; a layout stored on another grid carries that grid with it and every rule below reads it
 * from here rather than from the global constants.
 */
data class HomeLayout(
    val slots: List<String?>,
    val dock: List<String?>,
    val widgetPlacements: List<WidgetPlacement> = emptyList(),
    val folders: List<FolderEntry> = emptyList(),
    val widgetRestores: List<WidgetRestore> = emptyList(),
    val leadingSlots: List<String?> = List(HOME_CELLS) { null },
    val grid: GridSpec = DEFAULT_GRID,
) {
    val widgets: List<Int> get() {
        val last = widgetPlacements.maxOfOrNull { it.slot } ?: -1
        return List(maxOf(3, last + 1)) { slot -> placement(slot)?.id ?: EMPTY_WIDGET }
    }
    fun placement(slot: Int) = widgetPlacements.firstOrNull { it.slot == slot }
    fun folder(id: String) = folders.firstOrNull { it.id == id }
    fun widgetRestore(slot: Int) = widgetRestores.firstOrNull { it.slot == slot }
    val pageCount get() = maxOf(homePageCount(slots.size, grid),
        widgetPlacements.filter { it.page >= 0 }.maxOfOrNull { it.page + 1 } ?: 1)
    fun slotAt(index: Int): String? = when (homeCellPage(index, grid)) {
        -1 -> leadingSlots.getOrNull(homeCellLocal(index, grid))
        in 0..Int.MAX_VALUE -> slots.getOrNull(index)
        else -> null
    }
    fun indexOfShortcut(id: String): Int? {
        val leading = leadingSlots.indexOf(id)
        if (leading >= 0) return homeCellIndex(-1, leading, grid)
        return slots.indexOf(id).takeIf { it >= 0 }
    }
    fun slotsForPage(page: Int): List<String?> = when {
        page == -1 -> normalizedLeadingSlots(leadingSlots, grid)
        page >= 0 -> List(grid.cells) { local -> slots.getOrNull(homeCellIndex(page, local, grid)) }
        else -> emptyList()
    }
    fun withSlot(index: Int, value: String?): HomeLayout = if (homeCellPage(index, grid) == -1) {
        copy(leadingSlots = normalizedLeadingSlots(leadingSlots, grid).toMutableList().apply { this[homeCellLocal(index, grid)] = value })
    } else {
        val next = slots.toMutableList().apply { while (size <= index) add(null); this[index] = value }
        copy(slots = next.dropLastWhile { it == null })
    }
}

sealed interface DropTarget {
    data class Home(val index: Int) : DropTarget
    data class Dock(val index: Int) : DropTarget
    data class Library(val id: String) : DropTarget
    data class Widget(val index: Int) : DropTarget
    data class Folder(val id: String) : DropTarget
    data object Remove : DropTarget
}

fun canPlaceInDock(layout: HomeLayout, id: String): Boolean =
    id.isNotBlank() && !isReservedFolderId(id) && layout.folders.none { id in it.appIds } &&
        (id in layout.dock || layout.dock.any { it == null })

fun WidgetPlacement.coveredIndices(grid: GridSpec = DEFAULT_GRID): Set<Int> {
    if (page < -1) return emptySet()
    return buildSet {
        repeat(spanY) { y -> repeat(spanX) { x ->
            if (row + y < grid.rows) add(homeCellIndex(page, (row + y) * grid.columns + column + x, grid))
        } }
    }
}

private fun WidgetPlacement.valid(grid: GridSpec) =
    slot >= 0 && id != EMPTY_WIDGET && page >= -1 && column >= 0 && row >= 0 &&
        spanX in 1..grid.columns && spanY in 1..grid.rows && column + spanX <= grid.columns &&
        row + spanY <= grid.rows

private fun widgetCells(layout: HomeLayout, exceptSlot: Int? = null) = layout.widgetPlacements
    .filter { it.slot != exceptSlot }.flatMapTo(mutableSetOf()) { it.coveredIndices(layout.grid) }

private fun overlaps(a: WidgetPlacement, b: WidgetPlacement) = a.page == b.page &&
    a.column < b.column + b.spanX && b.column < a.column + a.spanX &&
    a.row < b.row + b.spanY && b.row < a.row + a.spanY

/** Builds a non-persistable placement draft when the requested rectangle is available. */
fun widgetCandidate(layout: HomeLayout, slot: Int, targetIndex: Int, spanX: Int, spanY: Int): WidgetPlacement? {
    val grid = layout.grid
    val page = homeCellPage(targetIndex, grid)
    if (slot < 0 || page !in -1..layout.pageCount) return null
    val local = homeCellLocal(targetIndex, grid)
    val candidate = WidgetPlacement(slot, EMPTY_WIDGET, page,
        local % grid.columns, local / grid.columns, spanX, spanY)
    if (spanX !in 1..grid.columns || spanY !in 1..grid.rows ||
        candidate.column + spanX > grid.columns || candidate.row + spanY > grid.rows) return null
    if (layout.widgetPlacements.any { it.slot != slot && overlaps(it, candidate) }) return null
    if (candidate.coveredIndices(grid).any { layout.slotAt(it) != null }) return null
    return candidate
}

/** Moves use insertion order and transfer shortcuts between Home and the dock. */
fun dropApp(layout: HomeLayout, id: String, target: DropTarget): HomeLayout {
    if (id.isBlank() || layout.folders.any { id in it.appIds }) return layout
    val grid = layout.grid
    return when (target) {
        is DropTarget.Home -> {
            val blocked = widgetCells(layout)
            val targetPage = homeCellPage(target.index, grid)
            if (targetPage !in -1..layout.pageCount || target.index in blocked) return layout
            if (targetPage == -1) {
                val cells = normalizedLeadingSlots(layout.leadingSlots, grid).toMutableList()
                val targetLocal = homeCellLocal(target.index, grid)
                val sourceLocal = cells.indexOf(id)
                cells.indices.filter { it != sourceLocal && cells[it] == id }.forEach { cells[it] = null }
                val blockedLocal = blocked.filter { homeCellPage(it, grid) == -1 }.mapTo(mutableSetOf()) { homeCellLocal(it, grid) }
                if (targetLocal in blockedLocal) return layout
                if (sourceLocal == targetLocal) return layout
                if (sourceLocal >= 0) {
                    if (cells[targetLocal] == null) {
                        cells[sourceLocal] = null
                        cells[targetLocal] = id
                        return layout.copy(leadingSlots = cells,
                            slots = layout.slots.map { it?.takeUnless(id::equals) }.dropLastWhile { it == null },
                            dock = layout.dock.map { it?.takeUnless(id::equals) })
                    }
                    val usable = (minOf(sourceLocal, targetLocal)..maxOf(sourceLocal, targetLocal)).filterNot { it in blockedLocal }
                    val from = usable.indexOf(sourceLocal); val to = usable.indexOf(targetLocal)
                    if (from < 0 || to < 0) return layout
                    if (from < to) for (position in from until to) cells[usable[position]] = cells[usable[position + 1]]
                    else for (position in from downTo to + 1) cells[usable[position]] = cells[usable[position - 1]]
                    cells[targetLocal] = id
                } else if (cells[targetLocal] == null) cells[targetLocal] = id else {
                    val later = (targetLocal + 1 until grid.cells).firstOrNull { it !in blockedLocal && cells[it] == null }
                    val earlier = (targetLocal - 1 downTo 0).firstOrNull { it !in blockedLocal && cells[it] == null }
                    val vacancy = later ?: earlier ?: return layout
                    val usable = (minOf(vacancy, targetLocal)..maxOf(vacancy, targetLocal)).filterNot { it in blockedLocal }
                    if (vacancy > targetLocal) for (position in usable.lastIndex downTo 1) cells[usable[position]] = cells[usable[position - 1]]
                    else for (position in 0 until usable.lastIndex) cells[usable[position]] = cells[usable[position + 1]]
                    cells[targetLocal] = id
                }
                return layout.copy(leadingSlots = cells,
                    slots = layout.slots.map { it?.takeUnless(id::equals) }.dropLastWhile { it == null },
                    dock = layout.dock.map { it?.takeUnless(id::equals) })
            }
            val slots = layout.slots.toMutableList()
            val source = slots.indexOf(id)
            slots.indices.filter { it != source && slots[it] == id }.forEach { slots[it] = null }
            while (slots.size <= target.index) slots.add(null)
            val occupied = slots[target.index] != null
            when {
                source == target.index -> Unit
                source >= 0 && !occupied -> { slots[source] = null; slots[target.index] = id }
                source >= 0 -> {
                    val usable = (minOf(source, target.index)..maxOf(source, target.index)).filterNot { it in blocked }
                    val from = usable.indexOf(source)
                    val to = usable.indexOf(target.index)
                    if (from < 0 || to < 0) return layout
                    if (from < to) for (position in from until to) slots[usable[position]] = slots[usable[position + 1]]
                    else for (position in from downTo to + 1) slots[usable[position]] = slots[usable[position - 1]]
                    slots[target.index] = id
                }
                else -> {
                    if (!occupied) slots[target.index] = id else {
                        var vacancy = target.index + 1
                        val limit = grid.cells * (layout.pageCount + 1)
                        while (vacancy < limit && (vacancy in blocked || slots.getOrNull(vacancy) != null)) vacancy++
                        if (vacancy >= limit) return layout
                        while (slots.size <= vacancy) slots.add(null)
                        val usable = (target.index..vacancy).filterNot { it in blocked }
                        for (position in usable.lastIndex downTo 1) slots[usable[position]] = slots[usable[position - 1]]
                        slots[target.index] = id
                    }
                }
            }
            layout.copy(slots = slots.dropLastWhile { it == null }, dock = layout.dock.map { it?.takeUnless { dockId -> dockId == id } },
                leadingSlots = normalizedLeadingSlots(layout.leadingSlots, grid).map { it?.takeUnless(id::equals) })
        }
        is DropTarget.Dock -> {
            if (target.index !in layout.dock.indices || !canPlaceInDock(layout, id)) return layout
            val dock = layout.dock.toMutableList()
            val source = dock.indexOf(id)
            dock.indices.filter { it != source && dock[it] == id }.forEach { dock[it] = null }
            val occupied = dock[target.index] != null
            when {
                source == target.index -> Unit
                source >= 0 && !occupied -> { dock[source] = null; dock[target.index] = id }
                source >= 0 -> { dock.removeAt(source); dock.add(target.index, id) }
                !occupied -> dock[target.index] = id
                else -> {
                    val later = (target.index + 1 until dock.size).firstOrNull { dock[it] == null }
                    val earlier = (target.index - 1 downTo 0).firstOrNull { dock[it] == null }
                    when {
                        later != null -> { for (i in later downTo target.index + 1) dock[i] = dock[i - 1]; dock[target.index] = id }
                        earlier != null -> { for (i in earlier until target.index) dock[i] = dock[i + 1]; dock[target.index] = id }
                        else -> return layout
                    }
                }
            }
            layout.copy(slots = layout.slots.map { it?.takeUnless { app -> app == id } }.dropLastWhile { it == null }, dock = dock,
                leadingSlots = normalizedLeadingSlots(layout.leadingSlots, grid).map { it?.takeUnless(id::equals) })
        }
        else -> layout
    }
}

fun placeWidget(layout: HomeLayout, placement: WidgetPlacement): HomeLayout {
    if (!placement.valid(layout.grid)) return replaceWidgetAtSameFootprint(layout, placement)
    if (placement.id == NEEDS_BINDING_WIDGET && layout.widgetRestore(placement.slot) == null) return layout
    val without = layout.widgetPlacements.filterNot { it.slot == placement.slot }
    if (without.any { overlaps(it, placement) }) return layout
    if (placement.coveredIndices(layout.grid).any { layout.slotAt(it) != null }) return layout
    return layout.copy(widgetPlacements = (without + placement).sortedBy { it.slot },
        widgetRestores = if (placement.id == NEEDS_BINDING_WIDGET) layout.widgetRestores
            else layout.widgetRestores.filterNot { it.slot == placement.slot })
}

/** Rebinds retained legacy overflow without making that footprint newly placeable. */
fun replaceWidgetAtSameFootprint(layout: HomeLayout, placement: WidgetPlacement): HomeLayout {
    val existing = layout.placement(placement.slot) ?: return layout
    val sameFootprint = placement.page == existing.page && placement.column == existing.column &&
        placement.row == existing.row && placement.spanX == existing.spanX && placement.spanY == existing.spanY
    val retainedSpecial = existing.page > 0 && existing.slot / 3 == existing.page && existing.slot % 3 == 2 &&
        existing.column == 0 && existing.row == layout.grid.rows && existing.spanX == layout.grid.columns && existing.spanY == 4
    if (!sameFootprint || !retainedSpecial || placement.id == EMPTY_WIDGET ||
        (placement.id == NEEDS_BINDING_WIDGET && layout.widgetRestore(placement.slot) == null)) return layout
    return layout.copy(widgetPlacements = layout.widgetPlacements.map { if (it.slot == placement.slot) placement else it },
        widgetRestores = if (placement.id == NEEDS_BINDING_WIDGET) layout.widgetRestores
            else layout.widgetRestores.filterNot { it.slot == placement.slot })
}

fun moveWidget(layout: HomeLayout, slot: Int, index: Int): HomeLayout {
    val old = layout.placement(slot) ?: return layout
    val grid = layout.grid
    val page = homeCellPage(index, grid)
    if (page !in -1..layout.pageCount) return layout
    val local = homeCellLocal(index, grid)
    return placeWidget(layout, old.copy(page = page, column = local % grid.columns,
        row = local / grid.columns, spanY = old.spanY.coerceAtMost(grid.rows)))
}

fun resizeWidget(layout: HomeLayout, slot: Int, spanX: Int, spanY: Int): HomeLayout {
    val old = layout.placement(slot) ?: return layout
    return placeWidget(layout, old.copy(spanX = spanX, spanY = spanY))
}

/** Remove only the shortcut/placement, never the installed app or widget binding. */
fun removePlacement(layout: HomeLayout, source: DropTarget): HomeLayout = when (source) {
    is DropTarget.Home -> if (layout.slotAt(source.index)?.let(::isFolderId) == true) layout
        else layout.withSlot(source.index, null)
    is DropTarget.Dock -> layout.copy(dock = layout.dock.mapIndexed { i, id -> if (i == source.index) null else id })
    is DropTarget.Widget -> layout.copy(widgetPlacements = layout.widgetPlacements.filterNot { it.slot == source.index },
        widgetRestores = layout.widgetRestores.filterNot { it.slot == source.index })
    else -> layout
}

fun pinHomeApp(slots: List<String?>, id: String, pinned: Boolean, blocked: Set<Int> = emptySet()): List<String?> {
    if (!pinned) return normalizeHomeSlots(slots.map { if (it == id) null else it })
    if (id in slots) return slots
    val gap = slots.indices.firstOrNull { slots[it] == null && it !in blocked }
    if (gap != null) return slots.toMutableList().apply { set(gap, id) }
    var index = slots.size
    while (index in blocked) index++
    return slots + List(index - slots.size) { null } + id
}

fun migrateSchema5Apps(slots: List<String?>): List<String?> {
    if (slots.isEmpty()) return emptyList()
    val result = MutableList(((slots.lastIndex / 16) + 1) * HOME_CELLS) { null as String? }
    slots.forEachIndexed { index, id -> result[index / 16 * HOME_CELLS + 8 + index % 16] = id }
    return normalizeHomeSlots(result)
}

// ---------------------------------------------------------------------------
// Page overview edits (FR-47). Pure, so every rule is unit tested.
// ---------------------------------------------------------------------------

/**
 * Moves the page at position [from] to position [to], carrying its contents with it.
 *
 * A page is not just a slice of [DuoLayout.slots]: its widgets name it by number and its identity is
 * its entry in [DuoLayout.pageIds]. All three move together, so a reorder is invisible to anything
 * keyed on the page's id — which is exactly what stops `hiddenPageIds` from following the *position*
 * and hiding an unrelated page after a move.
 *
 * Out-of-range positions are clamped rather than throwing, because the overview's move buttons sit
 * at the ends of a row and a double tap must not take Home down.
 */
fun reorderHomePages(layout: DuoLayout, from: Int, to: Int): DuoLayout {
    val pages = layout.pageCount
    if (pages < 2) return layout
    val source = from.coerceIn(0, pages - 1)
    val destination = to.coerceIn(0, pages - 1)
    if (source == destination) return layout
    val order = (0 until pages).toMutableList().apply { add(destination, removeAt(source)) }
    val grid = layout.grid
    val slots = MutableList<String?>(pages * grid.cells) { null }
    order.forEachIndexed { newPage, oldPage ->
        repeat(grid.cells) { local ->
            slots[newPage * grid.cells + local] = layout.slots.getOrNull(oldPage * grid.cells + local)
        }
    }
    val movedTo = order.withIndex().associate { (newPage, oldPage) -> oldPage to newPage }
    val ids = layout.withPageIds().pageIds
    return layout.copy(
        slots = slots.dropLastWhile { it == null },
        // The leading page is page -1 and is never part of the ordinary page order.
        widgetPlacements = layout.widgetPlacements
            .map { if (it.page >= 0) it.copy(page = movedTo[it.page] ?: it.page) else it }
            .sortedBy { it.slot },
        pageIds = order.map { ids.getOrNull(it) ?: it },
    )
}

/**
 * Deletes the page whose stable id is [pageId] (FR-47).
 *
 * Refuses unless the page is genuinely empty and is not the last one, so this can never destroy a
 * placement: [canDeletePage] gates the button, and this gates the edit. Later pages shift down, and
 * the deleted page's hidden-state entry goes with it rather than being left to collide with a future
 * page that is minted the same id.
 */
fun deleteHomePage(layout: DuoLayout, pageId: Int): DuoLayout {
    val withIds = layout.withPageIds()
    val page = withIds.pageIds.indexOf(pageId)
    if (page < 0 || withIds.pageCount <= 1) return layout
    val grid = withIds.grid
    val start = page * grid.cells
    val occupied = (0 until grid.cells).any { withIds.slots.getOrNull(start + it) != null } ||
        withIds.widgetPlacements.any { it.page == page }
    if (occupied) return layout
    val slots = withIds.slots.toMutableList()
    repeat(minOf(grid.cells, (slots.size - start).coerceAtLeast(0))) { slots.removeAt(start) }
    return withIds.copy(
        slots = slots.dropLastWhile { it == null },
        widgetPlacements = withIds.widgetPlacements.map { if (it.page > page) it.copy(page = it.page - 1) else it },
        pageIds = withIds.pageIds.filterIndexed { index, _ -> index != page },
        hiddenPageIds = withIds.hiddenPageIds - pageId,
    )
}

fun migrateSchema5Widgets(widgets: List<Int>): List<WidgetPlacement> = buildList {
    widgets.forEachIndexed { slot, id ->
        if (id == EMPTY_WIDGET) return@forEachIndexed
        val page = slot / 3
        when (slot % 3) {
            0 -> add(WidgetPlacement(slot, id, page, 0, 0, 2, 2))
            1 -> add(WidgetPlacement(slot, id, page, 2, 0, 2, 2))
            2 -> if (page == 0) add(WidgetPlacement(slot, id, -1, 0, 0, 4, 6))
                else add(WidgetPlacement(slot, id, page, 0, 6, 4, 4))
        }
    }
}
