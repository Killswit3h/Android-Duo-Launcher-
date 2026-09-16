package com.jake.duolauncher.home

import android.content.Context
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.history.DefaultSuggestionRanker
import com.jake.duolauncher.history.LaunchHistoryStore
import com.jake.duolauncher.history.SuggestionExclusions
import com.jake.duolauncher.icons.IconPackRepository
import com.jake.duolauncher.library.LibraryApp
import com.jake.duolauncher.library.LibraryExclusions
import com.jake.duolauncher.library.LibrarySuggestionsProvider
import com.jake.duolauncher.profiles.PrivateSpaceGate
import com.jake.duolauncher.profiles.DuoPrivateSpace
import com.jake.duolauncher.search.AppsProvider
import com.jake.duolauncher.search.ContactsContractSource
import com.jake.duolauncher.search.ContactsProvider
import com.jake.duolauncher.search.DuoSearchEngine
import com.jake.duolauncher.search.PackageManagerWebSearch
import com.jake.duolauncher.search.SearchAppIndex
import com.jake.duolauncher.search.SearchEngine
import com.jake.duolauncher.search.SettingsProvider
import com.jake.duolauncher.search.WebProvider
import com.jake.duolauncher.search.settingsAvailability

/**
 * The process-scoped collaborators Home hosts (FR-71 to FR-74, FR-75, FR-77, FR-84).
 *
 * ## Why these live here rather than in a composable
 *
 * The suggestion ranker owns a disk-backed ring buffer and the search index owns a folded copy of
 * the whole catalog. Both are expensive to build and must outlive any one composition — rebuilding
 * the index from a `remember` would re-fold every label on the first keystroke after a
 * recomposition, which is precisely the cost NFR-P3's budget cannot absorb. One instance per
 * process, built lazily, keeps `SearchAppIndex.setApps` on the catalog-refresh path and off the
 * keystroke path.
 *
 * ## FR-77 is enforced here, once
 *
 * Every exclusion this object hands out reads [PrivateSpaceGate] **live**, through a lambda, rather
 * than capturing a snapshot of it. That is the whole point: the gate flips the moment the space
 * locks — including when the user locks it from system UI, with Duo in the background — and a
 * captured boolean would keep a locked space's apps visible in Search, Suggestions and the App
 * Library until whatever event happened to rebuild that surface next. Reading per query means the
 * answer can never be stale.
 *
 * The same reasoning applies to hidden apps (FR-75), which change without the catalog changing.
 * [publishCatalog] is the one writer; every consumer reads the volatile fields at query time.
 */
object DuoHost {

    /** FR-75. Updated from `LauncherState`; read per query, never captured. */
    @Volatile
    private var hiddenAppIds: Set<String> = emptySet()

    /**
     * The installed catalog, so the ranker can drop launches of apps that are gone.
     *
     * Empty means "not published yet", which allows everything rather than suppressing every
     * suggestion during the window before the first catalog refresh completes.
     */
    @Volatile
    private var installedAppIds: Set<String> = emptySet()

    private var historyStore: LaunchHistoryStore? = null
    private var rankerInstance: DefaultSuggestionRanker? = null
    private var indexInstance: SearchAppIndex? = null
    private var engineInstance: SearchEngine? = null
    private var iconPacksInstance: IconPackRepository? = null

    /**
     * Publishes the catalog every exclusion reads, and re-indexes Search when the apps themselves
     * changed.
     *
     * The index is rebuilt **only** when the app list changes, never when hidden apps do: hiding is
     * applied per query by the exclusions, so it needs no re-fold. Call this from the catalog
     * refresh, not from a composable that recomposes with state.
     */
    fun publishCatalog(context: Context, state: LauncherState, libraryApps: List<LibraryApp>) {
        hiddenAppIds = state.hiddenApps
        val installed = state.apps.mapTo(HashSet(state.apps.size)) { it.id }
        val changed = installed != installedAppIds
        installedAppIds = installed
        if (changed) searchIndex(context).setApps(libraryApps)
    }

    /** FR-77's single source of truth, read live by everything below. */
    fun gate(context: Context): PrivateSpaceGate = DuoPrivateSpace.repository(context).gate

    /** FR-84. One store, one ranker, for Suggestions, the App Library and Search alike. */
    @Synchronized
    fun ranker(context: Context): DefaultSuggestionRanker = rankerInstance ?: run {
        val application = context.applicationContext
        val store = LaunchHistoryStore(application).also { historyStore = it }
        val gate = gate(application)
        DefaultSuggestionRanker(
            history = store,
            exclusions = SuggestionExclusions(
                isInstalled = { installedAppIds.isEmpty() || it in installedAppIds },
                isHidden = { it in hiddenAppIds },
                // FR-77: read through the gate on every call, so locking the space takes effect
                // on the next ranking rather than on the next process start.
                isPrivateSpaceLocked = gate::isHiddenWhileLocked,
            ),
        ).also { rankerInstance = it }
    }

    @Synchronized
    fun searchIndex(context: Context): SearchAppIndex =
        indexInstance ?: SearchAppIndex().also { indexInstance = it }

    /** FR-18. Built lazily because most installs never select a pack. */
    @Synchronized
    fun iconPacks(context: Context): IconPackRepository =
        iconPacksInstance ?: IconPackRepository(context.applicationContext).also { iconPacksInstance = it }

    /**
     * The search engine (FR-71 to FR-74), constructed once.
     *
     * The App shortcuts section is deliberately left on its default empty source: `ShortcutRepository`
     * publishes shortcuts through a suspending query, and its contract requires the *same list
     * instance* back until the shortcuts actually change, which needs a snapshot owner that does not
     * exist yet. An empty section is the honest state; a fresh query per keystroke would burn the
     * NFR-P3 budget for it.
     */
    @Synchronized
    fun searchEngine(context: Context): SearchEngine = engineInstance ?: run {
        val application = context.applicationContext
        val apps = AppsProvider(
            index = searchIndex(application),
            exclusions = libraryExclusions(application),
            suggestionSource = suggestionsProvider(application),
        )
        DuoSearchEngine(
            apps = apps,
            settings = SettingsProvider(availability = settingsAvailability(application)),
            contacts = ContactsProvider(source = ContactsContractSource(application)),
            web = WebProvider(PackageManagerWebSearch(application)),
        ).also { engineInstance = it }
    }

    /**
     * The exclusions every app-listing surface shares (FR-75, FR-77).
     *
     * Both predicates close over the live fields rather than over values, so one instance stays
     * correct for the life of the process and the App Library, Search and the suggestion sources
     * cannot drift apart about what exists.
     */
    fun libraryExclusions(context: Context): LibraryExclusions {
        val gate = gate(context)
        return LibraryExclusions(
            isHidden = { it.id in hiddenAppIds },
            isPrivateLocked = { gate.isHiddenWhileLocked(it.id) },
        )
    }

    /** FR-68's Suggestions group and FR-71's pre-typing rows, from the one ranker. */
    fun suggestionsProvider(context: Context): LibrarySuggestionsProvider {
        val ranker = ranker(context)
        return LibrarySuggestionsProvider { limit -> ranker.suggestions(limit) }
    }

    /**
     * FR-77 for Home itself: whether a placed id may be drawn right now.
     *
     * Home stores ids, not `LibraryApp`s, so it needs the id-shaped form of the same rule. A hidden
     * app is already removed from every layout by `hideApp`, so this only has to answer the private
     * case — but it checks both, because a layout restored from a backup taken before an app was
     * hidden can still name it.
     */
    fun isVisibleOnHome(context: Context, appId: String): Boolean =
        appId !in hiddenAppIds && !gate(context).isHiddenWhileLocked(appId)
}
