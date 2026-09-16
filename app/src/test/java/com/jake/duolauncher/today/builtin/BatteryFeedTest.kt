package com.jake.duolauncher.today.builtin

import com.jake.duolauncher.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryFeedTest {
    @Test fun mapsLevelAndChargingFromTheSharedDeviceStatus() {
        val reading = batteryReading(DeviceStatus(battery = 64, charging = true))

        assertEquals(64, reading.level)
        assertTrue(reading.charging)
        assertTrue(reading.isKnown)
    }

    @Test fun anUnreadBatteryIsUnknownRatherThanZero() {
        val reading = batteryReading(DeviceStatus(battery = null))

        assertEquals(null, reading.level)
        assertFalse(reading.isKnown)
        assertEquals(BatteryReading.Unknown, reading)
    }

    @Test fun outOfRangeLevelsAreClamped() {
        assertEquals(100, batteryReading(DeviceStatus(battery = 140)).level)
        assertEquals(0, batteryReading(DeviceStatus(battery = -5)).level)
    }

    @Test fun followsTheDeviceStatusMonitorWithoutRegisteringAnythingItself() {
        val status = MutableStateFlow(DeviceStatus(battery = 50, charging = false))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val feed = BatteryFeed(status, scope)

        feed.start()
        assertEquals(BatteryReading(50, charging = false), feed.state.value)

        status.value = DeviceStatus(battery = 51, charging = true)
        assertEquals(BatteryReading(51, charging = true), feed.state.value)

        scope.cancel()
    }

    @Test fun stoppedFeedStopsFollowingTheBattery() {
        val status = MutableStateFlow(DeviceStatus(battery = 50))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val feed = BatteryFeed(status, scope)
        feed.start()

        feed.stop()
        status.value = DeviceStatus(battery = 9, charging = true)

        assertEquals(BatteryReading(50, charging = false), feed.state.value)

        scope.cancel()
    }

    @Test fun restartingResumesFollowing() {
        val status = MutableStateFlow(DeviceStatus(battery = 50))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val feed = BatteryFeed(status, scope)
        feed.start()
        feed.stop()

        status.value = DeviceStatus(battery = 9, charging = true)
        feed.start()

        assertEquals(BatteryReading(9, charging = true), feed.state.value)

        scope.cancel()
    }
}
