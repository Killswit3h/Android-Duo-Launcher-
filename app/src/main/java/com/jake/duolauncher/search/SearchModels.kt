package com.jake.duolauncher.search

import com.jake.duolauncher.library.LibraryApp

/**
 * Pure-JVM models behind Search (FR-71 to FR-74).
 *
 * Nothing in this file touches Android or Compose types, so the whole engine — matching, ranking,
 * section merging, arithmetic — is exercisable from plain JVM unit tests. The Android seams live in
 * `SearchIntents.kt`, which is the only file here that imports the framework.
 */

/** Persisted app identity, as produced by `profileAppId` in the root package. */
typealias ProfileAppId = String

/**
 * The sections of FR-72, in the order they are shown. Declaration order *is* display order and the
 * order [SearchResults.top] walks, so ADR-6's "fixed section order" needs no separate sort.
 */
enum class SearchSection { APPS, SHORTCUTS, SETTINGS, CONTACTS, CALCULATION, WEB }

/**
 * How a candidate matched, best tier first. Declaration order is ranking order: every exact match
 * outranks every prefix match, every prefix match outranks every word-start match, and so on down
 * to fuzzy. [MatchKind.KEYWORD] covers the curated aliases and package-name tokens in
 * [SearchAliases], which is how "sms" reaches an app labelled "Messages".
 */
enum class MatchKind { EXACT, PREFIX, WORD_START, KEYWORD, CONTAINS, FUZZY }

/** One row the UI can render and FR-74 can launch. */
sealed interface SearchResult {
    val section: SearchSection

    /** The primary line of the row. */
    val title: String
}

/** An installed app (FR-72 "Apps"). Carries the whole [LibraryApp] so the UI needs no second lookup. */
data class AppResult(
    val app: LibraryApp,
    val match: MatchKind,
    val score: Int,
    /** True for the pre-typing rows of FR-71, which are ranked by launch history, not by text. */
    val isSuggestion: Boolean = false,
) : SearchResult {
    val id: ProfileAppId get() = app.id
    override val section: SearchSection get() = SearchSection.APPS
    override val title: String get() = app.label
}

/**
 * One app shortcut as Search sees it. The launcher's `ShortcutRepository` (build plan task B7) owns
 * the real `ShortcutInfo`; Search only ever holds this flattened, framework-free form.
 */
data class SearchShortcut(
    /** `ShortcutInfo.getId()`. Unique per package and user, not globally. */
    val id: String,
    /** The app the shortcut belongs to, so exclusions and visibility reuse the app rules. */
    val appId: ProfileAppId,
    val packageName: String,
    /** The shortcut's short label, already localized. */
    val label: String,
    /** The owning app's label, shown as the row's second line. */
    val appLabel: String = "",
)

data class ShortcutResult(
    val shortcut: SearchShortcut,
    val match: MatchKind,
    val score: Int,
) : SearchResult {
    override val section: SearchSection get() = SearchSection.SHORTCUTS
    override val title: String get() = shortcut.label
}

/**
 * One curated Android settings screen.
 *
 * Android publishes no searchable settings index, so the list in [DuoSettingsDestinations] is
 * curated by hand: a stable [id], a fallback English [title], the `Settings.ACTION_*` [action] to
 * fire, and the [keywords] users actually type. [id] is what the UI maps to a string resource when
 * FR-72's Search screen is localized (NFR-M4).
 */
data class SettingsDestination(
    val id: String,
    val title: String,
    val action: String,
    val keywords: List<String> = emptyList(),
)

data class SettingResult(
    val destination: SettingsDestination,
    val match: MatchKind,
    val score: Int,
) : SearchResult {
    override val section: SearchSection get() = SearchSection.SETTINGS
    override val title: String get() = destination.title
}

/**
 * One contact row. Only the three fields needed to draw and open the row are carried, and no
 * instance is ever cached, stored or logged (NFR-S4).
 */
data class SearchContact(
    val lookupKey: String,
    val contactId: Long,
    val displayName: String,
)

data class ContactResult(
    val contact: SearchContact,
    val match: MatchKind,
    val score: Int,
) : SearchResult {
    override val section: SearchSection get() = SearchSection.CONTACTS
    override val title: String get() = contact.displayName
}

/** An arithmetic answer (FR-72 "Calculation"). [formatted] is display text; [value] is the number. */
data class CalculationResult(
    val expression: String,
    val formatted: String,
    val value: Double,
) : SearchResult {
    override val section: SearchSection get() = SearchSection.CALCULATION
    override val title: String get() = formatted
}

/** The web hand-off row (FR-72). Present only when an activity resolves `ACTION_WEB_SEARCH`. */
data class WebResult(val query: String) : SearchResult {
    override val section: SearchSection get() = SearchSection.WEB
    override val title: String get() = query
}

/**
 * The frozen result shape from the build plan's internal contract, plus two conveniences the Search
 * UI needs: [sections] for rendering and [top] for FR-74's "Enter launches the top result".
 */
data class SearchResults(
    val apps: List<AppResult> = emptyList(),
    val shortcuts: List<ShortcutResult> = emptyList(),
    val settings: List<SettingResult> = emptyList(),
    val contacts: List<ContactResult> = emptyList(),
    val calculation: CalculationResult? = null,
    val web: WebResult? = null,
    /** The trimmed text these results answer. Empty for the pre-typing suggestions of FR-71. */
    val query: String = "",
    /** True when [apps] holds launch-history suggestions rather than matches (FR-71). */
    val isSuggestions: Boolean = false,
) {
    /** Nothing at all to show, not even a web row. */
    val isEmpty: Boolean
        get() = apps.isEmpty() && shortcuts.isEmpty() && settings.isEmpty() &&
            contacts.isEmpty() && calculation == null && web == null

    /**
     * True when the query produced no real answer. The web row does not count, which is exactly the
     * error table's "Search with no results" state: "No results" *plus* a Search the web row.
     */
    val hasNoResults: Boolean
        get() = apps.isEmpty() && shortcuts.isEmpty() && settings.isEmpty() &&
            contacts.isEmpty() && calculation == null

    /** The non-empty sections, in the fixed order of [SearchSection]. */
    fun sections(): List<SearchSection> = SearchSection.entries.filter { rows(it).isNotEmpty() }

    /** The rows of one section, in rank order. */
    fun rows(section: SearchSection): List<SearchResult> = when (section) {
        SearchSection.APPS -> apps
        SearchSection.SHORTCUTS -> shortcuts
        SearchSection.SETTINGS -> settings
        SearchSection.CONTACTS -> contacts
        SearchSection.CALCULATION -> listOfNotNull(calculation)
        SearchSection.WEB -> listOfNotNull(web)
    }

    /** Every row, section by section, in display order. */
    fun all(): List<SearchResult> = SearchSection.entries.flatMap(::rows)

    /** FR-74: what the keyboard's search action launches. The first row of the first section. */
    val top: SearchResult?
        get() {
            for (section in SearchSection.entries) rows(section).firstOrNull()?.let { return it }
            return null
        }

    companion object {
        val Empty = SearchResults()
    }
}

/**
 * Per-section caps (ADR-6). Limits keep the result list scannable and bound the work each keystroke
 * does, which is half of how NFR-P3's 100 ms budget is met.
 */
data class SearchLimits(
    val apps: Int = 6,
    val shortcuts: Int = 4,
    val settings: Int = 4,
    val contacts: Int = 4,
    /** How many pre-typing app suggestions FR-71 shows. */
    val suggestions: Int = 8,
    /** Contacts are not queried at all below this many characters: less IO, less data touched. */
    val minContactQueryLength: Int = 2,
) {
    init {
        require(apps >= 0 && shortcuts >= 0 && settings >= 0 && contacts >= 0 && suggestions >= 0) {
            "Search limits cannot be negative"
        }
        require(minContactQueryLength >= 1) { "Contacts need at least one character to filter on" }
    }

    companion object {
        val Default = SearchLimits()
    }
}
