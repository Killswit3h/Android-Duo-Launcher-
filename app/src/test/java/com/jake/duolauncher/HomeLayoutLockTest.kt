package com.jake.duolauncher

import com.jake.duolauncher.shortcuts.ACTION_CONFIRM_PIN_SHORTCUT
import com.jake.duolauncher.shortcuts.DuoPinRequests
import com.jake.duolauncher.shortcuts.HomeLayoutLock
import com.jake.duolauncher.shortcuts.HomeLayoutUnlock
import com.jake.duolauncher.shortcuts.PinItemKind
import com.jake.duolauncher.shortcuts.PinRequestDecision
import com.jake.duolauncher.shortcuts.PinRequestGate
import com.jake.duolauncher.shortcuts.PinnedItem
import com.jake.duolauncher.shortcuts.PinnedItemPlacer
import com.jake.duolauncher.shortcuts.PinnedShortcutKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-49 / FR-28: a locked Home layout refuses a pin request.
 *
 * `LauncherModel` registers three seams into [DuoPinRequests] in its `init` block and retracts them
 * in `onCleared`. That registration is what makes **Lock Home layout** reach [PinItemActivity] at
 * all, and before it existed the lock read as absent — which [DuoPinRequests.isLayoutLocked]
 * deliberately reports as *unlocked*, so a locked layout silently accepted pins.
 *
 * `LauncherModel` is an `AndroidViewModel`, so a JVM test cannot construct it (there is no
 * Robolectric here by design — see [DuoJson]). What this test pins down instead is the part that
 * actually carries the rule: seams shaped exactly like the model's three, driven by a real
 * [DuoSettings], composed through the real [PinRequestGate] and the real [DuoPinRequests]. If the
 * lock stops reaching the gate, or the placer stops refusing while locked, these fail.
 *
 * The one thing left to the instrumented suite is that `init` runs at all.
 */
class HomeLayoutLockTest {

    /**
     * Stands in for the model's `MutableStateFlow<LauncherState>`, holding the same
     * [LauncherState] the real seams close over.
     */
    private var state = LauncherState()

    /** The three seams, written exactly as `LauncherModel` builds them. */
    private val lock = HomeLayoutLock { state.settings.lockLayout }
    private val unlock = HomeLayoutUnlock { state = state.copy(settings = state.settings.copy(lockLayout = false)) }
    private val placer = PinnedItemPlacer { item -> place(item) }

    /** The model's `placePinnedItem` rule: refuse while locked, otherwise take the shortcut's id. */
    private var placed: String? = null
    private fun place(item: PinnedItem): Boolean {
        if (state.settings.lockLayout) return false
        val id = when (item.kind) {
            PinItemKind.SHORTCUT -> item.shortcut?.storageId ?: return false
            PinItemKind.APPWIDGET -> return true
        }
        placed = id
        return true
    }

    private val request = PinnedItem(PinItemKind.SHORTCUT, PinnedShortcutKey("com.example", "new-tab", 0L))

    private fun register() {
        DuoPinRequests.layoutLock = lock
        DuoPinRequests.unlock = unlock
        DuoPinRequests.placer = placer
    }

    private fun setLocked(locked: Boolean) {
        state = state.copy(settings = state.settings.copy(lockLayout = locked))
    }

    @After fun tearDown() = DuoPinRequests.reset()

    @Test fun `the lock setting reaches the pin gate once the model registers its seam`() {
        setLocked(true)
        // Unregistered, the lock does not exist and a locked layout is invisible to the activity.
        DuoPinRequests.reset()
        assertFalse(DuoPinRequests.isLayoutLocked())

        register()
        assertTrue(DuoPinRequests.isLayoutLocked())
    }

    @Test fun `a locked layout offers unlock instead of placing the item`() {
        register()
        setLocked(true)
        assertEquals(
            PinRequestDecision.Locked(PinItemKind.SHORTCUT),
            PinRequestGate.evaluate(
                action = ACTION_CONFIRM_PIN_SHORTCUT,
                requestKind = PinItemKind.SHORTCUT,
                requestValid = true,
                layoutLocked = DuoPinRequests.isLayoutLocked(),
            ),
        )
        // The second gate, immediately before accept(), refuses too.
        assertFalse(PinRequestGate.canAccept(requestValid = true, alreadyHandled = false,
            layoutLocked = DuoPinRequests.isLayoutLocked()))
    }

    /** The placer is the last gate: even if the sheet were bypassed, nothing lands on Home. */
    @Test fun `the placer refuses while the layout is locked`() {
        register()
        setLocked(true)
        assertFalse(DuoPinRequests.place(request))
        assertNull(placed)
    }

    @Test fun `an unlocked layout confirms the request and places the shortcut`() {
        register()
        setLocked(false)
        assertEquals(
            PinRequestDecision.Confirm(PinItemKind.SHORTCUT),
            PinRequestGate.evaluate(ACTION_CONFIRM_PIN_SHORTCUT, PinItemKind.SHORTCUT, true,
                DuoPinRequests.isLayoutLocked()),
        )
        assertTrue(DuoPinRequests.place(request))
        assertEquals(request.shortcut?.storageId, placed)
    }

    /** **Unlock and add**: the unlock seam clears the setting, and only then may the pin proceed. */
    @Test fun `unlock and add turns the setting off and lets the item through`() {
        register()
        setLocked(true)
        assertTrue(DuoPinRequests.unlockLayout())
        assertFalse(DuoPinRequests.isLayoutLocked())
        assertFalse(state.settings.lockLayout)
        assertTrue(DuoPinRequests.place(request))
        assertEquals(request.shortcut?.storageId, placed)
    }

    @Test fun `retracting the seams restores the unregistered reading`() {
        register()
        setLocked(true)
        assertTrue(DuoPinRequests.isLayoutLocked())
        // What `onCleared` does when this model owns the seams.
        DuoPinRequests.reset()
        assertFalse(DuoPinRequests.isLayoutLocked())
        assertTrue(DuoPinRequests.place(request))
    }

    // ---------------------------------------------------------------------------------------
    // FR-49 cold start. PinItemActivity is exported, so a pin request can start the process with
    // no Home screen and no LauncherModel behind it. In that window the live seam does not exist,
    // and "an unregistered lock reads as unlocked" let a pin land on a layout the user had locked.
    // ---------------------------------------------------------------------------------------

    @Test fun `the persisted lock closes the cold-start hole`() {
        DuoPinRequests.reset()
        assertFalse("no seam at all is still unlocked", DuoPinRequests.isLayoutLocked())

        DuoPinRequests.persistedLock = HomeLayoutLock { true }
        assertTrue(DuoPinRequests.isLayoutLocked())
        assertEquals(
            PinRequestDecision.Locked(PinItemKind.SHORTCUT),
            PinRequestGate.evaluate(ACTION_CONFIRM_PIN_SHORTCUT, PinItemKind.SHORTCUT, true,
                DuoPinRequests.isLayoutLocked()),
        )
        assertFalse(PinRequestGate.canAccept(requestValid = true, alreadyHandled = false,
            layoutLocked = DuoPinRequests.isLayoutLocked()))
    }

    @Test fun `the live seam wins over the persisted one in both directions`() {
        // The model is the better answer whenever it exists: it reflects an unlock the user
        // performed seconds ago that has not been written out yet, and equally a lock that has not.
        register()
        setLocked(false)
        DuoPinRequests.persistedLock = HomeLayoutLock { true }
        assertFalse(DuoPinRequests.isLayoutLocked())

        setLocked(true)
        DuoPinRequests.persistedLock = HomeLayoutLock { false }
        assertTrue(DuoPinRequests.isLayoutLocked())
    }

    @Test fun `a persisted lock that throws reads as locked`() {
        // Same rule the live seam follows: failing to answer must not be what lets a pin through.
        DuoPinRequests.reset()
        DuoPinRequests.persistedLock = HomeLayoutLock { error("unreadable payload") }
        assertTrue(DuoPinRequests.isLayoutLocked())
    }

    @Test fun `unlock and add cannot succeed on a cold start`() {
        // Nothing can persist an unlock without the model, so the sheet stays locked rather than
        // falling through to accept(). The placer is absent too, so nothing reaches Home.
        DuoPinRequests.reset()
        DuoPinRequests.persistedLock = HomeLayoutLock { true }
        assertFalse(DuoPinRequests.unlockLayout())
        assertTrue(DuoPinRequests.isLayoutLocked())
    }

    @Test fun `the state exposes the lock to the UI that has to grey out edit mode`() {
        setLocked(true)
        assertTrue(state.layoutLocked)
        setLocked(false)
        assertFalse(state.layoutLocked)
    }
}
