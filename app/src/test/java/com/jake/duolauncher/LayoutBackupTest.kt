package com.jake.duolauncher

import com.jake.duolauncher.icons.IconAppearance
import com.jake.duolauncher.icons.IconShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup v3 (FR-82, FR-83, AC-66, NFR-S7).
 *
 * These run on the JVM, which is the point: the previous codec sat on `org.json` and could only be
 * exercised on a device, so nothing checked it on an ordinary build. Everything here works with
 * plain ids, [AppProfile] and [BackupCatalog] — no `AppEntry`, because that carries a `Bitmap` and a
 * `ComponentName` and cannot exist off-device.
 */
class LayoutBackupTest {

    private companion object {
        const val SCOPE = "scope-a"
        const val OTHER_SCOPE = "scope-b"

        const val APP_ONE = "com.example.one/com.example.one.Main"
        const val APP_TWO = "com.example.two/com.example.two.Main"
        const val APP_THREE = "com.example.three/com.example.three.Main"
        const val APP_FOUR = "com.example.four/com.example.four.Main"
        const val APP_DOCK = "com.example.dock/com.example.dock.Main"
        const val APP_HIDDEN = "com.example.hidden/com.example.hidden.Main"
        const val APP_TINTED = "com.example.tinted/com.example.tinted.Main"
        const val WORK_APP = "duo-profile:v1:42:com.example.work/com.example.work.Main"
        const val PRIVATE_APP = "duo-profile:v1:77:com.example.secret/com.example.secret.Main"
        const val SHORTCUT = "duo-shortcut:v1:0:com.example.one:compose"
        const val FOLDER = "folder:00000000-0000-0000-0000-000000000001"

        val PROFILES = listOf(
            AppProfile(0, "Personal", isPersonal = true, isWork = false, quiet = false, unlocked = true, available = true),
            AppProfile(42, "Work", isPersonal = false, isWork = true, quiet = false, unlocked = true, available = true),
        )

        /** Everything the round-trip layout references, so nothing decodes as a missing app. */
        val CATALOG = BackupCatalog(
            ids = setOf(APP_ONE, APP_TWO, APP_THREE, APP_FOUR, APP_DOCK, APP_HIDDEN, APP_TINTED,
                WORK_APP, PRIVATE_APP),
            workIds = setOf(WORK_APP),
        )

        val TUNED_SETTINGS = DuoSettings(
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

        val COMPACT = LayoutPreset(52f, 10f, 70f, 0.5f, dockAlignToGrid = false)
        val EXPANDED = LayoutPreset(60f, 12f, 72f, 0.6f, dockAlignToGrid = true)
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private fun coverLayout(): DuoLayout {
        val grid = GridSpec(4, 6)
        val slots = MutableList<String?>(grid.cells) { null }
        slots[8] = APP_ONE
        slots[10] = FOLDER
        slots[12] = SHORTCUT
        return DuoLayout(
            grid = grid,
            slots = slots.dropLastWhile { it == null },
            leadingSlots = List(grid.cells) { null },
            // A built-in and an unbound widget: both survive a round trip exactly, where a *bound*
            // widget deliberately does not (it becomes a Reconnect placeholder).
            widgetPlacements = listOf(
                WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2),
                WidgetPlacement(1, NEEDS_BINDING_WIDGET, 0, 2, 0, 2, 2),
            ),
            widgetRestores = listOf(
                WidgetRestore(1, "com.example.one/com.example.one.Widget", 0, "One", "Personal",
                    isWork = false, sourceScope = SCOPE),
            ),
        ).withPageIds()
    }

    private fun innerLayout(): DuoLayout {
        val grid = GridSpec(6, 6)
        val slots = MutableList<String?>(grid.cells) { null }
        // The 2x2 widget below covers cells 0, 1, 6 and 7 on a six-column grid, so the apps sit
        // clear of it: a shortcut under a widget is exactly what validation refuses.
        slots[8] = APP_FOUR
        slots[9] = WORK_APP
        val leading = MutableList<String?>(grid.cells) { null }
        leading[3] = APP_TINTED
        return DuoLayout(
            grid = grid,
            slots = slots.dropLastWhile { it == null },
            leadingSlots = leading,
            widgetPlacements = listOf(WidgetPlacement(7, DATE_WIDGET, 0, 0, 0, 2, 2)),
            hiddenPageIds = setOf(2),
        ).withPageIds()
    }

    private fun fullLayoutSet(): LayoutSet = LayoutSet(
        mode = LayoutMode.SEPARATE,
        mirrored = DuoLayout(grid = GridSpec(4, 6), leadingSlots = List(24) { null }).withPageIds(),
        cover = coverLayout(),
        inner = innerLayout(),
        dock = DockConfig(side = DockSide.LEFT, capacity = 5, items = listOf(null, APP_DOCK, null, null, null)),
        folders = listOf(FolderEntry(FOLDER, "Work things", listOf(APP_TWO, APP_THREE), tint = 4, size = DuoFolderSize.LARGE)),
    )

    private fun fullState(set: LayoutSet = fullLayoutSet()): LauncherState = LauncherState(
        layoutSet = set,
        leadingPage = LeadingPageConfig(kind = LeadingPageKind.TODAY, today = listOf("1", "stack-1")),
        stacks = listOf(WidgetStack("stack-1", listOf(0, 1), activeIndex = 1, smartRotate = true)),
        hiddenApps = setOf(APP_HIDDEN),
        iconOverrides = listOf(IconOverrideRecord(APP_TINTED, "com.example.pack", "ic_tinted", "Tinted")),
        settings = TUNED_SETTINGS,
        labels = false,
        googleSearch = false,
        verticalStatus = false,
        compact = COMPACT,
        expanded = EXPANDED,
        loading = false,
    )

    private fun encode(
        state: LauncherState = fullState(),
        scope: String = SCOPE,
        isPrivateLocked: (String) -> Boolean = { false },
    ) = encodeLayoutBackup(state, emptyList(), scope, isPrivateLocked)

    private fun decode(raw: String, catalog: BackupCatalog = CATALOG, scope: String = SCOPE) =
        decodeLayoutBackup(raw, catalog, PROFILES, scope)

    private fun root(raw: String) = DuoJson.parse(raw) as DuoJson.Obj

    // -----------------------------------------------------------------------------------------
    // Round trip (FR-82, AC-66)
    // -----------------------------------------------------------------------------------------

    @Test fun version3RoundTripsEveryLayoutAndSettingSchema9Added() {
        val state = fullState()
        val preview = decode(encode(state))

        assertEquals(LAYOUT_BACKUP_VERSION, preview.version)
        assertTrue(preview.missingApps.isEmpty())
        assertTrue(preview.profileIssues.isEmpty())

        // All three layouts, each with its own grid, pages, leading page and widgets.
        assertEquals(state.layoutSet, preview.layoutSet)
        assertEquals(GridSpec(4, 6), preview.layoutSet.cover.grid)
        assertEquals(GridSpec(6, 6), preview.layoutSet.inner.grid)
        assertEquals(LayoutMode.SEPARATE, preview.layoutSet.mode)
        assertEquals(setOf(2), preview.layoutSet.inner.hiddenPageIds)

        // Dock side and capacity, folder tint and size.
        assertEquals(DockSide.LEFT, preview.layoutSet.dock.side)
        assertEquals(5, preview.layoutSet.dock.capacity)
        assertEquals(4, preview.layoutSet.folders.single().tint)
        assertEquals(DuoFolderSize.LARGE, preview.layoutSet.folders.single().size)

        // Leading page kind and Today items, stacks, hidden apps, icon overrides.
        assertEquals(LeadingPageKind.TODAY, preview.leadingPage.kind)
        assertEquals(listOf("1", "stack-1"), preview.leadingPage.today)
        assertEquals(state.stacks, preview.stacks)
        assertEquals(setOf(APP_HIDDEN), preview.hiddenApps)
        assertEquals(state.iconOverrides, preview.iconOverrides)

        // The whole settings block, plus the values that predate schema 9.
        assertEquals(TUNED_SETTINGS, preview.settings)
        assertEquals(COMPACT, preview.compact)
        assertEquals(EXPANDED, preview.expanded)
        assertEquals(false, preview.labels)
        assertEquals(false, preview.googleSearch)
        assertEquals(false, preview.verticalStatus)
    }

    @Test fun roundTripKeepsPinnedShortcutsAndUnboundWidgetRestores() {
        val preview = decode(encode())
        assertEquals(SHORTCUT, preview.layoutSet.cover.slots[12])
        val restore = preview.layoutSet.cover.widgetRestores.single()
        assertEquals("com.example.one/com.example.one.Widget", restore.providerComponent)
        assertEquals(SCOPE, restore.sourceScope)
        assertEquals(NEEDS_BINDING_WIDGET, preview.layoutSet.cover.widgetPlacements[1].id)
        assertEquals(CLOCK_WIDGET, preview.layoutSet.cover.widgetPlacements[0].id)
    }

    @Test fun boundWidgetExportsAsPortableDescriptorAndImportsAsReconnectPlaceholder() {
        val set = fullLayoutSet().let { base ->
            base.withLayout(
                LayoutTarget.COVER,
                base.cover.copy(
                    widgetPlacements = base.cover.widgetPlacements + WidgetPlacement(9, 31, 1, 0, 0, 2, 2),
                ),
            )
        }
        val descriptor = BackupWidgetDescriptor(9, "com.example.two/com.example.two.Widget", 0, "Two", "Personal")
        val raw = encodeLayoutBackup(fullState(set), listOf(descriptor), SCOPE) { false }
        val preview = decode(raw)

        val placement = preview.layoutSet.cover.widgetPlacements.single { it.slot == 9 }
        assertEquals(NEEDS_BINDING_WIDGET, placement.id)
        assertEquals("com.example.two/com.example.two.Widget",
            preview.layoutSet.cover.widgetRestores.single { it.slot == 9 }.providerComponent)
    }

    @Test fun unavailableAppBecomesAnEmptyCellNamedInThePreview() {
        val raw = encode()
        val preview = decode(raw, catalog = BackupCatalog(CATALOG.ids - APP_ONE))

        assertNull(preview.layoutSet.cover.slots[8])
        assertTrue(preview.missingApps.any { it.contains(APP_ONE) })
        // Everything else still restores: one unavailable app never costs the rest of the layout.
        assertEquals(FOLDER, preview.layoutSet.cover.slots[10])
    }

    @Test fun workProfileAppFromAnotherDeviceIsNotPlaced() {
        val raw = encode()
        val preview = decode(raw, scope = OTHER_SCOPE)

        // The work app was the last occupied cell, so dropping it trims the trailing empty cell.
        assertNull(preview.layoutSet.inner.slots.getOrNull(9))
        assertEquals(APP_FOUR, preview.layoutSet.inner.slots[8])
        assertTrue(preview.missingApps.any { it.contains(WORK_APP) })
    }

    @Test fun folderThatLosesAllButOneMemberCollapsesToThatApp() {
        val preview = decode(encode(), catalog = BackupCatalog(CATALOG.ids - APP_TWO))
        assertEquals(APP_THREE, preview.layoutSet.cover.slots[10])
        assertTrue(preview.layoutSet.folders.isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // v2 compatibility (FR-82, AC-66)
    // -----------------------------------------------------------------------------------------

    /**
     * A real version 2 document, in the exact shape the previous serializer wrote: the same keys in
     * the same order with the same types, including a folder, a built-in widget and an unbound
     * widget with its restore metadata inline.
     */
    private fun version2Fixture(version: Int = 2): String = buildString {
        append("""{"version":$version,"sourceScope":"$SCOPE",""")
        append(""""apps":[""")
        append(appMetadata(APP_ONE))
        append(",").append(appMetadata(APP_TWO))
        append(",").append(appMetadata(APP_THREE))
        append(",").append(appMetadata(APP_FOUR))
        append("],")
        // 12 cells: the clock covers 0,1,4,5 and the unbound widget covers 2,3,6,7.
        append(""""homeSlots":[null,null,null,null,null,null,null,null,"$APP_ONE",null,"$FOLDER",null],""")
        append(""""leadingSlots":[${List(24) { "null" }.joinToString(",")}],""")
        append(""""dock":["$APP_FOUR",null,null,null],""")
        append(""""folders":[{"id":"$FOLDER","title":"Mixed","apps":["$APP_TWO","$APP_THREE"]}],""")
        append(""""widgets":[""")
        append("""{"slot":0,"page":0,"column":0,"row":0,"spanX":2,"spanY":2,"builtinId":$CLOCK_WIDGET},""")
        append("""{"slot":1,"page":0,"column":2,"row":0,"spanX":2,"spanY":2,""")
        append(""""provider":"com.example.one/com.example.one.Widget","userSerial":0,""")
        append(""""title":"One","profileLabel":"Personal","work":false,"sourceScope":"$SCOPE"}],""")
        append(""""labels":true,"googleSearch":false,"verticalStatus":true,""")
        append(""""compact":{"iconSize":52.0,"rowGap":10.0,"dockWidth":70.0,"dockPosition":0.5,"dockAlignToGrid":false},""")
        append(""""expanded":{"iconSize":60.0,"rowGap":12.0,"dockWidth":72.0,"dockPosition":0.6,"dockAlignToGrid":true}}""")
    }

    private fun appMetadata(id: String): String {
        val identity = parseProfileAppId(id)!!
        val serial = identity.userSerial ?: 0L
        val work = identity.userSerial != null
        return """{"id":"$id","label":"Label ${identity.component.substringBefore('/')}",""" +
            """"component":"${identity.component}","userSerial":$serial,""" +
            """"profileLabel":"${if (work) "Work" else "Personal"}","work":$work}"""
    }

    @Test fun version2FixtureStillImportsWithDefaultsForEverythingSchema9Added() {
        val preview = decode(version2Fixture())

        assertEquals(2, preview.version)
        assertTrue(preview.missingApps.isEmpty())

        // The layout a v2 backup describes arrives intact.
        assertEquals(APP_ONE, preview.layout.slots[8])
        assertEquals(FOLDER, preview.layout.slots[10])
        assertEquals(APP_FOUR, preview.layout.dock[0])
        assertEquals(listOf(APP_TWO, APP_THREE), preview.layout.folders.single().appIds)
        assertEquals(2, preview.layout.widgetPlacements.size)
        assertEquals(false, preview.googleSearch)
        assertEquals(COMPACT, preview.compact)
        assertEquals(EXPANDED, preview.expanded)

        // Everything schema 9 added takes a default rather than failing the import.
        assertEquals(DuoSettings(), preview.settings)
        assertEquals(LeadingPageKind.CLASSIC, preview.leadingPage.kind)
        assertTrue(preview.stacks.isEmpty())
        assertTrue(preview.hiddenApps.isEmpty())
        assertTrue(preview.iconOverrides.isEmpty())

        // The single v2 arrangement is lifted into the schema-9 shape as the mirrored layout.
        assertEquals(LayoutMode.MIRRORED, preview.layoutSet.mode)
        assertEquals(APP_ONE, preview.layoutSet.mirrored.slots[8])
        assertEquals(DEFAULT_DOCK_CAPACITY, preview.layoutSet.dock.capacity)
        assertEquals(DockSide.RIGHT, preview.layoutSet.dock.side)
        assertEquals(FOLDER_TINT_FOLLOWS_ACCENT, preview.layoutSet.folders.single().tint)
        assertEquals(DuoFolderSize.SMALL, preview.layoutSet.folders.single().size)
    }

    @Test fun version1FixtureImportsWithAnEmptyLeadingPage() {
        val v1 = version2Fixture(version = 1).replace(
            """"leadingSlots":[${List(24) { "null" }.joinToString(",")}],""", "",
        )
        val preview = decode(v1)
        assertEquals(1, preview.version)
        assertEquals(List<String?>(HOME_CELLS) { null }, preview.layout.leadingSlots)
        assertEquals(APP_ONE, preview.layout.slots[8])
    }

    // -----------------------------------------------------------------------------------------
    // Refusal and hostile input (FR-83)
    // -----------------------------------------------------------------------------------------

    @Test fun backupFromANewerVersionIsRefusedWithTheSpecMessage() {
        val newer = encode().replace("\"version\":$LAYOUT_BACKUP_VERSION", "\"version\":99")
        val failure = assertThrows(IllegalArgumentException::class.java) { decode(newer) }
        assertEquals(BACKUP_TOO_NEW_MESSAGE, failure.message)
    }

    @Test fun refusalHappensBeforeAnyPlacementIsRead() {
        // Only the version is well formed; everything a layout needs is missing or wrong. If the
        // refusal were not the very first check, this would fail with some other message.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            decode("""{"version":4,"sourceScope":"","layoutSet":"not-an-object"}""")
        }
        assertEquals(BACKUP_TOO_NEW_MESSAGE, failure.message)
    }

    @Test fun oversizedPayloadIsRejectedAsAValue() {
        val huge = "{\"version\":3,\"padding\":\"" + "x".repeat(MAX_LAYOUT_BACKUP_BYTES) + "\"}"
        val failure = assertThrows(IllegalArgumentException::class.java) { decode(huge) }
        assertTrue(failure.message!!.contains("larger than 2 MB"))
    }

    @Test fun malformedAndWrongTypedPayloadsAreRejectedAsValues() {
        val cases = listOf(
            "" to "empty",
            "not json at all" to "garbage",
            "[]" to "a JSON array",
            "{" to "a truncated object",
            """{"version":"3"}""" to "a string version",
            """{"version":3}""" to "no source scope",
            """{"version":3,"sourceScope":""}""" to "a blank source scope",
        )
        cases.forEach { (payload, description) ->
            val failure = runCatching { decode(payload) }.exceptionOrNull()
            assertTrue("$description should be rejected", failure is IllegalArgumentException || failure is IllegalStateException)
        }
    }

    @Test fun wrongTypedFieldsInsideAValidDocumentAreRejected() {
        val mutations = listOf(
            "\"spanX\":2" to "\"spanX\":\"2\"",
            "\"capacity\":5" to "\"capacity\":\"five\"",
            "\"capacity\":5" to "\"capacity\":99",
            "\"glassLevel\":80" to "\"glassLevel\":true",
            "\"columns\":6" to "\"columns\":600",
            "\"labels\":false" to "\"labels\":\"false\"",
            "\"cell\":8" to "\"cell\":999",
        )
        mutations.forEach { (from, to) ->
            val broken = encode().replaceFirst(from, to)
            assertTrue("$from -> $to must be refused", broken != encode())
            val failure = runCatching { decode(broken) }.exceptionOrNull()
            assertTrue("$from -> $to must be refused",
                failure is IllegalArgumentException || failure is IllegalStateException)
        }
    }

    @Test fun aBackupDescribingAnIncoherentLayoutIsRefused() {
        // A widget sitting on top of an occupied cell is exactly what the on-disk validator
        // refuses, and a backup gets the identical treatment.
        val overlapping = encode().replaceFirst(
            """{"slot":1,"page":0,"column":2,"row":0""",
            """{"slot":1,"page":0,"column":0,"row":2""",
        )
        val failure = runCatching { decode(overlapping) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
    }

    @Test fun duplicateFolderIdsAreRefused() {
        val folder = """{"id":"$FOLDER","title":"Work things","tint":4,"size":"LARGE","apps":["$APP_TWO","$APP_THREE"]}"""
        val broken = encode().replaceFirst(folder, "$folder,$folder")
        val failure = runCatching { decode(broken) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
    }

    // -----------------------------------------------------------------------------------------
    // NFR-S7
    // -----------------------------------------------------------------------------------------

    @Test fun exportContainsNoNotificationContactOrLaunchHistoryData() {
        val document = root(encode())

        assertEquals(
            setOf("version", "sourceScope", "apps", "layoutSet", "dock", "folders", "stacks",
                "leadingPage", "hiddenApps", "iconOverrides", "settings", "labels", "googleSearch",
                "verticalStatus", "compact", "expanded"),
            document.values.keys,
        )
        // The settings block is a list of preferences, and every one of them is named here. A new
        // field that carried observed data rather than a preference would fail this.
        assertEquals(
            setOf("wallpaperSource", "dimInDark", "glassLevel", "reduceTransparency", "accent",
                "font", "iconAppearance", "iconTint", "iconTintIntensity", "iconShape", "largeIcons",
                "iconPack", "badgeStyle", "swipeDown", "swipeUp", "doubleTapLock", "lockLayout",
                "autoAddApps", "duoStatus", "libraryView", "searchContacts", "suggestions",
                "hidePrivateContainer"),
            (document["settings"] as DuoJson.Obj).values.keys,
        )

        // Nothing anywhere in the document may name observed data. `badgeStyle` is a preference
        // about how a badge is drawn, never a count, which is why the check is on whole key names.
        val forbidden = setOf(
            "badges", "badgeCount", "badgeCounts", "notification", "notifications", "notificationText",
            "contact", "contacts", "history", "launchHistory", "launches", "lastLaunched",
            "usage", "suggestionHistory", "media", "nowPlaying",
        )
        val offenders = mutableListOf<String>()
        fun scan(node: DuoJson) {
            when (node) {
                is DuoJson.Obj -> node.values.forEach { (key, value) ->
                    if (key in forbidden) offenders += key
                    scan(value)
                }
                is DuoJson.Arr -> node.values.forEach(::scan)
                else -> Unit
            }
        }
        scan(document)
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun anAppFromALockedPrivateSpaceIsNeverExported() {
        val set = fullLayoutSet().let { base ->
            // Cell 9 is the free cell between the folder and the widgets on the cover layout.
            val cover = base.cover.slots.toMutableList()
            cover[9] = PRIVATE_APP
            base.withLayout(LayoutTarget.COVER, base.cover.copy(slots = cover))
                .copy(dock = base.dock.copy(items = listOf(null, APP_DOCK, PRIVATE_APP, null, null)))
        }
        val state = fullState(set).copy(
            hiddenApps = setOf(APP_HIDDEN, PRIVATE_APP),
            iconOverrides = listOf(
                IconOverrideRecord(APP_TINTED, "com.example.pack", "ic_tinted", "Tinted"),
                IconOverrideRecord(PRIVATE_APP, "com.example.pack", "ic_secret", "Secret"),
            ),
        )

        val locked = encode(state) { it == PRIVATE_APP }
        assertTrue("A locked private app must not appear anywhere in the file",
            !locked.contains("com.example.secret"))
        val preview = decode(locked)
        assertNull(preview.layoutSet.cover.slots.getOrNull(9))
        assertNull(preview.layoutSet.dock.items[2])
        assertEquals(setOf(APP_HIDDEN), preview.hiddenApps)
        assertEquals(listOf(APP_TINTED), preview.iconOverrides.map(IconOverrideRecord::profileAppId))

        // Unlocked, the same layout keeps the app: PRIVACY.md promises exactly this difference.
        val unlocked = encode(state) { false }
        assertTrue(unlocked.contains("com.example.secret"))
        assertEquals(PRIVATE_APP, decode(unlocked).layoutSet.cover.slots[9])
    }

    @Test fun aPrivateAppInsideAFolderIsRemovedAndTheFolderStaysCoherent() {
        val set = fullLayoutSet().let { base ->
            base.copy(folders = listOf(base.folders.single().copy(appIds = listOf(APP_TWO, PRIVATE_APP))))
        }
        val locked = encode(fullState(set)) { it == PRIVATE_APP }
        assertTrue(!locked.contains("com.example.secret"))

        // The folder lost a member and collapsed to its last app rather than leaving a dangling
        // reference that would fail validation on import.
        val preview = decode(locked)
        assertEquals(APP_TWO, preview.layoutSet.cover.slots[10])
    }
}
