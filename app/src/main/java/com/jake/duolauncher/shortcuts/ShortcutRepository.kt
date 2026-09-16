package com.jake.duolauncher.shortcuts

import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The app-shortcut data layer the UI reads (FR-25 to FR-30).
 *
 * Every query is gated on [hostPermission]: Android only serves shortcuts to the *current* default
 * Home app, so while Duo is not Home every call here answers empty rather than throwing. The UI
 * reads [hostPermission] to tell that state apart from "this app simply has no shortcuts", and
 * shows the [SET_DEFAULT_HOME_ROW] row instead of an empty menu (FR-30).
 */
interface ShortcutRepository {
    /** Whether Duo currently holds shortcut host permission, i.e. is the default Home app. */
    val hostPermission: StateFlow<Boolean>

    /** An app's manifest and dynamic shortcuts, ordered by rank, capped at [limit] (FR-25). */
    suspend fun shortcutsFor(app: ProfileAppId, limit: Int = SHORTCUT_MENU_LIMIT): List<DuoShortcut>

    /** Launches a shortcut with the icon's source bounds for the zoom animation (FR-26). */
    fun start(shortcut: DuoShortcut, bounds: Rect?)

    /** Pins a shortcut so it can be placed on Home (FR-27). */
    suspend fun pin(shortcut: DuoShortcut): Boolean

    /** A rendered shortcut icon: cache hit, or null. [DuoShortcutRepository.loadIcon] fills it. */
    fun iconFor(shortcut: DuoShortcut, sizePx: Int): ImageBitmap?
}

/**
 * The Android edge of the shortcut layer.
 *
 * Kept as a seam so the repository holds no `LauncherApps`, `UserHandle` or `ShortcutInfo`, and so
 * the gating, ordering and availability rules are unit-testable without an emulator. Every method
 * is allowed to fail; the repository treats a throw as "nothing available".
 */
interface ShortcutSource {
    /** `LauncherApps.hasShortcutHostPermission()`: true only while Duo is the default Home app. */
    fun hasHostPermission(): Boolean

    /** The serial of the personal profile, used to resolve ids that carry no serial. */
    fun personalSerial(): Long

    /** An app's manifest and dynamic shortcuts, unordered. */
    fun query(target: ShortcutTarget): List<DuoShortcut>

    /** One shortcut by identity, including pinned-only ones, or null when it is gone. */
    fun shortcut(key: PinnedShortcutKey): DuoShortcut?

    /** Every shortcut Duo has pinned, across profiles. */
    fun pinned(): List<DuoShortcut>

    fun start(shortcut: DuoShortcut, bounds: Rect?): Boolean

    fun pin(shortcut: DuoShortcut): Boolean

    fun icon(shortcut: DuoShortcut, sizePx: Int): ImageBitmap?
}

private const val ICON_CACHE_ENTRIES = 64

/**
 * The shipped repository.
 *
 * Host permission is re-read on every call rather than cached, because it changes outside the app —
 * the user picks another launcher in system settings and Duo is never told. Re-reading is a single
 * binder call and it keeps [hostPermission] honest for the FR-30 row without a polling loop.
 */
class DuoShortcutRepository(private val source: ShortcutSource) : ShortcutRepository {

    private val mutableHostPermission = MutableStateFlow(false)
    override val hostPermission: StateFlow<Boolean> = mutableHostPermission.asStateFlow()

    private val icons = IconCache(ICON_CACHE_ENTRIES)

    /**
     * Re-reads host permission and publishes it. Returns the fresh value so callers can gate on it
     * without a second read. Losing permission drops the icon cache, since those icons belong to
     * shortcuts this launcher may no longer query.
     */
    fun refreshHostPermission(): Boolean {
        val granted = runCatching { source.hasHostPermission() }.getOrDefault(false)
        if (!granted) clearIconCache()
        mutableHostPermission.value = granted
        return granted
    }

    override suspend fun shortcutsFor(app: ProfileAppId, limit: Int): List<DuoShortcut> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            if (!refreshHostPermission()) return@withContext emptyList()
            val target = ShortcutRules.targetOf(app, personalSerial()) ?: return@withContext emptyList()
            val published = runCatching { source.query(target) }.getOrDefault(emptyList())
            ShortcutRules.order(published, limit)
        }

    override fun start(shortcut: DuoShortcut, bounds: Rect?) {
        startShortcut(shortcut, bounds)
    }

    /** [start] with the outcome, so a caller can show "Shortcut unavailable" on a failure. */
    fun startShortcut(shortcut: DuoShortcut, bounds: Rect?): Boolean {
        if (!refreshHostPermission()) return false
        return runCatching { source.start(shortcut, bounds) }.getOrDefault(false)
    }

    override suspend fun pin(shortcut: DuoShortcut): Boolean = withContext(Dispatchers.IO) {
        if (!refreshHostPermission()) return@withContext false
        runCatching { source.pin(shortcut) }.getOrDefault(false)
    }

    override fun iconFor(shortcut: DuoShortcut, sizePx: Int): ImageBitmap? {
        if (sizePx <= 0) return null
        return synchronized(icons) { icons[iconKey(shortcut, sizePx)] }
    }

    /** Renders and caches a shortcut icon off the main thread. */
    suspend fun loadIcon(shortcut: DuoShortcut, sizePx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (sizePx <= 0) return@withContext null
            iconFor(shortcut, sizePx)?.let { return@withContext it }
            if (!refreshHostPermission()) return@withContext null
            val rendered = runCatching { source.icon(shortcut, sizePx) }.getOrNull()
                ?: return@withContext null
            synchronized(icons) { icons[iconKey(shortcut, sizePx)] = rendered }
            rendered
        }

    /**
     * What a stored placement resolves to right now (error table): the live shortcut, or why it
     * cannot be launched. The placement is never dropped here; that is the user's call via
     * **Remove**.
     */
    suspend fun resolve(key: PinnedShortcutKey): PinnedShortcutState = withContext(Dispatchers.IO) {
        val granted = refreshHostPermission()
        val found = if (granted) runCatching { source.shortcut(key) }.getOrNull() else null
        ShortcutRules.resolve(key, granted, found)
    }

    /** [resolve] for a whole layout page in one pass. */
    suspend fun resolveAll(keys: Collection<PinnedShortcutKey>): Map<PinnedShortcutKey, PinnedShortcutState> =
        keys.distinct().associateWith { resolve(it) }

    /**
     * Every shortcut Duo has pinned at the system level.
     *
     * The reconciliation point for the layout owner: a pin accepted by [PinItemActivity] is pinned
     * here whether or not a placer was registered, so a placement can always be rebuilt from this.
     */
    suspend fun pinnedShortcuts(): List<DuoShortcut> = withContext(Dispatchers.IO) {
        if (!refreshHostPermission()) return@withContext emptyList()
        runCatching { source.pinned() }.getOrDefault(emptyList())
    }

    /** For `onTrimMemory`, and whenever the icon style changes. */
    fun clearIconCache() {
        synchronized(icons) { icons.clear() }
    }

    private fun personalSerial(): Long =
        runCatching { source.personalSerial() }.getOrDefault(0L).coerceAtLeast(0L)

    private fun iconKey(shortcut: DuoShortcut, sizePx: Int): String =
        "${shortcut.userSerial}:${shortcut.packageName}:${shortcut.id}@$sizePx"
}

/** Access-ordered LRU, so a page of icons cannot evict the ones still on screen. */
private class IconCache(private val maxEntries: Int) :
    LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
        size > maxEntries
}
