package com.jake.duolauncher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Debounce window for layout writes (NFR-P5): a burst of edits settles into a single write. */
internal const val PERSIST_DEBOUNCE_MILLIS = 150L

/**
 * Longest an edit may stay unwritten. A pure trailing debounce would defer indefinitely while edits
 * keep arriving faster than the window, so a stream of edits still reaches disk at this cadence.
 */
internal const val PERSIST_MAX_WAIT_MILLIS = 1_000L

/**
 * Defers a single task. Re-arming replaces the pending task rather than queueing another, which is
 * what turns a burst of edits into one write. Injectable so JVM tests drive the timer directly
 * instead of waiting on a real clock.
 */
internal interface PersistenceScheduler {
    /** Arms [task] to run after [delayMillis], cancelling any previously armed task. */
    fun schedule(delayMillis: Long, task: () -> Unit)

    /** Drops the armed task, if any. */
    fun cancel()
}

/** Production scheduler: the debounce waits on [scope], and the write runs on [context]. */
internal class CoroutinePersistenceScheduler(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
) : PersistenceScheduler {
    private val lock = Any()
    private var job: Job? = null

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        synchronized(lock) {
            job?.cancel()
            // Nothing is serialized or written here; the caller's thread only arms a timer.
            job = scope.launch(context) {
                delay(delayMillis)
                task()
            }
        }
    }

    override fun cancel() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }
}

/**
 * Coalesces layout writes. [request] marks the state dirty and re-arms the debounce; whichever of
 * the timer or [flush] wins serializes the *current* state exactly once and hands it to [write].
 *
 * Because [serialize] always reads live state, a late write can never resurrect a stale layout: the
 * holder of the lock always writes the newest state, so timer and flush cannot race destructively.
 */
internal class DebouncedPersistence(
    private val scheduler: PersistenceScheduler,
    private val serialize: () -> String,
    private val write: (String) -> Unit,
    private val debounceMillis: Long = PERSIST_DEBOUNCE_MILLIS,
    private val maxWaitMillis: Long = PERSIST_MAX_WAIT_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var dirty = false
    private var deadline = 0L

    /**
     * Records an edit. Never serializes and never touches disk on the calling thread. Re-arming
     * shortens the wait as [maxWaitMillis] approaches, so continuous editing still reaches disk.
     */
    fun request() {
        val delay = synchronized(lock) {
            val at = now()
            if (!dirty) {
                dirty = true
                deadline = at + maxWaitMillis
            }
            (deadline - at).coerceIn(0L, debounceMillis)
        }
        scheduler.schedule(delay) { writePending() }
    }

    /**
     * Writes any pending edit immediately, on the calling thread, and disarms the debounce. Called
     * where the process may be killed next (`onStop`, `onCleared`), so it must not depend on a
     * scope that has already been cancelled. A no-op when nothing is pending.
     */
    fun flush() {
        scheduler.cancel()
        writePending()
    }

    /** True while an edit has been recorded but not yet written. */
    internal val hasPendingWrite: Boolean get() = synchronized(lock) { dirty }

    private fun writePending() {
        synchronized(lock) {
            if (!dirty) return
            dirty = false
            write(serialize())
        }
    }
}
