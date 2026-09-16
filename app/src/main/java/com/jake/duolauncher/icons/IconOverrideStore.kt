package com.jake.duolauncher.icons

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val ICON_OVERRIDE_PREFS = "icon_overrides"
private const val KEY_OVERRIDES = "overrides"

/**
 * Per-app icon and label overrides on disk (FR-19), in their own preferences file.
 *
 * It is deliberately **not** the `launcher` state file. That file has a schema, a migration path
 * and exactly one writer (`LauncherModel`); adding a second writer for an unrelated concern would
 * mean two components racing on one JSON document and a migration that has to understand both.
 * Overrides are keyed by `ProfileAppId` and are meaningful on their own, so they get their own
 * file and their own lifetime.
 *
 * As with the launch history, the main thread only ever submits: every preferences read and write
 * happens on one background thread, and [overrides] publishes an immutable snapshot from it
 * (NFR-P5).
 */
class IconOverrideStore(
    context: Context,
    private val worker: Executor = iconOverrideWorker(),
) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(ICON_OVERRIDE_PREFS, Context.MODE_PRIVATE)
    }

    private val mutableOverrides = MutableStateFlow(IconOverrides.Empty)

    /** The current overrides. Empty until the first load completes, never null. */
    val overrides: StateFlow<IconOverrides> = mutableOverrides.asStateFlow()

    /** The override for one app, or null. */
    fun overrideFor(appId: ProfileAppId): IconOverride? = mutableOverrides.value[appId]

    /** The label to show for an app, honouring an override (FR-19). */
    fun labelFor(appId: ProfileAppId, appLabel: String): String =
        resolveLabel(appId, mutableOverrides.value, appLabel)

    /**
     * Sets or clears one app's override.
     *
     * The in-memory state is published immediately so the icon changes on the next frame, and the
     * write is queued behind it. An override that sanitizes down to nothing removes the entry
     * instead of storing a blank one, which is what "Reset both" does (FR-19).
     */
    fun set(appId: ProfileAppId, override: IconOverride?) {
        val next = mutableOverrides.value.with(appId, override)
        if (next == mutableOverrides.value) return
        mutableOverrides.value = next
        write(next)
    }

    /** Replaces the icon half of an app's override, keeping its label. */
    fun setIcon(appId: ProfileAppId, pack: String?, drawable: String?) {
        val existing = mutableOverrides.value[appId] ?: IconOverride()
        set(appId, existing.copy(pack = pack, drawable = drawable))
    }

    /** Replaces the label half of an app's override, keeping its icon. */
    fun setLabel(appId: ProfileAppId, label: String?) {
        val existing = mutableOverrides.value[appId] ?: IconOverride()
        set(appId, existing.copy(label = label))
    }

    /** Clears both halves for one app (FR-19, "reset both"). */
    fun reset(appId: ProfileAppId) = set(appId, null)

    /** Drops every override naming [packPackage], for a pack that was uninstalled. */
    fun forgetPack(packPackage: String) {
        val current = mutableOverrides.value
        val remaining = current.byApp.filterValues { it.pack != packPackage }
        if (remaining.size == current.size) return
        val next = IconOverrides(remaining)
        mutableOverrides.value = next
        write(next)
    }

    private fun write(overrides: IconOverrides) {
        worker.execute {
            prefs.edit().putString(KEY_OVERRIDES, encodeIconOverrides(overrides)).apply()
        }
    }

    init {
        worker.execute {
            val stored = decodeIconOverrides(prefs.getString(KEY_OVERRIDES, null))
            // A set() that landed before the load finishes is newer than the disk, so it wins.
            if (mutableOverrides.value.size == 0) mutableOverrides.value = stored
        }
    }
}

/** One low-priority thread: an icon override must never compete with a launch animation. */
internal fun iconOverrideWorker(): Executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "duo-icon-overrides").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }
}
