package com.jake.duolauncher.search

import com.jake.duolauncher.library.LibraryApp
import com.jake.duolauncher.library.LibraryExclusions
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val US: Locale = Locale.US

private val CHROME = LibraryApp(id = "com.android.chrome/.Main", label = "Chrome", packageName = "com.android.chrome")
private val MAPS = LibraryApp(id = "com.example.maps/.Main", label = "Maps", packageName = "com.example.maps")

private fun shortcut(id: String, label: String, app: LibraryApp) =
    SearchShortcut(id = id, appId = app.id, packageName = app.packageName, label = label, appLabel = app.label)

private fun appsProvider(exclusions: LibraryExclusions = LibraryExclusions.None) = AppsProvider(
    SearchAppIndex(US).apply { setApps(listOf(CHROME, MAPS)) },
    exclusions,
    locale = US,
)

class ShortcutsProviderTest {

    private val newTab = shortcut("new-tab", "New tab", CHROME)
    private val incognito = shortcut("incognito", "New Incognito tab", CHROME)
    private val home = shortcut("home", "Navigate home", MAPS)
    private val all = listOf(newTab, incognito, home)

    private fun provider(
        shortcuts: List<SearchShortcut> = all,
        exclusions: LibraryExclusions = LibraryExclusions.None,
    ) = ShortcutsProvider(appsProvider(exclusions), { shortcuts }, US)

    private fun ShortcutsProvider.labels(text: String, limit: Int = 10) =
        results(SearchQuery.of(text, US), limit).map { it.shortcut.label }

    @Test fun `shortcut labels are matched like app labels`() {
        assertEquals(listOf("New tab", "New Incognito tab"), provider().labels("new"))
        assertEquals(listOf("New Incognito tab"), provider().labels("incognito"))
        assertEquals(listOf("Navigate home"), provider().labels("home"))
    }

    @Test fun `the owning app's label is not matched`() {
        assertEquals(emptyList<String>(), provider().labels("chrome"))
    }

    @Test fun `shortcuts of a hidden app disappear with it`() {
        val hidden = provider(exclusions = LibraryExclusions(isHidden = { it.id == CHROME.id }))
        assertEquals(emptyList<String>(), hidden.labels("new"))
        assertEquals(listOf("Navigate home"), hidden.labels("home"))
    }

    @Test fun `shortcuts of a locked private space app disappear with it`() {
        val vault = LibraryApp(id = "com.example.vault/.Main", label = "Vault", packageName = "com.example.vault", isPrivate = true)
        val provider = ShortcutsProvider(
            AppsProvider(
                SearchAppIndex(US).apply { setApps(listOf(vault)) },
                LibraryExclusions(isPrivateLocked = { it.isPrivate }),
                locale = US,
            ),
            { listOf(shortcut("open", "Open vault", vault)) },
            US,
        )
        assertEquals(emptyList<String>(), provider.labels("vault"))
    }

    @Test fun `a shortcut whose app is unknown is dropped`() {
        val orphan = SearchShortcut("gone", "com.gone/.Main", "com.gone", "Ghost tab", "Gone")
        assertEquals(emptyList<String>(), provider(listOf(orphan)).labels("ghost"))
    }

    @Test fun `limits, empty queries and an empty source`() {
        assertEquals(1, provider().labels("new", limit = 1).size)
        assertEquals(emptyList<String>(), provider().labels("new", limit = 0))
        assertEquals(emptyList<String>(), provider().labels("   "))
        assertEquals(emptyList<String>(), provider(emptyList()).labels("new"))
        assertEquals(
            emptyList<String>(),
            ShortcutsProvider(appsProvider(), locale = US).labels("new"),
        )
    }

    @Test fun `a new snapshot replaces the folded index`() {
        var snapshot = listOf(newTab)
        val provider = ShortcutsProvider(appsProvider(), { snapshot }, US)
        assertEquals(listOf("New tab"), provider.labels("new"))

        snapshot = listOf(home)
        assertEquals(emptyList<String>(), provider.labels("new"))
        assertEquals(listOf("Navigate home"), provider.labels("home"))
    }
}

class WebProviderTest {

    @Test fun `the row carries the raw query when web search resolves`() {
        val provider = WebProvider(WebSearchAvailability.Available)
        val result = provider.result(SearchQuery.of("  best pizza  ", US))
        assertEquals(WebResult("best pizza"), result)
        assertEquals(SearchSection.WEB, result!!.section)
    }

    @Test fun `the row is hidden when nothing resolves the intent`() {
        assertNull(WebProvider(WebSearchAvailability.Unavailable).result(SearchQuery.of("pizza", US)))
        assertNull(WebProvider().result(SearchQuery.of("pizza", US)))
    }

    @Test fun `a blank query has no web row`() {
        assertNull(WebProvider(WebSearchAvailability.Available).result(SearchQuery.of("   ", US)))
    }
}
