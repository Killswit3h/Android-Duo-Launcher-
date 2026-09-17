package com.jake.duolauncher

import android.content.Context

/**
 * The read-only view of `launcher/state` used outside [LauncherModel].
 *
 * `LauncherModel` is the only writer of the saved layout. Before schema 9 four call sites in the
 * Discover path each parsed the raw payload themselves with `org.json`, reaching directly for
 * `verticalStatus` and the presets' `dockWidth`. Those parsers were written against the schema-8
 * document, so the schema-9 rewrite would have been free to silently change what they read.
 *
 * This helper replaces all four (build plan section 2.3). Behaviour is deliberately identical to
 * what they did before, including the fallbacks: any failure to read, parse or find a field yields
 * the same default the inline `runCatching` used, because the Discover window must still open on a
 * corrupt layout.
 *
 * The two fields it exposes are top-level in both schema 8 and schema 9 (`verticalStatus`, and the
 * `compact`/`expanded` preset objects), so one reader serves an un-migrated and a migrated install
 * alike — which matters because Discover can be launched before the model has migrated anything.
 */
object LauncherStateSnapshot {

    /** The status-rail mode. Defaults to the vertical rail, as the inline readers did. */
    fun verticalStatus(context: Context): Boolean = verticalStatus(rawState(context))

    /**
     * The dock width for a window of [widthDp], from the preset that window uses.
     *
     * Mirrors the previous inline behaviour exactly: the expanded preset is chosen at the same
     * 650dp threshold, the value is clamped to the same 56–84dp range, and a missing preset,
     * missing field or unreadable payload all fall back to 68dp.
     */
    fun dockWidth(context: Context, widthDp: Float): Float = dockWidth(rawState(context), widthDp)

    /**
     * Whether **Lock Home layout** is on, read straight off disk (FR-49).
     *
     * This exists for one case: a pin request can start the process through `PinItemActivity`
     * without [LauncherModel] ever being constructed, so the live lock seam in `DuoPinRequests` is
     * null. An absent seam deliberately reads as *unlocked* — before the setting existed there was
     * nothing to enforce — and that is exactly what let a pin through on a locked layout.
     *
     * The three outcomes are deliberately distinct, and mirror what `DuoPinRequests` already
     * documents about a seam that cannot answer:
     *
     * - **Nothing saved** (a fresh install, or the `{}` default this reader is handed when the key
     *   is absent) is unlocked. The user has not set the lock, so there is nothing to enforce.
     * - **A payload older than schema 9** is unlocked. `lockLayout` arrived with schema 9, so an
     *   un-migrated document genuinely has no such setting rather than a hidden one.
     * - **A payload that exists but cannot be parsed** is *locked*. Something is saved and it
     *   cannot be read, which is the "the setting exists and I could not read it" case. Failing
     *   open there would be the FR-49 hole again, so it fails closed. The sheet then offers
     *   **Unlock and add**, which cannot succeed without the model, so nothing is placed.
     */
    fun lockLayout(context: Context): Boolean = lockLayout(rawState(context))

    internal fun verticalStatus(raw: String?): Boolean =
        runCatching { parse(raw)?.boolean("verticalStatus", true) }.getOrNull() ?: true

    internal fun lockLayout(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val parsed = parse(raw) ?: return true
        val settings = parsed.obj("settings") ?: return false
        return runCatching { settings.boolean("lockLayout", false) }.getOrDefault(true)
    }

    internal fun dockWidth(raw: String?, widthDp: Float): Float =
        runCatching {
            parse(raw)
                ?.obj(if (widthDp >= 650f) "expanded" else "compact")
                ?.let { preset -> (preset["dockWidth"] as? DuoJson.Num)?.value?.toFloat() }
                ?.coerceIn(56f, 84f)
        }.getOrNull() ?: 68f

    private fun parse(raw: String?): DuoJson.Obj? =
        runCatching { DuoJson.parse(raw ?: "{}") }.getOrNull() as? DuoJson.Obj

    private fun rawState(context: Context): String? =
        runCatching { context.getSharedPreferences("launcher", 0).getString(STATE_KEY, "{}") }.getOrNull()
}
