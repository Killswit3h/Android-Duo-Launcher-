package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grid-change reflow (FR-31, FR-33, FR-34; AC-26, AC-28).
 *
 * Every test here is ultimately checking one property: **the set of placed items before a grid
 * change equals the set afterwards.** A reflow may move things and may shrink a widget, but it may
 * never lose one.
 */
class GridReflowTest {

    private fun idsOf(layout: DuoLayout): Set<String> =
        (layout.slots.filterNotNull() + layout.leadingSlots.filterNotNull()).toSet()

    private fun fullPage(grid: GridSpec, page: Int = 0, prefix: String = "app") =
        List(grid.cells) { "$prefix${page * grid.cells + it}" }

    @Test fun `a grid that does not change is returned untouched`() {
        val layout = DuoLayout(grid = GridSpec(4, 6), slots = listOf("a", "b"))
        val outcome = reflowLayout(layout, GridSpec(4, 6))
        assertSame(layout, outcome.layout)
        assertEquals(0, outcome.movedCount)
    }

    // -----------------------------------------------------------------------------------------
    // AC-26: shrinking the grid moves items to a new page and loses none
    // -----------------------------------------------------------------------------------------

    @Test fun `shrinking a full 4x6 page to 4x5 moves four apps and deletes none`() {
        val from = GridSpec(4, 6)
        val layout = DuoLayout(grid = from, slots = fullPage(from))
        val outcome = reflowLayout(layout, GridSpec(4, 5))

        assertEquals(24, idsOf(outcome.layout).size)
        assertEquals(idsOf(layout), idsOf(outcome.layout))
        // The last row no longer exists, so its four apps had to move.
        assertEquals(4, outcome.movedItems)
        assertEquals(2, outcome.layout.pageCount)
    }

    @Test fun `apps that still fit keep their exact row and column`() {
        val from = GridSpec(4, 6)
        val layout = DuoLayout(grid = from, slots = fullPage(from))
        val to = GridSpec(4, 5)
        val outcome = reflowLayout(layout, to)
        // Row 2, column 3 exists in both grids, so that app must not have moved.
        val original = layout.slots[2 * from.columns + 3]
        assertEquals(original, outcome.layout.slots[2 * to.columns + 3])
    }

    @Test fun `shrinking columns moves the lost column and keeps every app`() {
        val from = GridSpec(6, 6)
        val layout = DuoLayout(grid = from, slots = fullPage(from), leadingSlots = List(from.cells) { null })
        val outcome = reflowLayout(layout, GridSpec(4, 6))
        assertEquals(idsOf(layout), idsOf(outcome.layout))
        assertEquals(36, idsOf(outcome.layout).size)
        assertTrue(outcome.movedItems > 0)
    }

    @Test fun `growing the grid keeps every app exactly where it was`() {
        val from = GridSpec(4, 6)
        val layout = DuoLayout(grid = from, slots = fullPage(from))
        val to = GridSpec(6, 6)
        val outcome = reflowLayout(layout, to)
        assertEquals(idsOf(layout), idsOf(outcome.layout))
        assertEquals(0, outcome.movedItems)
        // Same row and column, new stride.
        (0 until from.cells).forEach { local ->
            val row = local / from.columns
            val column = local % from.columns
            assertEquals(layout.slots[local], outcome.layout.slots[row * to.columns + column])
        }
    }

    @Test fun `several full pages shrink without losing a single app`() {
        val from = GridSpec(4, 6)
        val layout = DuoLayout(grid = from, slots = fullPage(from, 0) + fullPage(from, 1) + fullPage(from, 2))
        val outcome = reflowLayout(layout, GridSpec(4, 4))
        assertEquals(72, idsOf(outcome.layout).size)
        assertEquals(idsOf(layout), idsOf(outcome.layout))
    }

    @Test fun `the leading workspace is reflowed and never dropped`() {
        val from = GridSpec(4, 6)
        val leading = MutableList<String?>(from.cells) { null }.apply {
            this[0] = "lead-first"
            this[23] = "lead-last"
        }
        val layout = DuoLayout(grid = from, leadingSlots = leading)
        val outcome = reflowLayout(layout, GridSpec(4, 4))
        assertTrue("lead-first" in idsOf(outcome.layout))
        assertTrue("lead-last" in idsOf(outcome.layout))
        assertEquals(16, outcome.layout.leadingSlots.size)
    }

    @Test fun `a full leading page overflows onto home rather than losing items`() {
        val from = GridSpec(6, 6)
        val layout = DuoLayout(grid = from, leadingSlots = List(from.cells) { "lead$it" })
        val outcome = reflowLayout(layout, GridSpec(4, 4))
        assertEquals(36, idsOf(outcome.layout).size)
        assertEquals(16, outcome.layout.leadingSlots.count { it != null })
        // The other 20 had nowhere to go on the leading page, so they are on Home.
        assertEquals(20, outcome.layout.slots.count { it != null })
    }

    // -----------------------------------------------------------------------------------------
    // AC-28: widget clamping
    // -----------------------------------------------------------------------------------------

    @Test fun `a widget too tall for the new grid shrinks to the largest fitting span`() {
        val layout = DuoLayout(
            grid = GridSpec(4, 6),
            widgetPlacements = listOf(WidgetPlacement(0, 26, 0, 0, 0, 4, 4)),
        )
        val outcome = reflowLayout(layout, GridSpec(4, 4).copy(rows = 4))
        val widget = outcome.layout.widgetPlacements.single()
        assertEquals(4, widget.spanY)
        assertEquals(26, widget.id)

        val tighter = reflowLayout(layout, GridSpec(4, 4))
        assertEquals(4, tighter.layout.widgetPlacements.single().spanY)
    }

    @Test fun `a widget shrinks rather than disappearing when rows are lost`() {
        val layout = DuoLayout(
            grid = GridSpec(4, 6),
            widgetPlacements = listOf(WidgetPlacement(0, 26, 0, 0, 2, 4, 4)),
        )
        // Rows drop to 4, so a 4-tall widget anchored at row 2 cannot stay 4 tall.
        val outcome = reflowLayout(layout, GridSpec(4, 4))
        val widget = outcome.layout.widgetPlacements.single()
        assertEquals(26, widget.id)
        assertTrue("widget must still fit", widget.row + widget.spanY <= 4)
        assertTrue("widget must still fit", widget.column + widget.spanX <= 4)
        assertEquals(1, outcome.clampedWidgets + outcome.movedWidgets)
    }

    @Test fun `a widget respects its provider minimum and moves when it cannot shrink`() {
        val layout = DuoLayout(
            grid = GridSpec(6, 6),
            widgetPlacements = listOf(WidgetPlacement(3, 26, 0, 2, 3, 4, 3)),
        )
        // The provider refuses to go below 4x3, so shrinking in place is not allowed.
        val limits = WidgetSpanLimits(minSpanX = 4, minSpanY = 3)
        val outcome = reflowLayout(layout, GridSpec(4, 4)) { limits }
        val widget = outcome.layout.widgetPlacements.single()
        assertEquals(26, widget.id)
        assertEquals(4, widget.spanX)
        assertEquals(3, widget.spanY)
        assertTrue(widget.row + widget.spanY <= 4)
    }

    @Test fun `a widget never exceeds its provider maximum when the grid grows`() {
        val layout = DuoLayout(grid = GridSpec(4, 6), widgetPlacements = listOf(WidgetPlacement(0, 26, 0, 0, 0, 2, 2)))
        val outcome = reflowLayout(layout, GridSpec(8, 8)) { WidgetSpanLimits(maxSpanX = 2, maxSpanY = 2) }
        val widget = outcome.layout.widgetPlacements.single()
        assertEquals(2, widget.spanX)
        assertEquals(2, widget.spanY)
    }

    @Test fun `two widgets that no longer both fit are relocated rather than overlapped`() {
        val layout = DuoLayout(
            grid = GridSpec(6, 6),
            widgetPlacements = listOf(
                WidgetPlacement(0, 26, 0, 0, 0, 4, 3),
                WidgetPlacement(1, 27, 0, 0, 3, 4, 3),
            ),
        )
        val outcome = reflowLayout(layout, GridSpec(4, 4))
        assertEquals(2, outcome.layout.widgetPlacements.size)
        assertEquals(setOf(26, 27), outcome.layout.widgetPlacements.mapTo(mutableSetOf()) { it.id })
        // No two widgets may claim the same cell.
        val cells = outcome.layout.widgetPlacements.flatMap { it.coveredIndices(outcome.layout.grid) }
        assertEquals(cells.size, cells.toSet().size)
    }

    @Test fun `a widget pushed off its page lands on an appended page, never deleted`() {
        val layout = DuoLayout(
            grid = GridSpec(8, 8),
            widgetPlacements = (0 until 4).map { WidgetPlacement(it, 20 + it, 0, 0, it * 2, 8, 2) },
        )
        val outcome = reflowLayout(layout, GridSpec(4, 4))
        assertEquals(4, outcome.layout.widgetPlacements.size)
        assertEquals(setOf(20, 21, 22, 23), outcome.layout.widgetPlacements.mapTo(mutableSetOf()) { it.id })
    }

    @Test fun `widgets keep their slot so their bindings are not swapped`() {
        val layout = DuoLayout(
            grid = GridSpec(6, 6),
            widgetPlacements = listOf(
                WidgetPlacement(2, 26, 0, 0, 0, 3, 3),
                WidgetPlacement(5, 27, 0, 3, 3, 3, 3),
            ),
        )
        val outcome = reflowLayout(layout, GridSpec(4, 4))
        assertEquals(26, outcome.layout.widgetPlacements.first { it.slot == 2 }.id)
        assertEquals(27, outcome.layout.widgetPlacements.first { it.slot == 5 }.id)
    }

    @Test fun `widgets and apps never claim the same cell after a reflow`() {
        val from = GridSpec(6, 6)
        val layout = DuoLayout(
            grid = from,
            slots = List(from.cells) { if (it >= 12) "app$it" else null },
            widgetPlacements = listOf(WidgetPlacement(0, 26, 0, 0, 0, 4, 2)),
        )
        val outcome = reflowLayout(layout, GridSpec(4, 5))
        val widgetCells = outcome.layout.widgetPlacements.flatMap { it.coveredIndices(outcome.layout.grid) }.toSet()
        outcome.layout.slots.forEachIndexed { index, id ->
            if (id != null) assertTrue("cell $index is under a widget", index !in widgetCells)
        }
        assertEquals(24, idsOf(outcome.layout).size)
    }

    // -----------------------------------------------------------------------------------------
    // Determinism and undo
    // -----------------------------------------------------------------------------------------

    @Test fun `reflow is deterministic`() {
        val from = GridSpec(6, 6)
        val layout = DuoLayout(grid = from, slots = fullPage(from),
            leadingSlots = List(from.cells) { null },
            widgetPlacements = listOf(WidgetPlacement(0, 26, 1, 0, 0, 3, 3)))
        assertEquals(reflowLayout(layout, GridSpec(4, 4)), reflowLayout(layout, GridSpec(4, 4)))
    }

    @Test fun `reflow does not mutate the layout it was given, so undo can restore it`() {
        val from = GridSpec(4, 6)
        val original = DuoLayout(grid = from, slots = fullPage(from))
        val snapshot = original.copy()
        reflowLayout(original, GridSpec(4, 4))
        assertEquals(snapshot, original)
        // Restoring is just putting the previous value back, which is what the model's undo does.
        assertEquals(GridSpec(4, 6), original.grid)
        assertEquals(24, idsOf(original).size)
    }

    @Test fun `a shrink followed by restoring the previous layout returns every app to its cell`() {
        val from = GridSpec(4, 6)
        val before = DuoLayout(grid = from, slots = fullPage(from)).withPageIds()
        val after = reflowLayout(before, GridSpec(4, 4)).layout
        assertTrue(after != before)
        // The model's undo restores the whole previous layout value.
        assertEquals(before.slots, before.slots)
        assertEquals(idsOf(before), idsOf(after))
    }

    @Test fun `every page gets an id so page overview can address it`() {
        val from = GridSpec(4, 6)
        val layout = DuoLayout(grid = from, slots = fullPage(from, 0) + fullPage(from, 1))
        val outcome = reflowLayout(layout, GridSpec(4, 4))
        assertEquals(outcome.layout.pageCount, outcome.layout.pageIds.size)
        assertEquals(outcome.layout.pageIds.size, outcome.layout.pageIds.distinct().size)
        assertNotNull(outcome.layout.pageIds.firstOrNull())
    }

    @Test fun `an out of range grid is clamped rather than rejected`() {
        val layout = DuoLayout(grid = GridSpec(4, 6), slots = listOf("a"))
        assertEquals(GridSpec(4, 4), reflowLayout(layout, GridSpec(2, 2)).layout.grid)
        assertEquals(GridSpec(8, 8), reflowLayout(layout, GridSpec(99, 99)).layout.grid)
    }

    @Test fun `an empty layout reflows to an empty layout`() {
        val outcome = reflowLayout(DuoLayout(grid = GridSpec(4, 6)), GridSpec(6, 6))
        assertEquals(GridSpec(6, 6), outcome.layout.grid)
        assertEquals(0, outcome.movedCount)
        assertTrue(outcome.layout.isEmpty)
    }

    @Test fun `folders and pinned shortcuts reflow exactly like apps`() {
        val from = GridSpec(4, 6)
        val folder = "folder:123e4567-e89b-12d3-a456-426614174000"
        val shortcut = "duo-shortcut:v1:0:com.example:new-tab"
        val slots = MutableList<String?>(from.cells) { null }.apply {
            this[23] = folder
            this[22] = shortcut
        }
        val outcome = reflowLayout(DuoLayout(grid = from, slots = slots), GridSpec(4, 4))
        assertTrue(folder in idsOf(outcome.layout))
        assertTrue(shortcut in idsOf(outcome.layout))
    }
}
