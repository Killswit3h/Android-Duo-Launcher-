package com.jake.duolauncher.search

import com.jake.duolauncher.library.LibraryApp
import com.jake.duolauncher.library.LibraryExclusions
import com.jake.duolauncher.library.LibrarySuggestionsProvider
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val US: Locale = Locale.US

private val BATTERY_GURU = LibraryApp(
    id = "com.example.batteryguru/.Main", label = "Battery Guru", packageName = "com.example.batteryguru",
)
private val CHROME = LibraryApp(id = "com.android.chrome/.Main", label = "Chrome", packageName = "com.android.chrome")
private val CALCULATOR = LibraryApp(
    id = "com.example.calculator/.Main", label = "Calculator", packageName = "com.example.calculator",
)

/** An address book that actually filters, so a query that names nobody produces no section. */
private class FilteringContacts(private val rows: List<SearchContact>) : SearchContactSource {
    override fun isGranted(): Boolean = true

    override fun find(query: String, limit: Int): List<SearchContact> = rows
        .filter { it.displayName.lowercase(US).contains(query.lowercase(US)) }
        .take(limit)
}

class SearchEngineTest {

    private val catalog = listOf(BATTERY_GURU, CHROME, CALCULATOR)
    private val batteryShortcut = SearchShortcut(
        id = "report", appId = BATTERY_GURU.id, packageName = BATTERY_GURU.packageName,
        label = "Battery report", appLabel = BATTERY_GURU.label,
    )
    private val contacts = listOf(SearchContact("lookup-bat", 7L, "Bat Terry"))

    private fun engine(
        exclusions: LibraryExclusions = LibraryExclusions.None,
        suggestions: LibrarySuggestionsProvider = LibrarySuggestionsProvider.None,
        limits: SearchLimits = SearchLimits.Default,
        web: WebSearchAvailability = WebSearchAvailability.Available,
        contactSource: SearchContactSource = FilteringContacts(contacts),
        debounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
        awaitDebounce: suspend (Long) -> Unit = {},
    ): DuoSearchEngine {
        val apps = AppsProvider(
            SearchAppIndex(US).apply { setApps(catalog) },
            exclusions,
            suggestions,
            US,
        )
        return DuoSearchEngine(
            apps = apps,
            shortcuts = ShortcutsProvider(apps, { listOf(batteryShortcut) }, US),
            settings = SettingsProvider(locale = US),
            contacts = ContactsProvider(contactSource, locale = US),
            calculator = CalculatorProvider(US),
            web = WebProvider(web),
            limits = limits,
            locale = US,
            debounceMillis = debounceMillis,
            awaitDebounce = awaitDebounce,
        )
    }

    @Test fun `sections keep their fixed order`() {
        val results = engine().resultsFor("bat")
        assertEquals(
            listOf(
                SearchSection.APPS,
                SearchSection.SHORTCUTS,
                SearchSection.SETTINGS,
                SearchSection.CONTACTS,
                SearchSection.WEB,
            ),
            results.sections(),
        )
        assertEquals("Battery Guru", results.apps.first().app.label)
        assertEquals("Battery report", results.shortcuts.first().shortcut.label)
        assertEquals("battery", results.settings.first().destination.id)
        assertEquals("Bat Terry", results.contacts.first().contact.displayName)
        assertNull(results.calculation)
        assertEquals(WebResult("bat"), results.web)
        assertEquals("bat", results.query)
        assertFalse(results.isSuggestions)
    }

    @Test fun `every row is returned in section order`() {
        val rows = engine().resultsFor("bat").all()
        val sections = rows.map { it.section }
        assertEquals(sections.sortedBy { it.ordinal }, sections)
        assertEquals(sections.distinct(), engine().resultsFor("bat").sections())
        assertEquals(rows.size, SearchSection.entries.sumOf { engine().resultsFor("bat").rows(it).size })
    }

    @Test fun `the top result is the first row of the first section`() {
        assertEquals("Battery Guru", engine().resultsFor("bat").top?.title)
        assertEquals("42", engine().resultsFor("2*21").top?.title)
        assertEquals(SearchSection.WEB, engine().resultsFor("zzqx").top?.section)
    }

    @Test fun `chr finds Chrome as the launchable top result`() {
        val top = engine().resultsFor("chr").top
        assertTrue(top is AppResult)
        assertEquals("Chrome", top?.title)
    }

    @Test fun `arithmetic gets a calculation section`() {
        val results = engine().resultsFor("2*21")
        assertEquals(listOf(SearchSection.CALCULATION, SearchSection.WEB), results.sections())
        assertEquals("42", results.calculation?.formatted)
        assertEquals("15% of 80", engine().resultsFor("15% of 80").calculation?.expression)
        assertEquals("12", engine().resultsFor("15% of 80").calculation?.formatted)
    }

    @Test fun `no results still offers the web row`() {
        val results = engine().resultsFor("zzqx")
        assertTrue(results.hasNoResults)
        assertFalse(results.isEmpty)
        assertEquals(WebResult("zzqx"), results.web)
        assertEquals(listOf(SearchSection.WEB), results.sections())
    }

    @Test fun `an unresolvable web intent hides the row`() {
        val results = engine(web = WebSearchAvailability.Unavailable).resultsFor("zzqx")
        assertTrue(results.isEmpty)
        assertNull(results.web)
        assertNull(results.top)
        assertEquals(emptyList<SearchSection>(), results.sections())
    }

    @Test fun `denied contacts access removes the section`() {
        val results = engine(contactSource = SearchContactSource.Unavailable).resultsFor("bat")
        assertEquals(emptyList<ContactResult>(), results.contacts)
        assertFalse(SearchSection.CONTACTS in results.sections())
    }

    @Test fun `each section obeys its limit`() {
        val limits = SearchLimits(apps = 1, shortcuts = 1, settings = 2, contacts = 1, suggestions = 2)
        val results = engine(limits = limits).resultsFor("bat")
        assertEquals(1, results.apps.size)
        assertEquals(1, results.shortcuts.size)
        assertEquals(2, results.settings.size)
        assertEquals(1, results.contacts.size)
    }

    @Test fun `an empty query shows launch-history suggestions`() {
        val suggestions = LibrarySuggestionsProvider { listOf(CHROME.id, CALCULATOR.id) }
        val results = engine(suggestions = suggestions).resultsFor("")
        assertTrue(results.isSuggestions)
        assertEquals(listOf("Chrome", "Calculator"), results.apps.map { it.app.label })
        assertEquals(emptyList<ShortcutResult>(), results.shortcuts)
        assertEquals(emptyList<SettingResult>(), results.settings)
        assertEquals(emptyList<ContactResult>(), results.contacts)
        assertNull(results.calculation)
        assertNull("no web row before anything is typed", results.web)
        assertEquals("", results.query)
    }

    @Test fun `hidden apps are absent from every section`() {
        val hidden = LibraryExclusions(isHidden = { it.id == BATTERY_GURU.id })
        val results = engine(exclusions = hidden).resultsFor("bat")
        assertEquals(emptyList<AppResult>(), results.apps)
        assertEquals("its shortcut goes too", emptyList<ShortcutResult>(), results.shortcuts)
        assertEquals("battery", results.settings.first().destination.id)
    }

    @Test fun `a locked private space is absent from every section`() {
        val vault = LibraryApp(
            id = "com.example.vault/.Main", label = "Battery Vault",
            packageName = "com.example.vault", isPrivate = true,
        )
        val apps = AppsProvider(
            SearchAppIndex(US).apply { setApps(catalog + vault) },
            LibraryExclusions(isPrivateLocked = { it.isPrivate }),
            LibrarySuggestionsProvider { listOf(vault.id) },
            US,
        )
        val engine = DuoSearchEngine(apps = apps, locale = US, debounceMillis = 0L)
        assertFalse(engine.resultsFor("bat").apps.any { it.app.id == vault.id })
        assertTrue(engine.resultsFor("bat").apps.any { it.app.id == BATTERY_GURU.id })
        assertEquals(emptyList<AppResult>(), engine.resultsFor("").apps)
    }

    @Test fun `results are identical for identical input`() {
        assertEquals(engine().resultsFor("bat"), engine().resultsFor("bat"))
    }

    // --- Flow behavior: debounce, supersede, cancellation -------------------------------------

    @Test fun `a query waits out the debounce before it runs`() = runBlocking {
        val waits = ArrayList<Long>()
        val engine = engine(debounceMillis = 120L, awaitDebounce = { waits += it })
        assertEquals("Chrome", engine.query("chr").first().apps.first().app.label)
        assertEquals(listOf(120L), waits)
    }

    @Test fun `the empty query is never debounced`() = runBlocking {
        val waits = ArrayList<Long>()
        val engine = engine(
            suggestions = LibrarySuggestionsProvider { listOf(CHROME.id) },
            awaitDebounce = { waits += it },
        )
        assertTrue(engine.query("").first().isSuggestions)
        assertTrue(engine.query("   ").first().isSuggestions)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test fun `a debounce of zero still produces results`() = runBlocking {
        val waits = ArrayList<Long>()
        val engine = engine(debounceMillis = 0L, awaitDebounce = { waits += it })
        assertEquals("Chrome", engine.query("chr").first().apps.first().app.label)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test fun `a newer query supersedes the one it interrupted`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val engine = engine(awaitDebounce = { if (calls.getAndIncrement() == 0) gate.await() })

        val superseded = ArrayList<SearchResults>()
        val first = launch { engine.query("ca").toList(superseded) }
        yield()

        val second = engine.query("chr").first()
        assertEquals("Chrome", second.apps.first().app.label)

        gate.complete(Unit)
        first.join()
        assertEquals("the superseded query emits nothing", emptyList<SearchResults>(), superseded)
    }

    @Test fun `cancelling a collection stops the query from emitting`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val engine = engine(awaitDebounce = { gate.await() })
        val emissions = ArrayList<SearchResults>()
        val job = launch { engine.query("chr").toList(emissions) }
        yield()
        job.cancel()
        job.join()
        gate.complete(Unit)
        assertEquals(emptyList<SearchResults>(), emissions)
    }

    @Test fun `each collection of the same flow is answered`() = runBlocking {
        val engine = engine(debounceMillis = 0L)
        val flow = engine.query("chr")
        assertEquals("Chrome", flow.first().apps.first().app.label)
        assertEquals("Chrome", flow.first().apps.first().app.label)
    }
}
