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

    internal fun verticalStatus(raw: String?): Boolean =
        runCatching { parse(raw)?.boolean("verticalStatus", true) }.getOrNull() ?: true

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
