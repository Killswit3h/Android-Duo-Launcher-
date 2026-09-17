package com.jake.duolauncher

import com.jake.duolauncher.shortcuts.isPinnedShortcutId
import com.jake.duolauncher.shortcuts.parsePinnedShortcutId

/**
 * Backup version 3: every layout and setting schema 9 added (FR-82).
 *
 * ## Shape
 *
 * The document mirrors the on-disk schema-9 record, with three backup-specific differences:
 *
 * 1. **`version` and `sourceScope`** identify the file and the device that wrote it. `sourceScope`
 *    is what keeps a work-profile placement from silently resolving to a different profile on
 *    another device.
 * 2. **Widgets carry a portable provider descriptor** instead of a live `appWidgetId`. An
 *    `appWidgetId` is meaningless on another device, so each widget is written as either a built-in
 *    kind or `provider` + `userSerial` + `title` + `profileLabel` + `work` + `sourceScope`, and
 *    imports as a Reconnect placeholder — the behaviour the error table already specifies.
 * 3. **`apps` carries label metadata** for the ids the layout references, so the review screen can
 *    name an app that is not installed here rather than showing a raw component string.
 *
 * ## What is not here
 *
 * There is no encoder for notification or badge counts, contacts, launch history or suggestion
 * ranking (NFR-S7). That is a property of this file rather than a filter applied to it: the writer
 * below reads only `layoutSet`, `leadingPage`, `stacks`, `hiddenApps`, `iconOverrides`, `settings`
 * and the presets, so there is no path by which observed data could reach a backup.
 *
 * The one category that *is* reachable from a stored layout is an app placed from a private space,
 * because its id stays in the layout after the space locks. [encodeLayoutBackupV3] drops those ids
 * when the caller's gate claims them, which matches `PRIVACY.md` exactly: an export taken while the
 * space is unlocked can include apps placed from it; one taken while it is locked cannot.
 */

// ---------------------------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------------------------

internal fun encodeLayoutBackupV3(
    state: LauncherState,
    widgetDescriptors: List<BackupWidgetDescriptor>,
    sourceScope: String,
    isPrivateLocked: (String) -> Boolean,
): String {
    require(sourceScope.isNotBlank())
    val descriptorBySlot = widgetDescriptors.associateBy(BackupWidgetDescriptor::slot)
    val set = state.exportedLayoutSet().withoutExcluded(isPrivateLocked)

    val referenced = set.referencedIds()
    val appIds = referenced.filterNot { isReservedFolderId(it) || isPinnedShortcutId(it) }.toMutableSet()
    val hiddenApps = state.hiddenApps.filterNot(isPrivateLocked).sorted()
    val iconOverrides = state.iconOverrides.filterNot { isPrivateLocked(it.profileAppId) }
    appIds += hiddenApps
    appIds += iconOverrides.map(IconOverrideRecord::profileAppId)

    val byId = state.apps.associateBy(AppEntry::id)
    val apps = appIds.sorted().mapNotNull { id -> byId[id] }.map { app ->
        DuoJson.obj(
            "id" to DuoJson.of(app.id),
            "label" to DuoJson.of(app.label),
            "component" to DuoJson.of(app.component.flattenToString()),
            "userSerial" to DuoJson.of(app.userSerial),
            "profileLabel" to DuoJson.of(app.profileLabel),
            "work" to DuoJson.of(app.isWork),
        )
    }

    return DuoJson.obj(
        "version" to DuoJson.of(LAYOUT_BACKUP_VERSION),
        "sourceScope" to DuoJson.of(sourceScope),
        "apps" to DuoJson.Arr(apps),
        "layoutSet" to DuoJson.obj(
            "mode" to DuoJson.of(set.mode.name),
            "mirrored" to encodeBackupLayout(set.mirrored, descriptorBySlot, sourceScope),
            "cover" to encodeBackupLayout(set.cover, descriptorBySlot, sourceScope),
            "inner" to encodeBackupLayout(set.inner, descriptorBySlot, sourceScope),
        ),
        "dock" to DuoJson.obj(
            "side" to DuoJson.of(set.dock.side.name),
            "capacity" to DuoJson.of(set.dock.capacity),
            "items" to DuoJson.ofNullableStrings(set.dock.items),
        ),
        "folders" to DuoJson.Arr(
            set.folders.map { folder ->
                DuoJson.obj(
                    "id" to DuoJson.of(folder.id),
                    "title" to DuoJson.of(folder.title),
                    "tint" to DuoJson.of(folder.tint),
                    "size" to DuoJson.of(folder.size.name),
                    "apps" to DuoJson.ofStrings(folder.appIds),
                )
            },
        ),
        "stacks" to DuoJson.Arr(
            state.stacks.map { stack ->
                DuoJson.obj(
                    "id" to DuoJson.of(stack.id),
                    "slots" to DuoJson.ofInts(stack.placementSlots),
                    "activeIndex" to DuoJson.of(stack.activeIndex),
                    "smartRotate" to DuoJson.of(stack.smartRotate),
                )
            },
        ),
        "leadingPage" to DuoJson.obj(
            "kind" to DuoJson.of(state.leadingPage.kind.name),
            "today" to DuoJson.ofStrings(state.leadingPage.today),
        ),
        "hiddenApps" to DuoJson.ofStrings(hiddenApps),
        "iconOverrides" to DuoJson.Arr(
            iconOverrides.map { override ->
                DuoJson.obj(
                    "app" to DuoJson.of(override.profileAppId),
                    "iconPack" to (override.iconPack?.let(DuoJson::of) ?: DuoJson.Null),
                    "drawable" to (override.drawableName?.let(DuoJson::of) ?: DuoJson.Null),
                    "label" to (override.label?.let(DuoJson::of) ?: DuoJson.Null),
                )
            },
        ),
        "settings" to encodeBackupSettings(state.settings),
        "labels" to DuoJson.of(state.labels),
        "googleSearch" to DuoJson.of(state.googleSearch),
        "verticalStatus" to DuoJson.of(state.verticalStatus),
        "compact" to encodeBackupPreset(state.compact),
        "expanded" to encodeBackupPreset(state.expanded),
    ).write()
}

/**
 * The layout set to export, with the active layout spliced back in.
 *
 * `layoutSet` is the source of truth and the flat fields are derived from it, so in a running
 * launcher this is a no-op. It matters when a [LauncherState] was assembled from the flat fields
 * alone — which is how the backup tests and every pre-schema-9 call site build one — because
 * exporting `layoutSet` directly would then write an empty layout. Splicing keeps the other two
 * layouts untouched either way.
 */
internal fun LauncherState.exportedLayoutSet(): LayoutSet {
    val active = layoutSet.layout(activeTarget)
    if (!active.isEmpty) return layoutSet
    val flat = layout
    val hasFlatLayout = flat.slots.any { it != null } || flat.leadingSlots.any { it != null } ||
        flat.widgetPlacements.isNotEmpty()
    return if (hasFlatLayout) layoutSet.withHomeLayout(activeTarget, flat) else layoutSet
}

/** Every id any surface of this layout set references. */
private fun LayoutSet.referencedIds(): Set<String> = buildSet {
    LayoutTarget.entries.forEach { target ->
        val layout = layout(target)
        addAll(layout.slots.filterNotNull())
        addAll(layout.leadingSlots.filterNotNull())
    }
    addAll(dock.items.filterNotNull())
    folders.forEach { addAll(it.appIds) }
}

/**
 * Removes every id the private-space gate claims (NFR-S7).
 *
 * A folder that loses members collapses the way the importer already collapses one: down to its
 * single remaining app, or to an empty cell. That keeps the exported document self-consistent —
 * no reference to a folder that is no longer there — without inventing a new rule for it.
 */
private fun LayoutSet.withoutExcluded(isExcluded: (String) -> Boolean): LayoutSet {
    if (LayoutTarget.entries.none { target ->
            val layout = layout(target)
            (layout.slots + layout.leadingSlots).filterNotNull().any(isExcluded)
        } && dock.items.filterNotNull().none(isExcluded) && folders.none { it.appIds.any(isExcluded) }
    ) {
        return this
    }
    val prunedFolders = folders.map { folder -> folder.copy(appIds = folder.appIds.filterNot(isExcluded)) }
    val replacement = mutableMapOf<String, String?>()
    val keptFolders = prunedFolders.filter { folder ->
        when (folder.appIds.size) {
            0 -> { replacement[folder.id] = null; false }
            1 -> { replacement[folder.id] = folder.appIds.single(); false }
            else -> true
        }
    }
    fun resolve(id: String?): String? = when {
        id == null -> null
        isExcluded(id) -> null
        id in replacement -> replacement[id]
        else -> id
    }
    var result = this
    LayoutTarget.entries.forEach { target ->
        val layout = layout(target)
        result = result.withLayout(
            target,
            layout.copy(
                slots = layout.slots.map(::resolve).dropLastWhile { it == null },
                leadingSlots = layout.leadingSlots.map(::resolve),
            ),
        )
    }
    return result.copy(dock = dock.copy(items = dock.items.map(::resolve)), folders = keptFolders)
}

private fun encodeBackupLayout(
    layout: DuoLayout,
    descriptors: Map<Int, BackupWidgetDescriptor>,
    sourceScope: String,
): DuoJson.Obj {
    val prepared = layout.withPageIds()
    val grid = prepared.grid
    val pages = (0 until prepared.pageCount).map { page ->
        val items = (0 until grid.cells).mapNotNull { local ->
            prepared.slots.getOrNull(page * grid.cells + local)?.let { encodeBackupItem(local, it) }
        }
        DuoJson.obj(
            "id" to DuoJson.of(prepared.pageIds.getOrElse(page) { page + 1 }),
            "items" to DuoJson.Arr(items),
        )
    }
    val leading = prepared.leadingSlots.mapIndexedNotNull { local, id ->
        id?.let { encodeBackupItem(local, it) }
    }
    val restoreBySlot = prepared.widgetRestores.associateBy(WidgetRestore::slot)
    return DuoJson.obj(
        "grid" to DuoJson.obj("columns" to DuoJson.of(grid.columns), "rows" to DuoJson.of(grid.rows)),
        "pages" to DuoJson.Arr(pages),
        "hiddenPageIds" to DuoJson.ofInts(prepared.hiddenPageIds.sorted()),
        "leading" to DuoJson.Arr(leading),
        "widgets" to DuoJson.Arr(
            prepared.widgetPlacements.map { placement ->
                encodeBackupWidget(placement, restoreBySlot[placement.slot], descriptors[placement.slot], sourceScope)
            },
        ),
    )
}

private fun encodeBackupItem(cell: Int, id: String): DuoJson.Obj = DuoJson.obj(
    "cell" to DuoJson.of(cell),
    "kind" to DuoJson.of(homeItemKind(id)),
    "id" to DuoJson.of(id),
)

/**
 * A widget as something another device can rebuild.
 *
 * The order of preference matters: a saved [WidgetRestore] wins over a live descriptor because it
 * carries the *original* `sourceScope`, so re-exporting an imported-but-not-yet-reconnected widget
 * keeps pointing at the device that really owned it rather than claiming this one did.
 */
private fun encodeBackupWidget(
    placement: WidgetPlacement,
    restore: WidgetRestore?,
    descriptor: BackupWidgetDescriptor?,
    sourceScope: String,
): DuoJson.Obj {
    val base = linkedMapOf<String, DuoJson>(
        "slot" to DuoJson.of(placement.slot),
        "page" to DuoJson.of(placement.page),
        "column" to DuoJson.of(placement.column),
        "row" to DuoJson.of(placement.row),
        "spanX" to DuoJson.of(placement.spanX),
        "spanY" to DuoJson.of(placement.spanY),
    )
    when {
        builtinWidgetKind(placement.id) != null -> {
            base["builtinId"] = DuoJson.of(placement.id)
            base["kind"] = DuoJson.of(builtinWidgetKind(placement.id)!!)
        }
        restore != null -> {
            base["provider"] = DuoJson.of(restore.providerComponent)
            base["userSerial"] = DuoJson.of(restore.userSerial)
            base["title"] = DuoJson.of(restore.title)
            base["profileLabel"] = DuoJson.of(restore.profileLabel)
            base["work"] = DuoJson.of(restore.isWork)
            base["sourceScope"] = DuoJson.of(exportedWidgetScope(restore, sourceScope))
        }
        descriptor?.providerComponent != null && descriptor.userSerial != null -> {
            base["provider"] = DuoJson.of(descriptor.providerComponent)
            base["userSerial"] = DuoJson.of(descriptor.userSerial)
            base["title"] = DuoJson.of(descriptor.title)
            base["profileLabel"] = DuoJson.of(descriptor.profileLabel)
            base["work"] = DuoJson.of(descriptor.isWork)
            base["sourceScope"] = DuoJson.of(sourceScope)
        }
        else -> error("Widget ${placement.slot} has no portable provider descriptor")
    }
    return DuoJson.Obj(base)
}

private fun encodeBackupPreset(preset: LayoutPreset): DuoJson.Obj = DuoJson.obj(
    "iconSize" to DuoJson.of(preset.iconSize),
    "rowGap" to DuoJson.of(preset.rowGap),
    "dockWidth" to DuoJson.of(preset.dockWidth),
    "dockPosition" to DuoJson.of(preset.dockPosition),
    "dockAlignToGrid" to DuoJson.of(preset.dockAlignToGrid),
)

private fun encodeBackupSettings(settings: DuoSettings): DuoJson.Obj = DuoJson.obj(
    "wallpaperSource" to DuoJson.of(settings.wallpaperSource.name),
    "dimInDark" to DuoJson.of(settings.dimInDark),
    "glassLevel" to DuoJson.of(settings.glassLevel),
    "reduceTransparency" to DuoJson.of(settings.reduceTransparency),
    "accent" to (settings.accent?.let(DuoJson::of) ?: DuoJson.Null),
    "font" to DuoJson.of(settings.font.name),
    "iconAppearance" to DuoJson.of(settings.iconAppearance.name),
    "iconTint" to DuoJson.of(settings.iconTint),
    "iconTintIntensity" to DuoJson.of(settings.iconTintIntensity),
    "iconShape" to DuoJson.of(settings.iconShape.name),
    "largeIcons" to DuoJson.of(settings.largeIcons),
    "iconPack" to (settings.iconPack?.let(DuoJson::of) ?: DuoJson.Null),
    "badgeStyle" to DuoJson.of(settings.badgeStyle.name),
    "swipeDown" to DuoJson.of(settings.swipeDown.name),
    "swipeUp" to DuoJson.of(settings.swipeUp.name),
    "doubleTapLock" to DuoJson.of(settings.doubleTapLock),
    "lockLayout" to DuoJson.of(settings.lockLayout),
    "autoAddApps" to DuoJson.of(settings.autoAddApps),
    "duoStatus" to DuoJson.of(settings.duoStatus),
    "libraryView" to DuoJson.of(settings.libraryView.name),
    "searchContacts" to DuoJson.of(settings.searchContacts),
    "suggestions" to DuoJson.of(settings.suggestions),
    "hidePrivateContainer" to DuoJson.of(settings.hidePrivateContainer),
)

// ---------------------------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------------------------

/**
 * Reads a v3 document.
 *
 * Every placement is resolved against what is installed *here* before anything is validated, so an
 * app this device does not have becomes an empty cell and a named entry in the review screen's
 * "Unavailable apps" list rather than a failure. Once resolved, the result is handed to
 * [validate] — the same invariant check the on-disk schema-9 loader uses — so a backup can never
 * describe a layout the launcher would refuse to save.
 */
internal fun decodeLayoutBackupV3(
    root: DuoJson.Obj,
    catalog: BackupCatalog,
    currentProfiles: List<AppProfile>,
    currentScope: String,
): LayoutImportPreview {
    val sourceScope = root.strictString("sourceScope").also { require(it.isNotBlank()) }
    val sameScope = sourceScope == currentScope
    val resolver = BackupAppResolver(root, catalog, currentProfiles, currentScope, sameScope)

    val folderArray = root.strictArray("folders")
    require(folderArray.size <= MAX_BACKUP_FOLDERS) { "Layout backup lists too many folders" }
    val declaredFolders = folderArray.strictObjects().map { item ->
        val apps = item.strictArray("apps")
        require(apps.size <= MAX_BACKUP_IDS) { "A folder lists too many apps" }
        FolderEntry(
            id = item.strictString("id"),
            title = item.strictString("title"),
            appIds = apps.strictIds(),
            tint = item.strictIntOr("tint", FOLDER_TINT_FOLLOWS_ACCENT),
            size = item.enumOr("size", DuoFolderSize.SMALL),
        )
    }
    require(declaredFolders.map(FolderEntry::id).distinct().size == declaredFolders.size) {
        "Folder ids must be unique"
    }
    declaredFolders.forEach { folder ->
        require(isFolderId(folder.id) && folder.title.isNotBlank()) { "A folder is malformed" }
        require(folder.appIds.none(::isReservedFolderId)) { "A folder cannot nest" }
    }
    val allChildren = declaredFolders.flatMap(FolderEntry::appIds)
    require(allChildren.distinct().size == allChildren.size) { "An app is in two folders" }

    // A folder that loses members collapses to its last app, or to an empty cell.
    val resolvedFolders = declaredFolders.map { folder ->
        folder.copy(appIds = folder.appIds.mapNotNull(resolver::resolve))
    }
    val collapsed = mutableMapOf<String, String?>()
    val folders = resolvedFolders.filter { folder ->
        when (folder.appIds.size) {
            0 -> { collapsed[folder.id] = null; false }
            1 -> { collapsed[folder.id] = folder.appIds.single(); false }
            else -> true
        }
    }
    val folderIds = folders.mapTo(mutableSetOf(), FolderEntry::id)

    fun resolveCell(id: String?): String? = when {
        id == null -> null
        isReservedFolderId(id) -> {
            require(id in folderIds || id in collapsed) { "A layout references a folder that does not exist" }
            if (id in folderIds) id else collapsed[id]
        }
        else -> resolver.resolve(id)
    }

    val setJson = root.strictObj("layoutSet")
    val dockJson = root.strictObj("dock")
    val dockItems = dockJson.strictArray("items")
    require(dockItems.size <= MAX_DOCK_CAPACITY) { "The dock lists too many items" }
    val dock = DockConfig(
        side = dockJson.enumOr("side", DockSide.RIGHT),
        capacity = dockJson.strictInt("capacity"),
        items = dockItems.strictNullableIds().map { id ->
            id?.also { require(!isReservedFolderId(it) || it in folderIds || it in collapsed) }
            resolveCell(id)
        },
    )
    require(dock.capacity in MIN_DOCK_CAPACITY..MAX_DOCK_CAPACITY) { "Dock capacity out of range" }
    require(dock.items.size == dock.capacity) { "Dock items must match capacity" }

    val set = LayoutSet(
        mode = setJson.enumOr("mode", LayoutMode.MIRRORED),
        mirrored = decodeBackupLayout(setJson.strictObj("mirrored"), ::resolveCell, resolver, sourceScope),
        cover = decodeBackupLayout(setJson.strictObj("cover"), ::resolveCell, resolver, sourceScope),
        inner = decodeBackupLayout(setJson.strictObj("inner"), ::resolveCell, resolver, sourceScope),
        dock = dock,
        folders = folders,
    )

    val stackArray = root.strictArray("stacks")
    require(stackArray.size <= MAX_BACKUP_STACKS) { "Layout backup lists too many stacks" }
    val stacks = stackArray.strictObjects().map { item ->
        val slots = item.strictArray("slots")
        require(slots.size <= MAX_STACK_WIDGETS) { "A stack holds at most $MAX_STACK_WIDGETS widgets" }
        WidgetStack(
            id = item.strictString("id").also { require(it.isNotBlank()) { "A stack needs an id" } },
            placementSlots = slots.strictInts(),
            activeIndex = item.strictIntOr("activeIndex", 0),
            smartRotate = item.optBoolean("smartRotate", false),
        )
    }

    val leadingJson = root.strictObj("leadingPage")
    val today = leadingJson.strictArray("today")
    require(today.size <= MAX_BACKUP_IDS) { "Today lists too many items" }

    val hiddenArray = root.strictArray("hiddenApps")
    require(hiddenArray.size <= MAX_BACKUP_IDS) { "Layout backup lists too many hidden apps" }
    // A hidden app is a preference about an app, not a placement, so one this device does not have
    // is simply dropped rather than reported as a missing placement.
    val hiddenApps = hiddenArray.strictIds()
        .onEach { require(parseProfileAppId(it) != null) { "A hidden app id is malformed" } }
        .filter(resolver::isInstalled).toSet()

    val overrideArray = root.strictArray("iconOverrides")
    require(overrideArray.size <= MAX_BACKUP_IDS) { "Layout backup lists too many icon overrides" }
    val iconOverrides = overrideArray.strictObjects().map { item ->
        IconOverrideRecord(
            profileAppId = item.strictString("app").also { require(parseProfileAppId(it) != null) },
            iconPack = item.optString("iconPack"),
            drawableName = item.optString("drawable"),
            label = item.optString("label"),
        )
    }.filter { resolver.isInstalled(it.profileAppId) }

    fun preset(key: String): LayoutPreset {
        val item = root.strictObj(key)
        val loaded = LayoutPreset(
            item.strictFloat("iconSize"), item.strictFloat("rowGap"),
            item.strictFloat("dockWidth"), item.strictFloat("dockPosition"), item.strictBoolean("dockAlignToGrid"),
        )
        require(loaded == loaded.sanitized()) { "Invalid layout preset" }
        return loaded
    }

    val persisted = LauncherPersistedState(
        layoutSet = set,
        leadingPage = LeadingPageConfig(
            kind = leadingJson.enumOr("kind", LeadingPageKind.TODAY),
            today = today.strictIds(),
        ),
        stacks = stacks,
        hiddenApps = hiddenApps,
        iconOverrides = iconOverrides,
        settings = decodeBackupSettings(root.strictObj("settings")),
        labels = root.strictBoolean("labels"),
        googleSearch = root.strictBoolean("googleSearch"),
        verticalStatus = root.strictBoolean("verticalStatus"),
        compact = preset("compact"),
        expanded = preset("expanded"),
    )
    // The same invariants an on-disk schema-9 payload must satisfy. A backup that fails here
    // describes a layout the launcher would refuse to save, so it is refused as a value. Stacks are
    // pruned first, for the same reason the decoder prunes: a backup written by an older build can
    // carry a stack whose widget has since gone, and repairing that is better than refusing it.
    val coherent = persisted.withCoherentStacks()
    validate(coherent)

    val placedApps = LayoutTarget.entries.flatMap { target ->
        val layout = set.layout(target)
        (layout.slots + layout.leadingSlots).filterNotNull()
    }.filterNot(::isReservedFolderId).toSet() +
        set.dock.items.filterNotNull().filterNot(::isReservedFolderId).toSet()
    return LayoutImportPreview(
        layout = set.homeLayout(set.targetFor(expanded = false)),
        missingApps = resolver.missing(),
        profileIssues = resolver.profileIssues(),
        appCount = placedApps.size + folders.sumOf { it.appIds.size },
        folderCount = folders.size,
        widgetCount = LayoutTarget.entries.sumOf { set.layout(it).widgetPlacements.size },
        compact = coherent.compact,
        expanded = coherent.expanded,
        labels = coherent.labels,
        googleSearch = coherent.googleSearch,
        verticalStatus = coherent.verticalStatus,
        layoutSet = set,
        // Built from the pruned document, not the raw one. Validating one value and previewing a
        // different one would show the user stacks that the restore is about to drop.
        leadingPage = coherent.leadingPage,
        stacks = coherent.stacks,
        hiddenApps = coherent.hiddenApps,
        iconOverrides = coherent.iconOverrides,
        settings = coherent.settings,
        version = LAYOUT_BACKUP_VERSION,
    )
}

private fun decodeBackupLayout(
    json: DuoJson.Obj,
    resolveCell: (String?) -> String?,
    resolver: BackupAppResolver,
    sourceScope: String,
): DuoLayout {
    val gridJson = json.strictObj("grid")
    val grid = GridSpec(gridJson.strictInt("columns"), gridJson.strictInt("rows"))
    require(grid.columns in MIN_GRID_SIZE..MAX_GRID_SIZE && grid.rows in MIN_GRID_SIZE..MAX_GRID_SIZE) {
        "A layout grid is outside $MIN_GRID_SIZE..$MAX_GRID_SIZE"
    }

    val pagesArray = json.strictArray("pages")
    require(pagesArray.size <= MAX_BACKUP_PAGES) { "A layout has too many pages" }
    val pages = pagesArray.strictObjects()
    val slots = MutableList<String?>(pages.size * grid.cells) { null }
    val pageIds = mutableListOf<Int>()
    pages.forEachIndexed { page, pageJson ->
        pageIds += pageJson.strictIntOr("id", page + 1)
        val items = pageJson.strictArray("items")
        require(items.size <= grid.cells) { "A page holds more items than it has cells" }
        items.strictObjects().forEach { item ->
            val cell = item.strictInt("cell")
            val id = item.strictString("id")
            require(cell in 0 until grid.cells) { "A home item is outside the grid" }
            val index = page * grid.cells + cell
            require(slots[index] == null) { "Two home items share a cell" }
            slots[index] = resolveCell(id)
        }
    }

    val leading = MutableList<String?>(grid.cells) { null }
    val leadingArray = json.strictArray("leading")
    require(leadingArray.size <= grid.cells) { "The leading page holds more items than it has cells" }
    leadingArray.strictObjects().forEach { item ->
        val cell = item.strictInt("cell")
        val id = item.strictString("id")
        require(cell in 0 until grid.cells) { "A leading item is outside the grid" }
        require(leading[cell] == null) { "Two leading items share a cell" }
        leading[cell] = resolveCell(id)
    }

    val widgetArray = json.strictArray("widgets")
    require(widgetArray.size <= MAX_BACKUP_WIDGETS) { "A layout has too many widgets" }
    val placements = mutableListOf<WidgetPlacement>()
    val restores = mutableListOf<WidgetRestore>()
    widgetArray.strictObjects().forEach { item ->
        val slot = item.strictInt("slot")
        val builtin = if (item.has("builtinId")) item.strictInt("builtinId") else null
        val id = if (builtin != null) {
            require(builtin in setOf(CLOCK_WIDGET, DATE_WIDGET, INFO_WIDGET)) { "Unknown built-in widget" }
            builtin
        } else {
            NEEDS_BINDING_WIDGET
        }
        placements += WidgetPlacement(
            slot, id, item.strictInt("page"), item.strictInt("column"), item.strictInt("row"),
            item.strictInt("spanX"), item.strictInt("spanY"),
        )
        if (id == NEEDS_BINDING_WIDGET) restores += resolver.restore(item, slot, sourceScope)
    }

    return DuoLayout(
        grid = grid,
        slots = slots.dropLastWhile { it == null },
        leadingSlots = leading,
        widgetPlacements = placements,
        widgetRestores = restores,
        pageIds = pageIds,
        hiddenPageIds = json.strictArray("hiddenPageIds").strictInts().toSet(),
    ).withPageIds()
}

private fun decodeBackupSettings(json: DuoJson.Obj): DuoSettings {
    val default = DuoSettings()
    return DuoSettings(
        wallpaperSource = json.enumOr("wallpaperSource", default.wallpaperSource),
        dimInDark = json.optBoolean("dimInDark", default.dimInDark),
        glassLevel = json.strictIntOr("glassLevel", default.glassLevel).coerceIn(0, 100),
        reduceTransparency = json.optBoolean("reduceTransparency", default.reduceTransparency),
        accent = json.optInt("accent"),
        font = json.enumOr("font", default.font),
        iconAppearance = json.enumOr("iconAppearance", default.iconAppearance),
        iconTint = json.strictIntOr("iconTint", default.iconTint),
        iconTintIntensity = json.strictIntOr("iconTintIntensity", default.iconTintIntensity).coerceIn(0, 100),
        iconShape = json.enumOr("iconShape", default.iconShape),
        largeIcons = json.optBoolean("largeIcons", default.largeIcons),
        iconPack = json.optString("iconPack"),
        badgeStyle = json.enumOr("badgeStyle", default.badgeStyle),
        swipeDown = json.enumOr("swipeDown", default.swipeDown),
        swipeUp = json.enumOr("swipeUp", default.swipeUp),
        doubleTapLock = json.optBoolean("doubleTapLock", default.doubleTapLock),
        lockLayout = json.optBoolean("lockLayout", default.lockLayout),
        autoAddApps = json.optBoolean("autoAddApps", default.autoAddApps),
        duoStatus = json.optBoolean("duoStatus", default.duoStatus),
        libraryView = json.enumOr("libraryView", default.libraryView),
        searchContacts = json.optBoolean("searchContacts", default.searchContacts),
        suggestions = json.optBoolean("suggestions", default.suggestions),
        hidePrivateContainer = json.optBoolean("hidePrivateContainer", default.hidePrivateContainer),
    )
}

/**
 * Decides what each stored id means on *this* device.
 *
 * The rules it enforces are the ones that stop a backup from resolving to the wrong thing:
 *
 * - An app id must be a well-formed `profileAppId`. A malformed one fails the import rather than
 *   being placed as an opaque string.
 * - A **work-profile** app or shortcut from another device is not placed. Profile serials are
 *   per-device, so serial 10 on the exporting phone need not be the same profile here; without a
 *   matching `sourceScope` the only safe answer is to leave the cell empty and say so.
 * - A pinned shortcut is self-describing (`duo-shortcut:v1:<user>:<package>:<id>`), so it is
 *   validated by parsing rather than by trusting a metadata block.
 */
private class BackupAppResolver(
    root: DuoJson.Obj,
    catalog: BackupCatalog,
    currentProfiles: List<AppProfile>,
    private val currentScope: String,
    private val sameScope: Boolean,
) {
    private val labels: Map<String, String>
    private val installed: Set<String>
    private val profileSerials = currentProfiles.mapTo(mutableSetOf(), AppProfile::userSerial)
    private val personalSerial = currentProfiles.firstOrNull(AppProfile::isPersonal)?.userSerial
    private val missing = linkedSetOf<String>()
    private val issues = linkedSetOf<String>()

    init {
        val appsArray = root.strictArray("apps")
        require(appsArray.size <= MAX_BACKUP_APPS) { "Layout backup lists too many apps" }
        val metadata = appsArray.strictObjects().map { item ->
            val id = item.strictString("id")
            val identity = parseProfileAppId(id) ?: error("Invalid app identity")
            require(item.strictString("component") == identity.component)
            require(isFlattenedComponent(identity.component))
            val serial = item.strictLong("userSerial")
            require(serial >= 0 && item.strictString("label").isNotBlank())
            require(item.strictString("profileLabel").isNotBlank())
            if (item.strictBoolean("work")) require(identity.userSerial == serial) else require(identity.userSerial == null)
            id to item.strictString("label")
        }
        require(metadata.map { it.first }.distinct().size == metadata.size) { "An app is described twice" }
        labels = metadata.toMap()
        installed = catalog.available(sameScope)
    }

    fun isInstalled(id: String): Boolean = id in installed

    /** The id to place, or null when this device cannot place it. */
    fun resolve(id: String): String? {
        if (isPinnedShortcutId(id)) return resolveShortcut(id)
        val identity = parseProfileAppId(id) ?: error("Layout references a malformed app id")
        require(isFlattenedComponent(identity.component)) { "Layout references a malformed component" }
        if (id in installed) return id
        missing += "$id (${labels[id] ?: identity.component})"
        return null
    }

    private fun resolveShortcut(id: String): String? {
        val key = parsePinnedShortcutId(id) ?: error("Layout references a malformed shortcut id")
        if (key.userSerial == personalSerial) return id
        if (sameScope && key.userSerial in profileSerials) return id
        issues += "${key.packageName} (a shortcut from another profile requires explicit mapping)"
        return null
    }

    /** Builds the Reconnect metadata for an unbound widget, applying the same profile rule. */
    fun restore(item: DuoJson.Obj, slot: Int, sourceScope: String): WidgetRestore {
        val provider = item.optString("provider") ?: error("A widget restore needs a provider")
        require(isFlattenedComponent(provider)) { "A widget provider is malformed" }
        val savedSerial = item.strictLong("userSerial")
        val title = item.strictString("title")
        val profileLabel = item.strictString("profileLabel")
        require(title.isNotBlank() && profileLabel.isNotBlank()) { "A widget restore is incomplete" }
        val work = item.strictBoolean("work")
        val widgetScope = item.optString("sourceScope") ?: sourceScope
        val serial = if (work) savedSerial else personalSerial ?: savedSerial
        if ((work && widgetScope != currentScope) || serial !in profileSerials) {
            issues += "$title ($profileLabel profile requires explicit mapping)"
        }
        return WidgetRestore(slot, provider, serial, title, profileLabel, work, widgetScope)
    }

    fun missing(): List<String> = missing.toList()

    fun profileIssues(): List<String> = issues.toList()
}
