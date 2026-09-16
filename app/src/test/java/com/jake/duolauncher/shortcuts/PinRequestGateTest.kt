package com.jake.duolauncher.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun evaluate(
    action: String? = ACTION_CONFIRM_PIN_SHORTCUT,
    requestKind: PinItemKind? = PinItemKind.SHORTCUT,
    requestValid: Boolean = true,
    layoutLocked: Boolean = false,
) = PinRequestGate.evaluate(action, requestKind, requestValid, layoutLocked)

class PinRequestGateTest {

    @Test fun aGenuineShortcutRequestIsConfirmed() {
        assertEquals(PinRequestDecision.Confirm(PinItemKind.SHORTCUT), evaluate())
    }

    @Test fun aGenuineWidgetRequestIsConfirmed() {
        assertEquals(
            PinRequestDecision.Confirm(PinItemKind.APPWIDGET),
            evaluate(action = ACTION_CONFIRM_PIN_APPWIDGET, requestKind = PinItemKind.APPWIDGET),
        )
    }

    /**
     * The forged case. A hostile app can copy the action into its own intent, but it cannot mint
     * the system extra that carries the request, so `getPinItemRequest` hands back null.
     */
    @Test fun anAbsentOrForgedRequestIsRejected() {
        assertEquals(
            PinRequestDecision.Reject(PinRequestRejection.NO_REQUEST),
            evaluate(requestKind = null),
        )
        assertEquals(
            PinRequestDecision.Reject(PinRequestRejection.NO_REQUEST),
            evaluate(action = ACTION_CONFIRM_PIN_APPWIDGET, requestKind = null),
        )
    }

    @Test fun anActivityStartedWithSomeOtherActionDoesNothing() {
        assertEquals(
            PinRequestDecision.Reject(PinRequestRejection.WRONG_ACTION),
            evaluate(action = "android.intent.action.VIEW"),
        )
        assertEquals(
            PinRequestDecision.Reject(PinRequestRejection.WRONG_ACTION),
            evaluate(action = null),
        )
        // Even a genuine request cannot rescue an action this activity does not serve.
        assertEquals(
            PinRequestDecision.Reject(PinRequestRejection.WRONG_ACTION),
            evaluate(action = "android.intent.action.MAIN", requestKind = PinItemKind.SHORTCUT),
        )
    }

    @Test fun aRequestWhoseTypeContradictsItsActionIsRejected() {
        assertEquals(
            PinRequestDecision.Reject(PinRequestRejection.TYPE_MISMATCH),
            evaluate(requestKind = PinItemKind.APPWIDGET),
        )
        assertEquals(
            PinRequestDecision.Reject(PinRequestRejection.TYPE_MISMATCH),
            evaluate(action = ACTION_CONFIRM_PIN_APPWIDGET, requestKind = PinItemKind.SHORTCUT),
        )
    }

    @Test fun anExpiredOrAlreadyAnsweredRequestIsRejected() {
        assertEquals(
            PinRequestDecision.Reject(PinRequestRejection.REQUEST_INVALID),
            evaluate(requestValid = false),
        )
    }

    @Test fun aLockedHomeLayoutOffersUnlockInsteadOfPlacing() {
        assertEquals(PinRequestDecision.Locked(PinItemKind.SHORTCUT), evaluate(layoutLocked = true))
    }

    @Test fun validityIsDecidedBeforeTheLock() {
        // A locked layout must never make an invalid request look answerable.
        assertEquals(
            PinRequestDecision.Reject(PinRequestRejection.REQUEST_INVALID),
            evaluate(requestValid = false, layoutLocked = true),
        )
    }

    @Test fun acceptanceNeedsAStillValidUnlockedRequest() {
        assertTrue(PinRequestGate.canAccept(requestValid = true, alreadyHandled = false, layoutLocked = false))
        assertFalse(PinRequestGate.canAccept(requestValid = false, alreadyHandled = false, layoutLocked = false))
        assertFalse(PinRequestGate.canAccept(requestValid = true, alreadyHandled = false, layoutLocked = true))
    }

    @Test fun acceptanceIsSingleShot() {
        // A double tap, a replayed intent or a configuration change cannot pin the item twice.
        assertFalse(PinRequestGate.canAccept(requestValid = true, alreadyHandled = true, layoutLocked = false))
    }

    @Test fun onlyTheTwoConfirmActionsMapToAKind() {
        assertEquals(PinItemKind.SHORTCUT, PinRequestGate.kindOf(ACTION_CONFIRM_PIN_SHORTCUT))
        assertEquals(PinItemKind.APPWIDGET, PinRequestGate.kindOf(ACTION_CONFIRM_PIN_APPWIDGET))
        assertEquals(null, PinRequestGate.kindOf("android.content.pm.action.CONFIRM_PIN"))
        assertEquals(null, PinRequestGate.kindOf(""))
        assertEquals(null, PinRequestGate.kindOf(null))
    }

    @Test fun anUnregisteredLockReadsAsUnlockedButAThrowingOneFailsSafe() {
        DuoPinRequests.reset()
        // Nothing registered: the lock setting does not exist yet, so there is nothing to enforce.
        assertFalse(DuoPinRequests.isLayoutLocked())

        // Registered but unable to answer. The setting exists, so the safe reading is "locked": a
        // seam that cannot be read must not become the reason an item is pinned past FR-49.
        DuoPinRequests.layoutLock = HomeLayoutLock { throw IllegalStateException("settings not ready") }
        assertTrue(DuoPinRequests.isLayoutLocked())

        DuoPinRequests.layoutLock = HomeLayoutLock { true }
        assertTrue(DuoPinRequests.isLayoutLocked())
        DuoPinRequests.layoutLock = HomeLayoutLock { false }
        assertFalse(DuoPinRequests.isLayoutLocked())
        DuoPinRequests.reset()
    }

    /**
     * **Unlock and add** may only lead to a pin when an unlock genuinely happened. The activity
     * gates `accept()` on this returning true, so an absent or throwing seam keeps the sheet locked.
     */
    @Test fun unlockOnlySucceedsWhenASeamActuallyRan() {
        DuoPinRequests.reset()
        assertFalse(DuoPinRequests.unlockLayout())

        DuoPinRequests.unlock = HomeLayoutUnlock { throw IllegalStateException("settings not ready") }
        assertFalse(DuoPinRequests.unlockLayout())

        var unlocked = false
        DuoPinRequests.unlock = HomeLayoutUnlock { unlocked = true }
        assertTrue(DuoPinRequests.unlockLayout())
        assertTrue(unlocked)
        DuoPinRequests.reset()
    }

    @Test fun placementSucceedsByDefaultAndSurvivesAThrowingPlacer() {
        DuoPinRequests.reset()
        val item = PinnedItem(PinItemKind.SHORTCUT, PinnedShortcutKey("com.example", "id", 0L))
        // No placer registered yet: the system-level pin still stands and is reconciled later.
        assertTrue(DuoPinRequests.place(item))

        DuoPinRequests.placer = PinnedItemPlacer { throw IllegalStateException("layout not ready") }
        assertTrue(DuoPinRequests.place(item))

        DuoPinRequests.placer = PinnedItemPlacer { false }
        assertFalse(DuoPinRequests.place(item))
        DuoPinRequests.reset()
    }
}
