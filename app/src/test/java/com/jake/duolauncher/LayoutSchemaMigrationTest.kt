package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema 9 and the v8 → v9 migration (FR-35, FR-36, AC-29, AC-30).
 *
 * [V8_FIXTURE] is a real schema-8 payload in the exact shape the 0.15.0-beta01 serializer wrote:
 * the same keys, the same order, the same types, including a work-profile app id, a folder, a bound
 * widget, a leading-workspace widget, an unbound widget with its restore metadata, and tuned
 * presets. The point of these tests is that migrating it changes *nothing* a user can see.
 */
class LayoutSchemaMigrationTest {

    private companion object {
        const val WORK_APP = "duo-profile:v1:10:com.example.work/.Main"
        const val FOLDER_ID = "folder:123e4567-e89b-12d3-a456-426614174000"

        /**
         * A captured schema-8 payload. Written exactly as `LauncherModel.serializeState()` emitted
         * it at schema 8: `pinned`, `homeSlots`, `leadingSlots`, `dock`, `widgets`, `labels`,
         * `folders`, `restores`, `googleSearch`, `verticalStatus`, `compact`, `expanded`.
         *
         * Two invariants below are not decoration — they are the rules the schema-8 *editor*
         * enforced, so a payload that breaks either one could never have been written by any build
         * and is not a valid migration fixture:
         *
         * 1. **No folder is ever in the dock.** `canPlaceInDock` refuses a reserved folder id,
         *    `LauncherModel.setDock` refuses it again, and `LayoutBackup`'s importer hard-requires
         *    `!isReservedFolderId` for every dock entry. A folder reaches the dock only in schema 9
         *    (FR-39), which is why `decodeLegacy` still rejects one here.
         * 2. **No home cell sits under a widget rectangle.** `placeWidget` refuses a rectangle
         *    covering an occupied cell and `dropApp` refuses a cell inside a widget, so the page-1
         *    app sits *below* the 4x2 unbound widget rather than beneath it.
         */
        val V8_FIXTURE: String = buildString {
            append("""{"schema":8,""")
            append(""""pinned":["com.android.chrome/.Main","$FOLDER_ID","$WORK_APP"],""")
            // 24 home cells: page 0 holds apps with deliberate gaps, page 1 holds one app.
            append(""""homeSlots":[null,null,null,null,null,null,null,null,""")
            append(""""com.android.chrome/.Main",null,"$FOLDER_ID",null,""")
            append(""""$WORK_APP",null,null,null,null,null,null,null,null,null,null,null,""")
            // Page 1 cells 0..7 are covered by the 4x2 unbound widget below, so the page's only
            // app sits at cell 8 (row 2, column 0) — the first cell the widget leaves free.
            append("""null,null,null,null,null,null,null,null,""")
            append(""""com.example.page2/.Main"],""")
            // Leading workspace: one app in the last cell, the rest empty.
            append(""""leadingSlots":[null,null,null,null,null,null,null,null,null,null,null,null,""")
            append("""null,null,null,null,null,null,null,null,null,null,null,"com.example.leading/.Main"],""")
            append(""""dock":["com.google.android.dialer/.Main",null,"com.spotify.music/.Main","com.android.camera/.Main"],""")
            append(""""widgets":[""")
            append("""{"slot":0,"id":-2,"page":0,"column":0,"row":0,"spanX":2,"spanY":2},""")
            append("""{"slot":1,"id":-3,"page":0,"column":2,"row":0,"spanX":2,"spanY":2},""")
            append("""{"slot":4,"id":26,"page":-1,"column":0,"row":0,"spanX":4,"spanY":3},""")
            append("""{"slot":7,"id":-5,"page":1,"column":0,"row":0,"spanX":4,"spanY":2}],""")
            append(""""labels":false,""")
            append(""""folders":[{"id":"$FOLDER_ID","title":"Work \"stuff\" 🎧","apps":["com.example.a/.Main","com.example.b/.Main"]}],""")
            append(""""restores":[{"slot":7,"provider":"com.example.weather/.WidgetProvider","userSerial":0,""")
            append(""""title":"Weather","profileLabel":"Personal","work":false,"sourceScope":"scope-a"}],""")
            append(""""googleSearch":false,"verticalStatus":false,""")
            append(""""compact":{"iconSize":61,"rowGap":7,"dockWidth":67,"dockPosition":0.44,"dockAlignToGrid":false},""")
            append(""""expanded":{"iconSize":62,"rowGap":9,"dockWidth":69,"dockPosition":0.55,"dockAlignToGrid":true}}""")
        }
    }

    private fun migrated(): LauncherPersistedState {
        val result = decodeLauncherState(V8_FIXTURE)
        assertTrue("v8 fixture must decode, got $result", result is LayoutDecodeResult.Loaded)
        return (result as LayoutDecodeResult.Loaded).state
    }

    // -----------------------------------------------------------------------------------------
    // AC-29: every placement and binding survives untouched
    // -----------------------------------------------------------------------------------------

    @Test fun `migration lands on mirrored mode with a four by six grid`() {
        val set = migrated().layoutSet
        assertEquals(LayoutMode.MIRRORED, set.mode)
        assertEquals(GridSpec(4, 6), set.mirrored.grid)
    }

    @Test fun `every home placement keeps its exact cell`() {
        val slots = migrated().layoutSet.mirrored.slots
        assertEquals("com.android.chrome/.Main", slots[8])
        assertNull(slots[9])
        assertEquals(FOLDER_ID, slots[10])
        assertNull(slots[11])
        assertEquals(WORK_APP, slots[12])
        // Page 1, cell 8: index 1 * 24 + 8.
        assertEquals("com.example.page2/.Main", slots[32])
        // Nothing else was invented or dropped.
        assertEquals(4, slots.count { it != null })
    }

    @Test fun `the leading workspace is preserved cell for cell`() {
        val leading = migrated().layoutSet.mirrored.leadingSlots
        assertEquals(HOME_CELLS, leading.size)
        assertEquals("com.example.leading/.Main", leading[23])
        assertEquals(1, leading.count { it != null })
    }

    @Test fun `the dock keeps its order and its gap`() {
        val dock = migrated().layoutSet.dock
        assertEquals(
            listOf("com.google.android.dialer/.Main", null, "com.spotify.music/.Main", "com.android.camera/.Main"),
            dock.items,
        )
        assertEquals(DockSide.RIGHT, dock.side)
        assertEquals(4, dock.capacity)
    }

    @Test fun `folders keep their id title and members including unicode and quotes`() {
        val folders = migrated().layoutSet.folders
        assertEquals(1, folders.size)
        assertEquals(FOLDER_ID, folders[0].id)
        assertEquals("Work \"stuff\" 🎧", folders[0].title)
        assertEquals(listOf("com.example.a/.Main", "com.example.b/.Main"), folders[0].appIds)
    }

    @Test fun `every widget placement keeps its id page position and span`() {
        val widgets = migrated().layoutSet.mirrored.widgetPlacements.sortedBy { it.slot }
        assertEquals(
            listOf(
                WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2),
                WidgetPlacement(1, DATE_WIDGET, 0, 2, 0, 2, 2),
                WidgetPlacement(4, 26, -1, 0, 0, 4, 3),
                WidgetPlacement(7, NEEDS_BINDING_WIDGET, 1, 0, 0, 4, 2),
            ),
            widgets,
        )
    }

    @Test fun `the bound widget id survives, which is what keeps the binding alive`() {
        val bound = migrated().layoutSet.mirrored.widgetPlacements.first { it.slot == 4 }
        assertEquals(26, bound.id)
        assertEquals(-1, bound.page)
    }

    @Test fun `widget restore metadata survives so an unbound widget can still reconnect`() {
        val restore = migrated().layoutSet.mirrored.widgetRestores.single()
        assertEquals(
            WidgetRestore(7, "com.example.weather/.WidgetProvider", 0, "Weather", "Personal", false, "scope-a"),
            restore,
        )
    }

    @Test fun `settings carried over from v8 keep their values`() {
        val state = migrated()
        assertEquals(false, state.labels)
        assertEquals(false, state.googleSearch)
        assertEquals(false, state.verticalStatus)
        assertEquals(61f, state.compact.iconSize, 0.001f)
        assertEquals(0.44f, state.compact.dockPosition, 0.001f)
        assertEquals(false, state.compact.dockAlignToGrid)
        assertEquals(62f, state.expanded.iconSize, 0.001f)
        assertEquals(true, state.expanded.dockAlignToGrid)
    }

    @Test fun `an upgrade keeps the classic leading page so today view never steals it`() {
        // FR-58 / AC-48: the existing leading workspace stays exactly where it is.
        assertEquals(LeadingPageKind.CLASSIC, migrated().leadingPage.kind)
    }

    @Test fun `an upgrade keeps opening the notification shade on swipe down`() {
        // FR-51: Search is the new-install default only; an upgrade's gesture must not change.
        assertEquals(SwipeDownAction.NOTIFICATIONS, migrated().settings.swipeDown)
    }

    @Test fun `cover and inner start empty so no widget binding is duplicated`() {
        val set = migrated().layoutSet
        assertTrue(set.cover.isEmpty)
        assertTrue(set.inner.isEmpty)
        assertTrue(set.cover.widgetPlacements.isEmpty())
        assertTrue(set.inner.widgetPlacements.isEmpty())
    }

    /** The whole point of AC-29, stated once: a full round trip changes no user-visible data. */
    @Test fun `migrating then saving then loading again is lossless`() {
        val first = migrated()
        val reloaded = decodeLauncherState(encodeLauncherState(first))
        assertTrue(reloaded is LayoutDecodeResult.Loaded)
        val second = (reloaded as LayoutDecodeResult.Loaded).state
        assertEquals(first.layoutSet.mirrored.slots, second.layoutSet.mirrored.slots)
        assertEquals(first.layoutSet.mirrored.leadingSlots, second.layoutSet.mirrored.leadingSlots)
        assertEquals(first.layoutSet.mirrored.widgetPlacements, second.layoutSet.mirrored.widgetPlacements)
        assertEquals(first.layoutSet.mirrored.widgetRestores, second.layoutSet.mirrored.widgetRestores)
        assertEquals(first.layoutSet.dock, second.layoutSet.dock)
        assertEquals(first.layoutSet.folders, second.layoutSet.folders)
        assertEquals(first.settings, second.settings)
        assertEquals(first.compact, second.compact)
        assertEquals(first.expanded, second.expanded)
        assertEquals(first.leadingPage, second.leadingPage)
    }

    @Test fun `a second save produces byte-identical JSON`() {
        val once = encodeLauncherState(migrated())
        val twice = encodeLauncherState((decodeLauncherState(once) as LayoutDecodeResult.Loaded).state)
        assertEquals(once, twice)
    }

    // -----------------------------------------------------------------------------------------
    // AC-30: new-install defaults
    // -----------------------------------------------------------------------------------------

    @Test fun `a fresh install gets separate layouts cover four by six and inner six by six`() {
        val set = newInstallLayoutSet()
        assertEquals(LayoutMode.SEPARATE, set.mode)
        assertEquals(GridSpec(4, 6), set.cover.grid)
        assertEquals(GridSpec(6, 6), set.inner.grid)
        assertEquals(DockSide.RIGHT, set.dock.side)
    }

    @Test fun `a fresh install leads with today view`() {
        assertEquals(LeadingPageKind.TODAY, LeadingPageConfig().kind)
        assertEquals(SwipeDownAction.SEARCH, DuoSettings().swipeDown)
    }

    @Test fun `a fresh install's inner leading page is sized to its own grid`() {
        assertEquals(36, newInstallLayoutSet().inner.leadingSlots.size)
        assertEquals(24, newInstallLayoutSet().cover.leadingSlots.size)
    }

    // -----------------------------------------------------------------------------------------
    // Validation and refusal
    // -----------------------------------------------------------------------------------------

    @Test fun `a payload from a newer schema is refused rather than downgraded`() {
        val result = decodeLauncherState("""{"schema":99}""")
        assertTrue(result is LayoutDecodeResult.TooNew)
        assertEquals(99, (result as LayoutDecodeResult.TooNew).schema)
    }

    @Test fun `a schema 9 payload with no layout set fails rather than loading empty`() {
        // This is the case the existing instrumented suite asserts must be rejected.
        assertTrue(decodeLauncherState("""{"schema":9}""") is LayoutDecodeResult.Failed)
    }

    @Test fun `unreadable or absent payloads fail instead of throwing`() {
        listOf(null, "", "   ", "not json", "[]", """{"schema":9,"layoutSet":5}""").forEach {
            assertTrue("$it must fail", decodeLauncherState(it) is LayoutDecodeResult.Failed)
        }
    }

    @Test fun `a v8 payload that broke the v8 rules still fails under schema 9`() {
        // Same shortcut on the leading page and on Home: rejected before and after.
        val duplicated = V8_FIXTURE.replace(
            """"leadingSlots":[null,null,null,null,null,null,null,null,null,null,null,null,""",
            """"leadingSlots":["com.android.chrome/.Main",null,null,null,null,null,null,null,null,null,null,null,""",
        )
        assertTrue(decodeLauncherState(duplicated) is LayoutDecodeResult.Failed)
    }

    @Test fun `validation rejects two layouts claiming one cell`() {
        val state = migrated()
        val broken = state.copy(
            layoutSet = state.layoutSet.copy(
                mirrored = state.layoutSet.mirrored.copy(
                    // A widget on top of an occupied cell.
                    widgetPlacements = state.layoutSet.mirrored.widgetPlacements +
                        WidgetPlacement(9, 30, 0, 0, 2, 2, 2),
                ),
            ),
        )
        assertTrue(runCatching { validate(broken) }.isFailure)
    }

    @Test fun `validation rejects a folder reference with no folder`() {
        val state = migrated()
        val broken = state.copy(layoutSet = state.layoutSet.copy(folders = emptyList()))
        assertTrue(runCatching { validate(broken) }.isFailure)
    }

    @Test fun `validation rejects a grid outside four to eight`() {
        val state = migrated()
        listOf(GridSpec(3, 6), GridSpec(4, 9)).forEach { grid ->
            val broken = state.copy(
                layoutSet = state.layoutSet.copy(
                    mirrored = state.layoutSet.mirrored.copy(
                        grid = grid,
                        leadingSlots = List(grid.cells) { null },
                        slots = emptyList(),
                        widgetPlacements = emptyList(),
                        widgetRestores = emptyList(),
                    ),
                ),
            )
            assertTrue("$grid must be rejected", runCatching { validate(broken) }.isFailure)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Page -1 addressing must keep meaning the leading workspace
    // -----------------------------------------------------------------------------------------

    @Test fun `page minus one addressing is unchanged on the default grid`() {
        assertEquals(-1, homeCellPage(-24))
        assertEquals(0, homeCellLocal(-24))
        assertEquals(-24, homeCellIndex(-1, 0))
        assertEquals(-1, homeCellIndex(-1, 23))
        assertEquals(0, homeCellIndex(0, 0))
    }

    @Test fun `page minus one addressing follows the grid stride on a larger grid`() {
        val grid = GridSpec(6, 6)
        assertEquals(-36, homeCellIndex(-1, 0, grid))
        assertEquals(-1, homeCellPage(-36, grid))
        assertEquals(0, homeCellLocal(-36, grid))
        assertEquals(-1, homeCellPage(-1, grid))
        assertEquals(35, homeCellLocal(-1, grid))
        assertEquals(36, homeCellIndex(1, 0, grid))
    }

    @Test fun `a leading widget still resolves to leading cells on a larger grid`() {
        val grid = GridSpec(6, 6)
        val widget = WidgetPlacement(4, 26, -1, 0, 0, 2, 2)
        assertEquals(setOf(-36, -35, -30, -29), widget.coveredIndices(grid))
    }

    @Test fun `home item kinds are told apart by their stored id`() {
        assertEquals("FOLDER", homeItemKind(FOLDER_ID))
        assertEquals("SHORTCUT", homeItemKind("duo-shortcut:v1:0:com.example:shortcut-id"))
        assertEquals("APP", homeItemKind("com.android.chrome/.Main"))
        assertEquals("APP", homeItemKind(WORK_APP))
    }

    @Test fun `built in clock and date placements gain their schema 9 kinds`() {
        assertEquals("CLOCK", builtinWidgetKind(CLOCK_WIDGET))
        assertEquals("CALENDAR", builtinWidgetKind(DATE_WIDGET))
        assertNull(builtinWidgetKind(26))
    }

    // -----------------------------------------------------------------------------------------
    // Mode switching keeps both layouts (FR-32, AC-27)
    // -----------------------------------------------------------------------------------------

    @Test fun `both layouts survive a round trip through separate mode`() {
        val cover = DuoLayout(grid = GridSpec(4, 6), slots = listOf("cover-app"))
        val inner = DuoLayout(grid = GridSpec(6, 6), slots = listOf("inner-app"),
            leadingSlots = List(36) { null })
        val set = LayoutSet(mode = LayoutMode.SEPARATE, cover = cover, inner = inner)
        val encoded = encodeLauncherState(LauncherPersistedState(layoutSet = set))
        val reloaded = (decodeLauncherState(encoded) as LayoutDecodeResult.Loaded).state.layoutSet
        assertEquals(listOf("cover-app"), reloaded.cover.slots)
        assertEquals(listOf("inner-app"), reloaded.inner.slots)
        assertEquals(GridSpec(6, 6), reloaded.inner.grid)
        assertEquals(LayoutMode.SEPARATE, reloaded.mode)
    }

    @Test fun `the active layout follows the mode and the posture`() {
        val set = LayoutSet(mode = LayoutMode.SEPARATE)
        assertEquals(LayoutTarget.COVER, set.targetFor(expanded = false))
        assertEquals(LayoutTarget.INNER, set.targetFor(expanded = true))
        val mirrored = set.copy(mode = LayoutMode.MIRRORED)
        assertEquals(LayoutTarget.MIRRORED, mirrored.targetFor(expanded = false))
        assertEquals(LayoutTarget.MIRRORED, mirrored.targetFor(expanded = true))
    }

    @Test fun `dock capacity changes keep filled slots instead of truncating them`() {
        val dock = DockConfig(capacity = 6, items = listOf("a", null, "b", "c", null, "d"))
        val shrunk = dock.copy(capacity = 4).sanitized()
        assertEquals(4, shrunk.items.size)
        // Every app that was in the dock is still in the dock.
        assertTrue(listOf("a", "b", "c", "d").all { it in shrunk.items })
    }

    @Test fun `dock capacity is clamped into the supported range`() {
        assertEquals(MIN_DOCK_CAPACITY, DockConfig(capacity = 1).sanitized().capacity)
        assertEquals(MAX_DOCK_CAPACITY, DockConfig(capacity = 99).sanitized().capacity)
    }

    @Test fun `folder tint and size round trip`() {
        val folder = FolderEntry(FOLDER_ID, "Tinted", listOf("a", "b"), tint = 0x4488FF, size = DuoFolderSize.LARGE)
        val set = LayoutSet(mirrored = DuoLayout(slots = listOf(FOLDER_ID)), folders = listOf(folder))
        val encoded = encodeLauncherState(LauncherPersistedState(layoutSet = set))
        val reloaded = (decodeLauncherState(encoded) as LayoutDecodeResult.Loaded).state
        assertEquals(folder, reloaded.layoutSet.folders.single())
    }

    @Test fun `hidden apps stacks and overrides round trip`() {
        val state = LauncherPersistedState(
            layoutSet = LayoutSet(mirrored = DuoLayout(widgetPlacements = listOf(
                WidgetPlacement(0, 26, 0, 0, 0, 2, 2), WidgetPlacement(1, 27, 0, 2, 0, 2, 2)))),
            stacks = listOf(WidgetStack("stack:1", listOf(0, 1), activeIndex = 1, smartRotate = true)),
            hiddenApps = setOf("com.example.hidden/.Main"),
            iconOverrides = listOf(IconOverrideRecord("com.android.chrome/.Main", "com.pack", "ic_web", "Web")),
        )
        val reloaded = (decodeLauncherState(encodeLauncherState(state)) as LayoutDecodeResult.Loaded).state
        assertEquals(state.stacks, reloaded.stacks)
        assertEquals(state.hiddenApps, reloaded.hiddenApps)
        assertEquals(state.iconOverrides, reloaded.iconOverrides)
    }

    @Test fun `a flattened component is recognised without Android`() {
        assertTrue(isFlattenedComponent("com.example/.Widget"))
        assertTrue(isFlattenedComponent("com.example/com.example.Widget"))
        listOf("", "com.example", "/x", "x/", "a/b/c").forEach {
            assertTrue("\"$it\" is not a component", !isFlattenedComponent(it))
        }
    }

    @Test fun `older schemas still migrate forward`() {
        // Schema 7 has no leading slot array; it must arrive with an empty leading page.
        val v7 = """{"schema":7,"pinned":["a/.M"],"homeSlots":["a/.M"],"dock":[null,null,null,null],""" +
            """"widgets":[],"folders":[],"restores":[],"labels":true,"googleSearch":true,"verticalStatus":true}"""
        val state = (decodeLauncherState(v7) as LayoutDecodeResult.Loaded).state
        assertEquals(listOf("a/.M"), state.layoutSet.mirrored.slots)
        assertEquals(List<String?>(HOME_CELLS) { null }, state.layoutSet.mirrored.leadingSlots)
        assertNotNull(state.layoutSet.mirrored.pageIds.firstOrNull())
    }
}
