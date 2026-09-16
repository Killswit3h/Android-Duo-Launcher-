package com.jake.duolauncher.search

import java.util.Locale

/**
 * Where contact rows come from (FR-72, NFR-S4).
 *
 * The rules this seam exists to enforce:
 *  - [isGranted] is re-checked on every query, never cached, because the user can revoke
 *    `READ_CONTACTS` while Search is open;
 *  - [find] queries the provider on demand and returns rows the caller uses and drops. Nothing is
 *    cached, written to disk, put in a backup or logged (NFR-S4, NFR-S7).
 */
interface SearchContactSource {
    /** Whether contacts access is granted *right now*. */
    fun isGranted(): Boolean

    /** An on-demand lookup. Called only when [isGranted] is true and the query is long enough. */
    fun find(query: String, limit: Int): List<SearchContact>

    companion object {
        /** Access denied or never requested: the Contacts section is absent (error table). */
        val Unavailable: SearchContactSource = object : SearchContactSource {
            override fun isGranted(): Boolean = false
            override fun find(query: String, limit: Int): List<SearchContact> = emptyList()
        }
    }
}

/**
 * The Contacts section of FR-72, present only "where contacts access is granted".
 *
 * When access is denied the section is absent entirely — not empty-with-a-prompt: the error table
 * puts the single "Allow contacts in Search" row in Search *settings*, not in the results.
 *
 * A query shorter than [minQueryLength] is not sent to the provider at all. One letter would match
 * most of an address book, which is both useless and more personal data touched than the answer
 * needs.
 */
class ContactsProvider(
    private val source: SearchContactSource = SearchContactSource.Unavailable,
    private val minQueryLength: Int = SearchLimits.Default.minContactQueryLength,
    private val locale: Locale = Locale.getDefault(),
) {
    private val order: Comparator<ContactResult> = compareByDescending<ContactResult> { it.score }
        .thenBy { it.match.ordinal }
        .thenBy { it.contact.displayName.length }
        .thenBy { it.contact.displayName }
        .thenBy { it.contact.lookupKey }

    fun results(query: SearchQuery, limit: Int): List<ContactResult> {
        if (limit <= 0 || query.isBlank || query.length < minQueryLength) return emptyList()
        if (!source.isGranted()) return emptyList()
        val found = runCatching { source.find(query.raw, limit) }.getOrDefault(emptyList())
        if (found.isEmpty()) return emptyList()
        val matches = ArrayList<ContactResult>(found.size)
        val seen = HashSet<String>(found.size)
        for (contact in found) {
            if (contact.displayName.isBlank() || !seen.add(contact.lookupKey)) continue
            // The provider already filtered; scoring it again only orders the rows.
            val match = matchLabel(query, IndexedText.of(contact.displayName, locale))
                ?: MatchScore(MatchKind.CONTAINS, tierBase(MatchKind.CONTAINS))
            matches += ContactResult(contact, match.kind, match.score)
        }
        if (matches.isEmpty()) return emptyList()
        matches.sortWith(order)
        return if (matches.size > limit) ArrayList(matches.subList(0, limit)) else matches
    }
}
