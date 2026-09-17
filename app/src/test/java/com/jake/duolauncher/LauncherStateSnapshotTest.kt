package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-only view the Discover path uses instead of parsing `launcher/state` itself.
 *
 * These tests exist to pin the behaviour the four replaced call sites had, because Discover must
 * keep working across the schema-9 change and on a payload the model has not migrated yet. The
 * fallbacks matter as much as the happy path: every one of the old readers fell back to a default
 * rather than failing, and so must this one.
 */
class LauncherStateSnapshotTest {

    private val v8 = """{"schema":8,"verticalStatus":false,""" +
        """"compact":{"iconSize":61,"rowGap":7,"dockWidth":67,"dockPosition":0.44,"dockAlignToGrid":false},""" +
        """"expanded":{"iconSize":62,"rowGap":9,"dockWidth":72,"dockPosition":0.55,"dockAlignToGrid":true}}"""

    @Test fun `reads the status mode from a schema 8 payload`() {
        assertTrue(!LauncherStateSnapshot.verticalStatus(v8))
    }

    @Test fun `reads the status mode from a schema 9 payload`() {
        val encoded = encodeLauncherState(LauncherPersistedState(verticalStatus = false))
        assertTrue(!LauncherStateSnapshot.verticalStatus(encoded))
        val on = encodeLauncherState(LauncherPersistedState(verticalStatus = true))
        assertTrue(LauncherStateSnapshot.verticalStatus(on))
    }

    @Test fun `defaults to the vertical rail when the field is missing or unreadable`() {
        listOf(null, "", "{}", "not json", """{"schema":9}""").forEach {
            assertTrue("\"$it\" must default to true", LauncherStateSnapshot.verticalStatus(it))
        }
    }

    @Test fun `picks the preset for the window width at the same 650dp threshold`() {
        assertEquals(67f, LauncherStateSnapshot.dockWidth(v8, 475f), 0.001f)
        assertEquals(72f, LauncherStateSnapshot.dockWidth(v8, 933f), 0.001f)
        assertEquals(72f, LauncherStateSnapshot.dockWidth(v8, 650f), 0.001f)
        assertEquals(67f, LauncherStateSnapshot.dockWidth(v8, 649f), 0.001f)
    }

    @Test fun `dock width falls back to 68 when it cannot be read`() {
        listOf(null, "", "{}", "not json", """{"compact":{}}""").forEach {
            assertEquals("\"$it\" must default", 68f, LauncherStateSnapshot.dockWidth(it, 475f), 0.001f)
        }
    }

    @Test fun `dock width is clamped to the supported range`() {
        val tiny = """{"compact":{"dockWidth":1}}"""
        val huge = """{"compact":{"dockWidth":9999}}"""
        assertEquals(56f, LauncherStateSnapshot.dockWidth(tiny, 475f), 0.001f)
        assertEquals(84f, LauncherStateSnapshot.dockWidth(huge, 475f), 0.001f)
    }

    @Test fun `a schema 9 payload still exposes the dock width Discover needs`() {
        val encoded = encodeLauncherState(
            LauncherPersistedState(
                compact = LayoutPreset(dockWidth = 67f),
                expanded = LayoutPreset(dockWidth = 72f),
            ),
        )
        assertEquals(67f, LauncherStateSnapshot.dockWidth(encoded, 475f), 0.001f)
        assertEquals(72f, LauncherStateSnapshot.dockWidth(encoded, 933f), 0.001f)
    }

    // ---------------------------------------------------------------------------------------
    // FR-49: the persisted lock, read when PinItemActivity starts the process cold.
    // ---------------------------------------------------------------------------------------

    @Test fun `reads the layout lock from a schema 9 payload`() {
        val locked = encodeLauncherState(
            LauncherPersistedState(settings = DuoSettings(lockLayout = true)),
        )
        val unlocked = encodeLauncherState(
            LauncherPersistedState(settings = DuoSettings(lockLayout = false)),
        )
        assertTrue(LauncherStateSnapshot.lockLayout(locked))
        assertTrue(!LauncherStateSnapshot.lockLayout(unlocked))
    }

    @Test fun `nothing saved yet reads as unlocked`() {
        // The user has not set the lock, so there is nothing to enforce. "{}" is what the reader is
        // handed when the preferences key is absent, so it has to behave like null.
        listOf(null, "", "   ", "{}").forEach {
            assertTrue("\"$it\" must read as unlocked", !LauncherStateSnapshot.lockLayout(it))
        }
    }

    @Test fun `a payload older than schema 9 reads as unlocked`() {
        // lockLayout arrived with schema 9. A v8 document genuinely has no such setting rather
        // than a hidden one, so reporting it locked would refuse pins nobody asked to refuse.
        assertTrue(!LauncherStateSnapshot.lockLayout(v8))
        assertTrue(!LauncherStateSnapshot.lockLayout("""{"schema":9}"""))
        assertTrue(!LauncherStateSnapshot.lockLayout("""{"schema":9,"settings":{}}"""))
    }

    @Test fun `a saved payload that cannot be parsed reads as locked`() {
        // The other half of the rule DuoPinRequests already documents: absent means "no such
        // setting", unreadable means "the setting exists and I could not read it". Failing open
        // here is the FR-49 hole this reader was added to close.
        listOf("not json", "{", """{"schema":9,"settings":""").forEach {
            assertTrue("\"$it\" must fail closed", LauncherStateSnapshot.lockLayout(it))
        }
    }

    @Test fun `an integer dock width is read as well as a decimal one`() {
        assertEquals(67f, LauncherStateSnapshot.dockWidth("""{"compact":{"dockWidth":67}}""", 475f), 0.001f)
        assertEquals(67.5f, LauncherStateSnapshot.dockWidth("""{"compact":{"dockWidth":67.5}}""", 475f), 0.001f)
    }
}
