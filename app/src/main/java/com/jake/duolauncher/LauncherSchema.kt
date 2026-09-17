package com.jake.duolauncher

import com.jake.duolauncher.icons.IconAppearance
import com.jake.duolauncher.icons.IconShape
import com.jake.duolauncher.shortcuts.isPinnedShortcutId

/**
 * Schema 9: the persisted shape of the user's Home screen (spec section 7, FR-31…FR-36).
 *
 * ## The rule this file is written to
 *
 * Losing or corrupting a saved layout is the worst outcome this project has, so every decision here
 * favours "keep the bytes" over "keep it tidy":
 *
 * - **Nothing is ever discarded.** A `LayoutSet` stores the mirrored, cover and inner layouts
 *   independently (ADR-4), so switching modes or grids is never destructive.
 * - **The v8 payload is backed up verbatim before anything is rewritten**, so even a bug in this
 *   file leaves the original recoverable (`state_v8_backup`).
 * - **Decoding is strict and total.** A payload either decodes into a fully valid state or it fails
 *   and the caller falls back to a backup. There is no partial read that silently drops placements.
 *
 * ## Relationship to the in-memory model
 *
 * [LauncherState] keeps its existing flat fields (`homeSlots`, `leadingSlots`, `dock`, …) as the
 * *active* layout's working representation, so the whole editing layer, the Compose UI and the
 * existing test suite keep operating on exactly the types they already use. `LayoutSet` is the
 * persisted superset that holds all three layouts plus the shared dock and folders.
 *
 * ## How schema 9 expresses the spec's `HomeItem` union
 *
 * A page's `items` carry the cell-occupying items — `App`, `Shortcut` and `Folder` — which are told
 * apart by their id (`isFolderId`, `isPinnedShortcutId`, otherwise an app's `profileAppId`).
 * `Widget` and `Stack` live in the layout's `widgets` array because they own a rectangle rather than
 * a cell, and that array is the proven v8 geometry record. Encoding a widget's rectangle in one
 * place only is deliberate: two copies of the same geometry is exactly the kind of redundancy that
 * eventually disagrees and loses a placement.
 */

/** The version written by [encodeLauncherState]. */
const val LAYOUT_SCHEMA_VERSION = 9

/** The oldest schema this build can still read and migrate. */
const val OLDEST_SUPPORTED_SCHEMA = 1

const val MIN_GRID_SIZE = 4
const val MAX_GRID_SIZE = 8
const val MIN_DOCK_CAPACITY = 3
const val MAX_DOCK_CAPACITY = 6

/** A stack holds at most 10 widgets (FR-61). */
const val MAX_STACK_WIDGETS = 10

/** Preference keys. The v8 payload is preserved under its own key forever (FR-35). */
const val STATE_KEY = "state"
const val STATE_V8_BACKUP_KEY = "state_v8_backup"

/** The last payload that decoded cleanly, used as the first recovery step (error table). */
const val STATE_V9_PREVIOUS_KEY = "state_v9_previous"

enum class LayoutMode { MIRRORED, SEPARATE }

enum class DockSide { RIGHT, LEFT }

enum class LeadingPageKind { TODAY, DISCOVER, CLASSIC }

/** FR-66: a folder tile is a 1×1 mini preview or a 2×2 large tile. */
enum class DuoFolderSize { SMALL, LARGE }

enum class DuoBadgeStyle { OFF, DOT, NUMBER }

enum class SwipeDownAction { SEARCH, NOTIFICATIONS, NONE }

enum class SwipeUpAction { APP_LIBRARY, NONE }

enum class WallpaperSource { DUO, SYSTEM }

enum class DuoFontChoice { INTER, SYSTEM }

enum class AppLibraryView { CATEGORIES, AZ }

/** Which of the three stored layouts a read or edit applies to. */
enum class LayoutTarget { MIRRORED, COVER, INNER }

/**
 * One stored layout: its grid, its pages and the widgets anchored on them.
 *
 * `slots` keeps the flat addressing the editing layer already uses, where a cell's index is
 * `page * grid.cells + local`. Page `-1` is the leading workspace (see `docs/architecture.md`;
 * note that pager page `-1` separately means Discover).
 */
data class DuoLayout(
    val grid: GridSpec = DEFAULT_GRID,
    val slots: List<String?> = emptyList(),
    val leadingSlots: List<String?> = List(DEFAULT_GRID.cells) { null },
    val widgetPlacements: List<WidgetPlacement> = emptyList(),
    val widgetRestores: List<WidgetRestore> = emptyList(),
    /** One stable id per page, index-aligned with the page number. Grown on demand. */
    val pageIds: List<Int> = emptyList(),
    /** Pages the user hid in Page overview (FR-47). Their contents are kept. */
    val hiddenPageIds: Set<Int> = emptySet(),
) {
    /** True when this layout has never been populated, which is what lets a mode switch seed it. */
    val isEmpty: Boolean
        get() = slots.none { it != null } && leadingSlots.none { it != null } && widgetPlacements.isEmpty()

    val pageCount: Int
        get() = maxOf(
            homePageCount(slots.size, grid),
            widgetPlacements.filter { it.page >= 0 }.maxOfOrNull { it.page + 1 } ?: 1,
        )

    /** Ensures every page has an id, minting new ones above the current maximum. */
    fun withPageIds(): DuoLayout {
        val needed = pageCount
        if (pageIds.size >= needed) return copy(pageIds = pageIds.take(needed))
        var next = (pageIds.maxOrNull() ?: 0) + 1
        return copy(pageIds = pageIds + List(needed - pageIds.size) { next++ })
    }
}

/** FR-37…FR-39: the dock is shared by both layouts, holds apps, shortcuts and folders. */
data class DockConfig(
    val side: DockSide = DockSide.RIGHT,
    val capacity: Int = DEFAULT_DOCK_CAPACITY,
    val items: List<String?> = List(DEFAULT_DOCK_CAPACITY) { null },
) {
    /** Clamps capacity into range and resizes `items` to match without dropping a filled slot. */
    fun sanitized(): DockConfig {
        val bounded = capacity.coerceIn(MIN_DOCK_CAPACITY, MAX_DOCK_CAPACITY)
        // Shrinking keeps the filled slots rather than blindly truncating, so reducing capacity
        // cannot delete a dock app that happened to sit in a high slot.
        val filled = items.filterNotNull()
        val resized = when {
            items.size == bounded -> items
            items.size < bounded -> items + List(bounded - items.size) { null }
            filled.size <= bounded -> items.take(bounded).let { kept ->
                val lost = filled.filterNot(kept::contains)
                kept.toMutableList().also { result ->
                    lost.forEach { id ->
                        val gap = result.indexOfFirst { it == null }
                        if (gap >= 0) result[gap] = id
                    }
                }
            }
            else -> items.filterNotNull().take(bounded)
        }
        return copy(capacity = bounded, items = resized.take(bounded))
    }
}

/** FR-61…FR-64. A stack occupies one widget placement, named by its slot. */
data class WidgetStack(
    val id: String,
    val placementSlots: List<Int>,
    val activeIndex: Int = 0,
    val smartRotate: Boolean = false,
)

/**
 * FR-55: which page sits left of Home 1.
 *
 * `classic` is not stored here: it is each layout's existing `leadingSlots` plus its page `-1`
 * widgets, kept exactly where they already live so that switching leading page never touches them
 * (FR-58, AC-48).
 */
data class LeadingPageConfig(
    val kind: LeadingPageKind = LeadingPageKind.TODAY,
    /** Widget placement slots and stack ids shown in the Today column, in order (FR-57). */
    val today: List<String> = emptyList(),
)

/** A per-app icon and label override (FR-19), stored as a reference the icons track resolves. */
data class IconOverrideRecord(
    val profileAppId: String,
    val iconPack: String? = null,
    val drawableName: String? = null,
    val label: String? = null,
)

/** Every user-facing setting schema 9 owns (spec section 7). */
data class DuoSettings(
    val wallpaperSource: WallpaperSource = WallpaperSource.DUO,
    val dimInDark: Boolean = false,
    val glassLevel: Int = DEFAULT_GLASS_LEVEL,
    val reduceTransparency: Boolean = false,
    /** Null means Automatic (FR-8); otherwise an ARGB colour. */
    val accent: Int? = null,
    val font: DuoFontChoice = DuoFontChoice.INTER,
    val iconAppearance: IconAppearance = IconAppearance.DEFAULT,
    val iconTint: Int = 0,
    val iconTintIntensity: Int = 100,
    val iconShape: IconShape = IconShape.SQUIRCLE,
    val largeIcons: Boolean = false,
    val iconPack: String? = null,
    val badgeStyle: DuoBadgeStyle = DuoBadgeStyle.DOT,
    val swipeDown: SwipeDownAction = SwipeDownAction.SEARCH,
    val swipeUp: SwipeUpAction = SwipeUpAction.APP_LIBRARY,
    val doubleTapLock: Boolean = false,
    /** FR-49. Blocks drag, remove, Edit mode, auto-add and pin-request placement. */
    val lockLayout: Boolean = false,
    /** FR-50. */
    val autoAddApps: Boolean = false,
    val duoStatus: Boolean = true,
    val libraryView: AppLibraryView = AppLibraryView.CATEGORIES,
    val searchContacts: Boolean = false,
    val suggestions: Boolean = true,
    val hidePrivateContainer: Boolean = false,
)

/**
 * The three layouts plus everything shared between them.
 *
 * Per ADR-4 all three are stored at all times. `setLayoutMode` never copies one over another, so a
 * user can move between Mirrored and Separate as often as they like without losing either
 * arrangement (FR-32, AC-27).
 */
data class LayoutSet(
    val mode: LayoutMode = LayoutMode.MIRRORED,
    val mirrored: DuoLayout = DuoLayout(),
    val cover: DuoLayout = DuoLayout(),
    val inner: DuoLayout = DuoLayout(),
    /** Shared by every layout (spec section 7). */
    val dock: DockConfig = DockConfig(),
    /** Referenced by id from any layout, the dock or Today. */
    val folders: List<FolderEntry> = emptyList(),
) {
    /** In Mirrored mode both screens read the one layout; in Separate they read their own. */
    fun targetFor(expanded: Boolean): LayoutTarget = when {
        mode == LayoutMode.MIRRORED -> LayoutTarget.MIRRORED
        expanded -> LayoutTarget.INNER
        else -> LayoutTarget.COVER
    }

    fun layout(target: LayoutTarget): DuoLayout = when (target) {
        LayoutTarget.MIRRORED -> mirrored
        LayoutTarget.COVER -> cover
        LayoutTarget.INNER -> inner
    }

    fun withLayout(target: LayoutTarget, value: DuoLayout): LayoutSet = when (target) {
        LayoutTarget.MIRRORED -> copy(mirrored = value)
        LayoutTarget.COVER -> copy(cover = value)
        LayoutTarget.INNER -> copy(inner = value)
    }

    /** Splices the shared dock and folders back in, producing the type the editing layer takes. */
    fun homeLayout(target: LayoutTarget): HomeLayout = layout(target).let {
        HomeLayout(it.slots, dock.items, it.widgetPlacements, folders, it.widgetRestores, it.leadingSlots, it.grid)
    }

    /** Splits an edited [HomeLayout] back into its per-layout and shared parts. */
    fun withHomeLayout(target: LayoutTarget, value: HomeLayout): LayoutSet {
        val existing = layout(target)
        return withLayout(
            target,
            existing.copy(
                slots = value.slots,
                leadingSlots = value.leadingSlots,
                widgetPlacements = value.widgetPlacements,
                widgetRestores = value.widgetRestores,
            ).withPageIds(),
        ).copy(dock = dock.copy(items = value.dock), folders = value.folders)
    }
}

const val DEFAULT_DOCK_CAPACITY = 4
const val DEFAULT_GLASS_LEVEL = 55

/** FR-66: a folder with no chosen tint follows the accent. */
const val FOLDER_TINT_FOLLOWS_ACCENT = 0

/**
 * AC-30: a fresh install gets Separate layouts, cover 4×6, inner 6×6, Today View and a right dock.
 * An upgrade never reaches this; it gets [migrateV8ToV9] instead.
 */
fun newInstallLayoutSet(): LayoutSet = LayoutSet(
    mode = LayoutMode.SEPARATE,
    mirrored = DuoLayout(grid = GridSpec(4, 6), leadingSlots = List(GridSpec(4, 6).cells) { null }),
    cover = DuoLayout(grid = GridSpec(4, 6), leadingSlots = List(GridSpec(4, 6).cells) { null }),
    inner = DuoLayout(grid = GridSpec(6, 6), leadingSlots = List(GridSpec(6, 6).cells) { null }),
    dock = DockConfig(side = DockSide.RIGHT, capacity = DEFAULT_DOCK_CAPACITY),
)

/** The whole schema-9 document, decoded. */
data class LauncherPersistedState(
    val layoutSet: LayoutSet = LayoutSet(),
    val leadingPage: LeadingPageConfig = LeadingPageConfig(),
    val stacks: List<WidgetStack> = emptyList(),
    val hiddenApps: Set<String> = emptySet(),
    val iconOverrides: List<IconOverrideRecord> = emptyList(),
    val settings: DuoSettings = DuoSettings(),
    /** Carried through unchanged from v8 so existing screens keep working. */
    val labels: Boolean = true,
    val googleSearch: Boolean = true,
    val verticalStatus: Boolean = true,
    val compact: LayoutPreset = LayoutPreset(),
    val expanded: LayoutPreset = LayoutPreset(),
)

// ---------------------------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------------------------

internal fun encodeLauncherState(state: LauncherPersistedState): String =
    encodeLauncherStateJson(state).write()

internal fun encodeLauncherStateJson(state: LauncherPersistedState): DuoJson.Obj {
    val set = state.layoutSet
    return DuoJson.obj(
        "schema" to DuoJson.of(LAYOUT_SCHEMA_VERSION),
        "layoutSet" to DuoJson.obj(
            "mode" to DuoJson.of(set.mode.name),
            "mirrored" to encodeLayout(set.mirrored),
            "cover" to encodeLayout(set.cover),
            "inner" to encodeLayout(set.inner),
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
        "hiddenApps" to DuoJson.ofStrings(state.hiddenApps.sorted()),
        "iconOverrides" to DuoJson.Arr(
            state.iconOverrides.map { override ->
                DuoJson.obj(
                    "app" to DuoJson.of(override.profileAppId),
                    "iconPack" to (override.iconPack?.let(DuoJson::of) ?: DuoJson.Null),
                    "drawable" to (override.drawableName?.let(DuoJson::of) ?: DuoJson.Null),
                    "label" to (override.label?.let(DuoJson::of) ?: DuoJson.Null),
                )
            },
        ),
        "settings" to encodeSettings(state.settings),
        "labels" to DuoJson.of(state.labels),
        "googleSearch" to DuoJson.of(state.googleSearch),
        "verticalStatus" to DuoJson.of(state.verticalStatus),
        "compact" to encodePreset(state.compact),
        "expanded" to encodePreset(state.expanded),
    )
}

private fun encodeLayout(layout: DuoLayout): DuoJson.Obj {
    val prepared = layout.withPageIds()
    val grid = prepared.grid
    val pages = (0 until prepared.pageCount).map { page ->
        val items = (0 until grid.cells).mapNotNull { local ->
            val id = prepared.slots.getOrNull(page * grid.cells + local)
            if (id == null) null else encodeItem(local, id)
        }
        DuoJson.obj(
            "id" to DuoJson.of(prepared.pageIds.getOrElse(page) { page + 1 }),
            "items" to DuoJson.Arr(items),
        )
    }
    val leadingItems = prepared.leadingSlots.mapIndexedNotNull { local, id ->
        if (id == null) null else encodeItem(local, id)
    }
    return DuoJson.obj(
        "grid" to DuoJson.obj(
            "columns" to DuoJson.of(grid.columns),
            "rows" to DuoJson.of(grid.rows),
        ),
        "pages" to DuoJson.Arr(pages),
        "hiddenPageIds" to DuoJson.ofInts(prepared.hiddenPageIds.sorted()),
        "leading" to DuoJson.Arr(leadingItems),
        "widgets" to DuoJson.Arr(prepared.widgetPlacements.map(::encodeWidget)),
        "restores" to DuoJson.Arr(prepared.widgetRestores.map(::encodeRestore)),
    )
}

/** App, Shortcut and Folder are the three cell-occupying `HomeItem` variants. */
private fun encodeItem(cell: Int, id: String): DuoJson.Obj = DuoJson.obj(
    "cell" to DuoJson.of(cell),
    "kind" to DuoJson.of(homeItemKind(id)),
    "id" to DuoJson.of(id),
)

internal fun homeItemKind(id: String): String = when {
    isReservedFolderId(id) -> "FOLDER"
    isPinnedShortcutId(id) -> "SHORTCUT"
    else -> "APP"
}

private fun encodeWidget(placement: WidgetPlacement): DuoJson.Obj = DuoJson.obj(
    "slot" to DuoJson.of(placement.slot),
    "id" to DuoJson.of(placement.id),
    "page" to DuoJson.of(placement.page),
    "column" to DuoJson.of(placement.column),
    "row" to DuoJson.of(placement.row),
    "spanX" to DuoJson.of(placement.spanX),
    "spanY" to DuoJson.of(placement.spanY),
    "kind" to (builtinWidgetKind(placement.id)?.let(DuoJson::of) ?: DuoJson.Null),
)

/** FR-59: existing Clock and Date placements carry a built-in kind alongside their legacy id. */
internal fun builtinWidgetKind(id: Int): String? = when (id) {
    CLOCK_WIDGET -> "CLOCK"
    DATE_WIDGET -> "CALENDAR"
    INFO_WIDGET -> "BATTERIES"
    else -> null
}

private fun encodeRestore(restore: WidgetRestore): DuoJson.Obj = DuoJson.obj(
    "slot" to DuoJson.of(restore.slot),
    "provider" to DuoJson.of(restore.providerComponent),
    "userSerial" to DuoJson.of(restore.userSerial),
    "title" to DuoJson.of(restore.title),
    "profileLabel" to DuoJson.of(restore.profileLabel),
    "work" to DuoJson.of(restore.isWork),
    "sourceScope" to (restore.sourceScope?.let(DuoJson::of) ?: DuoJson.Null),
)

private fun encodePreset(preset: LayoutPreset): DuoJson.Obj = DuoJson.obj(
    "iconSize" to DuoJson.of(preset.iconSize),
    "rowGap" to DuoJson.of(preset.rowGap),
    "dockWidth" to DuoJson.of(preset.dockWidth),
    "dockPosition" to DuoJson.of(preset.dockPosition),
    "dockAlignToGrid" to DuoJson.of(preset.dockAlignToGrid),
)

private fun encodeSettings(settings: DuoSettings): DuoJson.Obj = DuoJson.obj(
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

/** Why a payload could not be used, for the caller's recovery decision. */
sealed interface LayoutDecodeResult {
    data class Loaded(val state: LauncherPersistedState) : LayoutDecodeResult

    /** The payload is unreadable or fails validation. The caller falls back to a backup. */
    data class Failed(val reason: String) : LayoutDecodeResult

    /** The payload declares a schema this build does not know (a downgrade). */
    data class TooNew(val schema: Int) : LayoutDecodeResult
}

/**
 * Reads any supported payload, migrating v1…v8 forward.
 *
 * Never throws: every failure is a [LayoutDecodeResult.Failed] so the caller can try the next
 * backup rather than starting the user with an empty Home.
 */
internal fun decodeLauncherState(raw: String?): LayoutDecodeResult {
    if (raw.isNullOrBlank()) return LayoutDecodeResult.Failed("No saved layout")
    val root = runCatching { DuoJson.parse(raw) }.getOrNull() as? DuoJson.Obj
        ?: return LayoutDecodeResult.Failed("Saved layout is not a JSON object")
    val schema = root.int("schema") ?: 1
    if (schema > LAYOUT_SCHEMA_VERSION) return LayoutDecodeResult.TooNew(schema)
    if (schema < OLDEST_SUPPORTED_SCHEMA) return LayoutDecodeResult.Failed("Unsupported schema $schema")
    return runCatching {
        if (schema == LAYOUT_SCHEMA_VERSION) decodeV9(root) else migrateV8ToV9(decodeLegacy(root, schema))
    }.fold(
        onSuccess = { LayoutDecodeResult.Loaded(it) },
        onFailure = { LayoutDecodeResult.Failed(it.message ?: "Saved layout failed validation") },
    )
}

private fun decodeV9(root: DuoJson.Obj): LauncherPersistedState {
    val setJson = root.obj("layoutSet") ?: error("Schema 9 requires a layout set")
    val dockJson = root.obj("dock") ?: error("Schema 9 requires a dock")
    val folders = decodeFolders(root.array("folders") ?: error("Schema 9 requires a folder array"))
    val dock = DockConfig(
        side = enumOrDefault(dockJson.string("side"), DockSide.RIGHT),
        capacity = dockJson.int("capacity", DEFAULT_DOCK_CAPACITY),
        items = (dockJson.array("items") ?: DuoJson.Arr(emptyList())).stringsOrNulls(),
    ).sanitized()
    val set = LayoutSet(
        mode = enumOrDefault(setJson.string("mode"), LayoutMode.MIRRORED),
        mirrored = decodeLayout(setJson.obj("mirrored")),
        cover = decodeLayout(setJson.obj("cover")),
        inner = decodeLayout(setJson.obj("inner")),
        dock = dock,
        folders = folders,
    )
    val stacks = (root.array("stacks") ?: DuoJson.Arr(emptyList())).objects().map { item ->
        WidgetStack(
            id = item.string("id") ?: error("A stack needs an id"),
            placementSlots = (item.array("slots") ?: DuoJson.Arr(emptyList())).values
                .mapNotNull { (it as? DuoJson.Num)?.asInt() },
            activeIndex = item.int("activeIndex", 0),
            smartRotate = item.boolean("smartRotate", false),
        )
    }
    val leadingJson = root.obj("leadingPage")
    val state = LauncherPersistedState(
        layoutSet = set,
        leadingPage = LeadingPageConfig(
            kind = enumOrDefault(leadingJson?.string("kind"), LeadingPageKind.TODAY),
            today = (leadingJson?.array("today") ?: DuoJson.Arr(emptyList())).strings(),
        ),
        stacks = stacks,
        hiddenApps = (root.array("hiddenApps") ?: DuoJson.Arr(emptyList())).strings().toSet(),
        iconOverrides = (root.array("iconOverrides") ?: DuoJson.Arr(emptyList())).objects().mapNotNull { item ->
            item.string("app")?.let {
                IconOverrideRecord(it, item.string("iconPack"), item.string("drawable"), item.string("label"))
            }
        },
        settings = decodeSettings(root.obj("settings")),
        labels = root.boolean("labels", true),
        googleSearch = root.boolean("googleSearch", true),
        verticalStatus = root.boolean("verticalStatus", true),
        compact = decodePreset(root.obj("compact")),
        expanded = decodePreset(root.obj("expanded")),
    )
    val coherent = state.withCoherentStacks()
    validate(coherent)
    return coherent
}

private fun decodeLayout(json: DuoJson.Obj?): DuoLayout {
    if (json == null) return DuoLayout()
    val gridJson = json.obj("grid")
    val grid = GridSpec(
        gridJson?.int("columns") ?: GRID_COLUMNS,
        gridJson?.int("rows") ?: GRID_ROWS,
    ).sanitized()
    val pages = (json.array("pages") ?: DuoJson.Arr(emptyList())).objects()
    val slots = MutableList<String?>(pages.size * grid.cells) { null }
    pages.forEachIndexed { page, pageJson ->
        (pageJson.array("items") ?: DuoJson.Arr(emptyList())).objects().forEach { item ->
            val cell = item.int("cell") ?: error("A home item needs a cell")
            val id = item.string("id") ?: error("A home item needs an id")
            require(cell in 0 until grid.cells) { "Home item cell $cell is outside the grid" }
            val index = page * grid.cells + cell
            require(slots[index] == null) { "Two home items share cell $index" }
            slots[index] = id
        }
    }
    val leading = MutableList<String?>(grid.cells) { null }
    (json.array("leading") ?: DuoJson.Arr(emptyList())).objects().forEach { item ->
        val cell = item.int("cell") ?: error("A leading item needs a cell")
        val id = item.string("id") ?: error("A leading item needs an id")
        require(cell in 0 until grid.cells) { "Leading cell $cell is outside the grid" }
        require(leading[cell] == null) { "Two leading items share cell $cell" }
        leading[cell] = id
    }
    val widgets = (json.array("widgets") ?: DuoJson.Arr(emptyList())).objects().map { item ->
        WidgetPlacement(
            slot = requiredInt(item, "slot"),
            id = requiredInt(item, "id"),
            page = requiredInt(item, "page"),
            column = requiredInt(item, "column"),
            row = requiredInt(item, "row"),
            spanX = requiredInt(item, "spanX"),
            spanY = requiredInt(item, "spanY"),
        )
    }
    val restores = (json.array("restores") ?: DuoJson.Arr(emptyList())).objects().map { item ->
        WidgetRestore(
            slot = requiredInt(item, "slot"),
            providerComponent = item.string("provider") ?: error("A widget restore needs a provider"),
            userSerial = item.long("userSerial") ?: error("A widget restore needs a user"),
            title = item.string("title") ?: error("A widget restore needs a title"),
            profileLabel = item.string("profileLabel") ?: error("A widget restore needs a profile"),
            isWork = item.boolean("work", false),
            sourceScope = item.string("sourceScope"),
        )
    }
    return DuoLayout(
        grid = grid,
        slots = slots.dropLastWhile { it == null },
        leadingSlots = leading,
        widgetPlacements = widgets,
        widgetRestores = restores,
        pageIds = pages.mapIndexed { index, pageJson -> pageJson.int("id") ?: (index + 1) },
        hiddenPageIds = (json.array("hiddenPageIds") ?: DuoJson.Arr(emptyList())).values
            .mapNotNull { (it as? DuoJson.Num)?.asInt() }.toSet(),
    ).withPageIds()
}

private fun requiredInt(json: DuoJson.Obj, key: String): Int =
    json.int(key) ?: error("$key must be an integer")

private fun decodeFolders(array: DuoJson.Arr): List<FolderEntry> = array.objects().map { item ->
    FolderEntry(
        id = item.string("id") ?: error("A folder needs an id"),
        title = item.string("title") ?: error("A folder needs a title"),
        appIds = (item.array("apps") ?: DuoJson.Arr(emptyList())).strings(),
        tint = item.int("tint", FOLDER_TINT_FOLLOWS_ACCENT),
        size = enumOrDefault(item.string("size"), DuoFolderSize.SMALL),
    )
}

private fun decodePreset(json: DuoJson.Obj?): LayoutPreset {
    val default = LayoutPreset()
    if (json == null) return default
    return LayoutPreset(
        json.float("iconSize", default.iconSize),
        json.float("rowGap", default.rowGap),
        json.float("dockWidth", default.dockWidth),
        json.float("dockPosition", default.dockPosition),
        json.boolean("dockAlignToGrid", true),
    ).sanitized()
}

private fun decodeSettings(json: DuoJson.Obj?): DuoSettings {
    val default = DuoSettings()
    if (json == null) return default
    return DuoSettings(
        wallpaperSource = enumOrDefault(json.string("wallpaperSource"), default.wallpaperSource),
        dimInDark = json.boolean("dimInDark", default.dimInDark),
        glassLevel = json.int("glassLevel", default.glassLevel).coerceIn(0, 100),
        reduceTransparency = json.boolean("reduceTransparency", default.reduceTransparency),
        accent = json.int("accent"),
        font = enumOrDefault(json.string("font"), default.font),
        iconAppearance = enumOrDefault(json.string("iconAppearance"), default.iconAppearance),
        iconTint = json.int("iconTint", default.iconTint),
        iconTintIntensity = json.int("iconTintIntensity", default.iconTintIntensity).coerceIn(0, 100),
        iconShape = enumOrDefault(json.string("iconShape"), default.iconShape),
        largeIcons = json.boolean("largeIcons", default.largeIcons),
        iconPack = json.string("iconPack"),
        badgeStyle = enumOrDefault(json.string("badgeStyle"), default.badgeStyle),
        swipeDown = enumOrDefault(json.string("swipeDown"), default.swipeDown),
        swipeUp = enumOrDefault(json.string("swipeUp"), default.swipeUp),
        doubleTapLock = json.boolean("doubleTapLock", default.doubleTapLock),
        lockLayout = json.boolean("lockLayout", default.lockLayout),
        autoAddApps = json.boolean("autoAddApps", default.autoAddApps),
        duoStatus = json.boolean("duoStatus", default.duoStatus),
        libraryView = enumOrDefault(json.string("libraryView"), default.libraryView),
        searchContacts = json.boolean("searchContacts", default.searchContacts),
        suggestions = json.boolean("suggestions", default.suggestions),
        hidePrivateContainer = json.boolean("hidePrivateContainer", default.hidePrivateContainer),
    )
}

/**
 * An unknown enum name falls back to the default rather than failing the whole load.
 *
 * This is the one place tolerance is right: a value added by a newer build is a setting, never a
 * placement, so defaulting it costs the user a preference while failing would cost them their Home
 * screen.
 */
private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    name?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

// ---------------------------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------------------------

/**
 * The invariants a schema-9 payload must satisfy to be usable.
 *
 * Anything that fails here sends the caller to a backup, so these checks are restricted to things
 * that genuinely make a layout incoherent — duplicate placements, overlapping widgets, folder
 * references with no folder — rather than cosmetic problems.
 */
internal fun validate(state: LauncherPersistedState) {
    val set = state.layoutSet
    val folderIds = set.folders.mapTo(mutableSetOf(), FolderEntry::id)
    require(folderIds.size == set.folders.size) { "Folder ids must be unique" }
    set.folders.forEach { folder ->
        require(isFolderId(folder.id)) { "Folder id ${folder.id} is malformed" }
        require(folder.title.isNotBlank()) { "A folder needs a title" }
        require(folder.appIds.none { it.isBlank() || isReservedFolderId(it) }) { "A folder cannot nest" }
    }
    val childApps = set.folders.flatMap(FolderEntry::appIds)
    require(childApps.distinct().size == childApps.size) { "An app is in two folders" }

    require(set.dock.capacity in MIN_DOCK_CAPACITY..MAX_DOCK_CAPACITY) { "Dock capacity out of range" }
    require(set.dock.items.size == set.dock.capacity) { "Dock items must match capacity" }
    val dockIds = set.dock.items.filterNotNull()
    require(dockIds.distinct().size == dockIds.size) { "A dock shortcut appears twice" }
    require(dockIds.none { it in childApps }) { "A dock shortcut is also in a folder" }

    LayoutTarget.entries.forEach { target -> validateLayout(set.layout(target), target, folderIds, childApps) }

    // Every folder must be reachable from exactly one surface, or it is unreachable data.
    val referenced = LayoutTarget.entries.flatMap { target ->
        val layout = set.layout(target)
        (layout.slots + layout.leadingSlots).filterNotNull()
    }.filter(::isReservedFolderId).toSet() + dockIds.filter(::isReservedFolderId).toSet()
    require(referenced.all { it in folderIds }) { "A layout references a folder that does not exist" }

    val stackIds = state.stacks.mapTo(mutableSetOf(), WidgetStack::id)
    require(stackIds.size == state.stacks.size) { "Stack ids must be unique" }
    // Every slot a stack or the Today column names must be a placement that exists. Callers reach
    // this through withCoherentStacks(), which prunes first, so a failure here means a *new* way of
    // producing an incoherent state rather than an old payload being punished for an old bug.
    val liveSlots = set.liveWidgetSlots()
    state.stacks.forEach { stack ->
        require(stack.placementSlots.isNotEmpty()) { "A stack holds at least one widget" }
        require(stack.placementSlots.size <= MAX_STACK_WIDGETS) { "A stack holds at most $MAX_STACK_WIDGETS widgets" }
        require(stack.placementSlots.distinct().size == stack.placementSlots.size) { "A stack repeats a widget" }
        require(stack.activeIndex in stack.placementSlots.indices) { "Stack active index out of range" }
        require(stack.placementSlots.all { it in liveSlots }) { "A stack names a widget that does not exist" }
    }
    state.leadingPage.today.forEach { entry ->
        val slot = entry.toIntOrNull()
        if (slot != null) require(slot in liveSlots) { "The Today column names a widget that does not exist" }
        else if (entry.startsWith(STACK_ID_PREFIX)) {
            require(entry in stackIds) { "The Today column names a stack that does not exist" }
        }
    }
}

private fun validateLayout(
    layout: DuoLayout,
    target: LayoutTarget,
    folderIds: Set<String>,
    folderChildren: List<String>,
) {
    val grid = layout.grid
    require(grid.columns in MIN_GRID_SIZE..MAX_GRID_SIZE && grid.rows in MIN_GRID_SIZE..MAX_GRID_SIZE) {
        "$target grid ${grid.columns}x${grid.rows} is outside $MIN_GRID_SIZE..$MAX_GRID_SIZE"
    }
    require(layout.leadingSlots.size == grid.cells) { "$target leading page must have ${grid.cells} cells" }

    val placed = (layout.slots + layout.leadingSlots).filterNotNull()
    require(placed.distinct().size == placed.size) { "$target places a shortcut more than once" }
    require(placed.none { it in folderChildren }) { "$target places an app that is inside a folder" }
    require(placed.filter(::isReservedFolderId).all { it in folderIds }) { "$target references a missing folder" }

    val slots = layout.widgetPlacements.map { it.slot }
    require(slots.distinct().size == slots.size) { "$target repeats a widget slot" }
    layout.widgetPlacements.forEach { placement ->
        require(placement.slot >= 0) { "$target has a negative widget slot" }
        require(placement.id != EMPTY_WIDGET) { "$target has an empty widget placement" }
        require(placement.page >= -1) { "$target has a widget before the leading page" }
        require(placement.column >= 0 && placement.row >= 0) { "$target has a widget at a negative cell" }
        require(placement.spanX in 1..grid.columns && placement.spanY in 1..grid.rows) {
            "$target has a widget span outside its grid"
        }
        require(placement.column + placement.spanX <= grid.columns) { "$target has a widget past the last column" }
        // A legacy 4x6-grid overflow panel is the one placement allowed to exceed the row count;
        // it is retained rather than deleted so an upgraded install keeps its widget (v8 rule).
        val insideGrid = placement.row + placement.spanY <= grid.rows
        val legacyOverflow = grid == DEFAULT_GRID && placement.page > 0 &&
            placement.slot / 3 == placement.page && placement.slot % 3 == 2 &&
            placement.column == 0 && placement.row == GRID_ROWS &&
            placement.spanX == GRID_COLUMNS && placement.spanY == 4
        require(insideGrid || legacyOverflow) { "$target has a widget past the last row" }
    }
    layout.widgetPlacements.forEachIndexed { index, a ->
        layout.widgetPlacements.drop(index + 1).forEach { b ->
            require(!(a.page == b.page && a.column < b.column + b.spanX && b.column < a.column + a.spanX &&
                a.row < b.row + b.spanY && b.row < a.row + a.spanY)) { "$target has overlapping widgets" }
        }
    }
    // A widget may not sit on an occupied cell.
    val occupied = buildSet {
        layout.slots.forEachIndexed { index, id -> if (id != null) add(index) }
        layout.leadingSlots.forEachIndexed { local, id ->
            if (id != null) add(homeCellIndex(-1, local, grid))
        }
    }
    layout.widgetPlacements.forEach { placement ->
        require(placement.coveredIndices(grid).none { it in occupied }) { "$target has a widget over a shortcut" }
    }
    val restoreSlots = layout.widgetRestores.map { it.slot }
    require(restoreSlots.distinct().size == restoreSlots.size) { "$target repeats a widget restore" }
    require(
        layout.widgetPlacements.filter { it.id == NEEDS_BINDING_WIDGET }.map { it.slot }.toSet() ==
            restoreSlots.toSet(),
    ) { "$target has a restore without a placeholder, or the reverse" }
}

// ---------------------------------------------------------------------------------------------
// Legacy (v1…v8) reading and the v8 -> v9 migration
// ---------------------------------------------------------------------------------------------

/** The parts of a pre-9 payload that carry user data. */
internal data class LegacyLauncherState(
    val homeSlots: List<String?>,
    val leadingSlots: List<String?>,
    val dock: List<String?>,
    val widgetPlacements: List<WidgetPlacement>,
    val widgetRestores: List<WidgetRestore>,
    val folders: List<FolderEntry>,
    val labels: Boolean,
    val googleSearch: Boolean,
    val verticalStatus: Boolean,
    val compact: LayoutPreset,
    val expanded: LayoutPreset,
)

/**
 * Reads a v1…v8 payload, applying the same migrations and the same validation the v8 loader applied.
 *
 * This is a faithful port of the previous `LauncherModel.load()` onto [DuoJson]: the rules, the
 * order of the checks and the messages are the ones that were already protecting these payloads.
 */
internal fun decodeLegacy(root: DuoJson.Obj, schema: Int): LegacyLauncherState {
    require(schema <= 8) { "Unsupported saved-state schema $schema" }
    val order = (if (schema >= 2) root.array("pinned") else root.array("order")) ?: DuoJson.Arr(emptyList())
    val cells = (if (schema >= 4) root.array("homeSlots") else null) ?: order
    val rawSlots = cells.values.map { value -> (value as? DuoJson.Str)?.value?.takeIf { it.isNotBlank() && it != "null" } }
    val legacySlots = normalizeHomeSlots(rawSlots)

    val rawLeadingSlots = if (schema >= 8) {
        val leading = root.array("leadingSlots") ?: error("Schema 8 requires a leading slot array")
        require(leading.size == HOME_CELLS) { "Schema 8 leading page must have $HOME_CELLS cells" }
        List(HOME_CELLS) { leading.stringOrNull(it)?.takeIf { id -> id.isNotBlank() && id != "null" } }
    } else {
        List(HOME_CELLS) { null }
    }

    val dockArray = root.array("dock")
    val loadedDock = List(4) { dockArray?.stringOrNull(it)?.takeIf { id -> id.isNotBlank() && id != "null" } }

    val widgetArray = root.array("widgets")
    val placements = if (schema >= 6) {
        require(widgetArray != null) { "Schema $schema requires a widget placement array" }
        widgetArray.values.map { value ->
            val w = value as? DuoJson.Obj ?: error("A widget placement must be an object")
            WidgetPlacement(
                strictInt(w, "slot"), strictInt(w, "id"), strictInt(w, "page"),
                strictInt(w, "column"), strictInt(w, "row"), strictInt(w, "spanX"), strictInt(w, "spanY"),
            )
        }.also { loaded ->
            require(loaded.map { it.slot }.distinct().size == loaded.size) { "Widget placement slots must be unique" }
            loaded.forEach { placement ->
                val baseGeometry = placement.slot >= 0 && placement.id != EMPTY_WIDGET && placement.page >= -1 &&
                    placement.column >= 0 && placement.row >= 0 && placement.spanX in 1..GRID_COLUMNS &&
                    placement.spanY in 1..GRID_ROWS && placement.column + placement.spanX <= GRID_COLUMNS
                val insideGrid = placement.row + placement.spanY <= GRID_ROWS
                val migratedOverflow = placement.page > 0 && placement.slot / 3 == placement.page &&
                    placement.slot % 3 == 2 && placement.column == 0 && placement.row == GRID_ROWS &&
                    placement.spanX == GRID_COLUMNS && placement.spanY == 4
                require(baseGeometry && (insideGrid || migratedOverflow)) { "Invalid widget placement" }
            }
        }
    } else {
        val ids = if (schema < 5) {
            List(3) { index ->
                val stored = (widgetArray?.get(index) as? DuoJson.Num)?.asInt() ?: -1
                if (stored < 0) listOf(CLOCK_WIDGET, DATE_WIDGET, INFO_WIDGET)[index] else stored
            }
        } else {
            List(widgetArray?.size ?: 0) { (widgetArray?.get(it) as? DuoJson.Num)?.asInt() ?: EMPTY_WIDGET }
        }
        migrateSchema5Widgets(ids)
    }.filterNot { placement -> schema < 8 && placement == WidgetPlacement(2, INFO_WIDGET, -1, 0, 0, 4, 6) }

    val folders = if (schema >= 7) {
        val array = root.array("folders") ?: error("Schema 7 requires a folder array")
        array.values.map { value ->
            val item = value as? DuoJson.Obj ?: error("A folder must be an object")
            val apps = item.array("apps") ?: error("A folder requires an app array")
            FolderEntry(
                item.string("id") ?: error("A folder requires an id"),
                item.string("title") ?: error("A folder requires a title"),
                apps.values.map { (it as? DuoJson.Str)?.value ?: error("A folder app must be a string") },
            )
        }.also { loaded ->
            require(loaded.map(FolderEntry::id).distinct().size == loaded.size)
            require(loaded.flatMap(FolderEntry::appIds).distinct().size == loaded.sumOf { it.appIds.size })
            loaded.forEach { folder ->
                require(isFolderId(folder.id) && folder.title.isNotBlank() && folder.appIds.size >= 2)
                require(folder.appIds.none { it.isBlank() || isReservedFolderId(it) })
            }
            val children = loaded.flatMapTo(mutableSetOf(), FolderEntry::appIds)
            val folderIds = loaded.mapTo(mutableSetOf(), FolderEntry::id)
            val rawFolderRefs = (rawSlots + rawLeadingSlots).filterNotNull().filter(::isReservedFolderId)
            require(rawFolderRefs.all(::isFolderId))
            require(rawFolderRefs.size == folderIds.size && rawFolderRefs.toSet() == folderIds)
            require(
                (rawSlots + rawLeadingSlots).none { it in children } &&
                    loadedDock.none { it in children || (it != null && isReservedFolderId(it)) },
            )
        }
    } else {
        emptyList()
    }

    if (schema >= 8) {
        val leadingIds = rawLeadingSlots.filterNotNull()
        require(leadingIds.distinct().size == leadingIds.size) {
            "An unfolded-only shortcut appears more than once"
        }
        val leadingApps = leadingIds.filterNot(::isReservedFolderId)
        val otherApps = rawSlots.filterNotNull().filterNot(::isReservedFolderId) +
            loadedDock.filterNotNull() + folders.flatMap(FolderEntry::appIds)
        require(leadingApps.none { it in otherApps }) {
            "An unfolded-only app shortcut appears on another surface"
        }
        val occupiedLeadingCells = rawLeadingSlots.indices
            .filterTo(mutableSetOf()) { rawLeadingSlots[it] != null }
            .mapTo(mutableSetOf()) { homeCellIndex(-1, it) }
        require(
            placements.filter { it.page == -1 }.none { placement ->
                placement.coveredIndices().any { it in occupiedLeadingCells }
            },
        ) { "An unfolded-only shortcut overlaps a widget" }
    }

    val restores = if (schema >= 7) {
        val array = root.array("restores") ?: DuoJson.Arr(emptyList())
        array.values.map { value ->
            val item = value as? DuoJson.Obj ?: error("A widget restore must be an object")
            WidgetRestore(
                strictInt(item, "slot"),
                item.string("provider") ?: error("A widget restore requires a provider"),
                item.long("userSerial") ?: error("A widget restore requires a user"),
                item.string("title") ?: error("A widget restore requires a title"),
                item.string("profileLabel") ?: error("A widget restore requires a profile"),
                item.boolean("work", false),
                item.string("sourceScope")?.takeIf { it.isNotBlank() && it != "null" },
            )
        }.also { loaded ->
            require(loaded.map(WidgetRestore::slot).distinct().size == loaded.size)
            loaded.forEach { restore ->
                require(
                    restore.slot >= 0 && restore.userSerial >= 0 && restore.title.isNotBlank() &&
                        restore.profileLabel.isNotBlank() && isFlattenedComponent(restore.providerComponent),
                )
            }
            require(placements.filter { it.id == NEEDS_BINDING_WIDGET }.map { it.slot }.toSet() == loaded.map { it.slot }.toSet())
        }
    } else {
        emptyList()
    }

    fun preset(key: String, default: LayoutPreset): LayoutPreset {
        val p = root.obj(key) ?: return default
        val loaded = LayoutPreset(
            p.float("iconSize", default.iconSize),
            p.float("rowGap", default.rowGap),
            p.float("dockWidth", default.dockWidth),
            p.float("dockPosition", default.dockPosition),
            p.boolean("dockAlignToGrid", true),
        ).sanitized()
        return upgradePreset(loaded, schema, key == "expanded")
    }

    return LegacyLauncherState(
        homeSlots = if (schema in 2..5) migrateSchema5Apps(legacySlots) else legacySlots,
        leadingSlots = rawLeadingSlots,
        dock = loadedDock,
        widgetPlacements = placements,
        widgetRestores = restores,
        folders = folders,
        labels = root.boolean("labels", true),
        googleSearch = root.boolean("googleSearch", true),
        verticalStatus = root.boolean("verticalStatus", true),
        compact = preset("compact", LayoutPreset()),
        expanded = preset("expanded", LayoutPreset()),
    )
}

/**
 * `ComponentName.unflattenFromString`'s rule, reimplemented without Android.
 *
 * The decoder has to run in JVM unit tests, where `ComponentName` is stubbed, so this restates the
 * platform's condition: a single `/` with a non-empty package and class on either side.
 */
internal fun isFlattenedComponent(value: String): Boolean {
    val separator = value.indexOf('/')
    return separator > 0 && separator < value.lastIndex && value.indexOf('/', separator + 1) < 0
}

private fun strictInt(json: DuoJson.Obj, key: String): Int {
    val number = json[key] as? DuoJson.Num ?: error("$key must be an integer")
    return number.asInt() ?: error("$key must be a finite integer")
}

/**
 * FR-35 / AC-29: converts a v8 payload into schema 9 without changing a single placement.
 *
 * The result is Mirrored mode on a 4×6 grid holding the existing pages, dock, folders, widgets and
 * bindings exactly as they were. `cover` and `inner` are left empty on purpose: they are not
 * *lost*, they are unused until the user chooses Separate, and `setLayoutMode` seeds the empty one
 * from the mirrored layout at that moment. Seeding them here would duplicate every live
 * `appWidgetId` into three layouts, which breaks the spec's rule that a placement belongs to exactly
 * one place.
 */
internal fun migrateV8ToV9(legacy: LegacyLauncherState): LauncherPersistedState {
    val grid = DEFAULT_GRID
    val mirrored = DuoLayout(
        grid = grid,
        slots = legacy.homeSlots,
        leadingSlots = legacy.leadingSlots,
        widgetPlacements = legacy.widgetPlacements,
        widgetRestores = legacy.widgetRestores,
    ).withPageIds()
    val state = LauncherPersistedState(
        layoutSet = LayoutSet(
            mode = LayoutMode.MIRRORED,
            mirrored = mirrored,
            cover = DuoLayout(grid = grid),
            inner = DuoLayout(grid = grid),
            dock = DockConfig(
                side = DockSide.RIGHT,
                capacity = DEFAULT_DOCK_CAPACITY,
                items = legacy.dock,
            ).sanitized(),
            folders = legacy.folders,
        ),
        // FR-58: an upgrade keeps its Classic workspace, so the leading page stays Classic and the
        // existing leadingSlots stay exactly where they are.
        leadingPage = LeadingPageConfig(kind = LeadingPageKind.CLASSIC),
        settings = DuoSettings(
            // FR-51: Search is the default for *new installs* only. Before schema 9 a downward
            // swipe always opened the system shade with the 70/30 Notifications/Quick Settings
            // split, and whether that worked was an Android accessibility setting rather than
            // anything stored here. Defaulting an upgrade to Notifications is therefore the only
            // choice that leaves an existing user's gesture doing what it did yesterday.
            swipeDown = SwipeDownAction.NOTIFICATIONS,
        ),
        labels = legacy.labels,
        googleSearch = legacy.googleSearch,
        verticalStatus = legacy.verticalStatus,
        compact = legacy.compact,
        expanded = legacy.expanded,
    )
    val coherent = state.withCoherentStacks()
    validate(coherent)
    return coherent
}
