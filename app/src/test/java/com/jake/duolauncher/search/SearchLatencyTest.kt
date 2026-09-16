package com.jake.duolauncher.search

import com.jake.duolauncher.library.LibraryApp
import com.jake.duolauncher.library.LibraryExclusions
import com.jake.duolauncher.library.LibrarySuggestionsProvider
import java.util.Locale
import kotlin.math.roundToLong
import org.junit.Assert.assertTrue
import org.junit.Test

private val US: Locale = Locale.US

/**
 * NFR-P3: a search result update stays inside 100 ms per keystroke with 300 installed apps.
 *
 * This measures the engine's own work — matching, ranking and merging every section over a
 * 300-app catalog — which is the part of the budget this task owns. It is a JVM measurement, not a
 * device one, so the assertions are deliberately loose: what they defend against is an algorithmic
 * regression (re-folding labels per keystroke, an accidental O(n²) pass), not a few milliseconds of
 * machine noise. The device-side number is verified on hardware by AC-58.
 */
class SearchLatencyTest {

    private val words = listOf(
        "Photo", "Studio", "Cloud", "Mail", "Chat", "Notes", "Music", "Video", "Maps", "Wallet",
        "Fitness", "Weather", "News", "Books", "Radio", "Tasks", "Timer", "Bank", "Travel", "Games",
    )

    private val queries = listOf(
        "c", "ch", "cha", "chat", "m", "ma", "map", "maps", "ph", "pho", "phot", "photo",
        "zzqx", "wifi", "2*21", "15% of 80", "stu", "wa", "note", "fit", "tra", "gam", "bk",
    )

    private fun catalog(count: Int): List<LibraryApp> = List(count) { index ->
        val label = "${words[index % words.size]} ${words[(index / words.size + 3) % words.size]} $index"
        val packageName = "com.vendor${index % 40}.${words[index % words.size].lowercase(US)}$index"
        LibraryApp(
            id = "$packageName/.Main",
            label = label,
            packageName = packageName,
            installedAt = 1_700_000_000_000L + index,
            isWork = index % 17 == 0,
        )
    }

    private fun engine(apps: List<LibraryApp>): DuoSearchEngine {
        val index = SearchAppIndex(US).apply { setApps(apps) }
        val provider = AppsProvider(
            index,
            LibraryExclusions(isHidden = { it.label.endsWith("7") }),
            LibrarySuggestionsProvider { limit -> apps.take(limit).map { it.id } },
            US,
        )
        val shortcuts = apps.take(60).map { app ->
            SearchShortcut("s-${app.id}", app.id, app.packageName, "Open ${app.label}", app.label)
        }
        return DuoSearchEngine(
            apps = provider,
            shortcuts = ShortcutsProvider(provider, { shortcuts }, US),
            settings = SettingsProvider(locale = US),
            contacts = ContactsProvider(locale = US),
            calculator = CalculatorProvider(US),
            web = WebProvider(WebSearchAvailability.Available),
            locale = US,
            debounceMillis = 0L,
        )
    }

    @Test fun `a full query over 300 apps stays well inside the 100 ms budget`() {
        val apps = catalog(300)
        val engine = engine(apps)

        repeat(WARMUP_ROUNDS) { queries.forEach(engine::resultsFor) }

        val samples = ArrayList<Long>(MEASURED_ROUNDS * queries.size)
        repeat(MEASURED_ROUNDS) {
            for (query in queries) {
                val start = System.nanoTime()
                val results = engine.resultsFor(query)
                samples += System.nanoTime() - start
                // Keep the work observable so nothing can be optimized away.
                assertTrue(results.sections().size <= SearchSection.entries.size)
            }
        }

        samples.sort()
        val averageMs = samples.average() / 1_000_000.0
        val p95Ms = samples[(samples.size * 95 / 100).coerceAtMost(samples.lastIndex)] / 1_000_000.0
        val worstMs = samples.last() / 1_000_000.0
        println(
            "search latency over ${apps.size} apps: avg ${(averageMs * 1000).roundToLong() / 1000.0} ms, " +
                "p95 ${(p95Ms * 1000).roundToLong() / 1000.0} ms, max ${(worstMs * 1000).roundToLong() / 1000.0} ms",
        )

        assertTrue("average query took $averageMs ms", averageMs < AVERAGE_BUDGET_MS)
        assertTrue("p95 query took $p95Ms ms, over the NFR-P3 budget", p95Ms < KEYSTROKE_BUDGET_MS)
    }

    @Test fun `indexing 300 apps happens once, off the keystroke path`() {
        val apps = catalog(300)
        val index = SearchAppIndex(US)
        repeat(WARMUP_ROUNDS) { index.setApps(apps) }

        val start = System.nanoTime()
        index.setApps(apps)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        println("indexing ${apps.size} apps took $elapsedMs ms")

        assertTrue("indexing took $elapsedMs ms", elapsedMs < INDEXING_BUDGET_MS)
        assertTrue(index.size == apps.size)
    }

    private companion object {
        const val WARMUP_ROUNDS = 20
        const val MEASURED_ROUNDS = 20

        /** NFR-P3's per-keystroke budget. */
        const val KEYSTROKE_BUDGET_MS = 100.0

        /** A regression guard far below the budget: the real numbers are microseconds. */
        const val AVERAGE_BUDGET_MS = 25.0

        /** A full rebuild is a catalog-change cost, not a keystroke cost, but it still must not crawl. */
        const val INDEXING_BUDGET_MS = 250.0
    }
}
