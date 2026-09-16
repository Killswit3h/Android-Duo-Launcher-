package com.jake.duolauncher.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PRIVATE_SERIAL = 1000L
private const val WORK_SERIAL = 42L
private const val VAULT_ID = "duo-profile:v1:1000:com.example.vault/.MainActivity"
private const val WORK_MAIL_ID = "duo-profile:v1:42:com.example.mail/.MainActivity"
private const val PERSONAL_MAIL_ID = "com.example.mail/.MainActivity"

private val vault = PrivateSpaceApp(VAULT_ID, "Vault", "com.example.vault", PRIVATE_SERIAL)

/** A fake platform boundary; the project has no mocking library. */
private class FakePrivateSpaceSystem(
    var probe: PrivateSpaceProbe,
    var apps: List<PrivateSpaceApp> = listOf(vault),
    private val requestSucceeds: Boolean = true,
) : PrivateSpaceSystem {

    var appQueries = 0
        private set
    var requests = mutableListOf<Boolean>()
        private set
    var observers = 0
        private set

    private var listener: (() -> Unit)? = null

    override fun probe(): PrivateSpaceProbe = probe

    override fun appsFor(userSerial: Long): List<PrivateSpaceApp> {
        appQueries++
        return apps
    }

    override fun requestLocked(userSerial: Long, locked: Boolean): Boolean {
        requests += locked
        if (!requestSucceeds) return false
        probe = PrivateSpaceProbe.Present(userSerial, locked)
        return true
    }

    override fun observe(onProfileAvailabilityChanged: () -> Unit) {
        observers++
        listener = onProfileAvailabilityChanged
    }

    /** Simulates ACTION_PROFILE_AVAILABLE / ACTION_PROFILE_UNAVAILABLE arriving. */
    fun broadcastLocked(locked: Boolean) {
        probe = PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked)
        listener?.invoke()
    }
}

private fun attached(system: PrivateSpaceSystem): DuoPrivateSpaceRepository =
    DuoPrivateSpaceRepository(system).apply { attach() }

private fun unsupported(reason: PrivateSpaceUnsupportedReason) =
    FakePrivateSpaceSystem(PrivateSpaceProbe.Unsupported(reason))

class PrivateSpaceCapabilityTest {

    @Test fun belowAndroid15ReportsTheApiLevelReason() {
        val repository = attached(unsupported(PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15))
        assertEquals(
            PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15),
            repository.state.value,
        )
    }

    @Test fun notBeingDefaultHomeIsItsOwnReason() {
        val repository = attached(unsupported(PrivateSpaceUnsupportedReason.NOT_DEFAULT_HOME))
        assertEquals(
            PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.NOT_DEFAULT_HOME),
            repository.state.value,
        )
    }

    @Test fun acapableDeviceWithoutAProfileReportsNoProfile() {
        val repository = attached(unsupported(PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE))
        assertEquals(
            PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE),
            repository.state.value,
        )
    }

    @Test fun anUnsupportedDeviceIsNeverQueriedForApps() {
        val system = unsupported(PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15)
        val repository = attached(system)
        assertEquals(0, system.appQueries)
        assertFalse(repository.state.value.isAvailable)
    }

    @Test fun setLockedIsANoOpWhenThereIsNoPrivateSpace() {
        val system = unsupported(PrivateSpaceUnsupportedReason.NOT_DEFAULT_HOME)
        attached(system).setLocked(false)
        assertTrue(system.requests.isEmpty())
    }
}

class PrivateSpaceLockTransitionTest {

    @Test fun anUnlockedSpacePublishesItsApps() {
        val repository = attached(FakePrivateSpaceSystem(PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked = false)))
        assertEquals(PrivateSpaceState.Unlocked(listOf(vault)), repository.state.value)
    }

    @Test fun lockingHidesTheAppListEntirely() {
        val system = FakePrivateSpaceSystem(PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked = false))
        val repository = attached(system)
        repository.setLocked(true)
        assertEquals(PrivateSpaceState.Locked, repository.state.value)
        assertEquals(listOf(true), system.requests)
    }

    @Test fun unlockingRepublishesTheApps() {
        val system = FakePrivateSpaceSystem(PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked = true))
        val repository = attached(system)
        assertEquals(PrivateSpaceState.Locked, repository.state.value)
        repository.setLocked(false)
        assertEquals(PrivateSpaceState.Unlocked(listOf(vault)), repository.state.value)
    }

    @Test fun aRefusedRequestLeavesTheStateUnchanged() {
        val system = FakePrivateSpaceSystem(
            PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked = true),
            requestSucceeds = false,
        )
        val repository = attached(system)
        repository.setLocked(false)
        assertEquals(PrivateSpaceState.Locked, repository.state.value)
        assertTrue(repository.gate.isHiddenWhileLocked(VAULT_ID))
    }

    /** NFR-S7: while locked the repository holds no app identities to leak into a backup. */
    @Test fun aLockedSpaceIsNeverQueriedForAppIdentities() {
        val system = FakePrivateSpaceSystem(PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked = true))
        val repository = attached(system)
        assertEquals(0, system.appQueries)
        repository.setLocked(true)
        assertEquals(0, system.appQueries)
    }
}

class PrivateSpaceBroadcastTest {

    @Test fun attachRegistersExactlyOneObserver() {
        val system = FakePrivateSpaceSystem(PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked = false))
        val repository = DuoPrivateSpaceRepository(system)
        repository.attach()
        repository.attach()
        assertEquals(1, system.observers)
    }

    /** The user locks the space from system UI: Duo must follow without being asked. */
    @Test fun aProfileUnavailableBroadcastLocksTheState() {
        val system = FakePrivateSpaceSystem(PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked = false))
        val repository = attached(system)
        assertTrue(repository.state.value is PrivateSpaceState.Unlocked)
        system.broadcastLocked(true)
        assertEquals(PrivateSpaceState.Locked, repository.state.value)
        assertTrue(repository.gate.isHiddenWhileLocked(VAULT_ID))
    }

    @Test fun aProfileAvailableBroadcastUnlocksTheState() {
        val system = FakePrivateSpaceSystem(PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked = true))
        val repository = attached(system)
        system.broadcastLocked(false)
        assertEquals(PrivateSpaceState.Unlocked(listOf(vault)), repository.state.value)
        assertFalse(repository.gate.isHiddenWhileLocked(VAULT_ID))
    }

    @Test fun aProfileRemovedWhileRunningFallsBackToUnsupported() {
        val system = FakePrivateSpaceSystem(PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked = true))
        val repository = attached(system)
        system.probe = PrivateSpaceProbe.Unsupported(PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE)
        repository.refresh()
        assertEquals(
            PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE),
            repository.state.value,
        )
        // Nothing is private any more, so nothing stays hidden on the strength of a stale serial.
        assertFalse(repository.gate.isHiddenWhileLocked(VAULT_ID))
    }
}

/** FR-77: the one predicate every surface uses. */
class PrivateSpaceGateTest {

    private fun gateWith(locked: Boolean): PrivateSpaceGate =
        attached(FakePrivateSpaceSystem(PrivateSpaceProbe.Present(PRIVATE_SERIAL, locked))).gate

    @Test fun aLockedPrivateAppIsExcludedEverywhere() {
        val gate = gateWith(locked = true)
        // Home, App Library, Search and Suggestions all go through this id-based predicate.
        assertTrue(gate.isHiddenWhileLocked(VAULT_ID))
        assertTrue(gate.asPredicate()(VAULT_ID))
        // Badges resolve by profile serial rather than by id.
        assertTrue(gate.isLockedProfile(PRIVATE_SERIAL))
    }

    @Test fun personalAndWorkAppsAreNeverExcluded() {
        val gate = gateWith(locked = true)
        assertFalse(gate.isHiddenWhileLocked(PERSONAL_MAIL_ID))
        assertFalse(gate.isHiddenWhileLocked(WORK_MAIL_ID))
        assertFalse(gate.isLockedProfile(WORK_SERIAL))
    }

    @Test fun anUnlockedSpaceExcludesNothing() {
        val gate = gateWith(locked = false)
        assertFalse(gate.isHiddenWhileLocked(VAULT_ID))
        assertFalse(gate.isLockedProfile(PRIVATE_SERIAL))
    }

    @Test fun anUnsupportedDeviceExcludesNothing() {
        val gate = attached(unsupported(PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15)).gate
        assertFalse(gate.isHiddenWhileLocked(VAULT_ID))
        assertFalse(gate.isHiddenWhileLocked(PERSONAL_MAIL_ID))
    }

    @Test fun malformedAndBlankIdsAreNotTreatedAsPrivate() {
        val gate = gateWith(locked = true)
        assertFalse(gate.isHiddenWhileLocked(""))
        assertFalse(gate.isHiddenWhileLocked("duo-profile:v1:notaserial:com.example/.A"))
    }

    @Test fun theRuleIsPureAndSerialScoped() {
        assertTrue(isPrivateLockedApp(VAULT_ID, PRIVATE_SERIAL))
        assertFalse(isPrivateLockedApp(VAULT_ID, WORK_SERIAL))
        assertFalse(isPrivateLockedApp(VAULT_ID, null))
    }
}

/** FR-78: the container is data-driven, the UI only renders the answer. */
class PrivateSpaceContainerVisibilityTest {

    private val unlocked = PrivateSpaceState.Unlocked(listOf(vault))

    @Test fun theContainerShowsWhenTheSettingIsOff() {
        assertTrue(showsPrivateContainer(unlocked, hideContainer = false))
        assertTrue(showsPrivateContainer(PrivateSpaceState.Locked, hideContainer = false))
    }

    @Test fun hidingOmitsTheContainerUntilTheKeywordIsTyped() {
        assertFalse(showsPrivateContainer(unlocked, hideContainer = true))
        assertFalse(showsPrivateContainer(unlocked, hideContainer = true, searchText = "pri"))
        assertTrue(showsPrivateContainer(unlocked, hideContainer = true, searchText = "private"))
        assertTrue(showsPrivateContainer(unlocked, hideContainer = true, searchText = "  Private  "))
        assertTrue(showsPrivateContainer(unlocked, hideContainer = true, searchText = "private space"))
    }

    @Test fun anUnsupportedDeviceNeverShowsAContainer() {
        val absent = PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE)
        assertFalse(showsPrivateContainer(absent, hideContainer = false))
        assertFalse(showsPrivateContainer(absent, hideContainer = true, searchText = "private"))
    }

    @Test fun aShorterPrefixNeverRevealsAHiddenContainer() {
        assertFalse(revealsPrivateSpace(""))
        assertFalse(revealsPrivateSpace("p"))
        assertFalse(revealsPrivateSpace("privat"))
        assertTrue(revealsPrivateSpace("PRIVATE"))
    }
}
