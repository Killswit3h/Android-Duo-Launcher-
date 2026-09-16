package com.jake.duolauncher.library

import com.jake.duolauncher.profiles.PrivateSpaceApp
import com.jake.duolauncher.profiles.PrivateSpaceState
import com.jake.duolauncher.profiles.PrivateSpaceUnsupportedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions behind the App Library UI (FR-68, FR-69, FR-70, FR-75, FR-77). */
class LibraryHostingTest {

    private fun app(id: String, isPrivate: Boolean = false) =
        LibraryApp(id = id, label = id, isPrivate = isPrivate)

    private fun apps(count: Int) = (1..count).map { app("app$it") }

    // ---------------------------------------------------------------- group tiles

    @Test fun aGroupThatFitsTheTileIsAllLargeIconsAndOffersNoMiniGrid() {
        val tile = groupTileOf(apps(4))
        assertEquals(4, tile.large.size)
        assertTrue(tile.mini.isEmpty())
        assertEquals(0, tile.overflow)
        assertFalse(tile.opensGroup)
    }

    @Test fun aGroupSmallerThanTheTileStillFillsOnlyWhatItHas() {
        val tile = groupTileOf(apps(2))
        assertEquals(listOf("app1", "app2"), tile.large.map { it.id })
        assertTrue(tile.mini.isEmpty())
        assertFalse(tile.opensGroup)
    }

    @Test fun anOverflowingGroupShowsThreeLargeIconsPlusAFourIconMiniGrid() {
        // FR-68: "Each group shows 3 large icons plus a 4-icon mini grid."
        val tile = groupTileOf(apps(12))
        assertEquals(listOf("app1", "app2", "app3"), tile.large.map { it.id })
        assertEquals(listOf("app4", "app5", "app6", "app7"), tile.mini.map { it.id })
        assertEquals(5, tile.overflow)
        assertTrue(tile.opensGroup)
    }

    @Test fun aGroupJustOverTheTileReportsNoPhantomOverflow() {
        val tile = groupTileOf(apps(7))
        assertEquals(3, tile.large.size)
        assertEquals(4, tile.mini.size)
        assertEquals(0, tile.overflow)
        assertTrue(tile.opensGroup)
    }

    @Test fun anEmptyGroupProducesAnEmptyTile() {
        val tile = groupTileOf(emptyList())
        assertTrue(tile.large.isEmpty())
        assertTrue(tile.mini.isEmpty())
        assertFalse(tile.opensGroup)
    }

    // ---------------------------------------------------------------- scrubber

    @Test fun theScrubberMapsTheTrackEvenlyOntoItsStops() {
        assertEquals(0, scrubberIndexFor(y = 0f, height = 300f, stops = 3))
        assertEquals(1, scrubberIndexFor(y = 150f, height = 300f, stops = 3))
        assertEquals(2, scrubberIndexFor(y = 299f, height = 300f, stops = 3))
    }

    @Test fun draggingPastEitherEndOfTheScrubberClampsRatherThanLosingTheGesture() {
        assertEquals(0, scrubberIndexFor(y = -80f, height = 300f, stops = 3))
        assertEquals(2, scrubberIndexFor(y = 9_000f, height = 300f, stops = 3))
    }

    @Test fun theScrubberHasNoStopWhenThereAreNoSections() {
        assertEquals(-1, scrubberIndexFor(y = 10f, height = 300f, stops = 0))
    }

    @Test fun theScrubberSurvivesBeingMeasuredBeforeItIsLaidOut() {
        assertEquals(0, scrubberIndexFor(y = 10f, height = 0f, stops = 5))
        assertEquals(0, scrubberIndexFor(y = Float.NaN, height = 300f, stops = 5))
    }

    // ---------------------------------------------------------------- profile filter

    @Test fun theProfileChipsOnlyFilterWhenThereIsAWorkProfileToFilterBy() {
        assertEquals(ProfileFilter.ALL, profileFilterFor(hasWork = false, showWork = false))
        assertEquals(ProfileFilter.ALL, profileFilterFor(hasWork = false, showWork = true))
        assertEquals(ProfileFilter.PERSONAL, profileFilterFor(hasWork = true, showWork = false))
        assertEquals(ProfileFilter.WORK, profileFilterFor(hasWork = true, showWork = true))
    }

    // ---------------------------------------------------------------- the private guard

    @Test fun aPrivateAppIsNeverRenderedWhileTheSpaceIsNotPositivelyUnlocked() {
        // FR-77, as a correctness rule rather than a filter detail: even a host that forgot to wire
        // the gate cannot get a private app onto a library surface.
        val guard = withPrivateGuard(LibraryExclusions.None, privateUnlocked = false)
        assertTrue(guard.excludes(app("vault", isPrivate = true)))
        assertFalse(guard.excludes(app("chrome")))
    }

    @Test fun anUnlockedSpaceStopsExcludingItsOwnApps() {
        val guard = withPrivateGuard(LibraryExclusions.None, privateUnlocked = true)
        assertFalse(guard.excludes(app("vault", isPrivate = true)))
    }

    @Test fun theGuardOnlyEverExcludesMoreThanTheHostAsked() {
        val hidden = LibraryExclusions(isHidden = { it.id == "hidden" })
        val guard = withPrivateGuard(hidden, privateUnlocked = true)
        assertTrue("the host's own hidden rule must survive", guard.excludes(app("hidden")))
        assertFalse(guard.excludes(app("visible")))
    }

    @Test fun onlyTheUnlockedStateCountsAsUnlocked() {
        val locked = withPrivateGuard(LibraryExclusions.None, PrivateSpaceState.Locked)
        val unsupported = withPrivateGuard(
            LibraryExclusions.None,
            PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE),
        )
        val unlocked = withPrivateGuard(
            LibraryExclusions.None,
            PrivateSpaceState.Unlocked(listOf(PrivateSpaceApp("vault", "Vault", "com.vault", 10L))),
        )
        val vault = app("vault", isPrivate = true)
        assertTrue(locked.excludes(vault))
        assertTrue(unsupported.excludes(vault))
        assertFalse(unlocked.excludes(vault))
    }

    @Test fun theGuardKeepsPrivateAppsOutOfEveryGroupedAndSectionedSurface() {
        val catalog = listOf(app("chrome"), app("vault", isPrivate = true))
        val guard = withPrivateGuard(LibraryExclusions.None, privateUnlocked = false)
        val grouped = CategoryGrouper(java.util.Locale.US, LibrarySuggestionsProvider.None, guard)
            .group(catalog)
        val sectioned = AlphabetIndexer(java.util.Locale.US, guard).index(catalog)
        assertFalse(grouped.flatMap { it.apps }.any { it.id == "vault" })
        assertFalse(sectioned.apps().any { it.id == "vault" })
        assertFalse(visibleLibraryApps(catalog, exclusions = guard).any { it.id == "vault" })
    }

    @Test fun aSuggestionCannotSmuggleAPrivateOrHiddenAppOntoTheLibrary() {
        // The grouper resolves suggestion ids against the already-filtered catalog; this pins that
        // the guard is what it resolves against.
        val catalog = listOf(app("chrome"), app("vault", isPrivate = true), app("hidden"))
        val guard = withPrivateGuard(
            LibraryExclusions(isHidden = { it.id == "hidden" }),
            privateUnlocked = false,
        )
        val everything = LibrarySuggestionsProvider { listOf("vault", "hidden", "chrome") }
        val grouped = CategoryGrouper(java.util.Locale.US, everything, guard).group(catalog)
        val suggested = grouped.firstOrNull { it.group == LibraryGroup.SUGGESTIONS }?.apps.orEmpty()
        assertEquals(listOf("chrome"), suggested.map { it.id })
    }

    // ---------------------------------------------------------------- view choice

    @Test fun theViewToggleFlipsBetweenTheTwoPresentations() {
        assertEquals(LibraryView.AZ, LibraryView.CATEGORIES.toggled())
        assertEquals(LibraryView.CATEGORIES, LibraryView.AZ.toggled())
        assertNotEquals(LibraryView.CATEGORIES, LibraryView.AZ)
    }

    // ---------------------------------------------------------------- unsupported reasons

    @Test fun everyUnsupportedReasonHasItsOwnExplanation() {
        val texts = PrivateSpaceUnsupportedReason.entries.map(::privateSpaceReasonText)
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(texts.none { it.isBlank() })
    }
}
