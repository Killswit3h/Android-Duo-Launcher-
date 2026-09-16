package com.jake.duolauncher.search

import java.util.Locale

/**
 * Where Search gets app shortcuts from.
 *
 * The launcher's `ShortcutRepository` (build plan task B7) queries `LauncherApps` asynchronously and
 * needs the shortcut host permission; neither belongs on the keystroke path, so Search matches
 * against a snapshot the wiring layer refreshes when shortcuts change (catalog refresh, package
 * change, Search opening) rather than querying per keystroke.
 *
 * **Contract:** return the *same list instance* until the shortcuts actually change. A new instance
 * is the signal to re-fold the labels; returning a fresh copy every call would re-index on every
 * keystroke and burn the NFR-P3 budget.
 */
fun interface SearchShortcutSource {
    fun shortcuts(): List<SearchShortcut>

    companion object {
        /** No shortcuts at all: the state before the host permission exists, and the test default. */
        val None: SearchShortcutSource = SearchShortcutSource { emptyList() }
    }
}

/**
 * The App shortcuts section of FR-72.
 *
 * Only the shortcut's own label is matched. Matching the owning app's label too would repeat the
 * Apps section in a second form for every query that names an app, which is noise.
 *
 * Exclusions come free: a shortcut is dropped unless [AppsProvider] still considers its app visible,
 * so hiding an app (FR-75) or locking the private space (FR-77) removes its shortcuts from Search
 * in the same breath, with no second predicate to keep in sync.
 */
class ShortcutsProvider(
    private val apps: AppsProvider,
    private val source: SearchShortcutSource = SearchShortcutSource.None,
    private val locale: Locale = Locale.getDefault(),
) {
    private class Indexed(val shortcut: SearchShortcut, val label: IndexedText)

    private val order: Comparator<ShortcutResult> = compareByDescending<ShortcutResult> { it.score }
        .thenBy { it.match.ordinal }
        .thenBy { it.shortcut.label.length }
        .thenBy { it.shortcut.label }
        .thenBy { it.shortcut.appId }
        .thenBy { it.shortcut.id }

    private var cachedSnapshot: List<SearchShortcut> = emptyList()
    private var cachedIndex: List<Indexed> = emptyList()

    fun results(query: SearchQuery, limit: Int): List<ShortcutResult> {
        if (limit <= 0 || query.isBlank) return emptyList()
        val indexed = indexed()
        if (indexed.isEmpty()) return emptyList()
        val matches = ArrayList<ShortcutResult>(minOf(indexed.size, INITIAL_MATCH_CAPACITY))
        for (entry in indexed) {
            if (!apps.isVisible(entry.shortcut.appId)) continue
            val match = matchLabel(query, entry.label) ?: continue
            matches += ShortcutResult(entry.shortcut, match.kind, match.score)
        }
        if (matches.isEmpty()) return emptyList()
        matches.sortWith(order)
        return if (matches.size > limit) ArrayList(matches.subList(0, limit)) else matches
    }

    /** The folded snapshot, rebuilt only when the source hands back a different list. */
    @Synchronized
    private fun indexed(): List<Indexed> {
        val current = source.shortcuts()
        if (current === cachedSnapshot) return cachedIndex
        val built = current.map { Indexed(it, IndexedText.of(it.label, locale)) }
        cachedSnapshot = current
        cachedIndex = built
        return built
    }

    private companion object {
        const val INITIAL_MATCH_CAPACITY = 8
    }
}
