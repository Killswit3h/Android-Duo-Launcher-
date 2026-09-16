package com.jake.duolauncher.library

import java.text.Collator
import java.util.Locale

/**
 * Resolves a group for an app whose `ApplicationInfo.category` is `CATEGORY_UNDEFINED`.
 * Returning `null` means "no confident guess", which lands the app in [LibraryGroup.OTHER] —
 * the behavior the spec's error table requires for an app with no category.
 */
fun interface CategoryFallback {
    fun categoryFor(app: LibraryApp): LibraryGroup?

    companion object {
        /** Never guesses: every uncategorized app goes to Other. */
        val None: CategoryFallback = CategoryFallback { null }
    }
}

/**
 * Package-name heuristic for uncategorized apps. It deliberately ignores the label, which is
 * locale-dependent and would make grouping differ between devices.
 *
 * Resolution order (first match wins, so the result is deterministic):
 *  1. an exact package match in [exact];
 *  2. the first substring in [keywords] that the lowercase package name contains, in declaration order;
 *  3. `null` → the caller uses Other.
 */
object PackageNameCategoryFallback : CategoryFallback {
    private val exact: Map<String, LibraryGroup> = mapOf(
        "com.android.vending" to LibraryGroup.UTILITIES,
        "com.android.documentsui" to LibraryGroup.UTILITIES,
        "com.google.android.gms" to LibraryGroup.UTILITIES,
        "com.google.android.gm" to LibraryGroup.PRODUCTIVITY,
        "com.google.android.googlequicksearchbox" to LibraryGroup.INFORMATION,
    )

    private val keywords: List<Pair<String, LibraryGroup>> = listOf(
        "game" to LibraryGroup.GAMES,
        "puzzle" to LibraryGroup.GAMES,
        "arcade" to LibraryGroup.GAMES,
        "solitaire" to LibraryGroup.GAMES,
        "sudoku" to LibraryGroup.GAMES,
        "minecraft" to LibraryGroup.GAMES,
        "roblox" to LibraryGroup.GAMES,
        "messag" to LibraryGroup.SOCIAL,
        "messenger" to LibraryGroup.SOCIAL,
        "whatsapp" to LibraryGroup.SOCIAL,
        "telegram" to LibraryGroup.SOCIAL,
        "signal" to LibraryGroup.SOCIAL,
        "instagram" to LibraryGroup.SOCIAL,
        "facebook" to LibraryGroup.SOCIAL,
        "snapchat" to LibraryGroup.SOCIAL,
        "tiktok" to LibraryGroup.SOCIAL,
        "discord" to LibraryGroup.SOCIAL,
        "reddit" to LibraryGroup.SOCIAL,
        "mastodon" to LibraryGroup.SOCIAL,
        "linkedin" to LibraryGroup.SOCIAL,
        "dialer" to LibraryGroup.SOCIAL,
        "contacts" to LibraryGroup.SOCIAL,
        "youtube" to LibraryGroup.ENTERTAINMENT,
        "netflix" to LibraryGroup.ENTERTAINMENT,
        "spotify" to LibraryGroup.ENTERTAINMENT,
        "soundcloud" to LibraryGroup.ENTERTAINMENT,
        "deezer" to LibraryGroup.ENTERTAINMENT,
        "twitch" to LibraryGroup.ENTERTAINMENT,
        "disney" to LibraryGroup.ENTERTAINMENT,
        "podcast" to LibraryGroup.ENTERTAINMENT,
        "music" to LibraryGroup.ENTERTAINMENT,
        "video" to LibraryGroup.ENTERTAINMENT,
        "player" to LibraryGroup.ENTERTAINMENT,
        "radio" to LibraryGroup.ENTERTAINMENT,
        "camera" to LibraryGroup.CREATIVITY,
        "photo" to LibraryGroup.CREATIVITY,
        "gallery" to LibraryGroup.CREATIVITY,
        "lightroom" to LibraryGroup.CREATIVITY,
        "snapseed" to LibraryGroup.CREATIVITY,
        "canva" to LibraryGroup.CREATIVITY,
        "figma" to LibraryGroup.CREATIVITY,
        "procreate" to LibraryGroup.CREATIVITY,
        "draw" to LibraryGroup.CREATIVITY,
        "paint" to LibraryGroup.CREATIVITY,
        "sketch" to LibraryGroup.CREATIVITY,
        "gmail" to LibraryGroup.PRODUCTIVITY,
        "outlook" to LibraryGroup.PRODUCTIVITY,
        "mail" to LibraryGroup.PRODUCTIVITY,
        "calendar" to LibraryGroup.PRODUCTIVITY,
        "docs" to LibraryGroup.PRODUCTIVITY,
        "sheets" to LibraryGroup.PRODUCTIVITY,
        "slides" to LibraryGroup.PRODUCTIVITY,
        "drive" to LibraryGroup.PRODUCTIVITY,
        "office" to LibraryGroup.PRODUCTIVITY,
        "powerpoint" to LibraryGroup.PRODUCTIVITY,
        "notion" to LibraryGroup.PRODUCTIVITY,
        "evernote" to LibraryGroup.PRODUCTIVITY,
        "keep" to LibraryGroup.PRODUCTIVITY,
        "note" to LibraryGroup.PRODUCTIVITY,
        "todo" to LibraryGroup.PRODUCTIVITY,
        "task" to LibraryGroup.PRODUCTIVITY,
        "trello" to LibraryGroup.PRODUCTIVITY,
        "asana" to LibraryGroup.PRODUCTIVITY,
        "slack" to LibraryGroup.PRODUCTIVITY,
        "teams" to LibraryGroup.PRODUCTIVITY,
        "zoom" to LibraryGroup.PRODUCTIVITY,
        "webex" to LibraryGroup.PRODUCTIVITY,
        "dropbox" to LibraryGroup.PRODUCTIVITY,
        "onedrive" to LibraryGroup.PRODUCTIVITY,
        "news" to LibraryGroup.INFORMATION,
        "weather" to LibraryGroup.INFORMATION,
        "maps" to LibraryGroup.INFORMATION,
        "navigation" to LibraryGroup.INFORMATION,
        "browser" to LibraryGroup.INFORMATION,
        "chrome" to LibraryGroup.INFORMATION,
        "firefox" to LibraryGroup.INFORMATION,
        "opera" to LibraryGroup.INFORMATION,
        "duckduckgo" to LibraryGroup.INFORMATION,
        "wikipedia" to LibraryGroup.INFORMATION,
        "translate" to LibraryGroup.INFORMATION,
        "books" to LibraryGroup.INFORMATION,
        "kindle" to LibraryGroup.INFORMATION,
        "search" to LibraryGroup.INFORMATION,
        "settings" to LibraryGroup.UTILITIES,
        "clock" to LibraryGroup.UTILITIES,
        "alarm" to LibraryGroup.UTILITIES,
        "calculator" to LibraryGroup.UTILITIES,
        "files" to LibraryGroup.UTILITIES,
        "filemanager" to LibraryGroup.UTILITIES,
        "explorer" to LibraryGroup.UTILITIES,
        "scanner" to LibraryGroup.UTILITIES,
        "flashlight" to LibraryGroup.UTILITIES,
        "torch" to LibraryGroup.UTILITIES,
        "vpn" to LibraryGroup.UTILITIES,
        "bluetooth" to LibraryGroup.UTILITIES,
        "wifi" to LibraryGroup.UTILITIES,
        "battery" to LibraryGroup.UTILITIES,
        "cleaner" to LibraryGroup.UTILITIES,
        "recorder" to LibraryGroup.UTILITIES,
        "compass" to LibraryGroup.UTILITIES,
        "wallet" to LibraryGroup.UTILITIES,
        "installer" to LibraryGroup.UTILITIES,
        "backup" to LibraryGroup.UTILITIES,
        "print" to LibraryGroup.UTILITIES,
    )

    override fun categoryFor(app: LibraryApp): LibraryGroup? {
        val pkg = app.packageName.lowercase(Locale.ROOT)
        if (pkg.isEmpty()) return null
        exact[pkg]?.let { return it }
        return keywords.firstOrNull { (keyword, _) -> pkg.contains(keyword) }?.second
    }
}

/**
 * Groups the App Library into the glass category groups of FR-68.
 *
 * **Group order** is fixed by [LibraryGroup]'s declaration order: Suggestions, Recently Added, then
 * Social, Productivity, Games, Entertainment, Creativity, Information, Utilities, Other. Empty
 * groups are omitted.
 *
 * **Order inside a group**
 *  - Suggestions: the provider's ranking, untouched, capped at [suggestionCount].
 *  - Recently Added: newest install first; ties fall back to the label/id rule below.
 *  - Every category group: label order under a locale [Collator], ties broken by app id so the
 *    result is total and stable regardless of input order.
 *
 * An app can appear in more than one group: Suggestions and Recently Added are views onto the same
 * catalog, so a freshly installed, frequently launched app shows up in all three of Suggestions,
 * Recently Added and its category group — as it does on iOS.
 */
class CategoryGrouper(
    private val locale: Locale = Locale.getDefault(),
    private val suggestions: LibrarySuggestionsProvider = LibrarySuggestionsProvider.None,
    private val exclusions: LibraryExclusions = LibraryExclusions.None,
    private val fallback: CategoryFallback = PackageNameCategoryFallback,
    private val suggestionCount: Int = DEFAULT_SUGGESTION_COUNT,
    private val recentlyAddedWindowMs: Long = RECENTLY_ADDED_WINDOW_MS,
) {

    /**
     * The category group for a single app. `CATEGORY_UNDEFINED` — and any category constant this
     * build does not know — goes through [fallback], and lands in [LibraryGroup.OTHER] when the
     * fallback has no confident answer.
     */
    fun groupOf(app: LibraryApp): LibraryGroup = when (app.category) {
        AppCategories.GAME -> LibraryGroup.GAMES
        AppCategories.AUDIO, AppCategories.VIDEO -> LibraryGroup.ENTERTAINMENT
        AppCategories.IMAGE -> LibraryGroup.CREATIVITY
        AppCategories.SOCIAL -> LibraryGroup.SOCIAL
        AppCategories.NEWS, AppCategories.MAPS -> LibraryGroup.INFORMATION
        AppCategories.PRODUCTIVITY -> LibraryGroup.PRODUCTIVITY
        AppCategories.ACCESSIBILITY -> LibraryGroup.UTILITIES
        else -> fallback.categoryFor(app)?.takeIf { it in LibraryGroup.categories } ?: LibraryGroup.OTHER
    }

    /** True when [app] was installed inside the Recently Added window ending at [now]. */
    fun isRecentlyAdded(app: LibraryApp, now: Long): Boolean =
        app.installedAt > 0L && now - app.installedAt <= recentlyAddedWindowMs

    /**
     * The whole App Library, grouped. Hidden apps, locked private-space apps, the inactive profile
     * and anything not matching the search text are gone before grouping starts.
     */
    fun group(
        apps: List<LibraryApp>,
        query: LibraryQuery = LibraryQuery(),
        now: Long = System.currentTimeMillis(),
    ): List<LibraryGroupContent> {
        val visible = visibleLibraryApps(apps, query, exclusions)
        if (visible.isEmpty()) return emptyList()
        val byLabel = labelComparator(locale)
        val groups = ArrayList<LibraryGroupContent>(LibraryGroup.entries.size)

        val byId = LinkedHashMap<String, LibraryApp>(visible.size)
        visible.forEach { byId.putIfAbsent(it.id, it) }
        val suggested = suggestions.suggestedAppIds(suggestionCount)
            .distinct()
            .mapNotNull(byId::get)
            .take(suggestionCount)
        if (suggested.isNotEmpty()) groups += LibraryGroupContent(LibraryGroup.SUGGESTIONS, suggested)

        val recent = visible.filter { isRecentlyAdded(it, now) }
            .sortedWith(compareByDescending<LibraryApp> { it.installedAt }.then(byLabel))
        if (recent.isNotEmpty()) groups += LibraryGroupContent(LibraryGroup.RECENTLY_ADDED, recent)

        val byCategory = visible.groupBy(::groupOf)
        LibraryGroup.categories.forEach { category ->
            val members = byCategory[category] ?: return@forEach
            if (members.isNotEmpty()) groups += LibraryGroupContent(category, members.sortedWith(byLabel))
        }
        return groups
    }

    companion object {
        /** FR-68: "Recently Added (last 14 days)". The boundary is inclusive — exactly 14 days old still counts. */
        const val RECENTLY_ADDED_WINDOW_MS: Long = 14L * 24 * 60 * 60 * 1000

        /** FR-68 shows 3 large icons plus a 4-icon mini grid, so 8 covers a full group tile. */
        const val DEFAULT_SUGGESTION_COUNT: Int = 8
    }
}

/**
 * Locale-aware label ordering with an id tie-break, so equal labels (the same app in two profiles,
 * for instance) still have one deterministic order.
 */
internal fun labelComparator(locale: Locale): Comparator<LibraryApp> {
    val collator = Collator.getInstance(locale)
    return Comparator { a, b ->
        val byLabel = collator.compare(a.label, b.label)
        if (byLabel != 0) byLabel else a.id.compareTo(b.id)
    }
}
