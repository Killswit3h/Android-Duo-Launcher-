package com.jake.duolauncher.settings

import com.jake.duolauncher.search.IndexedText
import com.jake.duolauncher.search.SearchQuery
import com.jake.duolauncher.search.bestMatch
import com.jake.duolauncher.search.matchKeywords
import com.jake.duolauncher.search.matchLabel
import java.util.Locale

/**
 * The pure half of Duo Settings (FR-79, AC-64).
 *
 * Every row the settings screen can draw is declared here once, with the words a user would type to
 * look for it. The screen renders from this catalog and the search field filters against it, so a
 * row can never exist in the UI without being findable, and the ordering the spec fixes is data
 * rather than the accident of how the composables happen to be nested.
 *
 * Nothing here touches Android or Compose, so section ordering and search matching are exercised by
 * plain JVM unit tests (NFR-M2). Matching itself reuses the search track's folding and scoring
 * ([matchLabel], [matchKeywords]), which is what makes the settings field behave like the rest of
 * the launcher: case- and accent-insensitive, prefix, word-start and substring aware.
 */

/**
 * The thirteen sections of FR-79, in the order the spec lists them.
 *
 * **Declaration order is display order.** The screen never sorts sections, so this enum is the only
 * place the running order lives.
 */
enum class DuoSettingsSection(val title: String) {
    WALLPAPER("Wallpaper"),
    APPEARANCE("Appearance"),
    ICONS("Icons"),
    HOME_AND_DOCK("Home Screen and Dock"),
    TODAY("Today View"),
    LIBRARY_AND_SEARCH("App Library and Search"),
    GESTURES("Gestures"),
    BADGES("Badges"),
    HIDDEN_APPS("Hidden apps"),
    PRIVATE_SPACE("Private space"),
    BACKUP("Backup"),
    HELP("Help"),
    ABOUT("About"),
}

/**
 * One searchable control.
 *
 * [id] is the stable handle the UI uses to decide whether to draw the row while a search is active;
 * it never reaches the user. [keywords] are the alternative words for the same idea — synonyms
 * ("colour" for accent), the jargon ("squircle"), and the thing the setting is *about* rather than
 * what it is called ("sleep" for double-tap to lock).
 */
data class SettingsEntry(
    val id: String,
    val section: DuoSettingsSection,
    val title: String,
    val keywords: List<String> = emptyList(),
)

/** The row ids, as constants, so the screen and the catalog cannot drift apart over a typo. */
object SettingsIds {
    const val WALLPAPER_SOURCE = "wallpaper.source"
    const val WALLPAPER_PHOTO = "wallpaper.photo"
    const val WALLPAPER_RESET = "wallpaper.reset"
    const val WALLPAPER_DIM = "wallpaper.dim"
    const val WALLPAPER_ANDROID = "wallpaper.android"

    const val APPEARANCE_MODE = "appearance.mode"
    const val APPEARANCE_GLASS = "appearance.glass"
    const val APPEARANCE_REDUCE_TRANSPARENCY = "appearance.reduceTransparency"
    const val APPEARANCE_ACCENT = "appearance.accent"
    const val APPEARANCE_FONT = "appearance.font"

    const val ICONS_APPEARANCE = "icons.appearance"
    const val ICONS_TINT = "icons.tint"
    const val ICONS_SHAPE = "icons.shape"
    const val ICONS_LARGE = "icons.large"
    const val ICONS_LABELS = "icons.labels"
    const val ICONS_PACK = "icons.pack"

    const val HOME_LAYOUT_MODE = "home.layoutMode"
    const val HOME_GRID = "home.grid"
    const val HOME_ICON_SIZE = "home.iconSize"
    const val HOME_ROW_GAP = "home.rowGap"
    const val HOME_DOCK_SIDE = "home.dockSide"
    const val HOME_DOCK_CAPACITY = "home.dockCapacity"
    const val HOME_DOCK_WIDTH = "home.dockWidth"
    const val HOME_STATUS = "home.status"
    const val HOME_LOCK = "home.lock"
    const val HOME_AUTO_ADD = "home.autoAdd"
    const val HOME_UNDO = "home.undo"

    const val TODAY_LEADING_PAGE = "today.leadingPage"

    const val LIBRARY_VIEW = "library.view"
    const val SEARCH_CONTACTS = "search.contacts"
    const val SEARCH_SUGGESTIONS = "search.suggestions"
    const val SEARCH_CLEAR_HISTORY = "search.clearHistory"
    const val SEARCH_GOOGLE = "search.google"

    const val GESTURE_SWIPE_DOWN = "gestures.swipeDown"
    const val GESTURE_SWIPE_UP = "gestures.swipeUp"
    const val GESTURE_DOUBLE_TAP = "gestures.doubleTap"

    const val BADGE_STYLE = "badges.style"
    const val BADGE_ACCESS = "badges.access"

    const val HIDDEN_APPS = "hidden.apps"

    const val PRIVATE_STATE = "private.state"
    const val PRIVATE_HIDE = "private.hide"

    const val BACKUP_EXPORT = "backup.export"
    const val BACKUP_IMPORT = "backup.import"

    const val HELP_OPEN = "help.open"
    const val HELP_HOME_APP = "help.homeApp"

    const val ABOUT_VERSION = "about.version"
    const val ABOUT_PRIVACY = "about.privacy"
}

/** Every searchable row in Duo Settings. */
object DuoSettingsCatalog {

    val entries: List<SettingsEntry> = listOf(
        // ---- Wallpaper (FR-3, FR-4, FR-9) ----
        SettingsEntry(
            SettingsIds.WALLPAPER_SOURCE, DuoSettingsSection.WALLPAPER, "Wallpaper source",
            listOf("wallpaper", "background", "duo", "system", "photo", "blur"),
        ),
        SettingsEntry(
            SettingsIds.WALLPAPER_PHOTO, DuoSettingsSection.WALLPAPER, "Choose a photo",
            listOf("photo", "picture", "image", "background", "wallpaper"),
        ),
        SettingsEntry(
            SettingsIds.WALLPAPER_RESET, DuoSettingsSection.WALLPAPER, "Reset to Duo dunes",
            listOf("reset", "dunes", "default", "background", "wallpaper"),
        ),
        SettingsEntry(
            SettingsIds.WALLPAPER_DIM, DuoSettingsSection.WALLPAPER, "Dim wallpaper in dark mode",
            listOf("dim", "dark", "scrim", "brightness", "wallpaper", "night"),
        ),
        SettingsEntry(
            SettingsIds.WALLPAPER_ANDROID, DuoSettingsSection.WALLPAPER, "Android wallpaper",
            listOf("android", "system", "wallpaper", "preview", "change"),
        ),

        // ---- Appearance (FR-5, FR-6, FR-8, FR-13) ----
        SettingsEntry(
            SettingsIds.APPEARANCE_MODE, DuoSettingsSection.APPEARANCE, "Dark mode",
            listOf("dark", "light", "night", "theme", "appearance", "sunrise", "sunset", "schedule"),
        ),
        SettingsEntry(
            SettingsIds.APPEARANCE_GLASS, DuoSettingsSection.APPEARANCE, "Glass",
            listOf("glass", "transparency", "translucent", "blur", "clear", "tinted", "liquid", "frosted"),
        ),
        SettingsEntry(
            SettingsIds.APPEARANCE_REDUCE_TRANSPARENCY, DuoSettingsSection.APPEARANCE, "Reduce transparency",
            listOf("reduce", "transparency", "accessibility", "opaque", "blur", "contrast"),
        ),
        SettingsEntry(
            SettingsIds.APPEARANCE_ACCENT, DuoSettingsSection.APPEARANCE, "Accent color",
            listOf("accent", "color", "colour", "tint", "dynamic", "hue", "highlight"),
        ),
        SettingsEntry(
            SettingsIds.APPEARANCE_FONT, DuoSettingsSection.APPEARANCE, "Font",
            listOf("font", "typeface", "text", "inter", "system", "type", "letters"),
        ),

        // ---- Icons (FR-14 to FR-19) ----
        SettingsEntry(
            SettingsIds.ICONS_APPEARANCE, DuoSettingsSection.ICONS, "Icon appearance",
            listOf("icon", "appearance", "dark", "clear", "tinted", "themed", "monochrome", "style"),
        ),
        SettingsEntry(
            SettingsIds.ICONS_TINT, DuoSettingsSection.ICONS, "Icon tint",
            listOf("tint", "color", "colour", "intensity", "themed", "monochrome"),
        ),
        SettingsEntry(
            SettingsIds.ICONS_SHAPE, DuoSettingsSection.ICONS, "Icon shape",
            listOf("shape", "squircle", "circle", "square", "rounded", "scallop", "cookie", "mask"),
        ),
        SettingsEntry(
            SettingsIds.ICONS_LARGE, DuoSettingsSection.ICONS, "Large icons",
            listOf("large", "big", "size", "labels", "icons"),
        ),
        SettingsEntry(
            SettingsIds.ICONS_LABELS, DuoSettingsSection.ICONS, "Show app names",
            listOf("labels", "names", "text", "titles", "captions"),
        ),
        SettingsEntry(
            SettingsIds.ICONS_PACK, DuoSettingsSection.ICONS, "Icon pack",
            listOf("icon pack", "pack", "theme", "nova", "adw", "icons"),
        ),

        // ---- Home Screen and Dock (FR-31, FR-32, FR-37, FR-38, FR-41, FR-49, FR-50) ----
        SettingsEntry(
            SettingsIds.HOME_LAYOUT_MODE, DuoSettingsSection.HOME_AND_DOCK, "Layout mode",
            listOf("layout", "mirrored", "separate", "cover", "inner", "screens", "fold"),
        ),
        SettingsEntry(
            SettingsIds.HOME_GRID, DuoSettingsSection.HOME_AND_DOCK, "Home grid",
            listOf("grid", "rows", "columns", "size", "layout", "how many apps"),
        ),
        SettingsEntry(
            SettingsIds.HOME_ICON_SIZE, DuoSettingsSection.HOME_AND_DOCK, "App icon size",
            listOf("icon size", "size", "bigger", "smaller", "scale"),
        ),
        SettingsEntry(
            SettingsIds.HOME_ROW_GAP, DuoSettingsSection.HOME_AND_DOCK, "Space between rows",
            listOf("spacing", "gap", "rows", "density", "padding"),
        ),
        SettingsEntry(
            SettingsIds.HOME_DOCK_SIDE, DuoSettingsSection.HOME_AND_DOCK, "Dock side",
            listOf("dock", "side", "left", "right", "rail", "edge"),
        ),
        SettingsEntry(
            SettingsIds.HOME_DOCK_CAPACITY, DuoSettingsSection.HOME_AND_DOCK, "Apps in the dock",
            listOf("dock", "capacity", "slots", "apps", "how many"),
        ),
        SettingsEntry(
            SettingsIds.HOME_DOCK_WIDTH, DuoSettingsSection.HOME_AND_DOCK, "Dock width",
            listOf("dock", "width", "size", "rail"),
        ),
        SettingsEntry(
            SettingsIds.HOME_STATUS, DuoSettingsSection.HOME_AND_DOCK, "Duo status",
            listOf("status", "clock", "battery", "wifi", "cluster", "corner", "time"),
        ),
        SettingsEntry(
            SettingsIds.HOME_LOCK, DuoSettingsSection.HOME_AND_DOCK, "Lock Home layout",
            listOf("lock", "layout", "locked", "freeze", "prevent", "accidental"),
        ),
        SettingsEntry(
            SettingsIds.HOME_AUTO_ADD, DuoSettingsSection.HOME_AND_DOCK, "Add new apps to Home",
            listOf("auto", "add", "new apps", "install", "automatically"),
        ),
        SettingsEntry(
            SettingsIds.HOME_UNDO, DuoSettingsSection.HOME_AND_DOCK, "Undo last layout change",
            listOf("undo", "revert", "restore", "mistake", "back"),
        ),

        // ---- Today View (FR-55) ----
        SettingsEntry(
            SettingsIds.TODAY_LEADING_PAGE, DuoSettingsSection.TODAY, "Leading page",
            listOf("today", "discover", "classic", "widgets", "left page", "first page", "google feed"),
        ),

        // ---- App Library and Search (FR-69, FR-72, FR-84) ----
        SettingsEntry(
            SettingsIds.LIBRARY_VIEW, DuoSettingsSection.LIBRARY_AND_SEARCH, "App Library view",
            listOf("library", "categories", "a-z", "alphabetical", "drawer", "view", "all apps"),
        ),
        SettingsEntry(
            SettingsIds.SEARCH_CONTACTS, DuoSettingsSection.LIBRARY_AND_SEARCH, "Contacts in Search",
            listOf("contacts", "people", "search", "permission", "phone book"),
        ),
        SettingsEntry(
            SettingsIds.SEARCH_SUGGESTIONS, DuoSettingsSection.LIBRARY_AND_SEARCH, "Suggestions",
            listOf("suggestions", "suggested", "predicted", "recent", "history", "smart"),
        ),
        SettingsEntry(
            SettingsIds.SEARCH_CLEAR_HISTORY, DuoSettingsSection.LIBRARY_AND_SEARCH, "Clear suggestion history",
            listOf("clear", "history", "suggestions", "delete", "reset", "privacy"),
        ),
        SettingsEntry(
            SettingsIds.SEARCH_GOOGLE, DuoSettingsSection.LIBRARY_AND_SEARCH, "Search button opens Google",
            listOf("google", "search", "web", "button"),
        ),

        // ---- Gestures (FR-51, FR-52, FR-53) ----
        SettingsEntry(
            SettingsIds.GESTURE_SWIPE_DOWN, DuoSettingsSection.GESTURES, "Swipe down",
            listOf("swipe", "down", "search", "notifications", "shade", "quick settings", "gesture"),
        ),
        SettingsEntry(
            SettingsIds.GESTURE_SWIPE_UP, DuoSettingsSection.GESTURES, "Swipe up",
            listOf("swipe", "up", "app library", "drawer", "all apps", "gesture"),
        ),
        SettingsEntry(
            SettingsIds.GESTURE_DOUBLE_TAP, DuoSettingsSection.GESTURES, "Double-tap to lock",
            listOf("double tap", "lock", "sleep", "screen off", "accessibility", "gesture"),
        ),

        // ---- Badges (FR-20, FR-23) ----
        SettingsEntry(
            SettingsIds.BADGE_STYLE, DuoSettingsSection.BADGES, "Badge style",
            listOf("badge", "badges", "dot", "number", "count", "notification", "unread"),
        ),
        SettingsEntry(
            SettingsIds.BADGE_ACCESS, DuoSettingsSection.BADGES, "Notification access",
            listOf("badge", "badges", "notification", "access", "permission", "turn on", "restricted"),
        ),

        // ---- Hidden apps (FR-75) ----
        SettingsEntry(
            SettingsIds.HIDDEN_APPS, DuoSettingsSection.HIDDEN_APPS, "Hidden apps",
            listOf("hidden", "hide", "unhide", "apps", "conceal"),
        ),

        // ---- Private space (FR-76, FR-78) ----
        SettingsEntry(
            SettingsIds.PRIVATE_STATE, DuoSettingsSection.PRIVATE_SPACE, "Private space",
            listOf("private", "space", "profile", "locked", "secure", "hidden profile"),
        ),
        SettingsEntry(
            SettingsIds.PRIVATE_HIDE, DuoSettingsSection.PRIVATE_SPACE, "Hide private space",
            listOf("hide", "private", "container", "conceal", "space"),
        ),

        // ---- Backup (FR-82) ----
        SettingsEntry(
            SettingsIds.BACKUP_EXPORT, DuoSettingsSection.BACKUP, "Save layout",
            listOf("backup", "save", "export", "layout", "copy"),
        ),
        SettingsEntry(
            SettingsIds.BACKUP_IMPORT, DuoSettingsSection.BACKUP, "Restore layout",
            listOf("restore", "import", "backup", "layout", "recover"),
        ),

        // ---- Help ----
        SettingsEntry(
            SettingsIds.HELP_OPEN, DuoSettingsSection.HELP, "Help",
            listOf("help", "guide", "how to", "setup", "support", "widgets", "discover"),
        ),
        SettingsEntry(
            SettingsIds.HELP_HOME_APP, DuoSettingsSection.HELP, "Home app",
            listOf("home app", "default", "launcher", "set as home", "duo"),
        ),

        // ---- About ----
        SettingsEntry(
            SettingsIds.ABOUT_VERSION, DuoSettingsSection.ABOUT, "Version",
            listOf("about", "version", "build", "release"),
        ),
        SettingsEntry(
            SettingsIds.ABOUT_PRIVACY, DuoSettingsSection.ABOUT, "Privacy",
            listOf("privacy", "permissions", "data", "offline", "internet"),
        ),
    )

    /** Rows per section, keyed for the screen and for [SettingsVisibility]. */
    val bySection: Map<DuoSettingsSection, List<SettingsEntry>> = entries.groupBy { it.section }

    /** The sections in spec order. Never sorted at the call site. */
    val sections: List<DuoSettingsSection> = DuoSettingsSection.entries.toList()
}

/** One search result: the row that matched and what the match was worth. */
data class SettingsHit(val entry: SettingsEntry, val score: Int)

/**
 * Matches the settings catalog (AC-64).
 *
 * A query is compared against three things per row: the row's own title, its keywords, and the
 * title of the section it lives in. The section title is what makes typing "badge" surface the
 * **Badges** section as a whole rather than only the rows that happen to repeat the word, which is
 * exactly what AC-64 asks for.
 */
class SettingsSearchIndex(
    entries: List<SettingsEntry> = DuoSettingsCatalog.entries,
    private val locale: Locale = Locale.getDefault(),
) {
    private class IndexedEntry(
        val entry: SettingsEntry,
        val title: IndexedText,
        val keywords: List<IndexedText>,
    )

    private val indexed: List<IndexedEntry> = entries.map { entry ->
        IndexedEntry(
            entry = entry,
            title = IndexedText.of(entry.title, locale),
            keywords = entry.keywords.map { IndexedText.of(it, locale) },
        )
    }

    private val sectionTitles: Map<DuoSettingsSection, IndexedText> =
        DuoSettingsSection.entries.associateWith { IndexedText.of(it.title, locale) }

    /** Rows matching [text], best first. Empty for a blank query. */
    fun hits(text: String): List<SettingsHit> {
        val query = SearchQuery.of(text, locale)
        if (query.isBlank) return emptyList()
        val matchedSections = sectionTitles.filterValues { matchLabel(query, it) != null }.keys
        val results = ArrayList<SettingsHit>(indexed.size)
        for (candidate in indexed) {
            val direct = bestMatch(
                matchLabel(query, candidate.title),
                matchKeywords(query, candidate.keywords),
            )
            val score = when {
                direct != null -> direct.score
                // A section-title match carries its rows, below any row that matched on its own.
                candidate.entry.section in matchedSections -> SECTION_MATCH_SCORE
                else -> continue
            }
            results += SettingsHit(candidate.entry, score)
        }
        results.sortWith(
            compareByDescending<SettingsHit> { it.score }
                .thenBy { it.entry.section.ordinal }
                .thenBy { it.entry.id },
        )
        return results
    }

    /**
     * The ids to draw, or **null** when the query is blank, which means "no filter — draw
     * everything". Null rather than "all ids" so the screen can tell searching from not searching.
     */
    fun matchingIds(text: String): Set<String>? {
        if (SearchQuery.of(text, locale).isBlank) return null
        return hits(text).mapTo(LinkedHashSet()) { it.entry.id }
    }

    /** The sections that have at least one matching row, in spec order. */
    fun sections(text: String): List<DuoSettingsSection> {
        val ids = matchingIds(text) ?: return DuoSettingsCatalog.sections
        val present = DuoSettingsCatalog.entries.filter { it.id in ids }.mapTo(HashSet()) { it.section }
        return DuoSettingsCatalog.sections.filter { it in present }
    }

    private companion object {
        /** Below the lowest tier a real row match can earn, so rows always outrank their section. */
        const val SECTION_MATCH_SCORE = -1
    }
}

/**
 * Which rows the screen draws right now.
 *
 * The screen asks [shows] per row and [showsSection] per group, so filtering is decided in one
 * place and every section composable stays a plain list of conditional rows.
 */
class SettingsVisibility(private val matched: Set<String>?) {

    /** True while a query is filtering the screen. */
    val searching: Boolean get() = matched != null

    /** True when nothing at all matched, which is the screen's "No settings found" state. */
    val isEmpty: Boolean get() = matched != null && matched.isEmpty()

    fun shows(id: String): Boolean = matched == null || id in matched

    fun showsSection(section: DuoSettingsSection): Boolean =
        matched == null || DuoSettingsCatalog.bySection[section].orEmpty().any { it.id in matched }

    companion object {
        /** No search: everything is visible. */
        val All = SettingsVisibility(null)
    }
}
