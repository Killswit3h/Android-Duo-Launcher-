package com.jake.duolauncher.history

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val LAUNCH_HISTORY_PREFS = "launch_history"
private const val KEY_EVENTS = "events"
private const val KEY_ENABLED = "enabled"

/**
 * Launch history on disk (FR-84), in its own preferences file.
 *
 * It is deliberately not the `launcher` state file that LauncherModel owns, and no export path
 * reads it, so a layout backup carries no launch history (NFR-S4, NFR-S7). Nothing here is sent
 * anywhere; the app has no INTERNET permission.
 *
 * A launch happens on every app open, so the main thread only ever submits a task: the ring buffer
 * and every preferences read and write live on one background thread, and [events] returns an
 * immutable snapshot published from there.
 */
class LaunchHistoryStore(
    context: Context,
    private val worker: Executor = launchHistoryWorker(),
) : LaunchHistory {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(LAUNCH_HISTORY_PREFS, Context.MODE_PRIVATE)
    }
    private val buffer = InMemoryLaunchHistory()

    @Volatile private var snapshot: List<LaunchEvent> = emptyList()
    @Volatile private var recording = true

    override fun events(): List<LaunchEvent> = snapshot

    override fun enabled(): Boolean = recording

    override fun append(event: LaunchEvent) {
        if (!recording) return
        worker.execute {
            buffer.append(event)
            publish()
            prefs.edit().putString(KEY_EVENTS, encodeLaunchEvents(buffer.events())).apply()
        }
    }

    override fun clear() {
        snapshot = emptyList()
        worker.execute(::purge)
    }

    override fun setEnabled(enabled: Boolean) {
        recording = enabled
        if (!enabled) snapshot = emptyList()
        worker.execute {
            buffer.setEnabled(enabled)
            val editor = prefs.edit().putBoolean(KEY_ENABLED, enabled)
            if (!enabled) {
                buffer.clear()
                publish()
                editor.remove(KEY_EVENTS)
            }
            editor.apply()
        }
    }

    /**
     * Reads the stored history on the worker. Recording that was switched off before this process
     * started wins over anything recorded in the meantime, so the disabled state cannot be escaped
     * by restarting the launcher.
     */
    private fun load() {
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        recording = enabled
        buffer.setEnabled(enabled)
        if (!enabled) {
            purge()
            return
        }
        val stored = decodeLaunchEvents(prefs.getString(KEY_EVENTS, null))
        if (stored.isNotEmpty()) buffer.replaceWith(stored + buffer.events())
        publish()
    }

    private fun purge() {
        buffer.clear()
        publish()
        prefs.edit().remove(KEY_EVENTS).apply()
    }

    private fun publish() {
        snapshot = buffer.events()
    }

    init {
        worker.execute(::load)
    }
}

/** One low-priority thread: history work must never compete with a launch animation. */
internal fun launchHistoryWorker(): Executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "duo-launch-history").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }
}
