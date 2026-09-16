package com.jake.duolauncher

import com.jake.duolauncher.home.todayBuiltinId
import com.jake.duolauncher.today.TodayWidgetKind
import com.jake.duolauncher.today.TodayWidgetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-57, FR-61…FR-64: stacks and the Today column may only name widgets that exist.
 *
 * The case that produced this was a v1/v2 restore. A legacy backup describes no stacks, so the
 * user's current ones are deliberately kept, but the active layout's widget placements are replaced
 * wholesale — leaving a stack naming a slot that had vanished or now held a different widget.
 * `removePlacement` produced the same shape without any restore at all, because it dropped a
 * placement and never told the stack holding it.
 *
 * These tests pin the repair rather than a rejection. Dangling stacks already exist on disk from
 * builds that shipped before this rule, so validating without pruning first would turn an ordinary
 * upgrade into "Saved Home layout could not be read" and an empty Home.
 */
class WidgetStackCoherenceTest {

    /**
     * A 2×1 widget on its own row, so several can coexist on one page without tripping
     * [validate]'s overlap rule. Slots stay within the default 4×6 grid.
     */
    private fun placement(slot: Int) =
        WidgetPlacement(slot = slot, id = slot + 100, page = 0, column = 0, row = slot, spanX = 2, spanY = 1)

    private fun setWith(vararg slots: Int) = LayoutSet(
        mirrored = DuoLayout(widgetPlacements = slots.map(::placement)),
    )

    // ---------------------------------------------------------------------------------------
    // Stacks
    // ---------------------------------------------------------------------------------------

    @Test fun `a stack whose widgets all still exist is left exactly alone`() {
        val stacks = listOf(WidgetStack("stack:a", listOf(1, 2), activeIndex = 1))
        val (pruned, _) = stackCoherence(stacks, emptyList(), setOf(1, 2))
        assertEquals(stacks, pruned)
    }

    @Test fun `a vanished member is dropped and the rest of the stack survives`() {
        val stacks = listOf(WidgetStack("stack:a", listOf(1, 2, 3)))
        val (pruned, _) = stackCoherence(stacks, emptyList(), setOf(1, 3))
        assertEquals(listOf(WidgetStack("stack:a", listOf(1, 3))), pruned)
    }

    @Test fun `a stack left with one widget becomes a plain widget`() {
        // FR-64. Only the grouping goes; the surviving placement is untouched by this function.
        val stacks = listOf(WidgetStack("stack:a", listOf(1, 2)))
        val (pruned, _) = stackCoherence(stacks, emptyList(), setOf(1))
        assertTrue(pruned.isEmpty())
    }

    @Test fun `a stack that lost every widget disappears`() {
        val stacks = listOf(WidgetStack("stack:a", listOf(1, 2)))
        val (pruned, _) = stackCoherence(stacks, emptyList(), emptySet())
        assertTrue(pruned.isEmpty())
    }

    @Test fun `the widget that was showing keeps showing when it survives`() {
        val stacks = listOf(WidgetStack("stack:a", listOf(1, 2, 3), activeIndex = 2))
        val (pruned, _) = stackCoherence(stacks, emptyList(), setOf(2, 3))
        assertEquals(listOf(2, 3), pruned.single().placementSlots)
        assertEquals("slot 3 was showing and still is", 1, pruned.single().activeIndex)
    }

    @Test fun `an active index that did not survive falls back to the first widget`() {
        val stacks = listOf(WidgetStack("stack:a", listOf(1, 2, 3), activeIndex = 0))
        val (pruned, _) = stackCoherence(stacks, emptyList(), setOf(2, 3))
        assertEquals(listOf(2, 3), pruned.single().placementSlots)
        assertEquals(0, pruned.single().activeIndex)
    }

    @Test fun `smart rotate survives a prune`() {
        val stacks = listOf(WidgetStack("stack:a", listOf(1, 2, 3), smartRotate = true))
        val (pruned, _) = stackCoherence(stacks, emptyList(), setOf(1, 2))
        assertTrue(pruned.single().smartRotate)
    }

    // ---------------------------------------------------------------------------------------
    // The Today column
    // ---------------------------------------------------------------------------------------

    @Test fun `a today entry naming a vanished slot is dropped`() {
        val (_, today) = stackCoherence(emptyList(), listOf("1", "2"), setOf(1))
        assertEquals(listOf("1"), today)
    }

    @Test fun `a today entry naming a stack that was pruned away is dropped`() {
        val stacks = listOf(WidgetStack("stack:a", listOf(1, 2)))
        // Slot 2 goes, so the stack collapses to a plain widget and its id names nothing.
        val (pruned, today) = stackCoherence(stacks, listOf("stack:a", "1"), setOf(1))
        assertTrue(pruned.isEmpty())
        assertEquals(listOf("1"), today)
    }

    @Test fun `a today entry naming a surviving stack is kept`() {
        val stacks = listOf(WidgetStack("stack:a", listOf(1, 2)))
        val (_, today) = stackCoherence(stacks, listOf("stack:a"), setOf(1, 2))
        assertEquals(listOf("stack:a"), today)
    }

    @Test fun `built-in today widgets are opaque to this rule and never pruned`() {
        // The column also carries built-in ids, which have no placement and no binding at all.
        // Their grammar belongs to the Today track; pruning them here would empty the column.
        val builtin = todayBuiltinId(TodayWidgetKind.CLOCK, TodayWidgetSize.MEDIUM)
        val (_, today) = stackCoherence(emptyList(), listOf(builtin, "7"), emptySet())
        assertEquals(listOf(builtin), today)
    }

    @Test fun `an id of no recognised shape is left alone rather than guessed at`() {
        val (_, today) = stackCoherence(emptyList(), listOf("something-else"), emptySet())
        assertEquals(listOf("something-else"), today)
    }

    // ---------------------------------------------------------------------------------------
    // Whole documents
    // ---------------------------------------------------------------------------------------

    @Test fun `a coherent document is returned unchanged`() {
        val state = LauncherPersistedState(
            layoutSet = setWith(1, 2),
            stacks = listOf(WidgetStack("stack:a", listOf(1, 2))),
            leadingPage = LeadingPageConfig(today = listOf("stack:a")),
        )
        assertTrue("an identity, so no needless state churn", state.withCoherentStacks() === state)
    }

    @Test fun `a document whose widget went is repaired rather than refused`() {
        val state = LauncherPersistedState(
            layoutSet = setWith(1),
            stacks = listOf(WidgetStack("stack:a", listOf(1, 2))),
            leadingPage = LeadingPageConfig(today = listOf("stack:a", "2")),
        )
        val repaired = state.withCoherentStacks()
        assertTrue(repaired.stacks.isEmpty())
        assertTrue(repaired.leadingPage.today.isEmpty())
        // And the repaired document is one validate() accepts, which is the whole point.
        validate(repaired)
    }

    @Test fun `slots are counted across every layout, not just the active one`() {
        // A stack can name a widget on a layout the user is not looking at right now.
        val set = LayoutSet(
            mirrored = DuoLayout(widgetPlacements = listOf(placement(1))),
            cover = DuoLayout(widgetPlacements = listOf(placement(2))),
            inner = DuoLayout(widgetPlacements = listOf(placement(3))),
        )
        assertEquals(setOf(1, 2, 3), set.liveWidgetSlots())
        val state = LauncherPersistedState(
            layoutSet = set,
            stacks = listOf(WidgetStack("stack:a", listOf(1, 3))),
        )
        assertEquals(state.stacks, state.withCoherentStacks().stacks)
    }
}
