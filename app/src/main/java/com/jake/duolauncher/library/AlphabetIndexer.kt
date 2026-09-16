package com.jake.duolauncher.library

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** One A–Z section: its header letter, its flat-list positions and its apps. */
data class AlphabetSection(
    val letter: String,
    /** Index of this section's sticky header in the flat list the UI renders. */
    val headerIndex: Int,
    /** Index of this section's first app in that same flat list. */
    val firstItemIndex: Int,
    val apps: List<LibraryApp>,
)

/** One stop on the side scrubber (FR-69). */
data class ScrubberStop(
    val letter: String,
    val sectionIndex: Int,
    /** The flat-list index to scroll to when the finger lands on this letter. */
    val headerIndex: Int,
)

/**
 * The A–Z view model: ordered sections plus the scrubber the UI binds to.
 * Flat-list positions assume one row per section header followed by one row per app, which is how
 * the list is built today.
 */
data class AlphabetIndex(
    val sections: List<AlphabetSection>,
    val scrubber: List<ScrubberStop>,
) {
    val isEmpty: Boolean get() = sections.isEmpty()

    /** Total rows in the flat list: every header plus every app. */
    val itemCount: Int get() = sections.sumOf { it.apps.size + 1 }

    /** All apps in A–Z order, headers dropped. */
    fun apps(): List<LibraryApp> = sections.flatMap { it.apps }

    /** The stop for exactly this letter, or `null` when no app starts with it. */
    fun stopFor(letter: String): ScrubberStop? = scrubber.firstOrNull { it.letter == letter }

    /**
     * What to scroll to when the finger drags over a letter with no apps: the first stop at or
     * after it, falling back to the last stop. Returns `null` only for an empty index.
     */
    fun nearestStop(letter: String, locale: Locale = Locale.getDefault()): ScrubberStop? {
        if (scrubber.isEmpty()) return null
        stopFor(letter)?.let { return it }
        val comparator = sectionKeyComparator(locale)
        return scrubber.firstOrNull { comparator.compare(it.letter, letter) >= 0 } ?: scrubber.last()
    }

    companion object {
        val Empty = AlphabetIndex(emptyList(), emptyList())
    }
}

/**
 * Builds the A–Z list of FR-69.
 *
 * **Sectioning** follows `Collator`/`AlphabeticIndex` semantics without pulling in ICU:
 *  - the label's first non-whitespace code point decides the section;
 *  - it is NFD-normalized with combining marks stripped, then uppercased in [locale], so `école`,
 *    `Éclair` and `Eagle` all land in **E** and Turkish `i` uppercases to `İ` under a Turkish locale;
 *  - a letter that does not decompose to ASCII keeps its own section (`Ø`, `Б`, `あ`, `中`), which is
 *    the sensible bucket for a non-Latin label and collates next to its Latin neighbours;
 *  - digits, punctuation, symbols and emoji (handled by code point, so surrogate pairs do not split)
 *    go to **#**.
 *
 * **Section order** is the locale [Collator]'s order, with **#** always last.
 * **App order inside a section** is collated label order, ties broken by app id.
 *
 * Exclusions, the profile chip and the search text are applied before indexing, so a hidden app
 * (FR-75) or a locked private-space app (FR-77) is absent from every section and from the scrubber.
 */
class AlphabetIndexer(
    private val locale: Locale = Locale.getDefault(),
    private val exclusions: LibraryExclusions = LibraryExclusions.None,
) {

    /** The section a label belongs to. Public so Search and tests can ask the same question. */
    fun sectionKey(label: String): String {
        val start = label.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return OTHER_SECTION
        val codePoint = label.codePointAt(start)
        if (!Character.isLetter(codePoint)) return OTHER_SECTION
        val single = String(Character.toChars(codePoint))
        val folded = Normalizer.normalize(single, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .uppercase(locale)
        val first = folded.firstOrNull() ?: return OTHER_SECTION
        return if (Character.isLetter(first)) first.toString() else OTHER_SECTION
    }

    fun index(apps: List<LibraryApp>, query: LibraryQuery = LibraryQuery()): AlphabetIndex {
        val visible = visibleLibraryApps(apps, query, exclusions)
        if (visible.isEmpty()) return AlphabetIndex.Empty

        val buckets = LinkedHashMap<String, MutableList<LibraryApp>>()
        visible.forEach { buckets.getOrPut(sectionKey(it.label)) { mutableListOf() }.add(it) }

        val byLabel = labelComparator(locale)
        val keys = buckets.keys.sortedWith(sectionKeyComparator(locale))
        var position = 0
        val sections = keys.map { key ->
            val members = buckets.getValue(key).sortedWith(byLabel)
            val headerIndex = position
            position += members.size + 1
            AlphabetSection(key, headerIndex, headerIndex + 1, members)
        }
        val scrubber = sections.mapIndexed { index, section ->
            ScrubberStop(section.letter, index, section.headerIndex)
        }
        return AlphabetIndex(sections, scrubber)
    }

    companion object {
        /** Where digits, symbols and emoji go. */
        const val OTHER_SECTION = "#"

        private val COMBINING_MARKS = Regex("\\p{M}+")
    }
}

/** Collator order over section keys, with "#" forced last and a byte-order tie-break. */
internal fun sectionKeyComparator(locale: Locale): Comparator<String> {
    val collator = Collator.getInstance(locale)
    return Comparator { a, b ->
        when {
            a == b -> 0
            a == AlphabetIndexer.OTHER_SECTION -> 1
            b == AlphabetIndexer.OTHER_SECTION -> -1
            else -> collator.compare(a, b).let { if (it != 0) it else a.compareTo(b) }
        }
    }
}
