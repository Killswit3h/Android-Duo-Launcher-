package com.jake.duolauncher.library

import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetIndexerTest {

    private val indexer = AlphabetIndexer(Locale.US)

    private fun app(
        id: String,
        label: String = id,
        isWork: Boolean = false,
        isPrivate: Boolean = false,
    ) = LibraryApp(id = id, label = label, isWork = isWork, isPrivate = isPrivate)

    private fun lettersOf(vararg labels: String): List<String> =
        indexer.index(labels.mapIndexed { i, label -> app("id$i", label) }).sections.map { it.letter }

    @Test fun lowercaseLabelsSectionUnderTheirUppercaseLetter() {
        assertEquals("C", indexer.sectionKey("chrome"))
        assertEquals("C", indexer.sectionKey("Chrome"))
        assertEquals(listOf("C"), lettersOf("chrome", "Calendar"))
    }

    @Test fun accentedLabelsFoldIntoTheirBaseLetterSection() {
        assertEquals("E", indexer.sectionKey("École"))
        assertEquals("E", indexer.sectionKey("école"))
        assertEquals("E", indexer.sectionKey("Éclair"))
        assertEquals("A", indexer.sectionKey("Ångström"))
        assertEquals("N", indexer.sectionKey("Ñandú"))
        assertEquals("C", indexer.sectionKey("Çedilla"))
        assertEquals(listOf("E"), lettersOf("École", "Eagle", "éclair"))
    }

    @Test fun digitsSymbolsAndEmojiLabelsLandInTheHashSection() {
        assertEquals("#", indexer.sectionKey("1Password"))
        assertEquals("#", indexer.sectionKey("2048"))
        assertEquals("#", indexer.sectionKey("+Plus"))
        assertEquals("#", indexer.sectionKey("#hashtag"))
        // A leading emoji is a surrogate pair; code-point handling must not split it into a letter.
        assertEquals("#", indexer.sectionKey("🚀 Rocket"))
        assertEquals("#", indexer.sectionKey("🍎 Apples"))
    }

    @Test fun blankAndWhitespaceLeadingLabelsAreHandled() {
        assertEquals("#", indexer.sectionKey(""))
        assertEquals("#", indexer.sectionKey("   "))
        assertEquals("M", indexer.sectionKey("   Maps"))
    }

    @Test fun nonLatinLabelsGetTheirOwnSensibleSections() {
        assertEquals("Б", indexer.sectionKey("Браузер"))
        assertEquals("Б", indexer.sectionKey("браузер"))
        assertEquals("Α", indexer.sectionKey("Αθήνα"))
        assertEquals("中", indexer.sectionKey("中文"))
        assertEquals("あ", indexer.sectionKey("あいうえお"))
        // German sharp s uppercases to "SS", so it sections under S.
        assertEquals("S", indexer.sectionKey("ßeta"))
    }

    @Test fun sectionsAreCollatedWithTheHashSectionAlwaysLast() {
        val letters = lettersOf("1Password", "Zeta", "Alpha", "Браузер", "Echo", "🚀 Rocket")
        assertEquals("#", letters.last())
        assertEquals(listOf("A", "E", "Z"), letters.filter { it in listOf("A", "E", "Z") })
        assertTrue(letters.indexOf("Б") > letters.indexOf("Z"))
    }

    @Test fun appsInsideASectionAreCollatedByLabelWithAnIdTieBreak() {
        val apps = listOf(
            app("2", "Apple"),
            app("1", "Äpple"),
            app("z", "Alpha"),
            app("dup-z", "Apple"),
            app("dup-a", "Apple"),
        )
        val section = indexer.index(apps).sections.single()
        assertEquals(listOf("Alpha", "Apple", "Apple", "Apple", "Äpple"), section.apps.map { it.label })
        assertEquals(listOf("2", "dup-a", "dup-z"), section.apps.filter { it.label == "Apple" }.map { it.id })
    }

    @Test fun scrubberPositionsMatchTheFlatListIndices() {
        val apps = listOf(
            app("a1", "Alpha"), app("a2", "Amber"), app("b1", "Beta"), app("z1", "1Password"),
        )
        val index = indexer.index(apps)
        assertEquals(listOf("A", "B", "#"), index.sections.map { it.letter })
        // Flat list: [A][Alpha][Amber][B][Beta][#][1Password]
        assertEquals(listOf(0, 3, 5), index.sections.map { it.headerIndex })
        assertEquals(listOf(1, 4, 6), index.sections.map { it.firstItemIndex })
        assertEquals(7, index.itemCount)
        assertEquals(listOf(0, 3, 5), index.scrubber.map { it.headerIndex })
        assertEquals(listOf(0, 1, 2), index.scrubber.map { it.sectionIndex })
        assertEquals(listOf("A", "B", "#"), index.scrubber.map { it.letter })
        assertEquals(apps.size, index.apps().size)
    }

    @Test fun scrubberJumpsToTheFollowingSectionForALetterWithNoApps() {
        val index = indexer.index(listOf(app("a", "Alpha"), app("b", "Beta"), app("m", "Maps"), app("n", "1Note")))
        // Flat list: [A][Alpha][B][Beta][M][Maps][#][1Note]
        assertEquals(4, index.stopFor("M")?.headerIndex)
        assertNull(index.stopFor("D"))
        // Dragging over D lands on the first section at or after it.
        assertEquals("M", index.nearestStop("D", Locale.US)?.letter)
        assertEquals("A", index.nearestStop("A", Locale.US)?.letter)
        assertEquals("#", index.nearestStop("#", Locale.US)?.letter)
        assertEquals("#", index.nearestStop("Z", Locale.US)?.letter)
        assertNull(AlphabetIndex.Empty.nearestStop("A", Locale.US))
    }

    @Test fun hiddenAppsAreAbsentFromEverySectionAndFromTheScrubber() {
        val apps = listOf(app("hidden", "Hidden"), app("shown", "Shown"))
        val index = AlphabetIndexer(Locale.US, LibraryExclusions(isHidden = { it.id == "hidden" })).index(apps)
        assertEquals(listOf("S"), index.sections.map { it.letter })
        assertEquals(listOf("shown"), index.apps().map { it.id })
        assertFalse(index.scrubber.any { it.letter == "H" })
    }

    @Test fun lockedPrivateSpaceAppsAreAbsentFromEverySection() {
        val apps = listOf(app("secret", "Private thing", isPrivate = true), app("normal", "Normal"))
        val locked = AlphabetIndexer(Locale.US, LibraryExclusions(isPrivateLocked = { it.isPrivate }))
        assertEquals(listOf("normal"), locked.index(apps).apps().map { it.id })
        assertFalse(locked.index(apps).scrubber.any { it.letter == "P" })
        // Unlocking restores it.
        assertEquals(listOf("normal", "secret"), AlphabetIndexer(Locale.US).index(apps).apps().map { it.id })
    }

    @Test fun profileFilteringAndSearchApplyToTheAzView() {
        val apps = listOf(app("p", "Personal app"), app("w", "Work app", isWork = true), app("c", "Chrome"))
        assertEquals(listOf("c", "p"), indexer.index(apps, LibraryQuery(profile = ProfileFilter.PERSONAL)).apps().map { it.id })
        assertEquals(listOf("w"), indexer.index(apps, LibraryQuery(profile = ProfileFilter.WORK)).apps().map { it.id })
        assertEquals(listOf("c"), indexer.index(apps, LibraryQuery(text = " hro ")).apps().map { it.id })
        assertTrue(indexer.index(apps, LibraryQuery(text = "zzqx")).isEmpty)
    }

    @Test fun anEmptyCatalogProducesAnEmptyIndex() {
        val index = indexer.index(emptyList())
        assertTrue(index.isEmpty)
        assertEquals(0, index.itemCount)
        assertTrue(index.scrubber.isEmpty())
    }

    @Test fun indexingIsStableRegardlessOfInputOrder() {
        val apps = listOf(
            app("1", "Alpha"), app("2", "Äpple"), app("3", "1Password"), app("4", "Браузер"),
            app("5", "maps"), app("6", "Music"), app("7", "🚀 Rocket"), app("8", "École"),
        )
        val reference = indexer.index(apps)
        repeat(8) { seed -> assertEquals(reference, indexer.index(apps.shuffled(Random(seed)))) }
    }

    @Test fun localeAwareUppercasingIsUsedForSectionKeys() {
        // Turkish dotless/dotted i: the section key follows the locale's own uppercasing rules.
        assertEquals("I", AlphabetIndexer(Locale.US).sectionKey("istanbul"))
        assertEquals("İ", AlphabetIndexer(Locale("tr", "TR")).sectionKey("istanbul"))
    }
}
