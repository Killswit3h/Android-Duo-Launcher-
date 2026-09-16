package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-38: the dock is exactly as many slots as its capacity says.
 *
 * `LauncherState.dock` is what `canPlaceInDock` and the dock rail count, so a `DockConfig` whose
 * `items` had drifted from `capacity` made a six-slot dock reject a drop at four. `withLayoutSet`
 * now normalizes the dock on the one path the flat fields are derived through.
 */
class DockCapacityStateTest {

    private fun stateWith(dock: DockConfig) = LauncherState().withLayoutSet(LayoutSet(dock = dock))

    @Test fun `a six-slot dock stored with four items is presented as six slots`() {
        val state = stateWith(DockConfig(capacity = 6, items = List(4) { null }))
        assertEquals(6, state.dock.size)
        assertEquals("the persisted record matches too, which validate() requires", 6, state.layoutSet.dock.items.size)
    }

    @Test fun `shrinking capacity keeps every filled slot rather than truncating it away`() {
        val state = stateWith(DockConfig(capacity = 3, items = listOf("a", null, null, "d")))
        assertEquals(3, state.dock.size)
        assertTrue("d" in state.dock)
        assertTrue("a" in state.dock)
    }

    @Test fun `a dock that already matches its capacity is left exactly as it was`() {
        val dock = DockConfig(capacity = 4, items = listOf("a", null, "c", null))
        assertEquals(dock, stateWith(dock).layoutSet.dock)
    }

    @Test fun `the fifth app fits a six-slot dock`() {
        val state = stateWith(DockConfig(capacity = 6, items = listOf("a", "b", "c", "d")))
        assertTrue(canPlaceInDock(state.layout, "e"))
    }

    @Test fun `a full six-slot dock still rejects a newcomer (FR-40)`() {
        val state = stateWith(DockConfig(capacity = 6, items = listOf("a", "b", "c", "d", "e", "f")))
        assertFalse(canPlaceInDock(state.layout, "g"))
    }
}
