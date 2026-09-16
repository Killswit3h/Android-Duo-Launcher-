package com.jake.duolauncher.library

/**
 * Pure-JVM models behind the App Library (FR-68, FR-69, FR-70).
 *
 * Nothing in this package touches Android or Compose types, so the whole grouping and indexing
 * engine is exercisable from plain JVM unit tests. The launcher maps its own `AppEntry` list into
 * [LibraryApp] once per catalog refresh and hands the result to [CategoryGrouper] / [AlphabetIndexer].
 */
data class LibraryApp(
    /** Stable profile-scoped app id (the `profileAppId` string used everywhere else in the launcher). */
    val id: String,
    /** User-visible label, already localized. */
    val label: String,
    /** Package name, lowercase in practice; used only by the category fallback heuristic. */
    val packageName: String = "",
    /** Mirrors `android.content.pm.ApplicationInfo.category`; see [AppCategories]. */
    val category: Int = AppCategories.UNDEFINED,
    /** `PackageInfo.firstInstallTime` in epoch millis. `0` means "unknown", never "recently added". */
    val installedAt: Long = 0L,
    /** True when the app belongs to a managed (work) profile. */
    val isWork: Boolean = false,
    /** True when the app belongs to a private profile (FR-76, FR-77). */
    val isPrivate: Boolean = false,
)

/**
 * The `ApplicationInfo.CATEGORY_*` constants, mirrored so this package stays framework-free.
 * Values are part of the public Android API and are safe to copy; callers pass
 * `applicationInfo.category` straight through.
 */
object AppCategories {
    const val UNDEFINED = -1
    const val GAME = 0
    const val AUDIO = 1
    const val VIDEO = 2
    const val IMAGE = 3
    const val SOCIAL = 4
    const val NEWS = 5
    const val MAPS = 6
    const val PRODUCTIVITY = 7
    const val ACCESSIBILITY = 8
}

/**
 * Every group the App Library can show, in the order FR-68 lists them. Declaration order *is*
 * display order, so group ordering is deterministic without a separate sort.
 */
enum class LibraryGroup(val title: String) {
    SUGGESTIONS("Suggestions"),
    RECENTLY_ADDED("Recently Added"),
    SOCIAL("Social"),
    PRODUCTIVITY("Productivity"),
    GAMES("Games"),
    ENTERTAINMENT("Entertainment"),
    CREATIVITY("Creativity"),
    INFORMATION("Information"),
    UTILITIES("Utilities"),
    OTHER("Other");

    companion object {
        /** The Android-category groups only, excluding the two synthetic groups. */
        val categories: List<LibraryGroup> =
            listOf(SOCIAL, PRODUCTIVITY, GAMES, ENTERTAINMENT, CREATIVITY, INFORMATION, UTILITIES, OTHER)
    }
}

/** One populated group. Empty groups are never emitted. */
data class LibraryGroupContent(val group: LibraryGroup, val apps: List<LibraryApp>)

/** Personal / Work filtering, preserved from the current All-apps list (FR-70). */
enum class ProfileFilter { ALL, PERSONAL, WORK }

/**
 * The library's filter state: the local substring search plus the profile chip.
 * [text] is trimmed and matched case-insensitively against the label, exactly as the
 * current `AppLibrary` composable does.
 */
data class LibraryQuery(
    val text: String = "",
    val profile: ProfileFilter = ProfileFilter.ALL,
)

/**
 * Injected exclusion predicates. This package never reaches into the hidden-app store or the
 * private-space repository itself; the wiring layer supplies both checks.
 *
 * - [isHidden] — FR-75: hidden apps are gone from the App Library entirely.
 * - [isPrivateLocked] — FR-77: while the private space is locked its apps are gone from every surface.
 */
class LibraryExclusions(
    val isHidden: (LibraryApp) -> Boolean = { false },
    val isPrivateLocked: (LibraryApp) -> Boolean = { false },
) {
    fun excludes(app: LibraryApp): Boolean = isHidden(app) || isPrivateLocked(app)

    companion object {
        /** Excludes nothing; the default for tests and for the pin-picker surface. */
        val None = LibraryExclusions()
    }
}

/**
 * Source of the Suggestions group (FR-68). The launch-history ranker implements this; the grouper
 * only needs an ordered list of app ids and resolves them against the already-filtered catalog, so
 * a hidden, locked-private or filtered-out app can never leak in through a suggestion.
 */
fun interface LibrarySuggestionsProvider {
    /** Ordered best-first; may return fewer than [limit] and may contain unknown ids. */
    fun suggestedAppIds(limit: Int): List<String>

    companion object {
        val None: LibrarySuggestionsProvider = LibrarySuggestionsProvider { emptyList() }
    }
}

/**
 * The single filtering gate every library surface goes through: exclusions first, then the profile
 * chip, then the local substring search. Exposed publicly because the flat search-results list uses
 * it directly.
 */
fun visibleLibraryApps(
    apps: List<LibraryApp>,
    query: LibraryQuery = LibraryQuery(),
    exclusions: LibraryExclusions = LibraryExclusions.None,
): List<LibraryApp> {
    val text = query.text.trim()
    return apps.filter { app ->
        !exclusions.excludes(app) &&
            when (query.profile) {
                ProfileFilter.ALL -> true
                ProfileFilter.PERSONAL -> !app.isWork
                ProfileFilter.WORK -> app.isWork
            } &&
            (text.isEmpty() || app.label.contains(text, ignoreCase = true))
    }
}
