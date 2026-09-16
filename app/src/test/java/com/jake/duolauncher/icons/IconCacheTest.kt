package com.jake.duolauncher.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stand-in for a rendered icon, so the cache policy is testable without an Android bitmap. */
private data class FakeIcon(val name: String)

private fun key(name: String, sizePx: Int = 64, style: IconStyle = IconStyle()) =
    IconKey(PERSONAL_USER_SERIAL, "com.example.$name", "com.example.$name.Main", sizePx, style)

/** A cache that holds exactly [entries] icons of [sizePx], so eviction counts are exact. */
private fun cacheFor(entries: Int, sizePx: Int = 64) =
    IconCache<FakeIcon>(maxBytes = iconBytes(sizePx) * entries)

/** FR-12 and NFR-P4: a memory-bounded LRU that survives memory pressure without flashing placeholders. */
class IconCacheTest {

    @Test fun `a stored icon is returned and its bytes are accounted for`() {
        val cache = IconCache<FakeIcon>()
        val k = key("one")
        assertTrue(cache.put(k, FakeIcon("one")))
        assertEquals(FakeIcon("one"), cache.get(k))
        assertEquals(iconBytes(64), cache.sizeBytes)
        assertEquals(1, cache.count)
    }

    @Test fun `a miss is a miss, not an exception`() {
        assertNull(IconCache<FakeIcon>().get(key("absent")))
    }

    @Test fun `re-storing a key replaces it without double counting its bytes`() {
        val cache = IconCache<FakeIcon>()
        val k = key("one")
        cache.put(k, FakeIcon("first"))
        cache.put(k, FakeIcon("second"))
        assertEquals(1, cache.count)
        assertEquals(iconBytes(64), cache.sizeBytes)
        assertEquals(FakeIcon("second"), cache.get(k))
    }

    // --- Eviction order -----------------------------------------------------

    @Test fun `the least recently used icon is evicted first`() {
        val cache = cacheFor(entries = 3, sizePx = 512)
        val a = key("a", 512)
        val b = key("b", 512)
        val c = key("c", 512)
        cache.put(a, FakeIcon("a"))
        cache.put(b, FakeIcon("b"))
        cache.put(c, FakeIcon("c"))
        cache.put(key("d", 512), FakeIcon("d"))
        assertNull("the oldest untouched entry goes first", cache.get(a))
        assertNotNull(cache.get(b))
        assertNotNull(cache.get(c))
    }

    @Test fun `reading an icon makes it survive the next eviction`() {
        val cache = cacheFor(entries = 3, sizePx = 512)
        val a = key("a", 512)
        val b = key("b", 512)
        cache.put(a, FakeIcon("a"))
        cache.put(b, FakeIcon("b"))
        cache.put(key("c", 512), FakeIcon("c"))
        // A visible icon is read every frame; that is what has to keep it alive.
        assertNotNull(cache.get(a))
        cache.put(key("d", 512), FakeIcon("d"))
        assertNotNull("the recently read entry survives", cache.get(a))
        assertNull(cache.get(b))
    }

    @Test fun `the cache never exceeds its budget`() {
        val cache = cacheFor(entries = 4, sizePx = 512)
        repeat(50) { index -> cache.put(key("app$index", 512), FakeIcon("app$index")) }
        assertTrue(cache.sizeBytes <= cache.maxBytes)
        assertEquals(4, cache.count)
    }

    @Test fun `the budget can never be set above the specs sixty four megabyte ceiling`() {
        val cache = IconCache<FakeIcon>(maxBytes = Long.MAX_VALUE)
        assertEquals(MAX_ICON_CACHE_BYTES, cache.maxBytes)
        cache.setMaxBytes(Long.MAX_VALUE)
        assertEquals(MAX_ICON_CACHE_BYTES, cache.maxBytes)
    }

    @Test fun `an icon larger than the whole budget is not admitted at all`() {
        // Admitting it would evict everything else for one icon that cannot help the next frame.
        val cache = cacheFor(entries = 1, sizePx = 64)
        val huge = IconKey(PERSONAL_USER_SERIAL, "p", "a", MAX_ICON_PX, IconStyle())
        val small = key("small")
        cache.put(small, FakeIcon("small"))
        assertFalse(cache.put(huge, FakeIcon("huge")))
        assertNotNull("the existing entries survive the rejection", cache.get(small))
    }

    @Test fun `shrinking the budget trims immediately`() {
        val cache = cacheFor(entries = 8, sizePx = 512)
        repeat(8) { index -> cache.put(key("app$index", 512), FakeIcon("app$index")) }
        cache.setMaxBytes(MIN_ICON_CACHE_BYTES)
        assertTrue(cache.sizeBytes <= MIN_ICON_CACHE_BYTES)
    }

    // --- Invalidation -------------------------------------------------------

    @Test fun `invalidating a style drops only that styles renders`() {
        val cache = IconCache<FakeIcon>()
        val clear = IconStyle(appearance = IconAppearance.CLEAR)
        val default = IconStyle()
        cache.put(key("a", style = clear), FakeIcon("a-clear"))
        cache.put(key("a", style = default), FakeIcon("a-default"))
        val dropped = cache.invalidate { it.style == clear.normalized() }
        assertEquals(listOf(FakeIcon("a-clear")), dropped)
        assertNull(cache.get(key("a", style = clear)))
        assertNotNull(cache.get(key("a", style = default)))
    }

    @Test fun `invalidating a package drops every size and style of that app`() {
        val cache = IconCache<FakeIcon>()
        cache.put(key("a", 64), FakeIcon("a64"))
        cache.put(key("a", 128), FakeIcon("a128"))
        cache.put(key("b", 64), FakeIcon("b64"))
        assertEquals(2, cache.invalidate { it.packageName == "com.example.a" }.size)
        assertEquals(1, cache.count)
        assertNotNull(cache.get(key("b", 64)))
    }

    @Test fun `invalidation keeps the byte total honest`() {
        val cache = IconCache<FakeIcon>()
        cache.put(key("a"), FakeIcon("a"))
        cache.put(key("b"), FakeIcon("b"))
        cache.invalidate { it.packageName == "com.example.a" }
        assertEquals(iconBytes(64), cache.sizeBytes)
        cache.invalidate { true }
        assertEquals(0L, cache.sizeBytes)
        assertEquals(0, cache.count)
    }

    @Test fun `clearing empties the cache and its byte total`() {
        val cache = IconCache<FakeIcon>()
        cache.put(key("a"), FakeIcon("a"))
        cache.clear()
        assertEquals(0, cache.count)
        assertEquals(0L, cache.sizeBytes)
    }

    // --- Memory pressure (NFR-P4, error handling table) --------------------

    @Test fun `trim levels retain less as the pressure grows`() {
        assertEquals(1f, retainedFractionForTrim(0), 0f)
        assertEquals(0.75f, retainedFractionForTrim(TRIM_MEMORY_RUNNING_MODERATE), 0f)
        assertEquals(0.5f, retainedFractionForTrim(TRIM_MEMORY_RUNNING_LOW), 0f)
        assertEquals(0.25f, retainedFractionForTrim(TRIM_MEMORY_RUNNING_CRITICAL), 0f)
        assertEquals(0.25f, retainedFractionForTrim(TRIM_MEMORY_UI_HIDDEN), 0f)
        assertEquals(0f, retainedFractionForTrim(TRIM_MEMORY_BACKGROUND), 0f)
        assertEquals(0f, retainedFractionForTrim(TRIM_MEMORY_MODERATE), 0f)
        assertEquals(0f, retainedFractionForTrim(TRIM_MEMORY_COMPLETE), 0f)
    }

    @Test fun `trim levels never increase what is retained`() {
        var previous = 1f
        for (level in listOf(0, 5, 10, 15, 20, 40, 60, 80)) {
            val retained = retainedFractionForTrim(level)
            assertTrue("retention must not grow with pressure", retained <= previous)
            previous = retained
        }
    }

    @Test fun `a moderate trim keeps the most recently used icons`() {
        val cache = cacheFor(entries = 4, sizePx = 512)
        val keys = List(4) { key("app$it", 512) }
        keys.forEach { cache.put(it, FakeIcon(it.packageName)) }
        // Touch the last two: they are the ones on screen.
        cache.get(keys[2])
        cache.get(keys[3])
        cache.onTrimMemory(TRIM_MEMORY_RUNNING_LOW)
        assertTrue(cache.sizeBytes <= cache.maxBytes / 2)
        assertNotNull(cache.get(keys[2]))
        assertNotNull(cache.get(keys[3]))
    }

    @Test fun `going to the background drops the cache entirely`() {
        val cache = cacheFor(entries = 4, sizePx = 512)
        repeat(4) { cache.put(key("app$it", 512), FakeIcon("app$it")) }
        cache.onTrimMemory(TRIM_MEMORY_BACKGROUND)
        assertEquals(0, cache.count)
        assertEquals(0L, cache.sizeBytes)
    }

    @Test fun `a trim below the running threshold changes nothing`() {
        val cache = cacheFor(entries = 4, sizePx = 512)
        repeat(4) { cache.put(key("app$it", 512), FakeIcon("app$it")) }
        cache.onTrimMemory(0)
        assertEquals(4, cache.count)
    }

    @Test fun `statistics count hits, misses and evictions`() {
        val cache = cacheFor(entries = 2, sizePx = 512)
        cache.put(key("a", 512), FakeIcon("a"))
        cache.get(key("a", 512))
        cache.get(key("absent", 512))
        repeat(4) { cache.put(key("filler$it", 512), FakeIcon("filler$it")) }
        val stats = cache.stats
        assertEquals(1, stats.hits)
        assertEquals(1, stats.misses)
        assertTrue("filling past the budget must evict", stats.evictions > 0)
        assertEquals(cache.sizeBytes, stats.bytes)
    }
}
