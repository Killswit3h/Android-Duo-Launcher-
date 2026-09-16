package com.jake.duolauncher.shortcuts

import com.jake.duolauncher.parseProfileAppId

/**
 * The launcher's profile-aware app identity, the value produced by `profileAppId(...)`.
 *
 * Shortcuts are per-user: the same package installed in the personal and in the work profile
 * publishes two independent shortcut sets, and neither is ever queried with the other's user.
 */
typealias ProfileAppId = String

/** How many app shortcuts the context menu shows (FR-25). */
const val SHORTCUT_MENU_LIMIT: Int = 5

/** The single row the context menu shows in place of shortcuts while Duo is not Home (FR-30). */
const val SET_DEFAULT_HOME_ROW: String = "Set Duo as Home app to see shortcuts"

/** Tapping a pinned shortcut while Duo is not Home (error table). */
const val OPEN_SHORTCUT_NEEDS_HOME: String = "Set Duo as Home app to open shortcuts"

/** Fallback for a shortcut its app removed, or disabled without supplying a message. */
const val SHORTCUT_UNAVAILABLE: String = "Shortcut unavailable"

/**
 * A package in one Android user, which is the pair every `LauncherApps` shortcut call takes.
 *
 * Resolved from a [ProfileAppId] by [ShortcutRules.targetOf], so the shortcut layer never has to
 * know how the launcher encodes profile identities.
 */
data class ShortcutTarget(val packageName: String, val userSerial: Long) {
    init {
        require(packageName.isNotBlank()) { "A shortcut target always names a package" }
        require(userSerial >= 0) { "A user serial is never negative" }
    }
}

private const val PINNED_SHORTCUT_PREFIX = "duo-shortcut:v1:"

/**
 * The stable identity of a pinned shortcut placement.
 *
 * This is the triple Android itself uses to name a shortcut — package, shortcut id and user — so a
 * placement survives a reboot, an app update and a launcher restart, and the work-profile copy of
 * an app can never resolve to the personal copy's shortcut. [storageId] is what the schema-9 layout
 * stores; it is deliberately prefixed so it can never be mistaken for an app's `profileAppId`.
 */
data class PinnedShortcutKey(
    val packageName: String,
    val shortcutId: String,
    val userSerial: Long,
) {
    init {
        require(packageName.isNotBlank()) { "A pinned shortcut always names a package" }
        require(shortcutId.isNotBlank()) { "A pinned shortcut always has an id" }
        require(userSerial >= 0) { "A user serial is never negative" }
        require(':' !in packageName) { "A package name cannot contain the field separator" }
    }

    /** The layout-storable form. The shortcut id comes last, so it may itself contain `:`. */
    val storageId: String get() = "$PINNED_SHORTCUT_PREFIX$userSerial:$packageName:$shortcutId"

    val target: ShortcutTarget get() = ShortcutTarget(packageName, userSerial)
}

/** Whether a stored layout id names a pinned shortcut rather than an app or a folder. */
fun isPinnedShortcutId(id: String): Boolean = id.startsWith(PINNED_SHORTCUT_PREFIX)

/**
 * Parses [PinnedShortcutKey.storageId] back. Returns null for anything malformed, so a corrupted or
 * hand-edited layout drops the placement instead of resolving it to the wrong app or user.
 */
fun parsePinnedShortcutId(id: String): PinnedShortcutKey? {
    if (!isPinnedShortcutId(id)) return null
    val body = id.substring(PINNED_SHORTCUT_PREFIX.length)
    val serialEnd = body.indexOf(':')
    if (serialEnd <= 0) return null
    val serialText = body.substring(0, serialEnd)
    if (serialText.any { !it.isDigit() }) return null
    val serial = serialText.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val rest = body.substring(serialEnd + 1)
    val packageEnd = rest.indexOf(':')
    if (packageEnd <= 0 || packageEnd == rest.lastIndex) return null
    val packageName = rest.substring(0, packageEnd)
    val shortcutId = rest.substring(packageEnd + 1)
    if (packageName.isBlank() || shortcutId.isBlank()) return null
    return PinnedShortcutKey(packageName, shortcutId, serial)
}

/**
 * One app shortcut, flattened off `ShortcutInfo` so nothing above this layer holds an Android type.
 *
 * Only the fields the launcher actually renders or ranks on are carried. In particular no intent is
 * kept: a shortcut is always started by handing its id back to `LauncherApps.startShortcut`, which
 * is what stops Duo from ever building and firing an intent on another app's behalf.
 */
data class DuoShortcut(
    val id: String,
    val packageName: String,
    val userSerial: Long,
    val label: String,
    /** The app's own ordering within its manifest set and within its dynamic set. */
    val rank: Int = 0,
    val isDeclaredInManifest: Boolean = false,
    val isDynamic: Boolean = false,
    val isEnabled: Boolean = true,
    /** The app's explanation for a disabled shortcut, shown verbatim when it supplies one. */
    val disabledMessage: String? = null,
) {
    val key: PinnedShortcutKey get() = PinnedShortcutKey(packageName, id, userSerial)

    val target: ShortcutTarget get() = ShortcutTarget(packageName, userSerial)
}

/** Why a pinned shortcut cannot be launched right now (error table). */
enum class ShortcutUnavailableReason {
    /** Duo is not the default Home app, so shortcut queries are refused (FR-30). */
    NO_HOST_PERMISSION,

    /** The owning app disabled the shortcut but kept it. */
    DISABLED_BY_APP,

    /** The owning app removed the shortcut, or the app itself is gone. */
    REMOVED,
}

/**
 * What a stored pinned-shortcut placement currently resolves to.
 *
 * The placement always survives: an unavailable shortcut greys its icon out and offers **Remove**
 * rather than disappearing, because the app may re-enable it and because a silently vanishing icon
 * is indistinguishable from a launcher bug.
 */
sealed interface PinnedShortcutState {
    val key: PinnedShortcutKey

    data class Available(override val key: PinnedShortcutKey, val shortcut: DuoShortcut) :
        PinnedShortcutState

    data class Unavailable(
        override val key: PinnedShortcutKey,
        val reason: ShortcutUnavailableReason,
        /** The app's disabled message when it supplied one, otherwise null. */
        val message: String? = null,
    ) : PinnedShortcutState
}
