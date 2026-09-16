package com.jake.duolauncher.search

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val US: Locale = Locale.US

private fun SettingsProvider.ids(text: String, limit: Int = 10): List<String> =
    results(SearchQuery.of(text, US), limit).map { it.destination.id }

class SettingsProviderTest {

    private val provider = SettingsProvider(locale = US)

    @Test fun `the curated list covers the destinations the spec names`() {
        val ids = DuoSettingsDestinations.all.map { it.id }
        listOf(
            "wifi", "bluetooth", "display", "sound", "battery", "apps", "storage",
            "accessibility", "date-time", "language", "developer", "notification-access", "default-apps",
        ).forEach { assertTrue("missing $it", it in ids) }
    }

    @Test fun `every destination is well formed and uniquely identified`() {
        val destinations = DuoSettingsDestinations.all
        assertEquals(destinations.size, destinations.map { it.id }.toSet().size)
        destinations.forEach { destination ->
            assertTrue(destination.id.isNotBlank())
            assertTrue(destination.title.isNotBlank())
            assertTrue("empty action for ${destination.id}", destination.action.isNotBlank())
            assertTrue("no keywords for ${destination.id}", destination.keywords.isNotEmpty())
        }
    }

    @Test fun `wifi finds Wi-Fi`() {
        assertEquals("wifi", provider.ids("wifi").first())
        assertEquals("wifi", provider.ids("wi-fi").first())
        assertEquals("wifi", provider.ids("wireless").first())
    }

    @Test fun `keywords reach screens the title does not name`() {
        assertEquals("display", provider.ids("dark").first())
        assertEquals("sound", provider.ids("volume").first())
        assertEquals("security", provider.ids("fingerprint").first())
        assertEquals("keyboard", provider.ids("autocorrect").first())
    }

    @Test fun `a title match outranks a keyword-only match`() {
        val results = provider.results(SearchQuery.of("battery", US), 10)
        assertEquals("battery", results.first().destination.id)
        assertTrue(results.first().score >= results.last().score)
    }

    @Test fun `unresolvable destinations are dropped`() {
        val filtered = SettingsProvider(
            availability = { it.id != "wifi" },
            locale = US,
        )
        assertFalse("wifi" in filtered.ids("wifi"))
        assertEquals("bluetooth", filtered.ids("bluetooth").first())
    }

    @Test fun `limits and empty queries are respected`() {
        assertEquals(1, provider.ids("b", limit = 1).size)
        assertEquals(emptyList<String>(), provider.ids("wifi", limit = 0))
        assertEquals(emptyList<String>(), provider.ids("  "))
        assertEquals(emptyList<String>(), provider.ids("zzqx"))
    }

    @Test fun `results are deterministic`() {
        assertEquals(provider.ids("a"), provider.ids("a"))
        assertEquals(provider.ids("battery"), SettingsProvider(locale = US).ids("battery"))
    }
}
