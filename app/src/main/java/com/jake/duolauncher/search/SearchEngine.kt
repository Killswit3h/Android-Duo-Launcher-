package com.jake.duolauncher.search

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** The frozen contract from the build plan: debounced search that cancels the previous query. */
interface SearchEngine {
    fun query(text: String): Flow<SearchResults>
}

/**
 * How long typing has to pause before a query runs.
 *
 * NFR-P3 gives the whole keystroke 100 ms, so the debounce spends half the budget at most and
 * leaves the rest to matching, which measures in single-digit milliseconds over 300 apps.
 */
const val SEARCH_DEBOUNCE_MILLIS = 50L

/**
 * The shipped engine: a fixed provider list merged in a fixed section order (ADR-6).
 *
 * **Sections** are always Apps, App shortcuts, Settings, Contacts, Calculation, Search the web, in
 * that order, each capped by [SearchLimits]. Empty sections are simply empty; the order never
 * depends on the query, so the same input always produces the same layout.
 *
 * **Debounce and cancellation.** Each [query] call returns a cold flow. Collecting it takes a
 * ticket; after the debounce the ticket is checked against the newest one, and a superseded query
 * emits nothing at all rather than a result the user has already typed past. Collect with
 * `collectLatest`/`flatMapLatest` and the in-flight flow is also cancelled structurally, which is
 * what actually frees the CPU mid-computation.
 *
 * **The empty query** skips the debounce entirely and answers with FR-71's launch-history
 * suggestions, so opening Search shows something the moment the keyboard appears.
 *
 * **Threading.** Matching runs on [dispatcher] (`Dispatchers.Default`), never the caller's thread,
 * and the app index is rebuilt off the keystroke path by [SearchAppIndex.setApps].
 */
class DuoSearchEngine(
    private val apps: AppsProvider,
    private val shortcuts: ShortcutsProvider = ShortcutsProvider(apps),
    private val settings: SettingsProvider = SettingsProvider(),
    private val contacts: ContactsProvider = ContactsProvider(),
    private val calculator: CalculatorProvider = CalculatorProvider(),
    private val web: WebProvider = WebProvider(),
    private val limits: SearchLimits = SearchLimits.Default,
    private val locale: Locale = Locale.getDefault(),
    private val debounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
    /** The wait itself, injected so debounce behavior is testable without wall-clock sleeps. */
    private val awaitDebounce: suspend (Long) -> Unit = { delay(it) },
) : SearchEngine {

    private val generation = AtomicLong(0)

    override fun query(text: String): Flow<SearchResults> = flow {
        val ticket = generation.incrementAndGet()
        if (debounceMillis > 0 && text.isNotBlank()) awaitDebounce(debounceMillis)
        currentCoroutineContext().ensureActive()
        if (generation.get() != ticket) return@flow
        val results = withContext(dispatcher) { resultsFor(text) }
        currentCoroutineContext().ensureActive()
        if (generation.get() == ticket) emit(results)
    }

    /**
     * One query, computed synchronously. This is the whole engine; [query] only adds the debounce
     * and the supersede rule. Exposed because it is also how latency is measured.
     */
    fun resultsFor(text: String): SearchResults {
        val query = SearchQuery.of(text, locale)
        if (query.isBlank) {
            return SearchResults(
                apps = apps.suggestions(limits.suggestions),
                isSuggestions = true,
            )
        }
        return SearchResults(
            apps = apps.results(query, limits.apps),
            shortcuts = shortcuts.results(query, limits.shortcuts),
            settings = settings.results(query, limits.settings),
            contacts = contacts.results(query, limits.contacts),
            calculation = calculator.result(query.raw),
            web = web.result(query),
            query = query.raw,
        )
    }
}
