package com.jake.duolauncher.search

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val US: Locale = Locale.US

private fun query(text: String) = SearchQuery.of(text, US)

private fun indexed(text: String) = IndexedText.of(text, US)

private fun kindOf(text: String, label: String): MatchKind? = matchLabel(query(text), indexed(label))?.kind

private fun scoreOf(text: String, label: String): Int = matchLabel(query(text), indexed(label))!!.score

class TextMatchingTest {

    @Test fun `folding lowercases and strips accents`() {
        assertEquals("cafe zoe", indexed("Café Zoë").folded)
        assertEquals("aeiou n", indexed("ÁÉÍÓÚ Ñ").folded)
        assertEquals("cafe", query("  CAFÉ  ").folded)
        assertEquals("CAFÉ", query("  CAFÉ  ").raw)
    }

    @Test fun `word starts cover spaces, punctuation, camel case and digits`() {
        val text = indexed("Google Play-Store PlayStore Office365")
        assertTrue(text.isWordStart(0))
        assertTrue(text.isWordStart(text.folded.indexOf("play")))
        assertTrue(text.isWordStart(text.folded.indexOf("store")))
        assertTrue(text.isWordStart(text.folded.indexOf("365")))
        assertFalse(text.isWordStart(1))
    }

    @Test fun `an empty query never matches`() {
        assertNull(matchLabel(query("   "), indexed("Chrome")))
        assertTrue(query("").isBlank)
    }

    @Test fun `the tiers are exact, prefix, word start, substring, fuzzy`() {
        assertEquals(MatchKind.EXACT, kindOf("chrome", "Chrome"))
        assertEquals(MatchKind.PREFIX, kindOf("chr", "Chrome"))
        assertEquals(MatchKind.WORD_START, kindOf("chr", "Google Chrome"))
        assertEquals(MatchKind.CONTAINS, kindOf("rom", "Chrome"))
        assertEquals(MatchKind.FUZZY, kindOf("gml", "Gmail"))
        assertNull(kindOf("zzqx", "Chrome"))
    }

    @Test fun `a better tier always outscores a worse one, whatever the bonuses`() {
        val exact = scoreOf("chrome", "Chrome")
        val prefix = scoreOf("chr", "Chrome")
        val wordStart = scoreOf("chr", "Google Chrome")
        val contains = scoreOf("rom", "Chrome")
        val fuzzy = scoreOf("gml", "Gmail")
        assertTrue("exact > prefix", exact > prefix)
        assertTrue("prefix > word start", prefix > wordStart)
        assertTrue("word start > substring", wordStart > contains)
        assertTrue("substring > fuzzy", contains > fuzzy)
    }

    @Test fun `matching ignores case and accents in both directions`() {
        assertEquals(MatchKind.EXACT, kindOf("cafe", "Café"))
        assertEquals(MatchKind.EXACT, kindOf("CAFÉ", "cafe"))
        assertEquals(MatchKind.PREFIX, kindOf("uber", "Über Eats"))
        assertEquals(MatchKind.WORD_START, kindOf("eats", "Über Eats"))
    }

    @Test fun `an earlier word start beats a later one`() {
        assertTrue(scoreOf("maps", "Google Maps") > scoreOf("maps", "The Very Long Google Maps"))
    }

    @Test fun `covering more of the label beats covering less`() {
        assertTrue(scoreOf("chrome", "Chrome") > scoreOf("chrome", "Chrome Beta Canary"))
    }

    @Test fun `fuzzy needs at least two characters and must start on a word start`() {
        assertNull("one character is never fuzzy", kindOf("z", "Chrome"))
        assertNull("'rme' starts mid-word", kindOf("rme", "Chrome"))
        assertEquals(MatchKind.FUZZY, kindOf("gm", "Google Maps"))
    }

    @Test fun `initials rank above scattered fuzzy hits`() {
        assertTrue(scoreOf("gm", "Google Maps") > scoreOf("gm", "Grand Theft Autumn"))
    }

    @Test fun `keywords match exactly or by prefix, never fuzzily`() {
        val keywords = listOf(indexed("sms"), indexed("texting"))
        assertEquals(MatchKind.KEYWORD, matchKeywords(query("sms"), keywords)?.kind)
        assertEquals(MatchKind.KEYWORD, matchKeywords(query("text"), keywords)?.kind)
        assertNull(matchKeywords(query("tng"), keywords))
        assertNull(matchKeywords(query("sms"), emptyList()))
    }

    @Test fun `a keyword match never outranks a label match`() {
        val label = matchLabel(query("text"), indexed("Textor"))
        val keyword = matchKeywords(query("text"), listOf(indexed("texting")))
        assertNotNull(label)
        assertNotNull(keyword)
        assertTrue(label!!.score > keyword!!.score)
        assertEquals(label, bestMatch(label, keyword))
        assertEquals(label, bestMatch(keyword, label))
    }

    @Test fun `bestMatch tolerates nulls`() {
        val match = MatchScore(MatchKind.PREFIX, 400)
        assertEquals(match, bestMatch(match, null))
        assertEquals(match, bestMatch(null, match))
        assertNull(bestMatch(null, null))
    }

    @Test fun `a query longer than the label cannot match`() {
        assertNull(kindOf("chromecast", "Chrome"))
    }
}
