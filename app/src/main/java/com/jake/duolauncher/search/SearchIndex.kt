package com.jake.duolauncher.search

import com.jake.duolauncher.library.LibraryApp
import java.util.Locale

/**
 * The app index Search matches against (FR-72, NFR-P3).
 *
 * Folding a label and finding its word starts is the expensive half of matching, so it is done once
 * per catalog change — an app installed, removed, updated, relabelled or a profile change — and
 * never per keystroke. A keystroke walks [entries] and compares already-folded text.
 */

/** One app, pre-folded. [keywords] are the curated aliases and package tokens of [SearchAliases]. */
class IndexedApp internal constructor(
    val app: LibraryApp,
    internal val label: IndexedText,
    internal val keywords: List<IndexedText>,
)

/**
 * Curated synonyms for well-known apps, keyed by a substring of the package name.
 *
 * Android exposes no synonym source, so, exactly like the settings list, this is hand-curated. It
 * is what makes "sms" find an app labelled "Messages": no word in that label starts with "s", so a
 * label-only matcher can never reach it, while the alias matches in [MatchKind.KEYWORD]'s tier —
 * below every real label match, so it can never outrank one.
 *
 * Package names are matched by substring, in declaration order, and every match contributes, so the
 * result is deterministic regardless of how many entries an app matches.
 */
class SearchAliases(private val entries: List<Pair<String, List<String>>>) {

    fun keywordsFor(packageName: String): List<String> {
        if (packageName.isEmpty() || entries.isEmpty()) return emptyList()
        val lower = packageName.lowercase(Locale.ROOT)
        var collected: LinkedHashSet<String>? = null
        for ((needle, keywords) in entries) {
            if (!lower.contains(needle)) continue
            val target = collected ?: LinkedHashSet<String>(8).also { collected = it }
            target.addAll(keywords)
        }
        return collected?.toList() ?: emptyList()
    }

    companion object {
        val None = SearchAliases(emptyList())

        val Default = SearchAliases(
            listOf(
                "messag" to listOf("sms", "mms", "text", "texting", "chat"),
                "mms" to listOf("sms", "mms", "text", "texting"),
                "dialer" to listOf("phone", "call", "dial", "dialer"),
                "telecom" to listOf("phone", "call", "dialer"),
                "contacts" to listOf("people", "contacts", "address"),
                "camera" to listOf("camera", "selfie", "photo"),
                "gallery" to listOf("photos", "gallery", "pictures", "album"),
                "photos" to listOf("photos", "gallery", "pictures", "album"),
                "calculator" to listOf("calculator", "calc", "math"),
                "calendar" to listOf("calendar", "agenda", "schedule"),
                "deskclock" to listOf("clock", "alarm", "timer", "stopwatch"),
                "clock" to listOf("clock", "alarm", "timer", "stopwatch"),
                "chrome" to listOf("browser", "web", "internet"),
                "firefox" to listOf("browser", "web", "internet"),
                "browser" to listOf("browser", "web", "internet"),
                "youtube" to listOf("video", "videos", "watch"),
                "maps" to listOf("maps", "navigation", "directions"),
                "settings" to listOf("settings", "preferences", "options"),
                "gmail" to listOf("mail", "email", "inbox"),
                "outlook" to listOf("mail", "email", "inbox"),
                "vending" to listOf("store", "play", "install", "apps"),
                "documentsui" to listOf("files", "storage", "documents"),
                "files" to listOf("files", "storage", "documents"),
                "music" to listOf("music", "songs", "audio"),
                "wallet" to listOf("wallet", "pay", "payments", "cards"),
                "keep" to listOf("notes", "notepad"),
                "weather" to listOf("weather", "forecast"),
            ),
        )
    }
}

/**
 * The searchable app catalog.
 *
 * Reads take the current snapshot in one volatile read, so [setApps] can rebuild off the main
 * thread while a query is running: a query sees either the whole old catalog or the whole new one,
 * never a half-built list. Exclusions are *not* baked in — hidden apps (FR-75) and a locked private
 * space (FR-77) change without the catalog changing, so they are applied per query by
 * [AppsProvider].
 */
class SearchAppIndex(
    private val locale: Locale = Locale.getDefault(),
    private val aliases: SearchAliases = SearchAliases.Default,
) {
    private class Snapshot(val entries: List<IndexedApp>, val byId: Map<ProfileAppId, IndexedApp>) {
        companion object {
            val Empty = Snapshot(emptyList(), emptyMap())
        }
    }

    @Volatile private var snapshot: Snapshot = Snapshot.Empty

    val size: Int get() = snapshot.entries.size

    val isEmpty: Boolean get() = snapshot.entries.isEmpty()

    fun entries(): List<IndexedApp> = snapshot.entries

    fun entryFor(id: ProfileAppId): IndexedApp? = snapshot.byId[id]

    /** Rebuilds the index. Called on catalog change, off the main thread; never per keystroke. */
    fun setApps(apps: List<LibraryApp>) {
        if (apps.isEmpty()) {
            snapshot = Snapshot.Empty
            return
        }
        val entries = ArrayList<IndexedApp>(apps.size)
        val byId = LinkedHashMap<ProfileAppId, IndexedApp>(apps.size)
        for (app in apps) {
            if (byId.containsKey(app.id)) continue
            val entry = IndexedApp(app, IndexedText.of(app.label, locale), keywordsOf(app))
            entries += entry
            byId[app.id] = entry
        }
        snapshot = Snapshot(entries, byId)
    }

    private fun keywordsOf(app: LibraryApp): List<IndexedText> {
        val words = LinkedHashSet<String>(8)
        words.addAll(aliases.keywordsFor(app.packageName))
        words.addAll(packageTokens(app.packageName))
        if (words.isEmpty()) return emptyList()
        return words.asSequence().take(MAX_KEYWORDS).map { IndexedText.of(it, locale) }.toList()
    }

    companion object {
        /** A cap so a pathological package name cannot make one app's matching cost stand out. */
        const val MAX_KEYWORDS = 8

        private const val MIN_TOKEN_LENGTH = 3

        /** Segments carried by nearly every package name; as keywords they would match everything. */
        private val GENERIC_SEGMENTS = setOf(
            "com", "org", "net", "app", "apps", "android", "mobile", "client", "free", "pro", "inc",
        )

        /**
         * The distinctive segments of a package name, so "com.spotify.music" is reachable by
         * "spotify" even before its label is known, and a renamed app stays findable.
         */
        internal fun packageTokens(packageName: String): List<String> {
            if (packageName.isEmpty()) return emptyList()
            val tokens = ArrayList<String>(4)
            for (segment in packageName.lowercase(Locale.ROOT).split('.', '_', '-')) {
                if (segment.length < MIN_TOKEN_LENGTH) continue
                if (segment in GENERIC_SEGMENTS) continue
                if (segment !in tokens) tokens += segment
            }
            return tokens
        }
    }
}
