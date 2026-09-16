package com.jake.duolauncher.search

import com.jake.duolauncher.library.LibraryApp
import com.jake.duolauncher.library.LibraryExclusions
import com.jake.duolauncher.library.LibrarySuggestionsProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val US: Locale = Locale.US

private fun app(
    label: String,
    packageName: String = "com.example." + label.lowercase(US).filter(Char::isLetterOrDigit),
    isPrivate: Boolean = false,
    isWork: Boolean = false,
) = LibraryApp(
    id = "$packageName/.Main",
    label = label,
    packageName = packageName,
    isWork = isWork,
    isPrivate = isPrivate,
)

private fun indexOf(vararg apps: LibraryApp) = SearchAppIndex(US).apply { setApps(apps.toList()) }

private fun providerFor(
    index: SearchAppIndex,
    exclusions: LibraryExclusions = LibraryExclusions.None,
    suggestions: LibrarySuggestionsProvider = LibrarySuggestionsProvider.None,
) = AppsProvider(index, exclusions, suggestions, US)

private fun AppsProvider.labels(text: String, limit: Int = 10): List<String> =
    results(SearchQuery.of(text, US), limit).map { it.app.label }

class AppsProviderTest {

    private val calculator = app("Calculator")
    private val calendar = app("Google Calendar", "com.example.calendar")
    private val analytics = app("Contact Analytics Lab", "com.example.contactanalytics")
    private val chrome = app("Chrome", "com.android.chrome")
    private val messages = app("Messages", "com.google.android.apps.messaging")
    private val cafe = app("Café Noir", "com.example.cafenoir")

    private val catalog = listOf(calculator, calendar, analytics, chrome, messages, cafe)

    private fun catalogProvider() = providerFor(indexOf(*catalog.toTypedArray()))

    @Test fun `prefix beats word start beats fuzzy`() {
        val results = catalogProvider().results(SearchQuery.of("cal", US), 10)
        assertEquals(
            listOf("Calculator", "Google Calendar", "Contact Analytics Lab"),
            results.map { it.app.label },
        )
        assertEquals(
            listOf(MatchKind.PREFIX, MatchKind.WORD_START, MatchKind.FUZZY),
            results.map { it.match },
        )
        assertTrue(results[0].score > results[1].score)
        assertTrue(results[1].score > results[2].score)
    }

    @Test fun `an exact match beats a prefix match`() {
        val provider = providerFor(indexOf(app("Maps"), app("Maps Go", "com.example.mapsgo")))
        assertEquals(listOf("Maps", "Maps Go"), provider.labels("maps"))
    }

    @Test fun `a whole-word match beats one that stops mid-word`() {
        val provider = providerFor(indexOf(app("Ada Lovelace"), app("Adam Smith", "com.example.adamsmith")))
        assertEquals(listOf("Ada Lovelace", "Adam Smith"), provider.labels("ada"))
    }

    @Test fun `matching ignores case and accents`() {
        assertEquals(listOf("Café Noir"), catalogProvider().labels("cafe"))
        assertEquals(listOf("Café Noir"), catalogProvider().labels("CAFÉ"))
        assertEquals(listOf("Café Noir"), catalogProvider().labels("noir"))
    }

    @Test fun `chr finds Chrome`() {
        val results = catalogProvider().results(SearchQuery.of("chr", US), 10)
        assertEquals("Chrome", results.first().app.label)
        assertEquals(MatchKind.PREFIX, results.first().match)
    }

    @Test fun `sms finds Messages through the curated alias`() {
        val results = catalogProvider().results(SearchQuery.of("sms", US), 10)
        assertEquals(listOf("Messages"), results.map { it.app.label })
        assertEquals(MatchKind.KEYWORD, results.first().match)
    }

    @Test fun `a package token finds an app whose label does not contain the query`() {
        val spotify = app("Tunes", "com.spotify.music")
        assertEquals(listOf("Tunes"), providerFor(indexOf(spotify)).labels("spotify"))
    }

    @Test fun `a label match always outranks a keyword match`() {
        val browser = app("Browser Pro", "com.example.browserpro")
        val provider = providerFor(indexOf(browser, chrome))
        // Chrome only reaches "browser" through its alias, so the labelled app wins.
        assertEquals(listOf("Browser Pro", "Chrome"), provider.labels("browser"))
    }

    @Test fun `hidden apps never appear`() {
        val provider = providerFor(
            indexOf(*catalog.toTypedArray()),
            LibraryExclusions(isHidden = { it.id == chrome.id }),
        )
        assertEquals(emptyList<String>(), provider.labels("chrome"))
        assertFalse(provider.isVisible(chrome.id))
        assertTrue(provider.isVisible(calculator.id))
    }

    @Test fun `locked private space apps never appear`() {
        val vault = app("Vault", "com.example.vault", isPrivate = true)
        val provider = providerFor(
            indexOf(vault, chrome),
            LibraryExclusions(isPrivateLocked = { it.isPrivate }),
        )
        assertEquals(emptyList<String>(), provider.labels("vault"))
        assertFalse(provider.isVisible(vault.id))
        assertTrue(provider.isVisible(chrome.id))
    }

    @Test fun `the section respects its limit`() {
        assertEquals(listOf("Calculator", "Google Calendar"), catalogProvider().labels("cal", limit = 2))
        assertEquals(emptyList<String>(), catalogProvider().labels("cal", limit = 0))
    }

    @Test fun `launch history breaks a tie between equal matches`() {
        val tasks = app("Tick Tasks", "com.example.ticktasks")
        val timer = app("Tick Timer", "com.example.ticktimer")
        assertEquals(listOf("Tick Tasks", "Tick Timer"), providerFor(indexOf(tasks, timer)).labels("tick"))

        val boosted = providerFor(
            indexOf(tasks, timer),
            suggestions = LibrarySuggestionsProvider { listOf(timer.id) },
        )
        assertEquals(listOf("Tick Timer", "Tick Tasks"), boosted.labels("tick"))
    }

    @Test fun `a usage boost cannot lift a worse match above a better one`() {
        val provider = providerFor(
            indexOf(*catalog.toTypedArray()),
            suggestions = LibrarySuggestionsProvider { listOf(analytics.id) },
        )
        assertEquals("Calculator", provider.labels("cal").first())
    }

    @Test fun `ranking does not depend on catalog order`() {
        val forwards = providerFor(indexOf(*catalog.toTypedArray())).labels("cal")
        val backwards = providerFor(indexOf(*catalog.reversed().toTypedArray())).labels("cal")
        assertEquals(forwards, backwards)
        assertEquals(forwards, catalogProvider().labels("cal"))
    }

    @Test fun `no match means no rows`() {
        assertEquals(emptyList<String>(), catalogProvider().labels("zzqx"))
        assertEquals(emptyList<String>(), catalogProvider().labels("   "))
    }

    @Test fun `suggestions resolve launch history against the visible catalog`() {
        val hidden = app("Hidden", "com.example.hidden")
        val index = indexOf(chrome, hidden, calculator)
        val ranked = LibrarySuggestionsProvider {
            listOf(hidden.id, chrome.id, chrome.id, "com.nope/.Gone", calculator.id)
        }
        val provider = providerFor(index, LibraryExclusions(isHidden = { it.id == hidden.id }), ranked)

        val suggestions = provider.suggestions(4)
        assertEquals(listOf("Chrome", "Calculator"), suggestions.map { it.app.label })
        assertTrue(suggestions.all(AppResult::isSuggestion))
        assertEquals(listOf("Chrome"), provider.suggestions(1).map { it.app.label })
        assertEquals(emptyList<AppResult>(), provider.suggestions(0))
    }

    @Test fun `an empty index answers nothing`() {
        val provider = providerFor(SearchAppIndex(US))
        assertEquals(emptyList<String>(), provider.labels("chrome"))
        assertEquals(emptyList<AppResult>(), provider.suggestions(4))
    }

    @Test fun `re-indexing replaces the catalog`() {
        val index = indexOf(chrome)
        val provider = providerFor(index)
        assertEquals(listOf("Chrome"), provider.labels("chrome"))

        index.setApps(listOf(calculator))
        assertEquals(emptyList<String>(), provider.labels("chrome"))
        assertEquals(listOf("Calculator"), provider.labels("calc"))
        assertEquals(1, index.size)
    }

    @Test fun `duplicate ids are indexed once`() {
        val index = indexOf(chrome, chrome)
        assertEquals(1, index.size)
        assertEquals(listOf("Chrome"), providerFor(index).labels("chrome"))
    }

    @Test fun `package tokens drop the generic segments`() {
        assertEquals(listOf("spotify", "music"), SearchAppIndex.packageTokens("com.spotify.music"))
        assertEquals(listOf("example"), SearchAppIndex.packageTokens("com.android.apps.example"))
        assertEquals(emptyList<String>(), SearchAppIndex.packageTokens(""))
    }
}
