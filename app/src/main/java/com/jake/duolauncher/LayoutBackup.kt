package com.jake.duolauncher

/**
 * The portable layout backup (FR-82, FR-83).
 *
 * ## Why this file does not use `org.json`
 *
 * It used to. `org.json` is stubbed in JVM unit tests, which is why every test that could reach this
 * codec had to be an instrumented test needing a device. NFR-M2 requires backup v3 to be covered by
 * JVM unit tests, so the codec is on [DuoJson] — the same strict pure-Kotlin reader the schema-9
 * layout codec uses. Nothing else about the format's guarantees changed.
 *
 * ## What a backup is, and what it is deliberately not
 *
 * A backup carries the user's *arrangement*: the three layouts, the dock, folders, widget geometry
 * with portable provider descriptors, and every setting schema 9 owns. It never carries anything
 * observed about the user. NFR-S7 is enforced by construction — there is no encoder here for
 * notification or badge data, contacts, or launch history, so no code path can emit them — and by
 * [encodeLayoutBackup]'s `isPrivateLocked` filter for the one category that *is* reachable from a
 * stored layout: an app placed from a private space that is locked right now.
 *
 * ## Versions
 *
 * - **v1, v2** are still read, with defaults for everything schema 9 added (FR-82, AC-66). A user
 *   restoring an older backup must never lose their layout, so the legacy reader below keeps the
 *   exact rules it already enforced.
 * - **v3** is what this build writes; see `LayoutBackupV3.kt`.
 * - **Anything newer is refused** with [BACKUP_TOO_NEW_MESSAGE], before a single placement is read,
 *   so the current layout is untouched (FR-83).
 *
 * Decoding throws for any malformed, oversized or wrongly-typed payload. Every caller wraps it in
 * `runCatching`, so a hostile file is rejected as a value and can never partially apply: the preview
 * is built completely or not at all, and only the mandatory review screen can apply it.
 */

import android.content.Context

/** The version this build writes. */
const val LAYOUT_BACKUP_VERSION = 3

const val MAX_LAYOUT_BACKUP_BYTES = 2 * 1024 * 1024

/** FR-83's exact message. */
const val BACKUP_TOO_NEW_MESSAGE = "This backup was made by a newer Duo version."

private const val MAX_BACKUP_HOME_CELLS = HOME_CELLS * 100

/**
 * Bounds on a hostile payload. Every unbounded array in the document gets one, so a 2 MiB file
 * cannot turn into an allocation large enough to matter before validation rejects it.
 */
internal const val MAX_BACKUP_APPS = 5_000
internal const val MAX_BACKUP_PAGES = 200
internal const val MAX_BACKUP_WIDGETS = 500
internal const val MAX_BACKUP_FOLDERS = 500
internal const val MAX_BACKUP_STACKS = 500
internal const val MAX_BACKUP_IDS = 5_000

data class BackupWidgetDescriptor(
    val slot: Int,
    val providerComponent: String?,
    val userSerial: Long?,
    val title: String,
    val profileLabel: String,
    val builtinId: Int? = null,
    val isWork: Boolean = false,
)

/**
 * A decoded backup, ready for the review screen.
 *
 * [layout] is the flat active-layout form the editing layer and `applyImportedLayout` consume, kept
 * first and unchanged so every existing construction site still reads. The schema-9 fields after it
 * default, so a v1/v2 import — and the existing tests — construct exactly as before.
 */
data class LayoutImportPreview(
    val layout: HomeLayout,
    val missingApps: List<String>,
    val profileIssues: List<String>,
    val appCount: Int,
    val folderCount: Int,
    val widgetCount: Int,
    val compact: LayoutPreset,
    val expanded: LayoutPreset,
    val labels: Boolean,
    val googleSearch: Boolean,
    val verticalStatus: Boolean,
    // ----- backup v3 -----
    /** All three layouts plus the shared dock and folders. */
    val layoutSet: LayoutSet = LayoutSet(),
    val leadingPage: LeadingPageConfig = LeadingPageConfig(),
    val stacks: List<WidgetStack> = emptyList(),
    val hiddenApps: Set<String> = emptySet(),
    val iconOverrides: List<IconOverrideRecord> = emptyList(),
    val settings: DuoSettings = DuoSettings(),
    /** The version the file declared, so the review screen can say what it read. */
    val version: Int = LAYOUT_BACKUP_VERSION,
)

fun layoutBackupScope(context: Context): String {
    val prefs = context.getSharedPreferences("layout_backup_identity", Context.MODE_PRIVATE)
    return prefs.getString("scope", null)
        ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("scope", it).apply() }
}

/**
 * Writes a version 3 backup.
 *
 * [isPrivateLocked] is the NFR-S7 gate. It answers "does this stored id name an app in a private
 * space that is locked right now?", and every id it claims is dropped from the layouts, the dock,
 * folders, hidden apps, icon overrides and the app metadata. The predicate is a parameter rather
 * than a lookup so this stays a pure function the JVM suite can test the exclusion of directly.
 *
 * Exporting while the space is *unlocked* keeps those apps, which is exactly what `PRIVACY.md`
 * promises: "An export taken while your private space is unlocked can include apps placed from it;
 * exporting while it is locked cannot, because Duo holds no list of them then."
 */
fun encodeLayoutBackup(
    state: LauncherState,
    widgetDescriptors: List<BackupWidgetDescriptor>,
    sourceScope: String,
    isPrivateLocked: (String) -> Boolean = { false },
): String = encodeLayoutBackupV3(state, widgetDescriptors, sourceScope, isPrivateLocked)

internal fun exportedWidgetScope(restore: WidgetRestore, currentScope: String) = restore.sourceScope ?: currentScope

/**
 * Everything decoding needs to know about what is installed here.
 *
 * [AppEntry] carries a `Bitmap` and a `ComponentName`, so a list of them can only be built on a
 * device. Reducing the catalogue to the two things the importer actually consults — which ids exist,
 * and which of them belong to a work profile — is what lets the whole codec be covered by JVM unit
 * tests (NFR-M2) instead of only by instrumented ones.
 */
internal data class BackupCatalog(val ids: Set<String>, val workIds: Set<String> = emptySet()) {
    /**
     * A work-profile app is only placeable when the backup came from this device: profile serials
     * are assigned per device, so the same serial elsewhere need not mean the same profile.
     */
    fun available(sameScope: Boolean): Set<String> = if (sameScope) ids else ids - workIds

    companion object {
        fun of(apps: List<AppEntry>): BackupCatalog = BackupCatalog(
            apps.mapTo(mutableSetOf(), AppEntry::id),
            apps.filter(AppEntry::isWork).mapTo(mutableSetOf(), AppEntry::id),
        )
    }
}

/**
 * Reads any supported backup.
 *
 * The version is checked before anything else is interpreted, so a refusal cannot have read a
 * placement, let alone applied one (FR-83).
 */
fun decodeLayoutBackup(
    raw: String,
    currentApps: List<AppEntry>,
    currentProfiles: List<AppProfile>,
    currentScope: String,
): LayoutImportPreview = decodeLayoutBackup(raw, BackupCatalog.of(currentApps), currentProfiles, currentScope)

internal fun decodeLayoutBackup(
    raw: String,
    catalog: BackupCatalog,
    currentProfiles: List<AppProfile>,
    currentScope: String,
): LayoutImportPreview {
    require(raw.toByteArray(Charsets.UTF_8).size <= MAX_LAYOUT_BACKUP_BYTES) { "Layout backup is larger than 2 MB" }
    val root = DuoJson.parse(raw) as? DuoJson.Obj ?: error("This layout backup is not a JSON object")
    val version = root.strictInt("version")
    require(version <= LAYOUT_BACKUP_VERSION) { BACKUP_TOO_NEW_MESSAGE }
    require(version >= 1) { "Unsupported layout backup version" }
    return if (version == LAYOUT_BACKUP_VERSION) {
        decodeLayoutBackupV3(root, catalog, currentProfiles, currentScope)
    } else {
        decodeLegacyLayoutBackup(root, version, catalog, currentProfiles, currentScope)
    }
}

// -------------------------------------------------------------------------------------------
// v1 / v2
// -------------------------------------------------------------------------------------------

/**
 * The v1/v2 reader, preserved rule for rule (AC-66).
 *
 * This is the path a user restoring an older backup takes, so it keeps every check the previous
 * build enforced. What is new is only the tail: the decoded layout is lifted into a schema-9
 * [LayoutSet] so the rest of the app sees one shape regardless of which version was read.
 */
private fun decodeLegacyLayoutBackup(
    root: DuoJson.Obj,
    version: Int,
    catalog: BackupCatalog,
    currentProfiles: List<AppProfile>,
    currentScope: String,
): LayoutImportPreview {
    val sourceScope = root.strictString("sourceScope").also { require(it.isNotBlank()) }
    val sameScope = sourceScope == currentScope
    val appsArray = root.strictArray("apps")
    require(appsArray.size <= MAX_BACKUP_APPS) { "Layout backup lists too many apps" }
    val appMetadata = appsArray.strictObjects().map { item ->
        val id = item.strictString("id")
        val identity = parseProfileAppId(id) ?: error("Invalid app identity")
        require(item.strictString("component") == identity.component)
        require(isFlattenedComponent(identity.component))
        val serial = item.strictLong("userSerial")
        require(serial >= 0 && item.strictString("label").isNotBlank() && item.strictString("profileLabel").isNotBlank())
        if (item.strictBoolean("work")) require(identity.userSerial == serial) else require(identity.userSerial == null)
        id to item.strictString("label")
    }.also { entries -> require(entries.map { it.first }.distinct().size == entries.size) }.toMap()

    val available = catalog.available(sameScope)
    val missing = linkedSetOf<String>()
    fun importedApp(id: String?): String? {
        if (id == null) return null
        require(id in appMetadata) { "Layout references an app without metadata" }
        return id.takeIf { it in available } ?: run { missing += "$id (${appMetadata.getValue(id)})"; null }
    }

    val slotsArray = root.strictArray("homeSlots")
    require(slotsArray.size <= MAX_BACKUP_HOME_CELLS)
    val rawSlots = slotsArray.strictNullableIds()
    val rawLeadingSlots = if (version == 1) {
        List(HOME_CELLS) { null }
    } else {
        val array = root.strictArray("leadingSlots")
        require(array.size == HOME_CELLS) { "Unfolded-only page must contain exactly $HOME_CELLS cells" }
        array.strictNullableIds()
    }

    val folderArray = root.strictArray("folders")
    require(folderArray.size <= MAX_BACKUP_FOLDERS)
    val importedFolders = folderArray.strictObjects().map { item ->
        val children = item.strictArray("apps")
        require(children.size <= MAX_BACKUP_IDS)
        FolderEntry(item.strictString("id"), item.strictString("title"), children.strictIds())
    }
    require(importedFolders.map(FolderEntry::id).distinct().size == importedFolders.size)
    require(importedFolders.flatMap(FolderEntry::appIds).distinct().size == importedFolders.sumOf { it.appIds.size })
    importedFolders.forEach { folder ->
        require(isFolderId(folder.id) && folder.title.isNotBlank() && folder.appIds.size >= 2)
        require(folder.appIds.none(::isReservedFolderId))
        require((rawSlots + rawLeadingSlots).count(folder.id::equals) == 1)
    }

    val dockArray = root.strictArray("dock")
    require(dockArray.size == 4)
    val rawDock = dockArray.strictNullableIds()
    val surfaceApps = (rawSlots + rawLeadingSlots).filterNotNull().filterNot(::isReservedFolderId) +
        rawDock.filterNotNull() + importedFolders.flatMap(FolderEntry::appIds)
    require(surfaceApps.distinct().size == surfaceApps.size) { "An app shortcut appears more than once" }

    val folderResults = importedFolders.associate { folder ->
        folder.id to folder.copy(appIds = folder.appIds.mapNotNull(::importedApp))
    }
    val folders = folderResults.values.filter { it.appIds.size >= 2 }
    fun resolveCell(value: String?): String? = when {
        value == null -> null
        isReservedFolderId(value) -> folderResults[value]?.let { folder ->
            when (folder.appIds.size) {
                0 -> null
                1 -> folder.appIds.single()
                else -> folder.id
            }
        } ?: error("Orphan folder reference")
        else -> importedApp(value)
    }
    val slots = rawSlots.map(::resolveCell)
    val leadingSlots = rawLeadingSlots.map(::resolveCell)
    val dock = rawDock.map { value -> value?.also { require(!isReservedFolderId(it)) }?.let(::importedApp) }

    var layout = HomeLayout(slots.dropLastWhile { it == null }, dock, folders = folders, leadingSlots = leadingSlots)
    val profileSerials = currentProfiles.mapTo(mutableSetOf(), AppProfile::userSerial)
    val profileIssues = linkedSetOf<String>()
    val widgetArray = root.strictArray("widgets")
    require(widgetArray.size <= MAX_BACKUP_WIDGETS)
    val widgetSlots = mutableSetOf<Int>()
    widgetArray.strictObjects().forEach { item ->
        val slot = item.strictInt("slot")
        require(widgetSlots.add(slot)) { "Widget slots must be unique" }
        val builtin = if (item.has("builtinId")) item.strictInt("builtinId") else null
        val provider = item.optString("provider")
        val id = if (builtin != null) {
            require(builtin in setOf(CLOCK_WIDGET, DATE_WIDGET, INFO_WIDGET)); builtin
        } else {
            NEEDS_BINDING_WIDGET
        }
        val placement = WidgetPlacement(
            slot, id, item.strictInt("page"), item.strictInt("column"), item.strictInt("row"),
            item.strictInt("spanX"), item.strictInt("spanY"),
        )
        require(validBackupPlacement(placement) && layout.widgetPlacements.none { backupOverlaps(it, placement) })
        require(placement.coveredIndices().none { layout.slotAt(it) != null })
        val restore = if (id == NEEDS_BINDING_WIDGET) {
            require(provider != null && isFlattenedComponent(provider))
            val savedSerial = item.strictLong("userSerial"); require(savedSerial >= 0)
            val title = item.strictString("title"); val profileLabel = item.strictString("profileLabel")
            require(title.isNotBlank() && profileLabel.isNotBlank())
            val work = item.strictBoolean("work")
            val widgetScope = item.optString("sourceScope") ?: sourceScope
            val serial = if (work) savedSerial else currentProfiles.firstOrNull { it.isPersonal }?.userSerial ?: savedSerial
            if ((work && widgetScope != currentScope) || serial !in profileSerials) {
                profileIssues += "$title ($profileLabel profile requires explicit mapping)"
            }
            WidgetRestore(slot, provider, serial, title, profileLabel, work, widgetScope)
        } else {
            null
        }
        layout = layout.copy(
            widgetPlacements = (layout.widgetPlacements + placement).sortedBy { it.slot },
            widgetRestores = layout.widgetRestores + listOfNotNull(restore),
        )
    }

    fun preset(key: String): LayoutPreset {
        val item = root.strictObj(key)
        val loaded = LayoutPreset(
            item.strictFloat("iconSize"), item.strictFloat("rowGap"),
            item.strictFloat("dockWidth"), item.strictFloat("dockPosition"), item.strictBoolean("dockAlignToGrid"),
        )
        require(loaded == loaded.sanitized()) { "Invalid layout preset" }
        return loaded
    }
    // Validate settings eagerly even though HomeLayout contains placement data only.
    val compact = preset("compact"); val expanded = preset("expanded")
    val labels = root.strictBoolean("labels")
    val googleSearch = root.strictBoolean("googleSearch")
    val verticalStatus = root.strictBoolean("verticalStatus")

    return LayoutImportPreview(
        layout, missing.toList(), profileIssues.toList(),
        appCount = (slots + leadingSlots).count { it != null && !isReservedFolderId(it) } +
            dock.count { it != null } + folders.sumOf { it.appIds.size },
        folderCount = folders.size, widgetCount = layout.widgetPlacements.size,
        compact = compact, expanded = expanded, labels = labels, googleSearch = googleSearch,
        verticalStatus = verticalStatus,
        // FR-82: everything schema 9 added takes its default, and the one thing a v2 payload does
        // describe — a single 4x6 arrangement plus its Classic leading page — is lifted into the
        // mirrored layout, which is exactly what the v8 -> v9 upgrade does with the same data.
        layoutSet = legacyLayoutSet(layout),
        leadingPage = LeadingPageConfig(kind = LeadingPageKind.CLASSIC),
        settings = DuoSettings(),
        version = version,
    )
}

/** Lifts a v1/v2 layout into the schema-9 shape, mirroring [migrateV8ToV9]'s choices. */
private fun legacyLayoutSet(layout: HomeLayout): LayoutSet = LayoutSet(
    mode = LayoutMode.MIRRORED,
    mirrored = DuoLayout(
        grid = DEFAULT_GRID,
        slots = layout.slots,
        leadingSlots = layout.leadingSlots,
        widgetPlacements = layout.widgetPlacements,
        widgetRestores = layout.widgetRestores,
    ).withPageIds(),
    cover = DuoLayout(grid = DEFAULT_GRID),
    inner = DuoLayout(grid = DEFAULT_GRID),
    dock = DockConfig(side = DockSide.RIGHT, capacity = DEFAULT_DOCK_CAPACITY, items = layout.dock).sanitized(),
    folders = layout.folders,
)

internal fun validBackupPlacement(value: WidgetPlacement): Boolean {
    val base = value.slot in 0..10_000 && value.page in -1..99 && value.column >= 0 && value.row >= 0 &&
        value.spanX in 1..GRID_COLUMNS && value.spanY in 1..GRID_ROWS && value.column + value.spanX <= GRID_COLUMNS
    val inside = value.row + value.spanY <= GRID_ROWS
    val overflow = value.page > 0 && value.slot / 3 == value.page && value.slot % 3 == 2 && value.column == 0 &&
        value.row == GRID_ROWS && value.spanX == GRID_COLUMNS && value.spanY == 4
    return base && (inside || overflow)
}

internal fun backupOverlaps(a: WidgetPlacement, b: WidgetPlacement) = a.page == b.page &&
    a.column < b.column + b.spanX && b.column < a.column + a.spanX &&
    a.row < b.row + b.spanY && b.row < a.row + a.spanY

// -------------------------------------------------------------------------------------------
// Strict accessors
// -------------------------------------------------------------------------------------------
//
// Every one of these fails on a wrong type rather than substituting a default. A backup is a file
// the user can hand to Duo from anywhere, so "looks close enough" is never good enough: a span that
// arrived as a string, or a slot that arrived as 1.5, means the file is not what it claims to be.

internal fun DuoJson.Obj.strictInt(key: String): Int {
    val number = this[key] as? DuoJson.Num ?: error("$key must be an integer")
    return number.asInt() ?: error("$key must be a finite integer")
}

internal fun DuoJson.Obj.strictLong(key: String): Long {
    val number = this[key] as? DuoJson.Num ?: error("$key must be an integer")
    return number.asLong()?.takeIf { it >= 0 } ?: error("$key must be a non-negative integer")
}

internal fun DuoJson.Obj.strictFloat(key: String): Float {
    val number = this[key] as? DuoJson.Num ?: error("$key must be a number")
    return number.value.toFloat().takeIf(Float::isFinite) ?: error("$key must be finite")
}

internal fun DuoJson.Obj.strictBoolean(key: String): Boolean =
    (this[key] as? DuoJson.Bool)?.value ?: error("$key must be a boolean")

internal fun DuoJson.Obj.strictString(key: String): String =
    (this[key] as? DuoJson.Str)?.value ?: error("$key must be a string")

internal fun DuoJson.Obj.strictArray(key: String): DuoJson.Arr =
    this[key] as? DuoJson.Arr ?: error("$key must be an array")

internal fun DuoJson.Obj.strictObj(key: String): DuoJson.Obj =
    this[key] as? DuoJson.Obj ?: error("$key must be an object")

/** A present, non-blank string, or null when the key is absent or JSON null. */
internal fun DuoJson.Obj.optString(key: String): String? = when (val value = this[key]) {
    null, is DuoJson.Null -> null
    is DuoJson.Str -> value.value.takeIf(String::isNotBlank)
    else -> error("$key must be a string")
}

internal fun DuoJson.Obj.optInt(key: String): Int? = when (val value = this[key]) {
    null, is DuoJson.Null -> null
    is DuoJson.Num -> value.asInt() ?: error("$key must be a finite integer")
    else -> error("$key must be an integer")
}

internal fun DuoJson.Obj.optBoolean(key: String, default: Boolean): Boolean = when (val value = this[key]) {
    null, is DuoJson.Null -> default
    is DuoJson.Bool -> value.value
    else -> error("$key must be a boolean")
}

internal fun DuoJson.Obj.strictIntOr(key: String, default: Int): Int = optInt(key) ?: default

/** Every element must be an object; anything else means the file is not what it claims to be. */
internal fun DuoJson.Arr.strictObjects(): List<DuoJson.Obj> =
    values.map { it as? DuoJson.Obj ?: error("Expected a JSON object") }

/** Every element must be a non-blank string. */
internal fun DuoJson.Arr.strictIds(): List<String> = values.map { value ->
    (value as? DuoJson.Str)?.value?.takeIf(String::isNotBlank) ?: error("Expected an id string")
}

/** Every element must be a non-blank string or JSON null, which is how slot arrays are stored. */
internal fun DuoJson.Arr.strictNullableIds(): List<String?> = values.map { value ->
    when (value) {
        is DuoJson.Null -> null
        is DuoJson.Str -> value.value.takeIf(String::isNotBlank) ?: error("Expected an id string")
        else -> error("Expected an id string or null")
    }
}

internal fun DuoJson.Arr.strictInts(): List<Int> = values.map { value ->
    (value as? DuoJson.Num)?.asInt() ?: error("Expected an integer")
}

/**
 * An enum name that this build does not know falls back to [default].
 *
 * This is the same tolerance [decodeLauncherState] applies for the same reason: an unknown name here
 * is always a *setting*, never a placement, so defaulting it costs the user one preference while
 * failing would cost them the whole restore. A malformed *type* still fails.
 */
internal inline fun <reified T : Enum<T>> DuoJson.Obj.enumOr(key: String, default: T): T {
    val name = optString(key) ?: return default
    return enumValues<T>().firstOrNull { it.name == name } ?: default
}
