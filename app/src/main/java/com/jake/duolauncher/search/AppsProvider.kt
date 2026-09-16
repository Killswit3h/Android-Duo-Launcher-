package com.jake.duolauncher.search

import com.jake.duolauncher.library.LibraryApp
import com.jake.duolauncher.library.LibraryExclusions
import com.jake.duolauncher.library.LibrarySuggestionsProvider
import com.jake.duolauncher.library.labelComparator
import java.util.Locale

/**
 * The Apps section of FR-72, and the pre-typing suggestions of FR-71.
 *
 * **Exclusions.** Hidden apps (FR-75) and private-space apps while the container is locked (FR-77)
 * are removed by the same injected [LibraryExclusions] the App Library uses, so a single wiring
 * decision covers both surfaces and Search can never disagree with the library about what exists.
 *
 * **Ranking.** Score first, and scores are laid out in tiers of [MATCH_TIER_STEP] (exact, prefix,
 * word-start, keyword, substring, fuzzy) with every bonus smaller than one tier — so an exact or
 * prefix match can never be pushed below a fuzzy one. Inside a tier:
 *  - coverage: the more of the label the query accounts for, the better;
 *  - position: a match nearer the start of the label is better;
 *  - whole words: a match that ends on a word boundary beats one that stops mid-word;
 *  - usage: apps the launch-history ranker already likes get a small bounded boost, so two equally
 *    good text matches resolve towards the one the user actually opens;
 *  - and the final tie-break is label length, then collated label, then app id, which is total —
 *    identical input always produces identical output, so the list never reshuffles between frames.
 */
class AppsProvider(
    private val index: SearchAppIndex,
    private val exclusions: LibraryExclusions = LibraryExclusions.None,
    private val suggestionSource: LibrarySuggestionsProvider = LibrarySuggestionsProvider.None,
    private val locale: Locale = Locale.getDefault(),
) {
    private val byLabel = labelComparator(locale)

    private val order: Comparator<AppResult> = compareByDescending<AppResult> { it.score }
        .thenBy { it.match.ordinal }
        .thenBy { it.app.label.length }
        .thenComparator { first, second -> byLabel.compare(first.app, second.app) }

    /** Whether this app exists and is allowed on a launcher surface right now. */
    fun isVisible(id: ProfileAppId): Boolean = appFor(id) != null

    /** The app behind an id, or `null` when it is unknown, hidden, or in a locked private space. */
    fun appFor(id: ProfileAppId): LibraryApp? =
        index.entryFor(id)?.app?.takeUnless(exclusions::excludes)

    /** The Apps section for one query, best first, capped at [limit]. */
    fun results(query: SearchQuery, limit: Int): List<AppResult> {
        if (limit <= 0 || query.isBlank || index.isEmpty) return emptyList()
        val boosts = usageBoosts()
        val matches = ArrayList<AppResult>(INITIAL_MATCH_CAPACITY)
        for (entry in index.entries()) {
            if (exclusions.excludes(entry.app)) continue
            val match = bestMatch(
                matchLabel(query, entry.label),
                matchKeywords(query, entry.keywords),
            ) ?: continue
            val boost = boosts[entry.app.id] ?: 0
            matches += AppResult(entry.app, match.kind, match.score + boost)
        }
        if (matches.isEmpty()) return emptyList()
        matches.sortWith(order)
        return if (matches.size > limit) ArrayList(matches.subList(0, limit)) else matches
    }

    /**
     * FR-71's Siri-Suggestions-style rows, shown before anything is typed.
     *
     * The ranking comes from launch history; this only resolves ids against the visible catalog, so
     * a hidden, uninstalled or locked private-space app can never leak in through a suggestion.
     */
    fun suggestions(limit: Int): List<AppResult> {
        if (limit <= 0) return emptyList()
        val ranked = suggestionSource.suggestedAppIds(limit)
        if (ranked.isEmpty()) return emptyList()
        val results = ArrayList<AppResult>(minOf(limit, ranked.size))
        val seen = HashSet<ProfileAppId>(ranked.size)
        for (id in ranked) {
            if (!seen.add(id)) continue
            val app = appFor(id) ?: continue
            results += AppResult(app, MatchKind.EXACT, score = 0, isSuggestion = true)
            if (results.size == limit) break
        }
        return results
    }

    /**
     * A small, bounded boost for the apps launch history ranks highest. Bounded by
     * [MAX_USAGE_BOOST], which is far below [MATCH_TIER_STEP], so usage decides between comparable
     * matches and never between tiers.
     */
    private fun usageBoosts(): Map<ProfileAppId, Int> {
        val ranked = suggestionSource.suggestedAppIds(USAGE_BOOST_DEPTH)
        if (ranked.isEmpty()) return emptyMap()
        val boosts = HashMap<ProfileAppId, Int>(ranked.size)
        ranked.forEachIndexed { position, id ->
            if (position < USAGE_BOOST_DEPTH) boosts.putIfAbsent(id, MAX_USAGE_BOOST - position)
        }
        return boosts
    }

    companion object {
        /** How many of the ranker's top apps earn a boost, and how big the best boost is. */
        const val USAGE_BOOST_DEPTH = 8
        const val MAX_USAGE_BOOST = 15

        private const val INITIAL_MATCH_CAPACITY = 16
    }
}
