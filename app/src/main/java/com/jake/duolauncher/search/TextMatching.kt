package com.jake.duolauncher.search

import java.text.Normalizer
import java.util.Locale

/**
 * The matching core of FR-72: case- and accent-insensitive prefix, word-start, keyword, substring
 * and fuzzy matching, with a deterministic score per match.
 *
 * Everything expensive — folding a label, finding its word starts — happens once, when the index is
 * built ([IndexedText]). A keystroke only folds the query and then walks already-folded text, so the
 * per-keystroke path allocates one small string plus the result rows (NFR-P3).
 */

/**
 * A label, pre-folded for matching.
 *
 * [folded] is [original] with combining marks stripped and lowercased in the index's locale, so
 * "Café", "CAFE" and "cafe" all fold to `cafe` and an unaccented query matches an accented label.
 * Folding is done code point by code point and the resulting offsets are recorded as they are
 * produced, so [wordStartAt] indexes into [folded] even when folding changes a character's width.
 *
 * A word start is the first character of the label, any letter or digit that follows a non
 * letter-or-digit, a lowercase→uppercase hop (so "PlayStore" starts a word at "S"), and a
 * letter↔digit hop (so "Office365" starts a word at "3").
 */
class IndexedText private constructor(
    val original: String,
    val folded: String,
    private val wordStarts: IntArray,
) {
    val isEmpty: Boolean get() = folded.isEmpty()

    val wordStartCount: Int get() = wordStarts.size

    fun wordStartAt(index: Int): Int = wordStarts[index]

    /** Whether [offset] into [folded] begins a word. Binary search: the offsets are ascending. */
    fun isWordStart(offset: Int): Boolean {
        var low = 0
        var high = wordStarts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = wordStarts[mid]
            when {
                value < offset -> low = mid + 1
                value > offset -> high = mid - 1
                else -> return true
            }
        }
        return false
    }

    override fun toString(): String = folded

    companion object {
        val Empty = IndexedText("", "", IntArray(0))

        fun of(text: String, locale: Locale = Locale.getDefault()): IndexedText {
            if (text.isEmpty()) return Empty
            val folded = StringBuilder(text.length)
            val starts = ArrayList<Int>(4)
            var previous = 0
            var hasPrevious = false
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                offset += Character.charCount(codePoint)
                val isWord = Character.isLetterOrDigit(codePoint)
                if (isWord && startsWord(previous, codePoint, hasPrevious)) starts += folded.length
                previous = codePoint
                hasPrevious = true
                folded.append(fold(codePoint, locale))
            }
            return IndexedText(text, folded.toString(), starts.toIntArray())
        }

        private fun startsWord(previous: Int, current: Int, hasPrevious: Boolean): Boolean {
            if (!hasPrevious) return true
            if (!Character.isLetterOrDigit(previous)) return true
            if (Character.isLowerCase(previous) && Character.isUpperCase(current)) return true
            return Character.isDigit(previous) != Character.isDigit(current)
        }

        /** One code point, NFD-normalized with combining marks dropped, then lowercased. */
        private fun fold(codePoint: Int, locale: Locale): String {
            val source = String(Character.toChars(codePoint))
            val decomposed = Normalizer.normalize(source, Normalizer.Form.NFD)
            val stripped = if (decomposed.any(Char::isCombiningMark)) {
                buildString(decomposed.length) {
                    decomposed.forEach { if (!it.isCombiningMark()) append(it) }
                }
            } else {
                decomposed
            }
            return stripped.lowercase(locale)
        }

    }
}

/** The combining marks NFD produces. Folding drops them, which is what makes accents stop mattering. */
private fun Char.isCombiningMark(): Boolean = when (Character.getType(this).toByte()) {
    Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
    else -> false
}

/**
 * A search query, folded once per keystroke and shared by every provider.
 *
 * [raw] keeps what the user typed (the web row and the calculator need it verbatim); [folded] is
 * what matching compares against.
 */
class SearchQuery private constructor(val raw: String, val folded: String) {
    val isBlank: Boolean get() = folded.isEmpty()

    val length: Int get() = folded.length

    override fun toString(): String = folded

    companion object {
        val Blank = SearchQuery("", "")

        fun of(text: String, locale: Locale = Locale.getDefault()): SearchQuery {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return Blank
            val folded = IndexedText.of(trimmed, locale).folded
            return if (folded.isEmpty()) Blank else SearchQuery(trimmed, folded)
        }
    }
}

/** A match and what it is worth. Higher [score] always wins; [kind] only breaks ties. */
data class MatchScore(val kind: MatchKind, val score: Int)

/** Scores are laid out in tiers of this size, so no bonus can lift a match into a better tier. */
internal const val MATCH_TIER_STEP = 100

/** Fuzzy matching is off below this many characters: one-character fuzzy is noise, not a match. */
internal const val MIN_FUZZY_QUERY_LENGTH = 2

/** The largest bonus a fuzzy match's own quality can earn, before the coverage bonus. */
internal const val MAX_FUZZY_QUALITY = 40

/** The largest bonus for covering most of the target, and for matching near its start. */
internal const val MAX_COVERAGE_BONUS = 20
internal const val MAX_POSITION_BONUS = 20

/** The bonus for a match that ends on a word boundary: "Ada" answers "Ada Lovelace" before "Adam". */
internal const val MAX_WORD_END_BONUS = 10

/** The floor of each tier. The gap is [MATCH_TIER_STEP], and every bonus together stays below it. */
internal fun tierBase(kind: MatchKind): Int = when (kind) {
    MatchKind.EXACT -> 5 * MATCH_TIER_STEP
    MatchKind.PREFIX -> 4 * MATCH_TIER_STEP
    MatchKind.WORD_START -> 3 * MATCH_TIER_STEP
    MatchKind.KEYWORD -> 2 * MATCH_TIER_STEP
    MatchKind.CONTAINS -> MATCH_TIER_STEP
    MatchKind.FUZZY -> 0
}

/** How much of the target the query covers: a full label beats a long one it is only part of. */
internal fun coverageBonus(queryLength: Int, targetLength: Int): Int =
    if (targetLength <= 0) 0 else minOf(queryLength * MAX_COVERAGE_BONUS / targetLength, MAX_COVERAGE_BONUS)

/** Matches nearer the start of the label are worth more. */
internal fun positionBonus(index: Int): Int = MAX_POSITION_BONUS - minOf(index, MAX_POSITION_BONUS)

/** Whether a match ending at [endExclusive] consumed a whole word rather than part of one. */
internal fun wordEndBonus(label: String, endExclusive: Int): Int =
    if (endExclusive >= label.length || !label[endExclusive].isLetterOrDigit()) MAX_WORD_END_BONUS else 0

/**
 * The best match of [query] against one label, or `null` when it does not match at all.
 *
 * Tried in tier order, first hit wins, so the result is deterministic and never needs a second pass:
 * exact, whole-label prefix, prefix of a later word, substring inside a word, then fuzzy. Inside a
 * tier the bonuses decide: how much of the label the query covers, how near the start it matched,
 * and whether it consumed a whole word. Together they stay below [MATCH_TIER_STEP], so a bonus can
 * never lift a match into a better tier.
 */
fun matchLabel(query: SearchQuery, target: IndexedText): MatchScore? {
    val text = query.folded
    val label = target.folded
    if (text.isEmpty() || label.isEmpty() || text.length > label.length) return null

    val coverage = coverageBonus(text.length, label.length)
    if (label == text) return MatchScore(MatchKind.EXACT, tierBase(MatchKind.EXACT) + coverage)
    if (label.startsWith(text)) {
        return MatchScore(
            MatchKind.PREFIX,
            tierBase(MatchKind.PREFIX) + coverage + wordEndBonus(label, text.length),
        )
    }

    for (index in 0 until target.wordStartCount) {
        val start = target.wordStartAt(index)
        if (start == 0) continue
        if (start + text.length > label.length) break
        if (label.startsWith(text, start)) {
            return MatchScore(
                MatchKind.WORD_START,
                tierBase(MatchKind.WORD_START) + coverage + positionBonus(start) +
                    wordEndBonus(label, start + text.length),
            )
        }
    }

    val inside = label.indexOf(text)
    if (inside >= 0) {
        return MatchScore(
            MatchKind.CONTAINS,
            tierBase(MatchKind.CONTAINS) + coverage + positionBonus(inside) +
                wordEndBonus(label, inside + text.length),
        )
    }

    return fuzzyMatch(text, target, coverage)
}

/**
 * The best keyword match, or `null`.
 *
 * Keywords are the curated aliases and package tokens of [SearchAliases]. They only ever match
 * exactly or by prefix — a fuzzy keyword match would surface apps for no visible reason — and they
 * sit in their own tier below word-start matches, so a real label match always wins.
 */
fun matchKeywords(query: SearchQuery, keywords: List<IndexedText>): MatchScore? {
    val text = query.folded
    if (text.isEmpty() || keywords.isEmpty()) return null
    var best: MatchScore? = null
    for (keyword in keywords) {
        val folded = keyword.folded
        if (folded.isEmpty() || text.length > folded.length) continue
        val score = when {
            folded == text -> tierBase(MatchKind.KEYWORD) + MAX_COVERAGE_BONUS
            folded.startsWith(text) -> tierBase(MatchKind.KEYWORD) + coverageBonus(text.length, folded.length)
            else -> continue
        }
        if (best == null || score > best.score) best = MatchScore(MatchKind.KEYWORD, score)
    }
    return best
}

/** The better of two optional matches, preferring the higher score and then the better tier. */
fun bestMatch(first: MatchScore?, second: MatchScore?): MatchScore? = when {
    first == null -> second
    second == null -> first
    second.score > first.score -> second
    second.score == first.score && second.kind < first.kind -> second
    else -> first
}

/**
 * Subsequence matching, in the spirit of `fzy`: every query character appears in the label in order.
 *
 * Two rules keep it from matching everything. The first character must land on a word start, so
 * "gml" reaches "Gmail" but "mal" does not reach it by fuzzy (it is a substring match anyway); and
 * runs of adjacent characters plus hits on word starts are what earn the score, so "gm" ranks
 * "Google Maps" (two word starts) above a label where the same letters are scattered mid-word.
 */
private fun fuzzyMatch(text: String, target: IndexedText, coverage: Int): MatchScore? {
    if (text.length < MIN_FUZZY_QUERY_LENGTH) return null
    val label = target.folded
    var queryIndex = 0
    var quality = 0
    var streak = 0
    var previousMatch = -2
    var started = false
    var allOnWordStarts = true
    var labelIndex = 0
    while (labelIndex < label.length && queryIndex < text.length) {
        if (label[labelIndex] == text[queryIndex]) {
            val onWordStart = target.isWordStart(labelIndex)
            if (!started) {
                if (!onWordStart) return null
                started = true
            }
            if (labelIndex == previousMatch + 1) {
                streak++
                quality += minOf(streak * 3, 9)
            } else {
                streak = 0
            }
            if (onWordStart) quality += 8 else allOnWordStarts = false
            previousMatch = labelIndex
            queryIndex++
        }
        labelIndex++
    }
    if (queryIndex < text.length) return null
    if (allOnWordStarts) quality += 10
    return MatchScore(MatchKind.FUZZY, tierBase(MatchKind.FUZZY) + minOf(quality, MAX_FUZZY_QUALITY) + coverage)
}
