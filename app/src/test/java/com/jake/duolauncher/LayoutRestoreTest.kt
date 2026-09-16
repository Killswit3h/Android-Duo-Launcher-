package com.jake.duolauncher

import com.jake.duolauncher.icons.IconAppearance
import com.jake.duolauncher.icons.IconShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Applying a reviewed backup to the live state (FR-82, AC-66).
 *
 * [LayoutBackupTest] proves the codec round-trips every schema-9 field; this proves the model
 * *applies* what the codec read, that undo takes all of it back, that an old backup cannot quietly
 * reset settings it never described, and that a merge which would not validate is refused whole.
 *
 * The merge and the undo are pure functions of state precisely so they can be checked here rather
 * than only on a device: `LauncherState` is a plain data class, so the whole restore decision is
 * reachable from the JVM suite (NFR-M2).
 */
class LayoutRestoreTest {

    private companion object {
        const val SCOPE = "scope-a"

        const val APP_ONE = "com.example.one/com.example.one.Main"
        const val APP_TWO = "com.example.two/com.example.two.Main"
        const val APP_THREE = "com.example.three/com.example.three.Main"
        const val APP_FOUR = "com.example.four/com.example.four.Main"
        const val APP_DOCK = "com.example.dock/com.example.dock.Main"
        const val APP_HIDDEN = "com.example.hidden/com.example.hidden.Main"
        const val APP_TINTED = "com.example.tinted/com.example.tinted.Main"
        const val APP_LOCAL = "com.example.local/com.example.local.Main"
        const val FOLDER = "folder:00000000-0000-0000-0000-000000000001"
        const val LOCAL_FOLDER = "folder:00000000-0000-0000-0000-000000000002"

        val PROFILES = listOf(
            AppProfile(0, "Personal", isPersonal = true, isWork = false, quiet = false, unlocked = true, available = true),
        )

        val CATALOG = BackupCatalog(
            ids = setOf(APP_ONE, APP_TWO, APP_THREE, APP_FOUR, APP_DOCK, APP_HIDDEN, APP_TINTED, APP_LOCAL),
        )

        /** What the backup carries. Every field differs from [CURRENT_SETTINGS]. */
        val BACKUP_SETTINGS = DuoSettings(
            wallpaperSource = WallpaperSource.SYSTEM,
            dimInDark = true,
            glassLevel = 80,
            reduceTransparency = true,
            accent = 0x00FF00,
            font = DuoFontChoice.SYSTEM,
            iconAppearance = IconAppearance.TINTED,
            iconTint = 0x123456,
            iconTintIntensity = 40,
            iconShape = IconShape.SCALLOP,
            largeIcons = true,
            iconPack = "com.example.pack",
            badgeStyle = DuoBadgeStyle.NUMBER,
            swipeDown = SwipeDownAction.NONE,
            swipeUp = SwipeUpAction.NONE,
            doubleTapLock = true,
            lockLayout = true,
            autoAddApps = true,
            duoStatus = false,
            libraryView = AppLibraryView.AZ,
            searchContacts = true,
            suggestions = false,
            hidePrivateContainer = true,
        )

        /** What the phone doing the restoring is already set to. */
        val CURRENT_SETTINGS = DuoSettings(
            glassLevel = 12,
            accent = 0x0000FF,
            badgeStyle = DuoBadgeStyle.OFF,
            swipeDown = SwipeDownAction.NOTIFICATIONS,
            doubleTapLock = false,
            lockLayout = false,
            autoAddApps = true,
            libraryView = AppLibraryView.AZ,
        )

        val COMPACT = LayoutPreset(52f, 10f, 70f, 0.5f, dockAlignToGrid = false)
        val EXPANDED = LayoutPreset(60f, 12f, 72f, 0.6f, dockAlignToGrid = true)
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    /** 4×6, an app, a folder and a built-in widget. */
    private fun backupCover(): DuoLayout {
        val grid = GridSpec(4, 6)
        val slots = MutableList<String?>(grid.cells) { null }
        slots[8] = APP_ONE
        slots[10] = FOLDER
        return DuoLayout(
            grid = grid,
            slots = slots.dropLastWhile { it == null },
            leadingSlots = List(grid.cells) { null },
            widgetPlacements = listOf(WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2)),
        ).withPageIds()
    }

    /** 6×6, its own leading page, a built-in widget and one that will ask to reconnect. */
    private fun backupInner(): DuoLayout {
        val grid = GridSpec(6, 6)
        val slots = MutableList<String?>(grid.cells) { null }
        slots[12] = APP_FOUR
        val leading = MutableList<String?>(grid.cells) { null }
        leading[3] = APP_TINTED
        return DuoLayout(
            grid = grid,
            slots = slots.dropLastWhile { it == null },
            leadingSlots = leading,
            widgetPlacements = listOf(
                WidgetPlacement(7, DATE_WIDGET, 0, 0, 0, 2, 2),
                WidgetPlacement(8, NEEDS_BINDING_WIDGET, 0, 2, 0, 2, 2),
            ),
            widgetRestores = listOf(
                WidgetRestore(8, "com.example.one/com.example.one.Widget", 0, "One", "Personal",
                    isWork = false, sourceScope = SCOPE),
            ),
            hiddenPageIds = setOf(2),
        ).withPageIds()
    }

    private fun backupLayoutSet(): LayoutSet = LayoutSet(
        mode = LayoutMode.SEPARATE,
        mirrored = DuoLayout(grid = GridSpec(4, 6), leadingSlots = List(24) { null }).withPageIds(),
        cover = backupCover(),
        inner = backupInner(),
        dock = DockConfig(side = DockSide.LEFT, capacity = 5, items = listOf(null, APP_DOCK, null, null, null)),
        folders = listOf(FolderEntry(FOLDER, "Work things", listOf(APP_TWO, APP_THREE), tint = 4, size = DuoFolderSize.LARGE)),
    )

    private fun backupState(): LauncherState = LauncherState(
        leadingPage = LeadingPageConfig(kind = LeadingPageKind.TODAY, today = listOf("7", "stack-1")),
        stacks = listOf(WidgetStack("stack-1", listOf(7, 8), activeIndex = 1, smartRotate = true)),
        hiddenApps = setOf(APP_HIDDEN),
        iconOverrides = listOf(IconOverrideRecord(APP_TINTED, "com.example.pack", "ic_tinted", "Tinted")),
        settings = BACKUP_SETTINGS,
        labels = false,
        googleSearch = false,
        verticalStatus = false,
        compact = COMPACT,
        expanded = EXPANDED,
        loading = false,
    ).withLayoutSet(backupLayoutSet())

    /**
     * The phone being restored onto: Mirrored on a 6×6 grid, a six-slot dock on the right, its own
     * folder, and settings the user chose. The cover layout deliberately references that folder and
     * an app the imported folder will own, which is the state a legacy import has to leave coherent.
     */
    private fun currentState(): LauncherState {
        val grid = GridSpec(6, 6)
        val mirrored = MutableList<String?>(grid.cells) { null }
        mirrored[0] = APP_ONE
        mirrored[1] = LOCAL_FOLDER
        val cover = MutableList<String?>(grid.cells) { null }
        cover[0] = LOCAL_FOLDER
        cover[1] = APP_TWO
        return LauncherState(
            leadingPage = LeadingPageConfig(kind = LeadingPageKind.CLASSIC),
            stacks = emptyList(),
            hiddenApps = setOf(APP_LOCAL),
            iconOverrides = listOf(IconOverrideRecord(APP_ONE, label = "Local")),
            settings = CURRENT_SETTINGS,
            labels = true,
            googleSearch = true,
            verticalStatus = true,
            compact = LayoutPreset(),
            expanded = LayoutPreset(),
            loading = false,
        ).withLayoutSet(
            LayoutSet(
                mode = LayoutMode.MIRRORED,
                mirrored = DuoLayout(grid = grid, slots = mirrored.dropLastWhile { it == null },
                    leadingSlots = List(grid.cells) { null }).withPageIds(),
                cover = DuoLayout(grid = grid, slots = cover.dropLastWhile { it == null },
                    leadingSlots = List(grid.cells) { null }).withPageIds(),
                inner = DuoLayout(grid = GridSpec(5, 5), leadingSlots = List(25) { null }).withPageIds(),
                dock = DockConfig(side = DockSide.RIGHT, capacity = 6, items = List(6) { null }),
                folders = listOf(FolderEntry(LOCAL_FOLDER, "Mine", listOf(APP_THREE, APP_FOUR))),
            ),
        )
    }

    private fun version3Preview(): LayoutImportPreview =
        decodeLayoutBackup(encodeLayoutBackup(backupState(), emptyList(), SCOPE) { false }, CATALOG, PROFILES, SCOPE)

    // -----------------------------------------------------------------------------------------
    // Version 3: everything the backup carries is applied (AC-66)
    // -----------------------------------------------------------------------------------------

    @Test fun version3RestoreAppliesEveryLayoutAndSettingInOneState() {
        val current = currentState()
        val preview = version3Preview()
        val imported = importedLauncherState(current, preview)

        // All three layouts, each with its own grid and pages, plus mode, dock and folders.
        assertEquals(backupLayoutSet(), imported.layoutSet)
        assertEquals(LayoutMode.SEPARATE, imported.layoutSet.mode)
        assertEquals(GridSpec(4, 6), imported.layoutSet.cover.grid)
        assertEquals(GridSpec(6, 6), imported.layoutSet.inner.grid)
        assertEquals(setOf(2), imported.layoutSet.inner.hiddenPageIds)
        assertEquals(DockSide.LEFT, imported.layoutSet.dock.side)
        assertEquals(5, imported.layoutSet.dock.capacity)

        // The flat active-layout fields follow the imported set rather than the old one.
        assertEquals(LayoutTarget.COVER, imported.activeTarget)
        assertEquals(GridSpec(4, 6), imported.grid)
        assertEquals(APP_ONE, imported.homeSlots[8])
        assertEquals(FOLDER, imported.homeSlots[10])
        assertEquals(APP_DOCK, imported.dock[1])
        assertEquals(4, imported.folders.single().tint)
        assertEquals(DuoFolderSize.LARGE, imported.folders.single().size)

        // The leading page and its Today items, stacks, hidden apps and icon overrides.
        assertEquals(LeadingPageKind.TODAY, imported.leadingPage.kind)
        assertEquals(listOf("7", "stack-1"), imported.leadingPage.today)
        assertEquals(backupState().stacks, imported.stacks)
        assertEquals(setOf(APP_HIDDEN), imported.hiddenApps)
        assertEquals(backupState().iconOverrides, imported.iconOverrides)

        // Every settings field, and the values that predate schema 9.
        assertEquals(BACKUP_SETTINGS, imported.settings)
        assertEquals(80, imported.settings.glassLevel)
        assertEquals(0x00FF00, imported.settings.accent)
        assertEquals(IconAppearance.TINTED, imported.settings.iconAppearance)
        assertEquals(IconShape.SCALLOP, imported.settings.iconShape)
        assertEquals(DuoBadgeStyle.NUMBER, imported.settings.badgeStyle)
        assertEquals(SwipeDownAction.NONE, imported.settings.swipeDown)
        assertTrue(imported.settings.lockLayout)
        assertEquals(AppLibraryView.AZ, imported.settings.libraryView)
        assertEquals(COMPACT, imported.compact)
        assertEquals(EXPANDED, imported.expanded)
        assertFalse(imported.labels)
        assertFalse(imported.googleSearch)
        assertFalse(imported.verticalStatus)

        // An unbound widget keeps its saved space and its reconnect metadata.
        assertEquals(NEEDS_BINDING_WIDGET, imported.layoutSet.inner.widgetPlacements.single { it.slot == 8 }.id)
        assertEquals("com.example.one/com.example.one.Widget",
            imported.layoutSet.inner.widgetRestores.single().providerComponent)

        // Applying the same backup again changes nothing, which is what reports "already active".
        assertEquals(imported, importedLauncherState(imported, preview))
    }

    // -----------------------------------------------------------------------------------------
    // Undo (AC-66)
    // -----------------------------------------------------------------------------------------

    @Test fun undoAfterARestoreTakesBackEveryFieldTheImportChanged() {
        val before = currentState()
        val imported = importedLauncherState(before, version3Preview())
            .copy(editRevision = before.editRevision + 1, canUndoEdit = true)
        assertNotEquals(before.settings, imported.settings)
        assertNotEquals(before.layoutSet, imported.layoutSet)

        val installed = setOf(APP_ONE, APP_TWO, APP_THREE, APP_FOUR) + before.folders.map(FolderEntry::id)
        val undone = undoneLauncherState(before, imported, installed)

        assertEquals(before.layoutSet, undone.layoutSet)
        assertEquals(before.grid, undone.grid)
        assertEquals(before.layoutSet.dock.side, undone.layoutSet.dock.side)
        assertEquals(before.layoutSet.dock.capacity, undone.layoutSet.dock.capacity)
        assertEquals(before.settings, undone.settings)
        assertEquals(before.leadingPage, undone.leadingPage)
        assertEquals(before.stacks, undone.stacks)
        assertEquals(before.hiddenApps, undone.hiddenApps)
        assertEquals(before.iconOverrides, undone.iconOverrides)
        assertEquals(before.compact, undone.compact)
        assertEquals(before.expanded, undone.expanded)
        assertEquals(before.labels, undone.labels)
        assertEquals(before.googleSearch, undone.googleSearch)
        assertEquals(before.verticalStatus, undone.verticalStatus)
        assertFalse(undone.canUndoEdit)
    }

    // -----------------------------------------------------------------------------------------
    // Version 2: a backup may only write the fields it actually carries (FR-82)
    // -----------------------------------------------------------------------------------------

    /** A real version 2 document: one 4×6 arrangement, a folder, and two widgets. */
    private fun version2Fixture(): String = buildString {
        append("""{"version":2,"sourceScope":"$SCOPE","apps":[""")
        append(listOf(APP_ONE, APP_TWO, APP_THREE, APP_FOUR).joinToString(",", transform = ::appMetadata))
        append("],")
        append(""""homeSlots":[null,null,null,null,null,null,null,null,"$APP_ONE",null,"$FOLDER",null],""")
        append(""""leadingSlots":[${List(24) { "null" }.joinToString(",")}],""")
        append(""""dock":["$APP_FOUR",null,null,null],""")
        append(""""folders":[{"id":"$FOLDER","title":"Mixed","apps":["$APP_TWO","$APP_THREE"]}],""")
        append(""""widgets":[{"slot":0,"page":0,"column":0,"row":0,"spanX":2,"spanY":2,"builtinId":$CLOCK_WIDGET},""")
        append("""{"slot":1,"page":0,"column":2,"row":0,"spanX":2,"spanY":2,""")
        append(""""provider":"com.example.one/com.example.one.Widget","userSerial":0,""")
        append(""""title":"One","profileLabel":"Personal","work":false,"sourceScope":"$SCOPE"}],""")
        append(""""labels":true,"googleSearch":false,"verticalStatus":true,""")
        append(""""compact":{"iconSize":52.0,"rowGap":10.0,"dockWidth":70.0,"dockPosition":0.5,"dockAlignToGrid":false},""")
        append(""""expanded":{"iconSize":60.0,"rowGap":12.0,"dockWidth":72.0,"dockPosition":0.6,"dockAlignToGrid":true}}""")
    }

    private fun appMetadata(id: String): String {
        val identity = parseProfileAppId(id)!!
        return """{"id":"$id","label":"Label","component":"${identity.component}","userSerial":0,""" +
            """"profileLabel":"Personal","work":false}"""
    }

    @Test fun version2RestoreNeverOverwritesASettingItDoesNotCarry() {
        val before = currentState()
        val preview = decodeLayoutBackup(version2Fixture(), CATALOG, PROFILES, SCOPE)
        assertEquals(2, preview.version)

        val imported = importedLauncherState(before, preview)

        // Nothing version 2 never described may move: a v1/v2 preview defaults these fields, and a
        // default is indistinguishable from a deliberate choice, so they are left alone entirely.
        assertEquals(CURRENT_SETTINGS, imported.settings)
        assertEquals(12, imported.settings.glassLevel)
        assertEquals(0x0000FF, imported.settings.accent)
        assertEquals(SwipeDownAction.NOTIFICATIONS, imported.settings.swipeDown)
        assertEquals(before.hiddenApps, imported.hiddenApps)
        assertEquals(before.stacks, imported.stacks)
        assertEquals(before.leadingPage, imported.leadingPage)
        assertEquals(before.iconOverrides, imported.iconOverrides)
        assertEquals(DockSide.RIGHT, imported.layoutSet.dock.side)
        assertEquals(6, imported.layoutSet.dock.capacity)
        assertEquals(LayoutMode.MIRRORED, imported.layoutSet.mode)
        assertEquals(GridSpec(6, 6), imported.grid)
        assertEquals(before.layoutSet.inner, imported.layoutSet.inner)

        // What it does carry is restored, reflowed onto the grid the user is actually on: cell 8 of
        // a four-column grid is row 2 column 0, which is cell 12 on a six-column one.
        assertEquals(APP_ONE, imported.layoutSet.mirrored.slots[12])
        assertEquals(FOLDER, imported.layoutSet.mirrored.slots[14])
        assertEquals(listOf(APP_TWO, APP_THREE), imported.folders.single().appIds)
        assertEquals(APP_FOUR, imported.dock[0])
        assertEquals(COMPACT, imported.compact)
        assertEquals(EXPANDED, imported.expanded)
        assertFalse(imported.googleSearch)

        // The imported folder table replaces the old one, so the layout that is not the import's
        // target is left resolvable rather than referencing a folder that no longer exists.
        assertNull(imported.layoutSet.cover.slots.getOrNull(0))
        assertNull(imported.layoutSet.cover.slots.getOrNull(1))
    }

    // -----------------------------------------------------------------------------------------
    // Refusal (error table: corrupt or invalid backup)
    // -----------------------------------------------------------------------------------------

    @Test fun aMergeThatWouldNotValidateIsRefusedAndNothingIsApplied() {
        val before = currentState()
        val orphanFolderReference = LayoutSet(
            mirrored = DuoLayout(grid = GridSpec(4, 6), slots = listOf(FOLDER),
                leadingSlots = List(24) { null }).withPageIds(),
        )
        val broken = LayoutImportPreview(
            layout = orphanFolderReference.homeLayout(LayoutTarget.MIRRORED),
            missingApps = emptyList(), profileIssues = emptyList(),
            appCount = 0, folderCount = 0, widgetCount = 0,
            compact = COMPACT, expanded = EXPANDED, labels = true, googleSearch = true, verticalStatus = true,
            layoutSet = orphanFolderReference,
            version = LAYOUT_BACKUP_VERSION,
        )

        val failure = runCatching { importedLauncherState(before, broken) }.exceptionOrNull()
        assertTrue("An unsavable layout must be refused as a value",
            failure is IllegalArgumentException || failure is IllegalStateException)
        // The refusal happens while building the candidate, before the model's single assignment,
        // so the state the caller holds is the state it keeps.
        assertEquals(currentState(), before)
    }
}
