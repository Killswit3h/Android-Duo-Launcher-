package com.jake.duolauncher.shortcuts

/**
 * The two system actions [PinItemActivity] answers (FR-28). Declared here as constants so the
 * activity compares against a value it owns rather than one read out of the incoming intent.
 */
const val ACTION_CONFIRM_PIN_SHORTCUT: String = "android.content.pm.action.CONFIRM_PIN_SHORTCUT"
const val ACTION_CONFIRM_PIN_APPWIDGET: String = "android.content.pm.action.CONFIRM_PIN_APPWIDGET"

/** What an app asked to pin. */
enum class PinItemKind { SHORTCUT, APPWIDGET }

/** Why a pin request was refused. Every one of these ends the activity without side effects. */
enum class PinRequestRejection {
    /** The activity was started with an action it does not serve. */
    WRONG_ACTION,

    /**
     * `LauncherApps.getPinItemRequest` returned nothing.
     *
     * This is the forged-request case. The request rides on a system-populated extra that only the
     * platform can mint, so an app that copies the action and invents extras lands here.
     */
    NO_REQUEST,

    /** A genuine request whose type contradicts the action it arrived on. */
    TYPE_MISMATCH,

    /** The request expired, was already answered, or the requesting app went away. */
    REQUEST_INVALID,
}

/** The outcome of inspecting an incoming pin request. */
sealed interface PinRequestDecision {
    /** Show the confirmation sheet for [kind]. */
    data class Confirm(val kind: PinItemKind) : PinRequestDecision

    /** Home layout is locked (FR-49): offer **Unlock and add** instead of placing the item. */
    data class Locked(val kind: PinItemKind) : PinRequestDecision

    /** Refuse and finish. */
    data class Reject(val reason: PinRequestRejection) : PinRequestDecision
}

/**
 * The pin-request security gate (NFR-S6), kept pure so the hostile cases are unit tests rather
 * than a manual exercise with a malicious APK.
 *
 * The rule the whole activity rests on: the only thing that makes a request real is that
 * `LauncherApps.getPinItemRequest(intent)` returned it. The gate is handed the *result* of that
 * call, never the intent's extras, so there is no path through it that trusts caller-supplied data.
 */
object PinRequestGate {

    /** The kind an action asks for, or null when the action is not one of ours. */
    fun kindOf(action: String?): PinItemKind? = when (action) {
        ACTION_CONFIRM_PIN_SHORTCUT -> PinItemKind.SHORTCUT
        ACTION_CONFIRM_PIN_APPWIDGET -> PinItemKind.APPWIDGET
        else -> null
    }

    /**
     * Decides what to do with an incoming request.
     *
     * @param action the action the activity was started with.
     * @param requestKind the kind reported by the genuine request, or null when there is no genuine
     *   request — an absent or forged one.
     * @param requestValid `PinItemRequest.isValid` at the moment of inspection.
     * @param layoutLocked whether **Lock Home layout** is on (FR-49).
     */
    fun evaluate(
        action: String?,
        requestKind: PinItemKind?,
        requestValid: Boolean,
        layoutLocked: Boolean,
    ): PinRequestDecision {
        val wanted = kindOf(action) ?: return PinRequestDecision.Reject(PinRequestRejection.WRONG_ACTION)
        if (requestKind == null) return PinRequestDecision.Reject(PinRequestRejection.NO_REQUEST)
        if (requestKind != wanted) return PinRequestDecision.Reject(PinRequestRejection.TYPE_MISMATCH)
        if (!requestValid) return PinRequestDecision.Reject(PinRequestRejection.REQUEST_INVALID)
        return if (layoutLocked) PinRequestDecision.Locked(wanted) else PinRequestDecision.Confirm(wanted)
    }

    /**
     * The second gate, applied immediately before `accept()`.
     *
     * Validity is re-checked because an arbitrary amount of time passes while the sheet is on
     * screen, and the requesting app may have been uninstalled or the request answered elsewhere.
     * [alreadyHandled] makes acceptance single-shot, so a double tap, a configuration change or a
     * replayed intent cannot pin the item twice.
     */
    fun canAccept(requestValid: Boolean, alreadyHandled: Boolean, layoutLocked: Boolean): Boolean =
        requestValid && !alreadyHandled && !layoutLocked
}

/**
 * An accepted item, handed to whatever owns Home placement.
 *
 * Carries no intent and no `Parcelable`: the placer gets an identity and a label, and the system
 * has already done the pinning by the time it is called.
 */
data class PinnedItem(
    val kind: PinItemKind,
    /** Present for [PinItemKind.SHORTCUT]. */
    val shortcut: PinnedShortcutKey? = null,
    /** Present for [PinItemKind.APPWIDGET]. */
    val appWidgetProvider: String? = null,
    val label: String? = null,
)

/** Reports whether **Lock Home layout** is on (FR-49). Owned by the schema-9 settings task. */
fun interface HomeLayoutLock {
    fun isLocked(): Boolean
}

/** Turns **Lock Home layout** off, behind the sheet's **Unlock and add** (FR-49). */
fun interface HomeLayoutUnlock {
    fun unlock()
}

/**
 * Places an accepted item on Home (FR-28: first free space on the current page, or a new page).
 *
 * Returns false when there is genuinely nowhere to put it, which the sheet reports rather than
 * pretending the item was added.
 */
fun interface PinnedItemPlacer {
    fun place(item: PinnedItem): Boolean
}

/**
 * The seams [PinItemActivity] needs from parts of the launcher that are built by other tasks.
 *
 * Registered once at startup. Each one is optional, and the activity degrades honestly when a seam
 * is missing rather than guessing: an absent lock reads as unlocked, and an absent placer means the
 * system-level pin still happens and the layout task reconciles it from
 * [DuoShortcutRepository.pinnedShortcuts].
 */
object DuoPinRequests {

    @Volatile
    var layoutLock: HomeLayoutLock? = null

    @Volatile
    var unlock: HomeLayoutUnlock? = null

    @Volatile
    var placer: PinnedItemPlacer? = null

    /**
     * The cold-start fallback for [layoutLock], reading the persisted setting rather than the model.
     *
     * [PinItemActivity] is exported, so a pin request can start this process with no Home screen
     * and no [LauncherModel] behind it. In that window [layoutLock] is null, and "an unregistered
     * lock reads as unlocked" let a pin land on a layout the user had locked (FR-49). This seam
     * closes that window by answering from `launcher/state` directly.
     *
     * It is only ever consulted when [layoutLock] is absent. The live model, when it exists, is
     * always the better answer: it reflects an unlock the user performed seconds ago that has not
     * been written out yet.
     */
    @Volatile
    var persistedLock: HomeLayoutLock? = null

    /**
     * Whether **Lock Home layout** is on.
     *
     * An *unregistered* lock with no persisted fallback reads as unlocked: the setting does not
     * exist yet, and there is nothing to enforce. A *registered* lock that throws reads as locked.
     * A seam that cannot answer must not be the thing that lets an item be pinned past FR-49, and
     * the two cases are different: absent means "no such setting", failing means "the setting
     * exists and I could not read it".
     *
     * [persistedLock] is consulted only when the live [layoutLock] is absent, and it is held to the
     * same rule: if it throws, the layout reads as locked.
     */
    fun isLayoutLocked(): Boolean {
        val lock = layoutLock
        if (lock != null) return runCatching { lock.isLocked() }.getOrDefault(true)
        val persisted = persistedLock ?: return false
        return runCatching { persisted.isLocked() }.getOrDefault(true)
    }

    /** True only when an unlock seam is registered and ran without throwing. */
    fun unlockLayout(): Boolean {
        val seam = unlock ?: return false
        return runCatching { seam.unlock(); true }.getOrDefault(false)
    }

    /** True when the item has a home, or when no placer is registered yet. */
    fun place(item: PinnedItem): Boolean =
        runCatching { placer?.place(item) }.getOrNull() ?: true

    /** For tests and for process teardown. */
    fun reset() {
        layoutLock = null
        persistedLock = null
        unlock = null
        placer = null
    }
}
