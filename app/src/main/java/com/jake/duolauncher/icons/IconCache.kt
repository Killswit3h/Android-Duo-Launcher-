package com.jake.duolauncher.icons

/**
 * A memory-bounded LRU of rendered icons, keyed by user, package, activity, style and size
 * (FR-12), with a hard byte ceiling (NFR-P4).
 *
 * Generic over the rendered value so the policy — key identity, eviction order, the byte bound and
 * the trim levels — is unit-testable on the JVM without an Android bitmap in sight. The renderer
 * instantiates it with `ImageBitmap`.
 *
 * Every method is synchronized: [get] is called from the main thread on the scroll path while
 * [put] happens on a rendering dispatcher.
 */
class IconCache<V : Any>(
    maxBytes: Long = MAX_ICON_CACHE_BYTES,
    private val sizeOf: (IconKey, V) -> Long = { key, _ -> key.bytes },
) {
    private val entries = LinkedHashMap<IconKey, V>(INITIAL_CAPACITY, LOAD_FACTOR, ACCESS_ORDER)

    /**
     * Only the ceiling is enforced here, because only the ceiling is a requirement (NFR-P4). How
     * large a cache is *worth* having is a budgeting question, and it belongs to
     * [iconCacheBudgetBytes]; a cache told to hold one icon holds one icon.
     */
    var maxBytes: Long = maxBytes.coerceIn(0L, MAX_ICON_CACHE_BYTES)
        private set

    private var bytes: Long = 0L
    private var hits: Int = 0
    private var misses: Int = 0
    private var evictions: Int = 0

    /** Bytes currently held. */
    val sizeBytes: Long @Synchronized get() = bytes

    /** Entries currently held. */
    val count: Int @Synchronized get() = entries.size

    /** Cache statistics, for tests and for a debug overlay. */
    val stats: IconCacheStats @Synchronized get() = IconCacheStats(hits, misses, evictions, bytes, entries.size)

    /**
     * The cached render, or null. Recording the access is what makes the eviction order LRU rather
     * than insertion order, so the icons currently on screen survive a trim.
     */
    @Synchronized
    fun get(key: IconKey): V? {
        val value = entries[key]
        if (value == null) misses++ else hits++
        return value
    }

    /** Whether [key] is cached, without disturbing the recency order. */
    @Synchronized
    fun contains(key: IconKey): Boolean = entries.containsKey(key)

    /**
     * Stores a render, evicting the least recently used entries until the budget is met.
     *
     * A single value larger than the whole budget is not stored at all: admitting it would evict
     * everything else to make room for one icon that cannot help the next frame. It is still
     * returned to the caller that rendered it, so nothing is lost but the caching.
     */
    @Synchronized
    fun put(key: IconKey, value: V): Boolean {
        val entryBytes = sizeOf(key, value)
        if (entryBytes > maxBytes) return false
        entries.remove(key)?.let { bytes -= sizeOf(key, it) }
        entries[key] = value
        bytes += entryBytes
        trimToBytes(maxBytes)
        return true
    }

    @Synchronized
    fun remove(key: IconKey): V? {
        val removed = entries.remove(key) ?: return null
        bytes -= sizeOf(key, removed)
        return removed
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0L
    }

    /**
     * Drops every entry matching [predicate], for example one package after it was updated, or
     * every entry of a style after the user changed the icon appearance.
     *
     * Returns the values that were dropped so the caller can decide what to do with them. The
     * renderer deliberately does *not* recycle them: a bitmap already handed to Compose may still
     * be referenced by a frame in flight.
     */
    @Synchronized
    fun invalidate(predicate: (IconKey) -> Boolean): List<V> {
        val dropped = ArrayList<V>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!predicate(entry.key)) continue
            bytes -= sizeOf(entry.key, entry.value)
            dropped += entry.value
            iterator.remove()
        }
        return dropped
    }

    /** Evicts the least recently used entries until at most [limit] bytes remain. */
    @Synchronized
    fun trimToBytes(limit: Long) {
        val target = limit.coerceAtLeast(0L)
        val iterator = entries.entries.iterator()
        while (bytes > target && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= sizeOf(entry.key, entry.value)
            iterator.remove()
            evictions++
        }
        if (entries.isEmpty()) bytes = 0L
    }

    /**
     * Responds to `onTrimMemory` (NFR-P4, and the icon-cache row of the error handling table).
     *
     * Shrinking to a fraction rather than clearing outright is what keeps the promise that icons
     * do not flash placeholders for longer than a frame of scroll: the most recently used icons —
     * the ones on screen — are exactly the ones a fractional trim keeps.
     */
    @Synchronized
    fun onTrimMemory(level: Int) {
        val retained = retainedFractionForTrim(level)
        if (retained >= 1f) return
        trimToBytes((maxBytes * retained).toLong())
    }

    /** Re-budgets the cache, trimming immediately if the new budget is smaller. */
    @Synchronized
    fun setMaxBytes(value: Long) {
        maxBytes = value.coerceIn(0L, MAX_ICON_CACHE_BYTES)
        trimToBytes(maxBytes)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
        const val ACCESS_ORDER = true
    }
}

/** A snapshot of cache behaviour, for tests and for a debug overlay. */
data class IconCacheStats(
    val hits: Int,
    val misses: Int,
    val evictions: Int,
    val bytes: Long,
    val entries: Int,
)

// `ComponentCallbacks2`'s levels, restated as plain integers so the policy is pure. The values are
// public Android API constants and cannot change.
const val TRIM_MEMORY_RUNNING_MODERATE = 5
const val TRIM_MEMORY_RUNNING_LOW = 10
const val TRIM_MEMORY_RUNNING_CRITICAL = 15
const val TRIM_MEMORY_UI_HIDDEN = 20
const val TRIM_MEMORY_BACKGROUND = 40
const val TRIM_MEMORY_MODERATE = 60
const val TRIM_MEMORY_COMPLETE = 80

/**
 * How much of the icon cache survives a given `onTrimMemory` level.
 *
 * Once the launcher's UI is hidden the cache is pure cost — nothing is on screen to keep warm —
 * so from `TRIM_MEMORY_BACKGROUND` upward it goes entirely. While the launcher is still visible
 * the trim is proportional to how urgent the system says the pressure is.
 */
fun retainedFractionForTrim(level: Int): Float = when {
    level >= TRIM_MEMORY_BACKGROUND -> 0f
    level >= TRIM_MEMORY_UI_HIDDEN -> 0.25f
    level >= TRIM_MEMORY_RUNNING_CRITICAL -> 0.25f
    level >= TRIM_MEMORY_RUNNING_LOW -> 0.5f
    level >= TRIM_MEMORY_RUNNING_MODERATE -> 0.75f
    else -> 1f
}
