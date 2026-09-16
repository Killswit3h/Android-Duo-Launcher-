package com.jake.duolauncher.library

import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryGrouperTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun app(
        id: String,
        label: String = id,
        pkg: String = "",
        category: Int = AppCategories.UNDEFINED,
        installedAt: Long = 0L,
        isWork: Boolean = false,
        isPrivate: Boolean = false,
    ) = LibraryApp(id, label, pkg, category, installedAt, isWork, isPrivate)

    private fun grouper(
        suggestions: LibrarySuggestionsProvider = LibrarySuggestionsProvider.None,
        exclusions: LibraryExclusions = LibraryExclusions.None,
        fallback: CategoryFallback = PackageNameCategoryFallback,
    ) = CategoryGrouper(Locale.US, suggestions, exclusions, fallback)

    private fun List<LibraryGroupContent>.labelsOf(group: LibraryGroup): List<String> =
        firstOrNull { it.group == group }?.apps?.map { it.label } ?: emptyList()

    @Test fun everyAndroidCategoryMapsToItsSpecGroup() {
        val g = grouper()
        assertEquals(LibraryGroup.GAMES, g.groupOf(app("a", category = AppCategories.GAME)))
        assertEquals(LibraryGroup.ENTERTAINMENT, g.groupOf(app("a", category = AppCategories.AUDIO)))
        assertEquals(LibraryGroup.ENTERTAINMENT, g.groupOf(app("a", category = AppCategories.VIDEO)))
        assertEquals(LibraryGroup.CREATIVITY, g.groupOf(app("a", category = AppCategories.IMAGE)))
        assertEquals(LibraryGroup.SOCIAL, g.groupOf(app("a", category = AppCategories.SOCIAL)))
        assertEquals(LibraryGroup.INFORMATION, g.groupOf(app("a", category = AppCategories.NEWS)))
        assertEquals(LibraryGroup.INFORMATION, g.groupOf(app("a", category = AppCategories.MAPS)))
        assertEquals(LibraryGroup.PRODUCTIVITY, g.groupOf(app("a", category = AppCategories.PRODUCTIVITY)))
        assertEquals(LibraryGroup.UTILITIES, g.groupOf(app("a", category = AppCategories.ACCESSIBILITY)))
    }

    @Test fun anAppWithNoCategoryAndNoHeuristicMatchIsGroupedUnderOther() {
        // Spec error table: "App with no category → Grouped under Other."
        val g = grouper()
        assertEquals(LibraryGroup.OTHER, g.groupOf(app("a", pkg = "com.acme.widgetron")))
        assertEquals(LibraryGroup.OTHER, g.groupOf(app("a", pkg = "")))
        // An unknown future category constant also degrades to the fallback, never to a crash.
        assertEquals(LibraryGroup.OTHER, g.groupOf(app("a", category = 99, pkg = "com.acme.widgetron")))
    }

    @Test fun uncategorizedAppsAreClassifiedByPackageNameHeuristic() {
        val g = grouper()
        assertEquals(LibraryGroup.UTILITIES, g.groupOf(app("a", pkg = "com.android.settings")))
        assertEquals(LibraryGroup.UTILITIES, g.groupOf(app("a", pkg = "com.google.android.deskclock")))
        assertEquals(LibraryGroup.ENTERTAINMENT, g.groupOf(app("a", pkg = "com.google.android.youtube")))
        assertEquals(LibraryGroup.SOCIAL, g.groupOf(app("a", pkg = "com.whatsapp")))
        assertEquals(LibraryGroup.CREATIVITY, g.groupOf(app("a", pkg = "com.google.android.apps.photos")))
        assertEquals(LibraryGroup.INFORMATION, g.groupOf(app("a", pkg = "com.android.chrome")))
        assertEquals(LibraryGroup.PRODUCTIVITY, g.groupOf(app("a", pkg = "com.google.android.gm")))
        assertEquals(LibraryGroup.GAMES, g.groupOf(app("a", pkg = "com.acme.puzzlequest")))
        // The heuristic reads the package only, so the localized label cannot change grouping.
        assertEquals(LibraryGroup.OTHER, g.groupOf(app("a", label = "Chrome", pkg = "com.acme.widgetron")))
    }

    @Test fun heuristicIsDeterministicWhenSeveralKeywordsMatch() {
        // "game" is declared before "photo", so the first match in declaration order wins, every time.
        val g = grouper()
        val both = app("a", pkg = "com.acme.gamephoto")
        assertEquals(LibraryGroup.GAMES, g.groupOf(both))
        repeat(5) { assertEquals(LibraryGroup.GAMES, g.groupOf(both)) }
    }

    @Test fun theHeuristicCanBeTurnedOffSoEveryUncategorizedAppLandsInOther() {
        val strict = grouper(fallback = CategoryFallback.None)
        assertEquals(LibraryGroup.OTHER, strict.groupOf(app("a", pkg = "com.android.settings")))
        assertEquals(LibraryGroup.SOCIAL, strict.groupOf(app("a", pkg = "com.android.settings", category = AppCategories.SOCIAL)))
    }

    @Test fun recentlyAddedIncludesTheExactFourteenDayBoundaryAndNothingOlder() {
        val g = grouper()
        val exactly14 = app("edge", installedAt = now - 14 * day)
        val justOver = app("old", installedAt = now - 14 * day - 1)
        val fresh = app("new", installedAt = now - day)
        val unknown = app("unknown", installedAt = 0L)
        assertTrue(g.isRecentlyAdded(exactly14, now))
        assertFalse(g.isRecentlyAdded(justOver, now))
        assertTrue(g.isRecentlyAdded(fresh, now))
        assertFalse(g.isRecentlyAdded(unknown, now))

        val groups = g.group(listOf(exactly14, justOver, fresh, unknown), now = now)
        assertEquals(listOf("new", "edge"), groups.labelsOf(LibraryGroup.RECENTLY_ADDED))
    }

    @Test fun recentlyAddedIsNewestFirstAndTiesFallBackToLabelOrder() {
        val g = grouper()
        val apps = listOf(
            app("b", label = "Beta", installedAt = now - 2 * day),
            app("a", label = "Alpha", installedAt = now - 2 * day),
            app("c", label = "Gamma", installedAt = now - day),
        )
        assertEquals(listOf("Gamma", "Alpha", "Beta"), g.group(apps, now = now).labelsOf(LibraryGroup.RECENTLY_ADDED))
    }

    @Test fun groupOrderFollowsTheSpecAndEmptyGroupsAreOmitted() {
        val apps = listOf(
            app("u", label = "Utility", category = AppCategories.ACCESSIBILITY),
            app("s", label = "Social", category = AppCategories.SOCIAL),
            app("o", label = "Odd", pkg = "com.acme.widgetron"),
            app("g", label = "Game", category = AppCategories.GAME),
            app("p", label = "Prod", category = AppCategories.PRODUCTIVITY),
            app("r", label = "Recent", category = AppCategories.SOCIAL, installedAt = now - day),
        )
        val order = grouper(suggestions = { listOf("g") }).group(apps, now = now).map { it.group }
        assertEquals(
            listOf(
                LibraryGroup.SUGGESTIONS,
                LibraryGroup.RECENTLY_ADDED,
                LibraryGroup.SOCIAL,
                LibraryGroup.PRODUCTIVITY,
                LibraryGroup.GAMES,
                LibraryGroup.UTILITIES,
                LibraryGroup.OTHER,
            ),
            order,
        )
        assertFalse(LibraryGroup.ENTERTAINMENT in order)
        assertFalse(LibraryGroup.CREATIVITY in order)
        assertFalse(LibraryGroup.INFORMATION in order)
    }

    @Test fun appsInACategoryAreCollatedByLabelWithAnIdTieBreak() {
        val apps = listOf(
            app("2", label = "Banana", category = AppCategories.SOCIAL),
            app("1", label = "Äpple", category = AppCategories.SOCIAL),
            app("0", label = "Apple", category = AppCategories.SOCIAL),
            app("z-dup", label = "Same", category = AppCategories.SOCIAL),
            app("a-dup", label = "Same", category = AppCategories.SOCIAL),
        )
        val social = grouper().group(apps, now = now).first { it.group == LibraryGroup.SOCIAL }
        assertEquals(listOf("Apple", "Äpple", "Banana", "Same", "Same"), social.apps.map { it.label })
        // Equal labels (same app in two profiles) still have one deterministic order.
        assertEquals(listOf("a-dup", "z-dup"), social.apps.filter { it.label == "Same" }.map { it.id })
    }

    @Test fun suggestionsKeepTheProvidersRankingAndAreCapped() {
        val apps = (1..12).map { app("app$it", label = "App $it", category = AppCategories.SOCIAL) }
        val ranked = listOf("app9", "app3", "app7", "app1", "app5", "app2", "app11", "app4", "app6")
        val groups = CategoryGrouper(Locale.US, { limit -> ranked.take(limit) }).group(apps, now = now)
        val suggestions = groups.first { it.group == LibraryGroup.SUGGESTIONS }.apps.map { it.id }
        assertEquals(CategoryGrouper.DEFAULT_SUGGESTION_COUNT, suggestions.size)
        assertEquals(ranked.take(8), suggestions)
    }

    @Test fun suggestionsResolveOnlyAgainstVisibleAppsAndDropUnknownOrDuplicateIds() {
        val apps = listOf(app("a", label = "A"), app("b", label = "B"))
        val groups = grouper(suggestions = { listOf("ghost", "b", "b", "a") }).group(apps, now = now)
        assertEquals(listOf("b", "a"), groups.first { it.group == LibraryGroup.SUGGESTIONS }.apps.map { it.id })
    }

    @Test fun hiddenAppsNeverAppearInAnyGroupIncludingSuggestions() {
        val hidden = app("hidden", label = "Hidden", category = AppCategories.SOCIAL, installedAt = now - day)
        val shown = app("shown", label = "Shown", category = AppCategories.SOCIAL, installedAt = now - day)
        val exclusions = LibraryExclusions(isHidden = { it.id == "hidden" })
        val groups = grouper(suggestions = { listOf("hidden", "shown") }, exclusions = exclusions)
            .group(listOf(hidden, shown), now = now)
        val everyApp = groups.flatMap { it.apps }.map { it.id }
        assertEquals(listOf("shown", "shown", "shown"), everyApp)
        assertFalse("hidden" in everyApp)
    }

    @Test fun lockedPrivateSpaceAppsNeverAppearInAnyGroup() {
        val secret = app("secret", label = "Secret", category = AppCategories.SOCIAL, isPrivate = true, installedAt = now - day)
        val normal = app("normal", label = "Normal", category = AppCategories.SOCIAL)
        val locked = LibraryExclusions(isPrivateLocked = { it.isPrivate })
        val groups = grouper(suggestions = { listOf("secret") }, exclusions = locked)
            .group(listOf(secret, normal), now = now)
        assertFalse(groups.any { it.group == LibraryGroup.SUGGESTIONS })
        assertFalse(groups.any { it.group == LibraryGroup.RECENTLY_ADDED })
        assertEquals(listOf("normal"), groups.flatMap { it.apps }.map { it.id })
        // Unlocked, the same app comes back.
        val unlocked = grouper(exclusions = LibraryExclusions()).group(listOf(secret, normal), now = now)
        assertTrue("secret" in unlocked.flatMap { it.apps }.map { it.id })
    }

    @Test fun personalAndWorkFilteringMatchesTheExistingAllAppsBehavior() {
        val apps = listOf(
            app("p", label = "Personal app"),
            app("w", label = "Work app", isWork = true),
        )
        val g = grouper()
        assertEquals(listOf("p", "w"), g.group(apps, LibraryQuery(profile = ProfileFilter.ALL), now).flatMap { it.apps }.map { it.id })
        assertEquals(listOf("p"), g.group(apps, LibraryQuery(profile = ProfileFilter.PERSONAL), now).flatMap { it.apps }.map { it.id })
        assertEquals(listOf("w"), g.group(apps, LibraryQuery(profile = ProfileFilter.WORK), now).flatMap { it.apps }.map { it.id })
    }

    @Test fun searchTextIsTrimmedAndCaseInsensitiveSubstringMatching() {
        val apps = listOf(app("c", label = "Chrome"), app("m", label = "Maps"), app("s", label = "Settings"))
        val g = grouper()
        assertEquals(listOf("c"), g.group(apps, LibraryQuery(text = "  hro "), now).flatMap { it.apps }.map { it.id })
        assertEquals(listOf("c"), g.group(apps, LibraryQuery(text = "CHROME"), now).flatMap { it.apps }.map { it.id })
        assertTrue(g.group(apps, LibraryQuery(text = "zzqx"), now).isEmpty())
        assertEquals(3, g.group(apps, LibraryQuery(text = "   "), now).flatMap { it.apps }.size)
    }

    @Test fun groupingIsStableRegardlessOfInputOrder() {
        val apps = listOf(
            app("a", label = "Alpha", category = AppCategories.SOCIAL, installedAt = now - day),
            app("b", label = "Beta", category = AppCategories.GAME),
            app("c", label = "Gamma", pkg = "com.android.settings"),
            app("d", label = "Delta", category = AppCategories.PRODUCTIVITY, installedAt = now - 2 * day),
            app("e", label = "Epsilon", pkg = "com.acme.widgetron"),
            app("f", label = "Zeta", category = AppCategories.VIDEO),
        )
        val g = grouper(suggestions = { listOf("f", "a") })
        val reference = g.group(apps, now = now)
        repeat(8) { seed ->
            assertEquals(reference, g.group(apps.shuffled(Random(seed)), now = now))
        }
    }

    @Test fun suggestedAndRecentAppsStillAppearInTheirCategoryGroup() {
        val fresh = app("fresh", label = "Fresh", category = AppCategories.SOCIAL, installedAt = now - day)
        val groups = grouper(suggestions = { listOf("fresh") }).group(listOf(fresh), now = now)
        assertEquals(
            listOf(LibraryGroup.SUGGESTIONS, LibraryGroup.RECENTLY_ADDED, LibraryGroup.SOCIAL),
            groups.map { it.group },
        )
        assertTrue(groups.all { it.apps.single().id == "fresh" })
    }

    @Test fun anEmptyOrFullyExcludedCatalogProducesNoGroups() {
        val g = grouper(exclusions = LibraryExclusions(isHidden = { true }), suggestions = { listOf("a") })
        assertTrue(g.group(emptyList(), now = now).isEmpty())
        assertTrue(g.group(listOf(app("a", label = "A")), now = now).isEmpty())
    }
}
