package com.jake.duolauncher.today.builtin

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The data sources behind Duo's built-in Today View widgets (FR-59): Clock, Date and Calendar,
 * Batteries, Now Playing and App Suggestions.
 *
 * This package is data only. It holds no Compose and no Android UI types, so every rule in it is
 * unit-testable on the JVM behind the fakes each feed's seam defines. The Compose widgets that
 * render these feeds are a separate task; they read [TodayFeeds] and nothing else.
 *
 * Three promises hold across every feed here:
 *
 *  * **Nothing runs while the launcher is stopped.** A feed registers its receivers, observers and
 *    subscriptions in [TodayFeed.start] and releases all of them in [TodayFeed.stop], so a
 *    backgrounded launcher costs nothing (the same contract `DeviceStatusMonitor` already keeps).
 *  * **Nothing is persisted.** Every feed's state lives in a `MutableStateFlow` and dies with the
 *    process. Calendar events in particular are queried on demand and never cached (NFR-S4).
 *  * **Nothing heavy touches the main thread.** Provider queries go through a [BackgroundRunner]
 *    (NFR-P5).
 */
interface TodayFeed {
    /** Begins observing. Calling this on an already-started feed does nothing. */
    fun start()

    /**
     * Releases every subscription and drops any data the feed was holding, so a stopped feed both
     * costs nothing and retains nothing.
     */
    fun stop()
}

/** A [TodayFeed] that starts and stops with a lifecycle, exactly as `DeviceStatusMonitor` does. */
abstract class LifecycleTodayFeed : TodayFeed, DefaultLifecycleObserver {
    final override fun onStart(owner: LifecycleOwner) = start()

    final override fun onStop(owner: LifecycleOwner) = stop()
}

/** Hands a subscription back so a feed can release it in [TodayFeed.stop]. */
fun interface TickSubscription {
    fun cancel()
}

/**
 * A minute-boundary tick source.
 *
 * Duo drives every wall-clock surface from the system's `ACTION_TIME_TICK` broadcast rather than a
 * polling loop: the platform already fires it on each minute boundary, and the companion time,
 * timezone and date broadcasts cover the cases a timer would miss (the user changing the clock,
 * crossing a timezone, or the date rolling over).
 *
 * It is an interface so the feeds stay JVM-testable; [SystemTimeTicker] is the Android
 * implementation, and it fans one registered receiver out to every subscriber.
 */
interface TimeTicker {
    /** Delivers a tick on each minute boundary until the returned subscription is cancelled. */
    fun subscribe(onTick: () -> Unit): TickSubscription
}

/**
 * Where a feed runs work that must not block the main thread (NFR-P5).
 *
 * A seam rather than a hard-coded executor so tests can run the work inline and stay deterministic.
 */
fun interface BackgroundRunner {
    fun execute(task: () -> Unit)

    companion object {
        /** Runs the task on the calling thread. For tests, and for already-background callers. */
        val Immediate = BackgroundRunner { it() }
    }
}
