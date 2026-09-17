package com.jake.duolauncher

import android.app.Application
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.lifecycle.AndroidViewModel
import com.jake.duolauncher.icons.IconAppearance
import com.jake.duolauncher.icons.IconRasterizer
import com.jake.duolauncher.icons.IconShape
import com.jake.duolauncher.icons.IconStyle
import com.jake.duolauncher.shortcuts.DuoPinRequests
import com.jake.duolauncher.shortcuts.HomeLayoutLock
import com.jake.duolauncher.shortcuts.HomeLayoutUnlock
import com.jake.duolauncher.shortcuts.PinItemKind
import com.jake.duolauncher.shortcuts.PinnedItem
import com.jake.duolauncher.home.toggleHiddenPage
import com.jake.duolauncher.shortcuts.PinnedItemPlacer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator

data class AppEntry(
    val id: String,
    val label: String,
    val icon: Bitmap,
    val component: ComponentName = ComponentName.unflattenFromString(parseProfileAppId(id)?.component ?: id)
        ?: ComponentName("", ""),
    val user: UserHandle = Process.myUserHandle(),
    val userSerial: Long = 0,
    val profileLabel: String = "Personal",
    val isWork: Boolean = false,
    val available: Boolean = true,
) {
    val packageName: String get() = component.packageName
}

/**
 * The observable launcher state.
 *
 * The flat placement fields (`homeSlots`, `leadingSlots`, `dock`, `widgetPlacements`, …) are the
 * **active** layout — whichever of mirrored/cover/inner the current posture and layout mode select.
 * They are kept because the whole editing layer, the Compose UI and the existing tests are written
 * against them. [layoutSet] is the full schema-9 record that holds all three layouts, and it is the
 * source of truth: the flat fields are re-derived from it on every change so the two can never
 * drift apart.
 */
data class LauncherState(
    val apps: List<AppEntry> = emptyList(),
    val profiles: List<AppProfile> = emptyList(),
    val folders: List<FolderEntry> = emptyList(),
    val homeSlots: List<String?> = emptyList(),
    val leadingSlots: List<String?> = List(HOME_CELLS) { null },
    val editRevision: Int = 0,
    val canUndoEdit: Boolean = false,
    /** Always exactly `layoutSet.dock.capacity` slots once derived (FR-38). */
    val dock: List<String?> = List(DEFAULT_DOCK_CAPACITY) { null },
    val widgetPlacements: List<WidgetPlacement> = DEFAULT_WIDGET_PLACEMENTS,
    val widgetRestores: List<WidgetRestore> = emptyList(),
    val googleSearch: Boolean = true,
    val compact: LayoutPreset = LayoutPreset(),
    val expanded: LayoutPreset = LayoutPreset(),
    val labels: Boolean = true,
    val verticalStatus: Boolean = true,
    val loading: Boolean = true,
    val error: String? = null,
    // ----- schema 9 -----
    val layoutSet: LayoutSet = LayoutSet(),
    /** The active layout's grid (FR-31). */
    val grid: GridSpec = DEFAULT_GRID,
    /** True while the inner display is the active one, which selects the inner layout. */
    val expandedActive: Boolean = false,
    val leadingPage: LeadingPageConfig = LeadingPageConfig(),
    val stacks: List<WidgetStack> = emptyList(),
    val hiddenApps: Set<String> = emptySet(),
    val iconOverrides: List<IconOverrideRecord> = emptyList(),
    val settings: DuoSettings = DuoSettings(),
) {
    val order: List<String> get() = homeSlots.filterNotNull()
    val widgets: List<Int> get() = layout.widgets
    val layout: HomeLayout get() = HomeLayout(homeSlots, dock, widgetPlacements, folders, widgetRestores, leadingSlots, grid)
    val homePages get() = layout.pageCount

    /** Which stored layout the current posture and mode select. */
    val activeTarget: LayoutTarget get() = layoutSet.targetFor(expandedActive)

    /** Apps hidden from every surface (FR-75). */
    fun isHidden(appId: String) = appId in hiddenApps

    /** FR-49. */
    val layoutLocked: Boolean get() = settings.lockLayout
}

class LauncherModel(application: Application) : AndroidViewModel(application) {
    private data class RefreshedApps(val entries: List<AppEntry>, val profiles: List<AppProfile>,
        val authoritativeProfiles: Set<Long>, val removedProfiles: Set<Long>)
    private val prefs = application.getSharedPreferences("launcher", 0)
    private val launcherApps = application.getSystemService(LauncherApps::class.java)
    private val userManager = application.getSystemService(UserManager::class.java)
    private val appCatalogPrefs = application.getSharedPreferences("app_catalog", 0)
    private val legacyRaw = prefs.getString(STATE_KEY, null)
    private val sourceSchema = runCatching {
        (DuoJson.parse(legacyRaw ?: "{}") as? DuoJson.Obj)?.int("schema", 1) ?: 1
    }.getOrDefault(1)
    private var needsMigration = sourceSchema < 2
    private var statePayloadInvalid = false

    /**
     * The last payload known to decode cleanly. It becomes `state_v9_previous` on the next write,
     * which is the first place recovery looks (error table: "Schema 9 load fails validation").
     */
    private var lastGoodPayload: String? = null
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    // An edit arms a 150 ms debounce; the serialization and disk write run on IO (NFR-P5).
    // onStop/onCleared flush synchronously so a pending edit can never be lost to a process kill.
    private val persistence = DebouncedPersistence(
        scheduler = CoroutinePersistenceScheduler(viewModelScope, Dispatchers.IO),
        serialize = ::serializeState, write = ::writeState)
    private var persistRequested = false
    /**
     * The complete state before the last undoable edit, and the state it produced.
     *
     * Both halves are whole [LauncherState] values, which is what makes undo total: restoring
     * `first` brings back the layouts, the grid, the dock, and every schema-9 field an import may
     * have replaced, not merely the placements that were dragged.
     */
    private var undoLayout: Pair<LauncherState, LauncherState>? = null
    private var refreshing = false
    private var refreshPending = false
    private val invalidatedPackages = mutableSetOf<Pair<Long, String>>()
    private val removedPackages = mutableSetOf<Pair<Long, String>>()
    private val unavailablePackages = mutableSetOf<Pair<Long, String>>()
    // Accessed only in the serialized IO refresh. Returning Home reuses existing bitmaps.
    private val iconCache = mutableMapOf<String, AppEntry>()
    private var iconConfiguration = ""

    /** The page a pin request places onto (FR-28: "the current Home page"). Set by the UI. */
    private var currentPage = 0
    internal var completedRefreshes = 0
        private set
    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh(packageName, user)
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            removedPackages += userManager.getSerialNumberForUser(user) to packageName
            refresh(packageName, user)
        }
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh(packageName, user)
        override fun onPackagesAvailable(packages: Array<out String>, user: android.os.UserHandle, replacing: Boolean) {
            packages.forEach {
                val key = userManager.getSerialNumberForUser(user) to it
                invalidatedPackages += key; unavailablePackages -= key
            }; refresh()
        }
        override fun onPackagesUnavailable(packages: Array<out String>, user: android.os.UserHandle, replacing: Boolean) {
            // A package update temporarily hides activities; don't erase its pins or dock slot.
            packages.forEach {
                val key = userManager.getSerialNumberForUser(user) to it
                invalidatedPackages += key; unavailablePackages += key
            }; refresh()
        }
    }

    /** The seams the shortcuts track left for the layout owner (FR-28, FR-49). */
    private val pinLock = HomeLayoutLock { mutable.value.settings.lockLayout }
    private val pinUnlock = HomeLayoutUnlock { setLockLayout(false) }
    private val pinPlacer = PinnedItemPlacer(::placePinnedItem)

    init {
        launcherApps.registerCallback(callback)
        DuoPinRequests.layoutLock = pinLock
        DuoPinRequests.unlock = pinUnlock
        DuoPinRequests.placer = pinPlacer
        refresh()
    }

    fun refresh(invalidatedPackage: String? = null, user: UserHandle = Process.myUserHandle()) {
        invalidatedPackage?.let { invalidatedPackages += userManager.getSerialNumberForUser(user) to it }
        if (refreshing) { refreshPending = true; return }
        refreshing = true
        val invalidated = invalidatedPackages.toSet()
        val removed = removedPackages.toSet()
        val temporarilyUnavailable = unavailablePackages.toSet()
        invalidatedPackages.clear()
        removedPackages.clear()
        val resources = getApplication<Application>().resources
        val configuration = resources.configuration.let { "${it.densityDpi}|${it.locales.toLanguageTags()}|${it.uiMode}" }
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    if (configuration != iconConfiguration) { iconCache.clear(); iconConfiguration = configuration }
                    iconCache.keys.removeAll { key -> parseProfileAppId(key)?.let { identity ->
                        val serial = identity.userSerial ?: userManager.getSerialNumberForUser(Process.myUserHandle())
                        serial to (ComponentName.unflattenFromString(identity.component)?.packageName ?: "") in invalidated
                    } == true }
                    val collator = Collator.getInstance()
                    val application = getApplication<Application>()
                    val personal = Process.myUserHandle()
                    val personalSerial = userManager.getSerialNumberForUser(personal)
                    val associatedSerials = userManager.userProfiles.mapTo(mutableSetOf(), userManager::getSerialNumberForUser)
                    val handles = launcherApps.profiles
                        .filter { profile ->
                            val serial = userManager.getSerialNumberForUser(profile)
                            serial in associatedSerials && (serial == personalSerial || isSupportedWorkProfile(launcherApps, profile))
                        }
                        .distinctBy(userManager::getSerialNumberForUser)
                    val profiles = handles.map { profile ->
                        val serial = userManager.getSerialNumberForUser(profile)
                        val isPersonal = serial == personalSerial
                        val quiet = !isPersonal && runCatching { userManager.isQuietModeEnabled(profile) }.getOrDefault(false)
                        val unlocked = runCatching { userManager.isUserUnlocked(profile) }.getOrDefault(isPersonal)
                        AppProfile(serial, if (isPersonal) "Personal" else "Work", isPersonal, !isPersonal,
                            quiet, unlocked, !quiet && unlocked)
                    }
                    val cachedBeforeProfiles = loadCachedApps().filterNot { entry -> entry.userSerial to entry.packageName in removed }
                    val removedProfileSerials = removedAssociatedProfileSerials(
                        cachedBeforeProfiles.filter(AppEntry::isWork).mapTo(mutableSetOf(), AppEntry::userSerial), associatedSerials)
                    val cached = cachedBeforeProfiles.filterNot { it.isWork && it.userSerial in removedProfileSerials }
                    val authoritativeProfiles = mutableSetOf<Long>()
                    authoritativeProfiles += removedProfileSerials
                    val live = handles.flatMap { profile ->
                        val serial = userManager.getSerialNumberForUser(profile)
                        val descriptor = profiles.first { it.userSerial == serial }
                        val activityList = if (descriptor.available) runCatching { launcherApps.getActivityList(null, profile) }.getOrNull() else null
                        if (activityList == null) emptyList() else activityList.also { authoritativeProfiles += serial }.mapNotNull { info ->
                            if (info.componentName.packageName == application.packageName) return@mapNotNull null
                            val component = info.componentName
                            val id = profileAppId(component.flattenToString(), serial, personalSerial)
                            val label = info.label.toString()
                            iconCache[id]?.takeIf { it.label == label && it.available } ?: run {
                                val icon = runCatching { info.getBadgedIcon(0) }.getOrElse { application.packageManager.defaultActivityIcon }
                                AppEntry(id, label, launcherIcon(icon), component, profile, serial, descriptor.label,
                                    descriptor.isWork, available = true).also { iconCache[id] = it }
                            }
                        }
                    }
                    val liveIds = live.mapTo(mutableSetOf(), AppEntry::id)
                    val profileBySerial = profiles.associateBy(AppProfile::userSerial)
                    val unavailable = cached.filter { it.id !in liveIds }.mapNotNull { cachedEntry ->
                        val profile = profileBySerial[cachedEntry.userSerial]
                        val key = cachedEntry.userSerial to cachedEntry.packageName
                        // A successful profile query is authoritative except while Android explicitly
                        // reports a package unavailable (for example during an update).
                        if (cachedEntry.userSerial in authoritativeProfiles && key !in temporarilyUnavailable) null
                        else cachedEntry.copy(user = profile?.let { p -> handles.firstOrNull { userManager.getSerialNumberForUser(it) == p.userSerial } } ?: personal,
                            profileLabel = profile?.label ?: cachedEntry.profileLabel, available = false)
                    }
                    val knownBefore = cached.mapTo(mutableSetOf(), AppEntry::id)
                    val entries = (live + unavailable).distinctBy(AppEntry::id)
                        .sortedWith { a, b -> collator.compare(a.label, b.label) }
                    saveCachedApps(entries)
                    iconCache.keys.retainAll(entries.map { it.id }.toSet())
                    Triple(
                        RefreshedApps(entries, (profiles + unavailable.map { AppProfile(it.userSerial, it.profileLabel, false, true,
                            quiet = true, unlocked = false, available = false) }).distinctBy(AppProfile::userSerial),
                            authoritativeProfiles.toSet(), removedProfileSerials),
                        knownBefore,
                        liveIds,
                    )
                }
                val refreshed = apps.first
                val knownBefore = apps.second
                mutable.update { old ->
                    val entries = refreshed.entries
                    val profiles = refreshed.profiles
                    // persistRequested covers the debounce window before "initialized" reaches disk.
                    val freshInstall = !persistRequested && !prefs.getBoolean("initialized", false)
                    val dock = if (freshInstall) initialDock(entries, old.layoutSet.dock.capacity) else old.dock
                    val installed = entries.map { it.id }
                    val legacyPins = if (needsMigration) migrateHomePins(old.order, installed, suggestedPins(entries, dock))
                        else old.homeSlots
                    val pins = if (sourceSchema < 6 && needsMigration) migrateSchema5Apps(legacyPins) else legacyPins
                    val availableIds = entries.mapTo(mutableSetOf(), AppEntry::id)
                    val authoritative = refreshed.authoritativeProfiles
                    val everyPlacedId = old.layoutSet.let { set ->
                        LayoutTarget.entries.flatMap { target ->
                            val layout = set.layout(target)
                            layout.slots.filterNotNull() + layout.leadingSlots.filterNotNull()
                        }
                    } + old.dock.filterNotNull() + old.folders.flatMap { it.appIds }
                    val removedIds = removedAppIds(everyPlacedId, availableIds,
                        authoritative, temporarilyUnavailable, removed, userManager.getSerialNumberForUser(Process.myUserHandle()),
                        refreshed.removedProfiles)
                    val validPins = pins.map { it?.takeUnless(removedIds::contains) }
                    val validDock = dock.map { it?.takeUnless(removedIds::contains) }
                    val reconciled = reconcileFolders(HomeLayout(validPins, validDock, old.widgetPlacements, old.folders,
                        old.widgetRestores, old.leadingSlots, old.grid), removedIds)
                    // A removed app must also leave the layouts that are not currently on screen,
                    // or it would reappear the moment the user switches posture or layout mode.
                    val prunedSet = pruneInactiveLayouts(
                        old.layoutSet.withHomeLayout(old.activeTarget, reconciled), old.activeTarget, removedIds)
                    val autoAdded = if (freshInstall || !old.settings.autoAddApps) prunedSet
                        else autoAddNewApps(prunedSet, old, entries, knownBefore, removedIds)
                    old.withLayoutSet(autoAdded).copy(apps = entries, profiles = profiles,
                        canUndoEdit = old.canUndoEdit && old.layout == reconciled, loading = false,
                        error = if (statePayloadInvalid) old.error else old.recoveryBanner())
                }
                if (needsMigration && legacyRaw != null && !prefs.contains("state_v1_backup"))
                    prefs.edit().putString("state_v1_backup", legacyRaw).apply()
                needsMigration = false
                persist()
                completedRefreshes++
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, error = "Apps could not be loaded. Tap to retry.") }
            } finally {
                refreshing = false
                if (refreshPending) { refreshPending = false; refresh() }
            }
        }
    }

    /** The recovery banner survives a refresh so the user still sees it (error table). */
    private fun LauncherState.recoveryBanner(): String? = error?.takeIf { it == RECOVERED_BANNER }

    private fun pruneInactiveLayouts(set: LayoutSet, active: LayoutTarget, removedIds: Set<String>): LayoutSet {
        if (removedIds.isEmpty()) return set
        var result = set
        LayoutTarget.entries.filterNot { it == active }.forEach { target ->
            val layout = result.layout(target)
            result = result.withLayout(target, layout.copy(
                slots = layout.slots.map { it?.takeUnless(removedIds::contains) }.dropLastWhile { it == null },
                leadingSlots = layout.leadingSlots.map { it?.takeUnless(removedIds::contains) },
            ))
        }
        return result
    }

    /** FR-50: a newly installed app lands in the first free cell of the last page, or a new one. */
    private fun autoAddNewApps(
        set: LayoutSet,
        old: LauncherState,
        entries: List<AppEntry>,
        knownBefore: Set<String>,
        removedIds: Set<String>,
    ): LayoutSet {
        if (old.settings.lockLayout) return set
        if (knownBefore.isEmpty()) return set
        val placedAnywhere = LayoutTarget.entries.flatMap { target ->
            val layout = set.layout(target)
            layout.slots.filterNotNull() + layout.leadingSlots.filterNotNull()
        }.toMutableSet()
        placedAnywhere += set.dock.items.filterNotNull()
        placedAnywhere += set.folders.flatMap { it.appIds }
        val newcomers = entries.map { it.id }
            .filter { it !in knownBefore && it !in placedAnywhere && it !in removedIds && it !in old.hiddenApps }
        if (newcomers.isEmpty()) return set
        val target = set.targetFor(old.expandedActive)
        var layout = set.homeLayout(target)
        newcomers.forEach { id -> layout = placeOnFirstFreeCell(layout, id, layout.pageCount - 1) ?: layout }
        return set.withHomeLayout(target, layout)
    }

    private fun loadCachedApps(): List<AppEntry> = runCatching {
        val application = getApplication<Application>()
        val personal = Process.myUserHandle()
        val array = JSONArray(appCatalogPrefs.getString("apps", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val identity = parseProfileAppId(id) ?: error("Invalid cached app identity")
            val component = ComponentName.unflattenFromString(identity.component) ?: error("Invalid cached component")
            val serial = item.getLong("serial").takeIf { it >= 0 } ?: error("Invalid cached profile")
            val user = userManager.getUserForSerialNumber(serial) ?: personal
            val isWork = item.optBoolean("work", identity.userSerial != null)
            val baseIcon = application.packageManager.defaultActivityIcon
            val icon = runCatching { application.packageManager.getUserBadgedIcon(baseIcon, user) }.getOrDefault(baseIcon)
            AppEntry(id, item.getString("label"), launcherIcon(icon), component, user, serial,
                item.optString("profile", if (isWork) "Work" else "Personal"), isWork, available = false)
        }
    }.getOrDefault(emptyList())

    private fun saveCachedApps(apps: List<AppEntry>) {
        val array = JSONArray().also { result -> apps.forEach { app -> result.put(JSONObject()
            .put("id", app.id).put("label", app.label).put("serial", app.userSerial)
            .put("profile", app.profileLabel).put("work", app.isWork)) } }
        appCatalogPrefs.edit().putString("apps", array.toString()).apply()
    }

    private fun initialDock(apps: List<AppEntry>, capacity: Int): List<String?> {
        val packages = listOf(
            listOf("com.samsung.android.dialer", "com.google.android.dialer"),
            listOf("com.android.chrome", "com.sec.android.app.sbrowser"),
            listOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
            listOf("com.spotify.music", "com.google.android.apps.youtube.music"),
        )
        val chosen = packages.map { choices -> choices.firstNotNullOfOrNull { pkg -> apps.firstOrNull { !it.isWork && it.packageName == pkg }?.id } }
        return List(capacity) { chosen.getOrNull(it) }
    }

    private fun suggestedPins(apps: List<AppEntry>, dock: List<String?>): List<String> {
        val groups = listOf(
            listOf("com.samsung.android.calendar", "com.google.android.calendar"),
            listOf("com.sec.android.app.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
            listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"),
            listOf("com.google.android.gm", "com.samsung.android.email.provider"),
            listOf("com.google.android.apps.maps"),
            listOf("com.samsung.android.app.notes", "com.google.android.keep"),
            listOf("com.sec.android.app.myfiles", "com.google.android.documentsui"),
            listOf("com.android.settings"),
            listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"),
            listOf("com.android.vending"), listOf("com.google.android.youtube"),
            listOf("com.samsung.android.app.contacts", "com.google.android.contacts"),
            listOf("com.google.android.apps.docs"), listOf("com.google.android.apps.walletnfcrel"),
            listOf("com.sec.android.app.shealth"),
        )
        return groups.mapNotNull { choices -> choices.firstNotNullOfOrNull { pkg ->
            apps.firstOrNull { !it.isWork && it.packageName == pkg && it.id !in dock }?.id
        } }.distinct()
    }

    fun setPinned(id: String, pinned: Boolean) {
        if (statePayloadInvalid) return
        // FR-49: the App Library sheet can toggle a pin without going through Edit mode or a drag,
        // so a locked layout has to be refused here too.
        if (mutable.value.layoutLocked) return
        if (mutable.value.folders.any { id in it.appIds }) return
        val old = mutable.value
        val enable = pinned && old.apps.any { it.id == id }
        val next = old.layout.let { layout ->
            layout.copy(
                slots = if (enable && id in old.leadingSlots) layout.slots else pinHomeApp(layout.slots, id,
                    enable, layout.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices(layout.grid) }.filterTo(mutableSetOf()) { it >= 0 }),
                leadingSlots = if (enable) layout.leadingSlots else layout.leadingSlots.map { it?.takeUnless(id::equals) },
            )
        }
        mutable.value = old.withActiveLayout(next).copy(canUndoEdit = false)
        persist()
    }

    fun turnOnWork(userSerial: Long): Boolean = runCatching {
        val user = userManager.getUserForSerialNumber(userSerial) ?: return false
        if (userManager.getSerialNumberForUser(Process.myUserHandle()) == userSerial || user !in launcherApps.profiles) return false
        userManager.requestQuietModeEnabled(false, user).also { refresh() }
    }.getOrDefault(false)

    fun setDock(slot: Int, id: String?) {
        if (statePayloadInvalid) return
        // FR-49: setting or clearing a dock slot is a layout edit like any other.
        if (mutable.value.layoutLocked) return
        if (slot !in mutable.value.dock.indices) return
        if (id != null && (isReservedFolderId(id) || mutable.value.folders.any { id in it.appIds })) return
        val old = mutable.value
        val next = old.layout.copy(
            slots = if (id == null) old.homeSlots else old.homeSlots.map { it?.takeUnless(id::equals) }.dropLastWhile { it == null },
            leadingSlots = if (id == null) old.leadingSlots else old.leadingSlots.map { it?.takeUnless(id::equals) },
            dock = old.dock.mapIndexed { index, value ->
                when { index == slot -> id; value == id && id != null -> null; else -> value }
            })
        mutable.value = old.withActiveLayout(next).copy(canUndoEdit = false)
        persist()
    }

    fun move(id: String, offset: Int) {
        val old = mutable.value
        val grid = old.grid
        val from = old.layout.indexOfShortcut(id) ?: return
        val page = homeCellPage(from, grid)
        val target = if (page == -1) homeCellIndex(-1,
            (homeCellLocal(from, grid) + offset).coerceIn(0, grid.cells - 1), grid)
        else (from + offset).coerceIn(0, old.homeSlots.lastIndex)
        applyDrop(id, DropTarget.Home(target))
    }

    fun applyDrop(id: String, target: DropTarget): Boolean {
        val old = mutable.value
        if (old.layoutLocked) return false
        val folder = old.layout.folder(id)
        if (old.apps.none { it.id == id } && folder == null) return false
        if (target is DropTarget.Folder) return folder == null && addAppToFolder(target.id, id)
        if (folder != null && target !is DropTarget.Home) return false
        return commitLayout(dropApp(old.layout, id, target))
    }

    fun createFolder(firstAppId: String, secondAppId: String, targetIndex: Int, title: String = "Folder"): String? {
        val installed = mutable.value.apps.mapTo(mutableSetOf(), AppEntry::id)
        if (firstAppId !in installed || secondAppId !in installed) return null
        val id = newFolderId()
        return id.takeIf { commitLayout(com.jake.duolauncher.createFolder(mutable.value.layout, FolderEntry(id, title, emptyList()),
            firstAppId, secondAppId, targetIndex)) }
    }
    fun renameFolder(folderId: String, title: String) = commitLayout(com.jake.duolauncher.renameFolder(mutable.value.layout, folderId, title))
    fun addAppToFolder(folderId: String, appId: String, index: Int? = null): Boolean {
        if (mutable.value.apps.none { it.id == appId }) return false
        return commitLayout(com.jake.duolauncher.addAppToFolder(mutable.value.layout, folderId, appId, index))
    }
    fun removeAppFromFolder(folderId: String, appId: String, target: DropTarget) =
        commitLayout(com.jake.duolauncher.removeAppFromFolder(mutable.value.layout, folderId, appId, target))
    fun moveFolderApp(folderId: String, appId: String, index: Int) =
        commitLayout(com.jake.duolauncher.moveFolderApp(mutable.value.layout, folderId, appId, index))
    fun folder(id: String) = mutable.value.layout.folder(id)

    /** FR-66. */
    fun setFolderTint(folderId: String, tint: Int): Boolean = updateFolder(folderId) { it.copy(tint = tint) }

    /** FR-66. */
    fun setFolderSize(folderId: String, size: DuoFolderSize): Boolean = updateFolder(folderId) { it.copy(size = size) }

    private fun updateFolder(folderId: String, transform: (FolderEntry) -> FolderEntry): Boolean {
        val old = mutable.value
        if (old.folders.none { it.id == folderId }) return false
        return commitLayout(old.layout.copy(folders = old.folders.map { if (it.id == folderId) transform(it) else it }))
    }

    /**
     * Applies a reviewed backup (FR-82, AC-66).
     *
     * Everything the backup carries is merged into **one** [LauncherState], validated, and then
     * assigned once. One commit means one persisted write, one undo snapshot, and no window in
     * which half a backup is on screen. [importedLauncherState] validates the merge before this
     * method can reach the assignment, so a backup that would describe an unsavable layout leaves
     * `mutable` untouched and the current layout standing.
     *
     * A rejection is *thrown* rather than returned false, because `false` already means the far
     * more ordinary "this layout is already active". The caller treats a bad backup as a value and
     * wraps this in `runCatching`, exactly as it does the decoder.
     */
    fun applyImportedLayout(preview: LayoutImportPreview): Boolean {
        if (statePayloadInvalid) return false
        val old = mutable.value
        val imported = importedLauncherState(old, preview)
        if (imported == old) return false
        val next = imported.copy(editRevision = old.editRevision + 1, canUndoEdit = true)
        undoLayout = old to next
        mutable.value = next
        persist()
        return true
    }

    fun moveWidget(from: Int, to: Int): Boolean {
        val old = mutable.value
        val target = old.layout.placement(to) ?: return false
        return moveWidgetTo(from, target.page * old.grid.cells + target.row * old.grid.columns + target.column)
    }
    fun moveWidgetTo(slot: Int, index: Int) = commitLayout(moveWidget(mutable.value.layout, slot, index))
    fun resizeWidget(slot: Int, spanX: Int, spanY: Int) = commitLayout(resizeWidget(mutable.value.layout, slot, spanX, spanY))
    fun placeWidget(placement: WidgetPlacement) = commitLayout(placeWidget(mutable.value.layout, placement))
    fun placement(slot: Int) = mutable.value.layout.placement(slot)

    /**
     * Widget slots key live `appWidgetId` bindings, so a new slot has to be unique across *every*
     * stored layout, not just the one on screen. Reusing a slot from an off-screen layout would
     * make two placements claim the same binding.
     */
    fun nextWidgetSlot(): Int {
        val set = mutable.value.layoutSet
        val highest = LayoutTarget.entries
            .flatMap { set.layout(it).widgetPlacements }
            .maxOfOrNull { it.slot } ?: -1
        return highest + 1
    }
    /**
     * FR-49 blocks *remove* as well as drag, and this is the model's last gate before a locked
     * layout would lose a placement. The UI hides the − badge while locked, but the badge is not the
     * only route here: the dock sheet's **Clear** and `WidgetController.remove` both land on this
     * method, so the rule is enforced where the mutation actually happens.
     */
    fun removePlacement(source: DropTarget): Boolean {
        if (mutable.value.layoutLocked) return false
        return commitLayout(removePlacement(mutable.value.layout, source))
    }

    private fun commitLayout(next: HomeLayout): Boolean {
        if (statePayloadInvalid) return false
        val old = mutable.value
        if (old.layout == next) return false
        val updated = old.withActiveLayout(next).withCoherentStacks()
            .copy(editRevision = old.editRevision + 1, canUndoEdit = true)
        undoLayout = old to updated
        mutable.value = updated
        persist()
        return true
    }

    /**
     * Undo restores the whole previous state, which is what makes a grid change undoable (FR-33)
     * and what makes an import undoable in full (AC-66): the grid, all three layouts, the dock,
     * every displaced item and every schema-9 setting come back together, because the snapshot is
     * the entire [LauncherState] rather than a list of the fields the edit happened to touch.
     */
    fun undoEdit(): Boolean {
        val (before, after) = undoLayout ?: return false
        val old = mutable.value
        if (!old.canUndoEdit || old.layout != after.layout || old.layoutSet != after.layoutSet) return false
        val installed = (old.apps.map { it.id } + before.folders.map { it.id }).toSet()
        mutable.value = undoneLauncherState(before, old, installed)
        undoLayout = null
        persist()
        return true
    }

    fun setLabels(value: Boolean) = updateSimple { it.copy(labels = value) }
    fun setVerticalStatus(value: Boolean) = updateSimple { it.copy(verticalStatus = value) }
    fun setGoogleSearch(value: Boolean) = updateSimple { it.copy(googleSearch = value) }
    fun setPreset(expanded: Boolean, value: LayoutPreset) = updateSimple {
        if (expanded) it.copy(expanded = value.sanitized()) else it.copy(compact = value.sanitized())
    }

    private inline fun updateSimple(transform: (LauncherState) -> LauncherState) {
        if (statePayloadInvalid) return
        undoLayout = null
        mutable.update { transform(it).copy(canUndoEdit = false) }
        persist()
    }

    // -----------------------------------------------------------------------------------------
    // Schema-9 model API (build plan section 2.2)
    // -----------------------------------------------------------------------------------------

    /** Tells the model which display is active, which selects the layout in Separate mode. */
    fun setExpandedActive(expanded: Boolean) {
        if (mutable.value.expandedActive == expanded) return
        mutable.update { it.copy(expandedActive = expanded).withLayoutSet(it.layoutSet) }
    }

    /** The page a pin request should target (FR-28). */
    fun setCurrentPage(page: Int) { currentPage = page.coerceAtLeast(0) }

    fun activeLayout(expanded: Boolean = mutable.value.expandedActive): DuoLayout =
        mutable.value.layoutSet.let { it.layout(it.targetFor(expanded)) }

    /**
     * FR-31/33/34. Re-lays the target layout onto [grid], moving anything that no longer fits and
     * deleting nothing. The whole change is one undoable edit.
     */
    fun setGrid(
        target: LayoutTarget = mutable.value.activeTarget,
        grid: GridSpec,
        providerLimits: (Int) -> WidgetSpanLimits = { WidgetSpanLimits() },
    ): ReflowOutcome? {
        if (statePayloadInvalid) return null
        val old = mutable.value
        val current = old.layoutSet.layout(target)
        val wanted = grid.sanitized()
        if (current.grid == wanted) return ReflowOutcome(current)
        val outcome = reflowLayout(current, wanted, providerLimits)
        val next = old.withLayoutSet(old.layoutSet.withLayout(target, outcome.layout))
            .copy(editRevision = old.editRevision + 1, canUndoEdit = true)
        undoLayout = old to next
        mutable.value = next
        persist()
        return outcome
    }

    /**
     * FR-32. Both layouts are kept, so this is never destructive. Switching into Separate seeds an
     * empty target from the mirrored arrangement — apps and folders only, because a live widget
     * binding belongs to exactly one placement and must not be duplicated into two layouts.
     */
    fun setLayoutMode(mode: LayoutMode) {
        if (statePayloadInvalid) return
        val old = mutable.value
        if (old.layoutSet.mode == mode) return
        var set = old.layoutSet.copy(mode = mode)
        if (mode == LayoutMode.SEPARATE) {
            listOf(LayoutTarget.COVER, LayoutTarget.INNER).forEach { target ->
                val existing = set.layout(target)
                if (existing.isEmpty) {
                    val seeded = reflowLayout(
                        set.mirrored.copy(widgetPlacements = emptyList(), widgetRestores = emptyList()),
                        existing.grid,
                    ).layout
                    set = set.withLayout(target, seeded.copy(grid = existing.grid).withPageIds())
                }
            }
        }
        commitState(old.withLayoutSet(set))
    }

    /** FR-37. */
    fun setDockSide(side: DockSide) = commitSet { it.copy(dock = it.dock.copy(side = side)) }

    /** FR-38. Shrinking keeps filled slots rather than truncating them away. */
    fun setDockCapacity(capacity: Int) = commitSet {
        it.copy(dock = it.dock.copy(capacity = capacity).sanitized())
    }

    /** FR-75. Hiding removes the app from Home, the dock and folders across every layout. */
    fun hideApp(appId: String) {
        if (statePayloadInvalid || appId.isBlank()) return
        val old = mutable.value
        if (appId in old.hiddenApps) return
        var set = old.layoutSet
        LayoutTarget.entries.forEach { target ->
            val layout = set.layout(target)
            set = set.withLayout(target, layout.copy(
                slots = layout.slots.map { it?.takeUnless(appId::equals) }.dropLastWhile { it == null },
                leadingSlots = layout.leadingSlots.map { it?.takeUnless(appId::equals) },
            ))
        }
        set = set.copy(
            dock = set.dock.copy(items = set.dock.items.map { it?.takeUnless(appId::equals) }),
            folders = set.folders.map { it.copy(appIds = it.appIds.filterNot(appId::equals)) },
        )
        // A folder emptied to one child dissolves the same way a removal does.
        set = set.copy(folders = set.folders.filter { it.appIds.size >= 2 })
        commitState(old.withLayoutSet(set).copy(hiddenApps = old.hiddenApps + appId))
    }

    /** FR-75. Unhiding restores it to the App Library, not to Home. */
    fun unhideApp(appId: String) {
        val old = mutable.value
        if (appId !in old.hiddenApps) return
        commitState(old.copy(hiddenApps = old.hiddenApps - appId))
    }

    /** FR-55. Every option's contents are preserved, so this only changes which one is shown. */
    fun setLeadingPage(kind: LeadingPageKind) = commitState(
        mutable.value.let { it.copy(leadingPage = it.leadingPage.copy(kind = kind)) })

    // -----------------------------------------------------------------------------------------
    // Page overview (FR-47)
    // -----------------------------------------------------------------------------------------

    /**
     * FR-47: moves a Home page, taking its contents with it.
     *
     * [from] and [to] are page *positions*, which is what the overview's move buttons report. The
     * page's cells, its widgets and its stable id all travel together, so hiding state keyed on the
     * id keeps pointing at the same page after a move.
     */
    fun reorderPages(from: Int, to: Int): Boolean = commitActiveLayout { reorderHomePages(it, from, to) }

    /**
     * FR-47: hides or shows the page with [pageId]. Its contents are kept either way, and the last
     * visible page cannot be hidden — [toggleHiddenPage] owns that rule.
     */
    fun setPageHidden(pageId: Int): Boolean = commitActiveLayout { layout ->
        val withIds = layout.withPageIds()
        withIds.copy(hiddenPageIds = toggleHiddenPage(withIds.hiddenPageIds, pageId, withIds.pageIds))
    }

    /** FR-47: deletes an empty page. [deleteHomePage] refuses a page that still holds anything. */
    fun deletePage(pageId: Int): Boolean = commitActiveLayout { deleteHomePage(it, pageId) }

    /**
     * One undoable edit to the active [DuoLayout], committed exactly the way [commitLayout] commits
     * a [HomeLayout]: one assignment, one undo snapshot, one persist.
     */
    private fun commitActiveLayout(transform: (DuoLayout) -> DuoLayout): Boolean {
        if (statePayloadInvalid) return false
        val old = mutable.value
        if (old.layoutLocked) return false
        val target = old.activeTarget
        val current = old.layoutSet.layout(target)
        val next = transform(current).withPageIds()
        if (next == current) return false
        val updated = old.withLayoutSet(old.layoutSet.withLayout(target, next))
            .copy(editRevision = old.editRevision + 1, canUndoEdit = true)
        undoLayout = old to updated
        mutable.value = updated
        persist()
        return true
    }

    /**
     * FR-57: replaces the whole Today column in one edit.
     *
     * The column is drawn from a *resolved* list — a fresh install shows a default column that has
     * never been written to disk — so an edit cannot be expressed as "insert into what is stored":
     * moving the second default widget up has to commit all four, not one. The host resolves the
     * column, applies the edit and hands the result here, which keeps the defaults out of storage
     * until the user actually touches them and keeps every edit a single commit.
     *
     * Blank and duplicate ids are dropped rather than rejected, because an id is also a Compose key.
     */
    fun setTodayItems(ids: List<String>) {
        val cleaned = ids.filter { it.isNotBlank() }.distinct()
        val old = mutable.value
        if (cleaned == old.leadingPage.today) return
        commitState(old.copy(leadingPage = old.leadingPage.copy(today = cleaned)))
    }

    /** FR-57. */
    fun addToToday(itemId: String, index: Int? = null) {
        val old = mutable.value
        if (itemId.isBlank() || itemId in old.leadingPage.today) return
        val today = old.leadingPage.today.toMutableList()
        today.add((index ?: today.size).coerceIn(0, today.size), itemId)
        commitState(old.copy(leadingPage = old.leadingPage.copy(today = today)))
    }

    fun removeFromToday(itemId: String) {
        val old = mutable.value
        if (itemId !in old.leadingPage.today) return
        commitState(old.copy(leadingPage = old.leadingPage.copy(today = old.leadingPage.today - itemId)))
    }

    fun moveTodayItem(itemId: String, index: Int) {
        val old = mutable.value
        val from = old.leadingPage.today.indexOf(itemId)
        if (from < 0 || index !in old.leadingPage.today.indices || from == index) return
        val today = old.leadingPage.today.toMutableList().apply { add(index, removeAt(from)) }
        commitState(old.copy(leadingPage = old.leadingPage.copy(today = today)))
    }

    /** FR-61. */
    fun createStack(firstSlot: Int, secondSlot: Int): String? {
        val old = mutable.value
        if (firstSlot == secondSlot) return null
        if (old.stacks.any { firstSlot in it.placementSlots || secondSlot in it.placementSlots }) return null
        if (old.layout.placement(firstSlot) == null || old.layout.placement(secondSlot) == null) return null
        val id = STACK_ID_PREFIX + java.util.UUID.randomUUID()
        commitState(old.copy(stacks = old.stacks + WidgetStack(id, listOf(firstSlot, secondSlot))))
        return id
    }

    fun addToStack(stackId: String, slot: Int): Boolean {
        val old = mutable.value
        val stack = old.stacks.firstOrNull { it.id == stackId } ?: return false
        if (slot in stack.placementSlots || stack.placementSlots.size >= MAX_STACK_WIDGETS) return false
        // The same guard createStack applies. Without it this reported success for a slot that
        // names no widget, and the commit's prune would then drop it again behind the caller's back.
        if (old.layout.placement(slot) == null) return false
        commitState(old.copy(stacks = old.stacks.map {
            if (it.id == stackId) it.copy(placementSlots = it.placementSlots + slot) else it
        }))
        return true
    }

    /** FR-64: a stack left with one widget becomes a plain widget; an empty one disappears. */
    fun removeFromStack(stackId: String, slot: Int): Boolean {
        val old = mutable.value
        val stack = old.stacks.firstOrNull { it.id == stackId } ?: return false
        if (slot !in stack.placementSlots) return false
        val remaining = stack.placementSlots - slot
        val stacks = if (remaining.size <= 1) old.stacks.filterNot { it.id == stackId }
            else old.stacks.map {
                if (it.id == stackId) it.copy(placementSlots = remaining,
                    activeIndex = it.activeIndex.coerceIn(0, remaining.lastIndex)) else it
            }
        commitState(old.copy(stacks = stacks))
        return true
    }

    fun setStackActive(stackId: String, index: Int): Boolean {
        val old = mutable.value
        val stack = old.stacks.firstOrNull { it.id == stackId } ?: return false
        if (index !in stack.placementSlots.indices || index == stack.activeIndex) return false
        commitState(old.copy(stacks = old.stacks.map {
            if (it.id == stackId) it.copy(activeIndex = index) else it
        }))
        return true
    }

    /** FR-63. */
    fun setStackSmartRotate(stackId: String, enabled: Boolean) {
        val old = mutable.value
        if (old.stacks.none { it.id == stackId }) return
        commitState(old.copy(stacks = old.stacks.map {
            if (it.id == stackId) it.copy(smartRotate = enabled) else it
        }))
    }

    /** FR-49. */
    fun setLockLayout(locked: Boolean) = updateSettings { it.copy(lockLayout = locked) }

    /** FR-50. */
    fun setAutoAddApps(enabled: Boolean) = updateSettings { it.copy(autoAddApps = enabled) }

    /** FR-51/52/53. */
    fun setGesture(
        swipeDown: SwipeDownAction = mutable.value.settings.swipeDown,
        swipeUp: SwipeUpAction = mutable.value.settings.swipeUp,
        doubleTapLock: Boolean = mutable.value.settings.doubleTapLock,
    ) = updateSettings { it.copy(swipeDown = swipeDown, swipeUp = swipeUp, doubleTapLock = doubleTapLock) }

    /** FR-5. */
    fun setGlass(level: Int) = updateSettings { it.copy(glassLevel = level.coerceIn(0, 100)) }

    /** FR-8. Null selects Automatic. */
    fun setAccent(color: Int?) = updateSettings { it.copy(accent = color) }

    /** FR-20. */
    fun setBadgeStyle(style: DuoBadgeStyle) = updateSettings { it.copy(badgeStyle = style) }

    fun setReduceTransparency(value: Boolean) = updateSettings { it.copy(reduceTransparency = value) }
    fun setWallpaperSource(source: WallpaperSource) = updateSettings { it.copy(wallpaperSource = source) }
    fun setDimInDark(value: Boolean) = updateSettings { it.copy(dimInDark = value) }
    fun setFont(font: DuoFontChoice) = updateSettings { it.copy(font = font) }
    fun setLargeIcons(value: Boolean) = updateSettings { it.copy(largeIcons = value) }
    fun setIconAppearance(appearance: IconAppearance) = updateSettings { it.copy(iconAppearance = appearance) }
    fun setIconShape(shape: IconShape) = updateSettings { it.copy(iconShape = shape) }
    fun setIconPack(packPackage: String?) = updateSettings { it.copy(iconPack = packPackage) }
    fun setIconTint(color: Int, intensity: Int) = updateSettings {
        it.copy(iconTint = color, iconTintIntensity = intensity.coerceIn(0, 100))
    }
    fun setLibraryView(view: AppLibraryView) = updateSettings { it.copy(libraryView = view) }
    fun setSearchContacts(value: Boolean) = updateSettings { it.copy(searchContacts = value) }
    fun setSuggestions(value: Boolean) = updateSettings { it.copy(suggestions = value) }
    fun setDuoStatus(value: Boolean) = updateSettings { it.copy(duoStatus = value) }
    fun setHidePrivateContainer(value: Boolean) = updateSettings { it.copy(hidePrivateContainer = value) }

    /** The icon style the icons track renders with, assembled from the stored settings. */
    val iconStyle: IconStyle get() = mutable.value.settings.let {
        IconStyle(it.iconAppearance, it.iconTint, it.iconTintIntensity, it.iconShape, it.iconPack)
    }

    fun setIconOverride(override: IconOverrideRecord) {
        val old = mutable.value
        val others = old.iconOverrides.filterNot { it.profileAppId == override.profileAppId }
        val cleared = override.iconPack == null && override.drawableName == null && override.label == null
        commitState(old.copy(iconOverrides = if (cleared) others else others + override))
    }

    private inline fun updateSettings(transform: (DuoSettings) -> DuoSettings) {
        if (statePayloadInvalid) return
        mutable.update { it.copy(settings = transform(it.settings)) }
        persist()
    }

    private fun commitSet(transform: (LayoutSet) -> LayoutSet) {
        if (statePayloadInvalid) return
        val old = mutable.value
        commitState(old.withLayoutSet(transform(old.layoutSet)))
    }

    /** A settings-shaped change: persisted, but not part of the layout undo stack. */
    private fun commitState(next: LauncherState) {
        if (statePayloadInvalid) return
        val old = mutable.value
        val coherent = next.withCoherentStacks()
        if (old == coherent) return
        mutable.value = coherent.copy(editRevision = old.editRevision + 1)
        persist()
    }

    /**
     * Drops stacks and Today entries that no longer name a live widget placement (FR-57, FR-64).
     *
     * Removing a widget is the case that matters: `removePlacement` drops a placement, and before
     * this nothing told the stack holding it. Applying the prune at the two commit points means the
     * live state can no longer drift out of step with its own layouts, which is what lets
     * [validate] assert the invariant instead of merely hoping for it.
     */
    private fun LauncherState.withCoherentStacks(): LauncherState {
        val (prunedStacks, prunedToday) =
            stackCoherence(stacks, leadingPage.today, layoutSet.liveWidgetSlots())
        return if (prunedStacks == stacks && prunedToday == leadingPage.today) this
        else copy(stacks = prunedStacks, leadingPage = leadingPage.copy(today = prunedToday))
    }

    // -----------------------------------------------------------------------------------------
    // Pin requests (FR-28, FR-49)
    // -----------------------------------------------------------------------------------------

    /**
     * Places an accepted pin request on the first free cell of the current page, or a new page.
     *
     * A pinned shortcut is stored by its `PinnedShortcutKey.storageId`, which is prefixed so it can
     * never be mistaken for an app's `profileAppId`. Widget requests are reported as handled
     * without a placement: the system-level pin has already happened, and creating the placement
     * needs the `appWidgetId` that `WidgetController` owns, so the widget track reconciles it.
     */
    private fun placePinnedItem(item: PinnedItem): Boolean {
        if (statePayloadInvalid) return false
        // FR-49 is enforced here as well as in the sheet: this is the last gate before a locked
        // layout would be modified.
        if (mutable.value.settings.lockLayout) return false
        val id = when (item.kind) {
            PinItemKind.SHORTCUT -> item.shortcut?.storageId ?: return false
            PinItemKind.APPWIDGET -> return true
        }
        val old = mutable.value
        if (old.layout.indexOfShortcut(id) != null) return true
        val placed = placeOnFirstFreeCell(old.layout, id, currentPage) ?: return false
        return commitLayout(placed)
    }

    /** First free cell on [preferredPage], then any later page, then a newly appended one. */
    private fun placeOnFirstFreeCell(layout: HomeLayout, id: String, preferredPage: Int): HomeLayout? {
        val grid = layout.grid
        val blocked = layout.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices(grid) }
        val start = preferredPage.coerceIn(0, maxOf(0, layout.pageCount - 1))
        val pages = (start until layout.pageCount) + (0 until start) + listOf(layout.pageCount)
        pages.forEach { page ->
            (0 until grid.cells).forEach { local ->
                val index = homeCellIndex(page, local, grid)
                if (index !in blocked && layout.slotAt(index) == null) return layout.withSlot(index, id)
            }
        }
        return null
    }

    val retainedWidgetIds get() = (mutable.value.layoutSet.let { set ->
        LayoutTarget.entries.flatMap { set.layout(it).widgetPlacements }.map { it.id }
    } + (if (mutable.value.canUndoEdit) undoLayout?.first?.layoutSet?.let { set ->
        LayoutTarget.entries.flatMap { set.layout(it).widgetPlacements }.map { it.id }
    }.orEmpty() else emptyList())).filter { it >= 0 }.toSet()
    val canPruneWidgetIds get() = !statePayloadInvalid

    fun setWidget(slot: Int, id: Int) {
        if (statePayloadInvalid) return
        val old = mutable.value
        val existing = old.layout.placement(slot)
        val next = when {
            id == EMPTY_WIDGET -> removePlacement(old.layout, DropTarget.Widget(slot))
            existing != null -> old.layout.copy(widgetPlacements = old.widgetPlacements.map { if (it.slot == slot) it.copy(id = id) else it },
                widgetRestores = old.widgetRestores.filterNot { it.slot == slot })
            else -> placeWidget(old.layout, migrateSchema5Widgets(List(slot) { EMPTY_WIDGET } + id).single())
        }
        mutable.value = old.withActiveLayout(next).copy(canUndoEdit = false, editRevision = old.editRevision + 1)
        undoLayout = null
        persist()
    }

    internal fun restoreLayout(layout: HomeLayout) {
        if (statePayloadInvalid) return
        val old = mutable.value
        val installed = (old.apps.map { it.id } + layout.folders.map { it.id }).toSet()
        val restored = layout.copy(
            slots = reconcileHomeSlots(layout.slots, installed),
            leadingSlots = layout.slotsForPage(-1).map { id -> id?.takeIf { it in installed || isFolderId(it) } },
            dock = layout.dock.map { id -> id?.takeIf { it in installed || isFolderId(it) } })
        mutable.value = old.withActiveLayout(restored).copy(canUndoEdit = false, editRevision = old.editRevision + 1)
        undoLayout = null
        persist()
    }

    private fun persist() {
        if (needsMigration || statePayloadInvalid) return
        // The guard is evaluated now; serialization and the write are deferred and coalesced.
        persistRequested = true
        persistence.request()
    }

    /** Writes a pending edit synchronously, for paths where the process may be killed next. */
    internal fun flushPersistence() = persistence.flush()

    private fun serializeState(): String = encodeLauncherState(mutable.value.persistedState())

    /**
     * Writes the layout, taking every backup that is due first.
     *
     * The v8 backup is written with a synchronous `commit()` *before* anything else, and before the
     * schema-9 payload can replace `state`. This runs on the persistence IO thread, and it happens
     * exactly once per install, so the cost is irrelevant next to the guarantee: if the process
     * dies at any point during the upgrade, the untouched v8 payload is already durable on disk
     * (FR-35, AC-29).
     */
    private fun writeState(json: String) {
        if (legacyRaw != null && sourceSchema <= 8 && !prefs.contains(STATE_V8_BACKUP_KEY)) {
            prefs.edit().putString(STATE_V8_BACKUP_KEY, legacyRaw).commit()
        }
        val editor = prefs.edit()
        if (legacyRaw != null && sourceSchema == 2 && !prefs.contains("state_v2_backup"))
            editor.putString("state_v2_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 4 && !prefs.contains("state_v3_backup"))
            editor.putString("state_v3_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 5 && !prefs.contains("state_v4_backup"))
            editor.putString("state_v4_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 6 && !prefs.contains("state_v5_backup"))
            editor.putString("state_v5_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 7 && !prefs.contains("state_v6_backup"))
            editor.putString("state_v6_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 8 && !prefs.contains("state_v7_backup"))
            editor.putString("state_v7_backup", legacyRaw)
        // The payload being replaced is known-good because it was serialized from valid state, so
        // it is the layout recovery falls back to first.
        lastGoodPayload?.takeIf { it != json }?.let { editor.putString(STATE_V9_PREVIOUS_KEY, it) }
        editor.putString(STATE_KEY, json).putBoolean("initialized", true).apply()
        lastGoodPayload = json
    }

    /**
     * Loads the saved layout, falling back through the backups rather than starting empty.
     *
     * Order is current payload, then the previous schema-9 payload, then the v8 backup (error
     * table: "Schema 9 load fails validation"). Only when every one of them fails does the model
     * refuse to write, which is what stops a transient read problem from destroying a good layout.
     */
    private fun load(): LauncherState {
        val current = prefs.getString(STATE_KEY, null)
        if (current == null) {
            // A genuinely fresh install gets the AC-30 defaults.
            return LauncherState(loading = true).withLayoutSet(newInstallLayoutSet())
        }
        val candidates = listOf(
            current to null,
            prefs.getString(STATE_V9_PREVIOUS_KEY, null) to RECOVERED_BANNER,
            prefs.getString(STATE_V8_BACKUP_KEY, null) to RECOVERED_BANNER,
        )
        candidates.forEachIndexed { index, (raw, banner) ->
            if (raw == null) return@forEachIndexed
            when (val result = decodeLauncherState(raw)) {
                is LayoutDecodeResult.Loaded -> {
                    if (index == 0) lastGoodPayload = raw
                    return result.state.toLauncherState(banner)
                }
                is LayoutDecodeResult.TooNew -> {
                    // A downgrade must never rewrite a payload it cannot understand.
                    statePayloadInvalid = true
                    return LauncherState(loading = false, error = NEWER_VERSION_BANNER)
                }
                is LayoutDecodeResult.Failed -> Unit
            }
        }
        statePayloadInvalid = true
        return LauncherState(loading = false, error = "Saved Home layout could not be read; it was left unchanged.")
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here, so this flush has to be synchronous.
        flushPersistence()
        launcherApps.unregisterCallback(callback)
        // Only retract the seams this instance registered; another live model may own them now.
        if (DuoPinRequests.layoutLock === pinLock) DuoPinRequests.layoutLock = null
        if (DuoPinRequests.unlock === pinUnlock) DuoPinRequests.unlock = null
        if (DuoPinRequests.placer === pinPlacer) DuoPinRequests.placer = null
    }
}

/** The banner shown after a layout is recovered from a backup (error table). */
internal const val RECOVERED_BANNER = "Duo restored your last saved layout"

/** FR-83's sibling for the live layout: a payload from a newer build is left untouched. */
internal const val NEWER_VERSION_BANNER = "This layout was saved by a newer Duo version."

/**
 * Rebuilds the flat active-layout fields from [set] so the two can never disagree.
 *
 * The dock is normalized to its own capacity on the way through (FR-38). Every dock rule downstream
 * — `canPlaceInDock`, `dockSlots`, `dockAcceptsDrop` and the rail itself — counts the slots it is
 * given, so a `DockConfig` whose `items` had drifted from `capacity` would make a six-slot dock
 * reject a drop at four. Normalizing in the one place the flat fields are derived also keeps the
 * persisted payload satisfying `validate`'s "dock items must match capacity".
 */
internal fun LauncherState.withLayoutSet(set: LayoutSet): LauncherState {
    val target = set.targetFor(expandedActive)
    val sanitized = set.dock.sanitized()
    val normalized = if (sanitized == set.dock) set else set.copy(dock = sanitized)
    val active = normalized.homeLayout(target)
    return copy(
        layoutSet = normalized,
        grid = active.grid,
        homeSlots = active.slots,
        leadingSlots = active.leadingSlots,
        dock = active.dock,
        widgetPlacements = active.widgetPlacements,
        folders = active.folders,
        widgetRestores = active.widgetRestores,
    )
}

/** Writes an edited [HomeLayout] back into whichever layout is active. */
internal fun LauncherState.withActiveLayout(layout: HomeLayout): LauncherState =
    withLayoutSet(layoutSet.withHomeLayout(activeTarget, layout))

/**
 * The persisted record behind a live state: what is written to disk, and what [validate] checks.
 *
 * Keeping this in one place is what lets a *candidate* state be validated with exactly the rules
 * the on-disk payload must satisfy, before it is ever committed.
 */
internal fun LauncherState.persistedState(): LauncherPersistedState = LauncherPersistedState(
    layoutSet = layoutSet,
    leadingPage = leadingPage,
    stacks = stacks,
    hiddenApps = hiddenApps,
    iconOverrides = iconOverrides,
    settings = settings,
    labels = labels,
    googleSearch = googleSearch,
    verticalStatus = verticalStatus,
    compact = compact,
    expanded = expanded,
)

/**
 * Merges a reviewed backup into [old], producing the single state the model commits (FR-82, AC-66).
 *
 * ## Which fields a backup is allowed to write
 *
 * **Only the ones it actually carries.** Version 3 is the format that added the three layouts, the
 * grids, dock side and capacity, the leading page, stacks, hidden apps, icon overrides and the whole
 * settings block, so a v3 document applies all of them. A v1/v2 document describes a single 4×6
 * arrangement and nothing else, and [LayoutImportPreview] fills the rest with *defaults* — so
 * applying those fields would quietly reset the user's glass level, accent, gestures, grids and dock
 * to factory values as a side effect of restoring an old layout. The rule is therefore keyed on the
 * declared version rather than on comparing values: a default is indistinguishable from a user who
 * deliberately chose the default, so only the version can say whether a field was ever described.
 *
 * ## Fitting a legacy layout
 *
 * A v1/v2 layout is always 4×6, and the user's current layout may not be. It is reflowed onto the
 * grid they are on, which keeps the grid a *setting the backup did not carry* while guaranteeing the
 * result is coherent — a 4×6 leading page spliced into a 6×6 layout is exactly the kind of state
 * [validate] refuses. The reflow is the identity when the grids already match, which is the ordinary
 * case, so an upgrade-era backup restores byte for byte the way it always did.
 *
 * Throws if the merge would not validate, before any of it can be committed.
 */
internal fun importedLauncherState(old: LauncherState, preview: LayoutImportPreview): LauncherState {
    val carriesSchema9Fields = preview.version >= LAYOUT_BACKUP_VERSION
    val merged = if (carriesSchema9Fields) {
        old.withLayoutSet(preview.layoutSet).copy(
            leadingPage = preview.leadingPage,
            stacks = preview.stacks,
            hiddenApps = preview.hiddenApps,
            iconOverrides = preview.iconOverrides,
            settings = preview.settings,
        )
    } else {
        old.withLayoutSet(legacyImportedLayoutSet(old, preview.layout))
    }
    val withPresets = merged.copy(
        compact = preview.compact,
        expanded = preview.expanded,
        labels = preview.labels,
        googleSearch = preview.googleSearch,
        verticalStatus = preview.verticalStatus,
    )
    // A v1/v2 document describes no stacks, so the user's current ones are kept — but the active
    // layout's widget placements have just been replaced, and a kept stack can be left naming a
    // slot that vanished or now holds a different widget. Pruning happens here, inside the single
    // value the caller assigns, so the restore stays atomic: a stack is never half-pruned, and a
    // refusal below leaves the live state untouched.
    val (stacks, today) = stackCoherence(
        withPresets.stacks, withPresets.leadingPage.today, withPresets.layoutSet.liveWidgetSlots(),
    )
    val imported = withPresets.copy(
        stacks = stacks,
        leadingPage = withPresets.leadingPage.copy(today = today),
    )
    // The same invariants the on-disk payload must satisfy. This runs before the caller's single
    // assignment, so a refusal leaves the live state exactly as it was.
    validate(imported.persistedState())
    return imported
}

/** Writes a v1/v2 arrangement into the active layout, leaving every schema-9 field alone. */
private fun legacyImportedLayoutSet(old: LauncherState, imported: HomeLayout): LayoutSet {
    val target = old.activeTarget
    val existing = old.layoutSet.layout(target)
    val replaced = existing.copy(
        grid = imported.grid,
        slots = imported.slots,
        leadingSlots = imported.leadingSlots,
        widgetPlacements = imported.widgetPlacements,
        widgetRestores = imported.widgetRestores,
    )
    val fitted = reflowLayout(replaced, existing.grid).layout.withPageIds()
    return old.layoutSet.withLayout(target, fitted)
        .copy(
            // The dock is shared, so an imported four-slot dock is fitted to the capacity the user
            // is on rather than left disagreeing with it.
            dock = old.layoutSet.dock.copy(items = imported.dock).sanitized(),
            folders = imported.folders,
        )
        .withoutDanglingReferences(target)
}

/**
 * Clears references the *other* layouts can no longer resolve.
 *
 * An imported folder table replaces the shared one, so a layout that is not the import's target can
 * be left naming a folder that no longer exists, or placing an app that is now inside an imported
 * folder. Both are states [validate] refuses, and neither is the user's doing, so the stale cell is
 * emptied rather than the whole restore being rejected.
 */
private fun LayoutSet.withoutDanglingReferences(active: LayoutTarget): LayoutSet {
    val folderIds = folders.mapTo(mutableSetOf(), FolderEntry::id)
    val children = folders.flatMapTo(mutableSetOf(), FolderEntry::appIds)
    fun keep(id: String?): String? = when {
        id == null -> null
        isReservedFolderId(id) -> id.takeIf { it in folderIds }
        else -> id.takeUnless { it in children }
    }
    var result = this
    LayoutTarget.entries.filterNot { it == active }.forEach { target ->
        val layout = layout(target)
        result = result.withLayout(
            target,
            layout.copy(
                slots = layout.slots.map(::keep).dropLastWhile { it == null },
                leadingSlots = layout.leadingSlots.map(::keep),
            ),
        )
    }
    return result
}

/**
 * The state an undo returns to: [before] in full, carrying only the live catalogue forward.
 *
 * [installed] is passed in rather than read from [current] so this stays a pure function the JVM
 * suite can exercise; the model supplies the installed apps plus the snapshot's own folder ids.
 */
internal fun undoneLauncherState(
    before: LauncherState,
    current: LauncherState,
    installed: Set<String>,
): LauncherState {
    val restored = before.layout.let { layout ->
        layout.copy(
            slots = reconcileHomeSlots(layout.slots, installed),
            leadingSlots = layout.leadingSlots.map { it?.takeIf(installed::contains) },
            dock = layout.dock.map { it?.takeIf(installed::contains) },
        )
    }
    return before.withActiveLayout(restored).copy(
        apps = current.apps,
        profiles = current.profiles,
        loading = current.loading,
        error = current.error,
        canUndoEdit = false,
        editRevision = current.editRevision + 1,
    )
}

internal fun LauncherPersistedState.toLauncherState(banner: String?): LauncherState =
    LauncherState(
        loading = true,
        error = banner,
        leadingPage = leadingPage,
        stacks = stacks,
        hiddenApps = hiddenApps,
        iconOverrides = iconOverrides,
        settings = settings,
        labels = labels,
        googleSearch = googleSearch,
        verticalStatus = verticalStatus,
        compact = compact,
        expanded = expanded,
    ).withLayoutSet(layoutSet)

/**
 * The catalog thumbnail carried by [AppEntry], rendered through the icon pipeline so adaptive
 * layers get the real squircle mask and its anti-aliased edge (FR-16) instead of the hard-edged
 * rounded-rectangle clip this used to apply.
 *
 * The size stays a fixed budget on purpose. This bitmap is one shared thumbnail held for *every*
 * installed app for as long as the catalog lives, so making it density-correct would multiply a
 * 300-app catalog's footprint by the display density and put NFR-P4's PSS budget at risk. Icons
 * that are actually drawn go through `icons.DuoIconRenderer`, which renders at the display's real
 * pixel size (FR-12) and is bounded by its own 64 MB LRU. This thumbnail disappears with
 * `AppEntry`'s bitmap when B3's icon-key refactor lands.
 */
private const val CATALOG_ICON_PX = 144

private val CATALOG_ICON_STYLE = IconStyle()

private fun launcherIcon(drawable: Drawable): Bitmap =
    IconRasterizer.rasterize(drawable, CATALOG_ICON_PX, CATALOG_ICON_STYLE)
