package com.jake.duolauncher.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val US: Locale = Locale.US

private fun index() = SettingsSearchIndex(locale = US)

private fun sectionsFor(text: String) = index().sections(text)

private fun idsFor(text: String): Set<String> = index().matchingIds(text).orEmpty()

class SettingsCatalogTest {

    // -----------------------------------------------------------------------------------------
    // Section ordering (FR-79)
    // -----------------------------------------------------------------------------------------

    @Test fun `sections are in the order FR-79 lists them`() {
        assertEquals(
            listOf(
                "Wallpaper",
                "Appearance",
                "Icons",
                "Home Screen and Dock",
                "Today View",
                "App Library and Search",
                "Gestures",
                "Badges",
                "Hidden apps",
                "Private space",
                "Backup",
                "Help",
                "About",
            ),
            DuoSettingsCatalog.sections.map { it.title },
        )
    }

    @Test fun `every section has at least one row`() {
        DuoSettingsSection.entries.forEach { section ->
            assertTrue(
                "${section.title} has no rows",
                DuoSettingsCatalog.bySection[section].orEmpty().isNotEmpty(),
            )
        }
    }

    @Test fun `row ids are unique`() {
        val ids = DuoSettingsCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `a blank query filters nothing`() {
        assertNull(index().matchingIds(""))
        assertNull(index().matchingIds("   "))
        assertEquals(DuoSettingsCatalog.sections, sectionsFor(""))
        assertTrue(index().hits("").isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // AC-64: typing "badge" finds Badges
    // -----------------------------------------------------------------------------------------

    @Test fun `typing badge finds the Badges section`() {
        assertEquals(listOf(DuoSettingsSection.BADGES), sectionsFor("badge"))
        assertTrue(SettingsIds.BADGE_STYLE in idsFor("badge"))
        assertTrue(SettingsIds.BADGE_ACCESS in idsFor("badge"))
    }

    @Test fun `typing badges finds the Badges section too`() {
        assertEquals(listOf(DuoSettingsSection.BADGES), sectionsFor("badges"))
    }

    @Test fun `a section title match carries the whole section`() {
        // "Hidden apps" is both a section and its only row, so it leads the results. The private
        // space row also answers to "hidden" (its keyword is "hidden profile"), which is wanted:
        // a user looking for hidden things should be shown both.
        val sections = sectionsFor("hidden")
        assertTrue(DuoSettingsSection.HIDDEN_APPS in sections)
        assertTrue(SettingsIds.HIDDEN_APPS in idsFor("hidden"))
        assertEquals(SettingsIds.HIDDEN_APPS, index().hits("hidden").first().entry.id)
    }

    @Test fun `rows outrank the section they were carried in by`() {
        val hits = index().hits("badge")
        // Both rows mention badges directly, so neither falls to the section-only tier.
        assertTrue(hits.all { it.score > 0 })
    }

    // -----------------------------------------------------------------------------------------
    // Matching behaviour
    // -----------------------------------------------------------------------------------------

    @Test fun `matching is case insensitive`() {
        assertEquals(idsFor("badge"), idsFor("BADGE"))
        assertEquals(idsFor("glass"), idsFor("Glass"))
    }

    @Test fun `keywords reach settings whose title does not contain the word`() {
        // "sleep" only appears as a keyword of Double-tap to lock.
        assertTrue(SettingsIds.GESTURE_DOUBLE_TAP in idsFor("sleep"))
        // "colour" is the British spelling, a keyword on the accent row.
        assertTrue(SettingsIds.APPEARANCE_ACCENT in idsFor("colour"))
        // "squircle" only exists as an icon-shape keyword.
        assertTrue(SettingsIds.ICONS_SHAPE in idsFor("squircle"))
        // "drawer" is what an Android user calls the App Library.
        assertTrue(SettingsIds.LIBRARY_VIEW in idsFor("drawer"))
    }

    @Test fun `a prefix finds the setting`() {
        assertTrue(SettingsIds.APPEARANCE_GLASS in idsFor("gla"))
        assertTrue(SettingsIds.HOME_GRID in idsFor("gri"))
    }

    @Test fun `dock settings are found together`() {
        val ids = idsFor("dock")
        assertTrue(SettingsIds.HOME_DOCK_SIDE in ids)
        assertTrue(SettingsIds.HOME_DOCK_CAPACITY in ids)
        assertTrue(SettingsIds.HOME_DOCK_WIDTH in ids)
        // "dock" is also a subsequence of "Double-tap to lock", so the shared fuzzy rule surfaces
        // Gestures too. That match scores in the bottom tier, so the real dock settings still lead.
        assertTrue(DuoSettingsSection.HOME_AND_DOCK in sectionsFor("dock"))
        assertEquals(
            DuoSettingsSection.HOME_AND_DOCK,
            index().hits("dock").first().entry.section,
        )
    }

    @Test fun `private finds the private space section`() {
        assertTrue(DuoSettingsSection.PRIVATE_SPACE in sectionsFor("private"))
    }

    @Test fun `a query that matches nothing returns no sections`() {
        assertTrue(idsFor("zzqx").isEmpty())
        assertTrue(sectionsFor("zzqx").isEmpty())
    }

    @Test fun `hits are ordered best first`() {
        val scores = index().hits("dock").map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test fun `every hit resolves to a catalog entry`() {
        val known = DuoSettingsCatalog.entries.mapTo(HashSet()) { it.id }
        listOf("badge", "dock", "icon", "glass", "swipe", "backup").forEach { query ->
            index().hits(query).forEach { hit ->
                assertTrue("${hit.entry.id} is not in the catalog", hit.entry.id in known)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Visibility
    // -----------------------------------------------------------------------------------------

    @Test fun `the unfiltered visibility shows everything`() {
        val visibility = SettingsVisibility.All
        assertFalse(visibility.searching)
        assertFalse(visibility.isEmpty)
        DuoSettingsCatalog.entries.forEach { assertTrue(visibility.shows(it.id)) }
        DuoSettingsSection.entries.forEach { assertTrue(visibility.showsSection(it)) }
    }

    @Test fun `a filtered visibility hides every section but the match`() {
        val visibility = SettingsVisibility(index().matchingIds("badge"))
        assertTrue(visibility.searching)
        assertFalse(visibility.isEmpty)
        assertTrue(visibility.showsSection(DuoSettingsSection.BADGES))
        assertTrue(visibility.shows(SettingsIds.BADGE_STYLE))
        assertFalse(visibility.showsSection(DuoSettingsSection.GESTURES))
        assertFalse(visibility.shows(SettingsIds.HOME_GRID))
    }

    @Test fun `a visibility with no matches reports empty`() {
        val visibility = SettingsVisibility(index().matchingIds("zzqx"))
        assertTrue(visibility.searching)
        assertTrue(visibility.isEmpty)
        assertFalse(visibility.showsSection(DuoSettingsSection.BADGES))
    }

    @Test fun `search finds at least one row for every section title`() {
        DuoSettingsSection.entries.forEach { section ->
            val found = sectionsFor(section.title)
            assertNotNull(found)
            assertTrue(
                "searching \"${section.title}\" did not surface its own section",
                section in found,
            )
        }
    }
}
