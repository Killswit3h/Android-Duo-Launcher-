package com.jake.duolauncher.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CHROME = "com.android.chrome"
private const val CHROME_ID = "com.android.chrome/.Main"
private const val CHROME_WORK_ID = "duo-profile:v1:42:com.android.chrome/.Main"
private const val WORK_SERIAL = 42L

private fun shortcut(
    id: String,
    rank: Int = 0,
    manifest: Boolean = false,
    dynamic: Boolean = false,
    enabled: Boolean = true,
    userSerial: Long = 0L,
    disabledMessage: String? = null,
) = DuoShortcut(
    id = id,
    packageName = CHROME,
    userSerial = userSerial,
    label = id,
    rank = rank,
    isDeclaredInManifest = manifest,
    isDynamic = dynamic,
    isEnabled = enabled,
    disabledMessage = disabledMessage,
)

class ShortcutRulesTest {

    @Test fun personalIdTakesThePersonalSerial() {
        assertEquals(ShortcutTarget(CHROME, 11L), ShortcutRules.targetOf(CHROME_ID, personalSerial = 11L))
    }

    @Test fun workIdKeepsItsOwnSerialSoProfilesNeverCross() {
        assertEquals(ShortcutTarget(CHROME, WORK_SERIAL), ShortcutRules.targetOf(CHROME_WORK_ID, 0L))
    }

    @Test fun malformedIdentitiesResolveToNothing() {
        assertNull(ShortcutRules.targetOf("", 0L))
        assertNull(ShortcutRules.targetOf("/.Main", 0L))
        assertNull(ShortcutRules.targetOf("duo-profile:v1:notanumber:com.a/.M", 0L))
    }

    @Test fun manifestShortcutsSortAheadOfDynamicOnesAndEachSetOrdersByRank() {
        val ordered = ShortcutRules.order(
            listOf(
                shortcut("dynamic-b", rank = 1, dynamic = true),
                shortcut("manifest-b", rank = 1, manifest = true),
                shortcut("dynamic-a", rank = 0, dynamic = true),
                shortcut("manifest-a", rank = 0, manifest = true),
            ),
        )
        assertEquals(
            listOf("manifest-a", "manifest-b", "dynamic-a", "dynamic-b"),
            ordered.map(DuoShortcut::id),
        )
    }

    @Test fun equalRanksFallBackToTheIdSoTheMenuNeverReshuffles() {
        val tied = listOf(shortcut("c", dynamic = true), shortcut("a", dynamic = true), shortcut("b", dynamic = true))
        assertEquals(listOf("a", "b", "c"), ShortcutRules.order(tied).map(DuoShortcut::id))
        assertEquals(ShortcutRules.order(tied), ShortcutRules.order(tied.reversed()))
    }

    @Test fun theMenuShowsAtMostFive() {
        val many = (0..9).map { shortcut("s$it", rank = it, dynamic = true) }
        assertEquals(5, ShortcutRules.order(many).size)
        assertEquals(SHORTCUT_MENU_LIMIT, ShortcutRules.order(many).size)
        assertEquals(listOf("s0", "s1", "s2"), ShortcutRules.order(many, limit = 3).map(DuoShortcut::id))
        assertTrue(ShortcutRules.order(many, limit = 0).isEmpty())
    }

    @Test fun disabledAndPinnedOnlyShortcutsAreNeverOffered() {
        val mixed = listOf(
            shortcut("disabled", dynamic = true, enabled = false),
            shortcut("pinned-only"),
            shortcut("live", dynamic = true),
        )
        assertEquals(listOf("live"), ShortcutRules.order(mixed).map(DuoShortcut::id))
    }

    @Test fun noHostPermissionReadsAsSuchRatherThanAsRemoved() {
        val key = PinnedShortcutKey(CHROME, "new-tab", 0L)
        val state = ShortcutRules.resolve(key, hostPermission = false, found = shortcut("new-tab"))
        assertEquals(
            PinnedShortcutState.Unavailable(key, ShortcutUnavailableReason.NO_HOST_PERMISSION),
            state,
        )
        assertEquals(OPEN_SHORTCUT_NEEDS_HOME, ShortcutRules.tapMessage(state))
    }

    @Test fun aShortcutTheAppRemovedReadsAsRemoved() {
        val key = PinnedShortcutKey(CHROME, "gone", 0L)
        val state = ShortcutRules.resolve(key, hostPermission = true, found = null)
        assertEquals(PinnedShortcutState.Unavailable(key, ShortcutUnavailableReason.REMOVED), state)
        assertEquals(SHORTCUT_UNAVAILABLE, ShortcutRules.tapMessage(state))
    }

    @Test fun aDisabledShortcutShowsTheAppsOwnMessageWhenItSuppliedOne() {
        val key = PinnedShortcutKey(CHROME, "locked", 0L)
        val withMessage = ShortcutRules.resolve(
            key,
            hostPermission = true,
            found = shortcut("locked", enabled = false, disabledMessage = "Sign in first"),
        )
        assertEquals("Sign in first", ShortcutRules.tapMessage(withMessage))

        val withoutMessage = ShortcutRules.resolve(
            key,
            hostPermission = true,
            found = shortcut("locked", enabled = false, disabledMessage = "  "),
        )
        assertEquals(SHORTCUT_UNAVAILABLE, ShortcutRules.tapMessage(withoutMessage))
    }

    @Test fun anAvailableShortcutJustLaunches() {
        val key = PinnedShortcutKey(CHROME, "new-tab", 0L)
        val live = shortcut("new-tab", dynamic = true)
        val state = ShortcutRules.resolve(key, hostPermission = true, found = live)
        assertEquals(PinnedShortcutState.Available(key, live), state)
        assertNull(ShortcutRules.tapMessage(state))
    }

    @Test fun placementIdsRoundTripAndStayDistinctPerUser() {
        val personal = PinnedShortcutKey(CHROME, "new-tab", 0L)
        val work = PinnedShortcutKey(CHROME, "new-tab", WORK_SERIAL)
        assertEquals(personal, parsePinnedShortcutId(personal.storageId))
        assertEquals(work, parsePinnedShortcutId(work.storageId))
        assertTrue(personal.storageId != work.storageId)
        assertTrue(isPinnedShortcutId(personal.storageId))
    }

    @Test fun aShortcutIdMayContainTheFieldSeparator() {
        val key = PinnedShortcutKey(CHROME, "deep:link:1", 0L)
        assertEquals(key, parsePinnedShortcutId(key.storageId))
    }

    @Test fun placementIdsAreNeverConfusedWithAppIds() {
        assertNull(parsePinnedShortcutId(CHROME_ID))
        assertNull(parsePinnedShortcutId(CHROME_WORK_ID))
        assertTrue(!isPinnedShortcutId(CHROME_ID))
    }

    @Test fun malformedPlacementIdsAreDropped() {
        assertNull(parsePinnedShortcutId("duo-shortcut:v1:"))
        assertNull(parsePinnedShortcutId("duo-shortcut:v1:0:com.a"))
        assertNull(parsePinnedShortcutId("duo-shortcut:v1:0:com.a:"))
        assertNull(parsePinnedShortcutId("duo-shortcut:v1:x:com.a:id"))
        assertNull(parsePinnedShortcutId("duo-shortcut:v1::com.a:id"))
    }
}
