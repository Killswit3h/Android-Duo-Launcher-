package com.jake.duolauncher.today.builtin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The launcher's wall-clock tick source, backed by the system's time broadcasts.
 *
 * `ACTION_TIME_TICK` cannot be received by a manifest-declared receiver, so it has to be registered
 * from code while the launcher is running. That is exactly the behaviour every clock surface wants:
 * one registration while visible, none at all while stopped.
 *
 * The three companion actions matter as much as the minute tick. `TIME_CHANGED` covers the user
 * setting the clock, `TIMEZONE_CHANGED` covers travel and DST, and `DATE_CHANGED` covers midnight,
 * none of which a one-minute timer would notice promptly.
 *
 * One receiver serves every subscriber. It is registered when the first subscriber arrives and
 * unregistered when the last one leaves, so the cost is a single registration no matter how many
 * widgets are on screen, and zero when none are.
 */
class SystemTimeTicker(context: Context) : TimeTicker {
    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            // A misbehaving subscriber must not stop the others from ticking.
            listeners.forEach { listener -> runCatching { listener() } }
        }
    }

    override fun subscribe(onTick: () -> Unit): TickSubscription {
        synchronized(this) {
            listeners.add(onTick)
            register()
        }
        return TickSubscription { unsubscribe(onTick) }
    }

    private fun unsubscribe(onTick: () -> Unit) {
        synchronized(this) {
            listeners.remove(onTick)
            if (listeners.isEmpty()) unregister()
        }
    }

    private fun register() {
        if (registered || listeners.isEmpty()) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        registered = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        }.getOrDefault(false)
    }

    private fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }
}

/**
 * The process-wide [SystemTimeTicker].
 *
 * Shared so that the Today View widgets, and eventually the other clock surfaces in the launcher,
 * all hang off one registration rather than each keeping their own.
 */
object DuoTicker {
    @Volatile
    private var instance: SystemTimeTicker? = null

    fun of(context: Context): SystemTimeTicker =
        instance ?: synchronized(this) {
            instance ?: SystemTimeTicker(context.applicationContext).also { instance = it }
        }
}
