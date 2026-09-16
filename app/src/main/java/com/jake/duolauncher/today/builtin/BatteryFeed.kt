package com.jake.duolauncher.today.builtin

import com.jake.duolauncher.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the Batteries widget draws: the device's charge and whether it is charging (FR-59).
 *
 * [level] is null until the first battery broadcast arrives, which the widget shows as a placeholder
 * rather than as 0%.
 */
data class BatteryReading(val level: Int? = null, val charging: Boolean = false) {
    val isKnown: Boolean get() = level != null

    companion object {
        val Unknown = BatteryReading()
    }
}

/**
 * The pure mapping from the launcher's device status to a battery reading.
 *
 * Kept as a free function so it is unit-testable without any Android plumbing, matching how
 * `StatusSignalMapping` handles the status rail's signal icons.
 */
fun batteryReading(status: DeviceStatus): BatteryReading =
    BatteryReading(level = status.battery?.coerceIn(0, 100), charging = status.charging)

/**
 * Device battery for the Batteries widget (FR-59).
 *
 * **This feed registers nothing.** `DeviceStatusMonitor` already owns the `ACTION_BATTERY_CHANGED`
 * receiver for the status rail and already starts and stops it with the launcher's lifecycle, so
 * this feed simply projects that existing `StateFlow` into the widget's shape. A second battery
 * receiver would be redundant work for identical data, so there is not one, and `DeviceStatus.kt`
 * needed no change to support this.
 *
 * The collection is cancelled in [stop], so a stopped launcher holds no subscription; the underlying
 * monitor has independently unregistered its receiver by then.
 */
class BatteryFeed(
    private val status: StateFlow<DeviceStatus>,
    private val scope: CoroutineScope,
) : LifecycleTodayFeed() {

    private val mutable = MutableStateFlow(BatteryReading.Unknown)

    /** Current charge and charging state. */
    val state: StateFlow<BatteryReading> = mutable.asStateFlow()

    private var job: Job? = null

    override fun start() {
        if (job != null) return
        job = scope.launch {
            status.collect { mutable.value = batteryReading(it) }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
