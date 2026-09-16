package com.jake.duolauncher.search

/**
 * Whether anything on this device can handle `Intent.ACTION_WEB_SEARCH`.
 *
 * Production passes the `PackageManager`-backed check from `SearchIntents.kt`. It is a seam because
 * the answer decides a user-visible row and must therefore be testable without a device.
 */
fun interface WebSearchAvailability {
    fun canSearchWeb(): Boolean

    companion object {
        val Available: WebSearchAvailability = WebSearchAvailability { true }

        /** The safe default: no resolver has been wired yet, so the row stays hidden. */
        val Unavailable: WebSearchAvailability = WebSearchAvailability { false }
    }
}

/**
 * The "Search the web" hand-off row of FR-72.
 *
 * The row is the last section, so it is the fallback the error table describes: a query with no
 * results shows "No results" plus this row. When no activity resolves `ACTION_WEB_SEARCH` the row
 * is hidden instead, which is the table's other web row. Duo itself never touches the network
 * (NFR-S1) — the row hands the query to whatever app owns web search.
 */
class WebProvider(
    private val availability: WebSearchAvailability = WebSearchAvailability.Unavailable,
) {
    fun result(query: SearchQuery): WebResult? {
        if (query.isBlank) return null
        if (!availability.canSearchWeb()) return null
        return WebResult(query.raw)
    }
}
