package com.jake.duolauncher.library

import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.profiles.PrivateSpaceState

/**
 * The pure half of the App Library UI (FR-68, FR-69, FR-70, FR-75, FR-77).
 *
 * Everything here is framework-free so the decisions that are easy to get subtly wrong — which apps
 * a group tile shows, which letter the scrubber lands on, and above all *which apps are allowed to
 * be rendered at all* — are exercisable from plain JVM unit tests rather than only from an
 * instrumented run.
 */

/** The two App Library presentations of FR-68 and FR-69. The choice is remembered (FR-69). */
enum class LibraryView {
    /** FR-68's glass category groups. The default presentation. */
    CATEGORIES,

    /** FR-69's A–Z list with section headers and the side letter scrubber. */
    AZ,
    ;

    fun toggled(): LibraryView = if (this == CATEGORIES) AZ else CATEGORIES
}

/**
 * The per-app facts the App Library needs that [AppEntry] does not carry today.
 *
 * `AppEntry` has no `ApplicationInfo.category` and no `firstInstallTime`, so until the catalog
 * publishes them the library degrades honestly rather than inventing values: an unknown category
 * falls through [PackageNameCategoryFallback] to Other (the spec's "App with no category" row), and
 * an unknown install time means "not recently added" — never "added today".
 */
data class LibraryAppFacts(
    /** Mirrors `ApplicationInfo.category`; see [AppCategories]. */
    val category: Int = AppCategories.UNDEFINED,
    /** `PackageInfo.firstInstallTime` in epoch millis. `0` means unknown. */
    val installedAt: Long = 0L,
    /** True when the app belongs to a private profile (FR-76, FR-77). */
    val isPrivate: Boolean = false,
) {
    companion object {
        /** What every app looks like before the catalog supplies categories and install times. */
        val Unknown = LibraryAppFacts()
    }
}

/** Maps the launcher's catalog into the library's own model. */
fun libraryAppsOf(
    apps: List<AppEntry>,
    facts: (AppEntry) -> LibraryAppFacts = { LibraryAppFacts.Unknown },
): List<LibraryApp> = apps.map { entry ->
    val known = facts(entry)
    LibraryApp(
        id = entry.id,
        label = entry.label,
        packageName = entry.packageName,
        category = known.category,
        installedAt = known.installedAt,
        isWork = entry.isWork,
        isPrivate = known.isPrivate,
    )
}

/**
 * One category tile's contents (FR-68): a few large, directly launchable icons plus a mini grid
 * that opens the whole group.
 *
 * A group that fits entirely in the tile shows only large icons and no mini grid, which is what
 * iOS does and what stops a two-app group rendering a mostly-empty 2×2.
 */
data class GroupTile(
    /** Directly tappable icons, drawn large. */
    val large: List<LibraryApp>,
    /** The 4-icon mini grid. Empty when the whole group already fits in [large]. */
    val mini: List<LibraryApp>,
    /** Apps in the group beyond those drawn on the tile at all. */
    val overflow: Int,
) {
    /** True when this tile stands for more apps than it draws, so it must offer a way in. */
    val opensGroup: Boolean get() = mini.isNotEmpty()
}

/**
 * Splits a group across the tile's [cells] slots.
 *
 * When the group fits, every slot is a large icon. When it does not, the last slot becomes the
 * mini grid, so the tile always leads with the most-relevant apps and never hides the way in.
 */
fun groupTileOf(apps: List<LibraryApp>, cells: Int = TILE_CELLS, miniCells: Int = TILE_MINI_CELLS): GroupTile {
    if (cells <= 0) return GroupTile(emptyList(), emptyList(), apps.size)
    if (apps.size <= cells) return GroupTile(apps, emptyList(), 0)
    val large = apps.take(cells - 1)
    val mini = apps.drop(cells - 1).take(miniCells)
    return GroupTile(large, mini, (apps.size - large.size - mini.size).coerceAtLeast(0))
}

/** Large icon slots on a category tile. */
const val TILE_CELLS = 4

/** Icons in a tile's mini grid (FR-68: "a 4-icon mini grid"). */
const val TILE_MINI_CELLS = 4

/**
 * Which scrubber stop a finger at [y] is over (FR-69).
 *
 * The track is divided into equal slots rather than matched against each letter's drawn bounds, so
 * the letter follows the finger continuously even past the ends of the strip — dragging above the
 * first letter or below the last one clamps instead of losing the gesture.
 */
fun scrubberIndexFor(y: Float, height: Float, stops: Int): Int {
    if (stops <= 0) return -1
    if (height <= 0f || !y.isFinite()) return 0
    val slot = height / stops
    if (slot <= 0f) return 0
    return (y / slot).toInt().coerceIn(0, stops - 1)
}

/**
 * The profile chip's filter (FR-70). With no work profile present there is nothing to choose
 * between, so every app is shown and no chips are drawn.
 */
fun profileFilterFor(hasWork: Boolean, showWork: Boolean): ProfileFilter = when {
    !hasWork -> ProfileFilter.ALL
    showWork -> ProfileFilter.WORK
    else -> ProfileFilter.PERSONAL
}

/**
 * Hardens the host's exclusions against the one mistake that would be a privacy bug rather than a
 * display bug (FR-75, FR-77).
 *
 * The host is expected to pass a [LibraryExclusions] already wired to the hidden-app store and to
 * `PrivateSpaceGate`. This wraps it so that a private-space app can never be rendered while the
 * space is not positively unlocked, even if the host passed [LibraryExclusions.None] or wired the
 * gate incorrectly. The guard is additive: it only ever excludes more, never less.
 */
fun withPrivateGuard(base: LibraryExclusions, privateUnlocked: Boolean): LibraryExclusions =
    LibraryExclusions(
        isHidden = base.isHidden,
        isPrivateLocked = { app -> base.isPrivateLocked(app) || (app.isPrivate && !privateUnlocked) },
    )

/** [withPrivateGuard] for a repository state: only [PrivateSpaceState.Unlocked] counts as unlocked. */
fun withPrivateGuard(base: LibraryExclusions, state: PrivateSpaceState): LibraryExclusions =
    withPrivateGuard(base, state is PrivateSpaceState.Unlocked)
