package com.jake.duolauncher.today.builtin

/**
 * A [TimeTicker] the tests drive by hand.
 *
 * [subscriberCount] is how "the feed stops costing anything when stopped" is asserted: a stopped
 * feed must leave no subscriber behind, which in production is what lets the shared receiver
 * unregister.
 */
internal class FakeTicker : TimeTicker {
    private val listeners = mutableListOf<() -> Unit>()

    val subscriberCount: Int get() = listeners.size

    override fun subscribe(onTick: () -> Unit): TickSubscription {
        listeners += onTick
        return TickSubscription { listeners -= onTick }
    }

    fun tick() = listeners.toList().forEach { it() }
}
