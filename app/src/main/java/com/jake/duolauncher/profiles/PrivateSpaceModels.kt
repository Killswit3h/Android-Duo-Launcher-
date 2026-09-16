package com.jake.duolauncher.profiles

import com.jake.duolauncher.parseProfileAppId

/** Profile-scoped app identity, the same `profileAppId` string the rest of the launcher stores. */
typealias ProfileAppId = String

/**
 * Why the private space cannot be offered (FR-76).
 *
 * The Private settings row shows the reason rather than silently hiding the feature, which is the
 * error table's "Private space API unavailable" row, so each cause is a distinct value.
 */
enum class PrivateSpaceUnsupportedReason {
    /** The private profile API landed in Android 15 (API 35); 31–34 degrade to this (NFR-C2). */
    REQUIRES_ANDROID_15,

    /** Android only grants `ACCESS_HIDDEN_PROFILES` to the app holding `ROLE_HOME`. */
    NOT_DEFAULT_HOME,

    /** The platform is capable and Duo is Home, but the user has not created a private space. */
    NO_PRIVATE_PROFILE,
}

/**
 * One private-space app, only ever published while the space is unlocked.
 *
 * Nothing here is persisted. While the space is locked the repository holds no app list at all, so
 * a backup taken in that state cannot contain private-space app identities (NFR-S7).
 */
data class PrivateSpaceApp(
    val id: ProfileAppId,
    val label: String,
    val packageName: String,
    val userSerial: Long,
)

/**
 * The frozen state shape from the build plan: `Unsupported(reason) | Locked | Unlocked(apps)`.
 *
 * [Locked] deliberately carries nothing. The profile serial the visibility rule needs stays inside
 * the repository, so no surface can read an app list — or infer one — out of the locked state.
 */
sealed interface PrivateSpaceState {
    data class Unsupported(val reason: PrivateSpaceUnsupportedReason) : PrivateSpaceState

    data object Locked : PrivateSpaceState

    data class Unlocked(val apps: List<PrivateSpaceApp>) : PrivateSpaceState
}

/** True when the state is one where a Private container could be shown at all (FR-76). */
val PrivateSpaceState.isAvailable: Boolean
    get() = this !is PrivateSpaceState.Unsupported

/**
 * The single authoritative rule behind FR-77, expressed as pure data.
 *
 * [lockedPrivateSerial] is the private profile's user serial while the space is locked, and null
 * in every other case (unlocked, absent, or unsupported). An app belongs to the locked space when
 * its id carries that serial, which is exactly the encoding `profileAppId` produces, so Home, the
 * App Library, Search, Suggestions and badges all reach the same verdict from the id alone.
 */
fun isPrivateLockedApp(app: ProfileAppId, lockedPrivateSerial: Long?): Boolean {
    val serial = lockedPrivateSerial ?: return false
    val identity = parseProfileAppId(app) ?: return false
    return identity.userSerial == serial
}

/** The keyword that reveals a hidden Private container (FR-78). */
const val PRIVATE_SPACE_REVEAL_KEYWORD: String = "private"

/**
 * FR-78's reveal rule: a hidden container comes back only once the user actually types "private".
 *
 * The whole word must be present, so no shorter prefix ("p", "pri") can surface a container the
 * user asked to hide. Matching is case-insensitive and ignores surrounding text, which lets
 * "private space" work as well as "private".
 */
fun revealsPrivateSpace(searchText: String): Boolean =
    searchText.split(' ', '\t', '\n').any { it.trim().equals(PRIVATE_SPACE_REVEAL_KEYWORD, ignoreCase = true) }

/**
 * Whether the App Library should render the Private container right now (FR-76, FR-78).
 *
 * This is data, not UI: the container is omitted while **Hide private space** is on until
 * [searchText] reveals it. The UI applies the answer; the rule lives here so Search and the
 * library cannot disagree about it.
 */
fun showsPrivateContainer(
    state: PrivateSpaceState,
    hideContainer: Boolean,
    searchText: String = "",
): Boolean = state.isAvailable && (!hideContainer || revealsPrivateSpace(searchText))
