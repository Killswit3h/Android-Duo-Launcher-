package com.jake.duolauncher.profiles

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.jake.duolauncher.profileAppId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Android version that introduced private profiles. */
private const val PRIVATE_SPACE_SDK = 35

/**
 * The private space data layer (FR-76, FR-77, FR-78).
 *
 * This is the frozen contract from the build plan and nothing is added to it: surfaces that only
 * render the container need [state], and the lock control needs [setLocked]. The visibility rule
 * other subsystems consume lives on [DuoPrivateSpaceRepository.gate].
 */
interface PrivateSpaceRepository {
    /** Unsupported(reason) | Locked | Unlocked(apps). Never null, safe to collect from API 31. */
    val state: StateFlow<PrivateSpaceState>

    /** Locks or unlocks the space via quiet mode. A refused request leaves [state] unchanged. */
    fun setLocked(locked: Boolean)
}

/**
 * What the platform reports about the private profile.
 *
 * [Present] is only produced when the launcher may actually use the profile, so every caller below
 * this boundary can treat a serial as usable.
 */
sealed interface PrivateSpaceProbe {
    data class Unsupported(val reason: PrivateSpaceUnsupportedReason) : PrivateSpaceProbe

    data class Present(val userSerial: Long, val locked: Boolean) : PrivateSpaceProbe

    /**
     * The platform could not be read at all: a role check, profile enumeration or serial lookup
     * failed.
     *
     * This is deliberately distinct from [Unsupported]. "There is no private profile" and "I cannot
     * tell whether there is one" have opposite safe answers, and collapsing them into one value is
     * what would let a transient binder failure reveal a locked space (FR-77).
     */
    data object Unreadable : PrivateSpaceProbe
}

/**
 * The Android boundary, kept as a seam so the repository holds no framework types and the whole
 * state machine is exercisable from plain JVM unit tests — the same shape `BadgeRepository` and
 * `PostureProvider` use.
 */
interface PrivateSpaceSystem {
    /** Current capability and lock state. Called on every refresh; must not throw. */
    fun probe(): PrivateSpaceProbe

    /** The profile's launchable apps. Only ever called while the space is unlocked. */
    fun appsFor(userSerial: Long): List<PrivateSpaceApp>

    /** Requests quiet mode. Returns false when the platform refused the request. */
    fun requestLocked(userSerial: Long, locked: Boolean): Boolean

    /** Registers for the profile availability broadcasts. Idempotent. */
    fun observe(onProfileAvailabilityChanged: () -> Unit)
}

/**
 * The one place FR-77 is decided.
 *
 * Every surface that can show an app — Home, the App Library, Search, Suggestions and badges —
 * asks this gate instead of reimplementing the rule, so a locked private app cannot leak through a
 * surface that forgot to check. The gate reads the repository's live state, so it flips the moment
 * the space locks, including when the user locks it from system UI.
 */
class PrivateSpaceGate internal constructor(private val lockedSerial: () -> Long?) {

    /** True when [app] belongs to a private space that is locked right now. */
    fun isHiddenWhileLocked(app: ProfileAppId): Boolean = isPrivateLockedApp(app, lockedSerial())

    /** Profile-level form, for callers that already know the serial (badges resolve by serial). */
    fun isLockedProfile(userSerial: Long): Boolean = lockedSerial() == userSerial

    /** Ready to drop into `SuggestionExclusions.isPrivateSpaceLocked` and the search providers. */
    fun asPredicate(): (ProfileAppId) -> Boolean = ::isHiddenWhileLocked
}

/**
 * The shipped repository.
 *
 * It keeps exactly one piece of derived state — the private profile's serial while that profile is
 * locked — and publishes an app list only while the space is unlocked. Nothing is written to disk,
 * to `SharedPreferences` or to a log, and the app declares `android:allowBackup="false"`, so no
 * private-space app identity can reach a backup while the space is locked (NFR-S7).
 *
 * Callbacks arrive on the main thread from the profile broadcasts; the mutators are synchronized so
 * a refresh from another thread cannot interleave.
 */
class DuoPrivateSpaceRepository(private val system: PrivateSpaceSystem) : PrivateSpaceRepository {

    private val mutable = MutableStateFlow<PrivateSpaceState>(
        PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15),
    )
    override val state: StateFlow<PrivateSpaceState> = mutable.asStateFlow()

    /** Non-null while a private profile is locked, or while its state cannot be established. */
    @Volatile private var lockedPrivateSerial: Long? = null

    /**
     * The last serial positively identified as the private profile's.
     *
     * Kept so that losing *visibility* of the profile — Duo stops being Home, a binder call fails —
     * can fail closed on the serial we already know rather than opening the gate (FR-77).
     */
    @Volatile private var lastKnownPrivateSerial: Long? = null

    /** The FR-77 predicate every other subsystem is wired to. */
    val gate: PrivateSpaceGate = PrivateSpaceGate { lockedPrivateSerial }

    private var observing = false

    /**
     * Notified after every state change.
     *
     * Surfaces that cache a projection of the gate — badges keep a per-app map — must recompute when
     * the space locks, otherwise they keep rendering a stale answer until their own next event.
     * Listeners are invoked without the repository's monitor being required by [gate], which reads a
     * volatile field, so a listener may call straight back into the gate.
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOnChanged(listener: () -> Unit) {
        listeners += listener
    }

    /** Reads the current state and starts reacting to lock changes made outside the launcher. */
    @Synchronized
    fun attach() {
        if (!observing) {
            observing = true
            system.observe(::refresh)
        }
        refresh()
    }

    /**
     * Re-reads the platform. This is what the availability broadcasts call, so locking the space
     * from system UI reaches every surface without the launcher being in the foreground.
     */
    @Synchronized
    fun refresh() {
        val next = when (val probe = system.probe()) {
            is PrivateSpaceProbe.Unsupported -> unsupported(probe.reason)

            // Nothing could be established. Anything already known to be private stays hidden.
            PrivateSpaceProbe.Unreadable -> {
                lockedPrivateSerial = lastKnownPrivateSerial
                if (lastKnownPrivateSerial != null) {
                    PrivateSpaceState.Locked
                } else {
                    PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE)
                }
            }

            is PrivateSpaceProbe.Present -> {
                lastKnownPrivateSerial = probe.userSerial
                if (probe.locked) {
                    // Set the serial before publishing so no collector can observe Locked while the
                    // gate still says the apps are visible.
                    lockedPrivateSerial = probe.userSerial
                    PrivateSpaceState.Locked
                } else {
                    // Read the list while the gate is still closed. A throwing seam must not be able
                    // to leave the gate open, so the serial is only cleared once the list is in hand.
                    val apps = runCatching { system.appsFor(probe.userSerial) }.getOrNull()
                    if (apps == null) {
                        lockedPrivateSerial = probe.userSerial
                        PrivateSpaceState.Locked
                    } else {
                        lockedPrivateSerial = null
                        PrivateSpaceState.Unlocked(apps)
                    }
                }
            }
        }
        mutable.value = next
        listeners.forEach { listener -> runCatching { listener() } }
    }

    /**
     * The gate's answer when the profile cannot be offered.
     *
     * Only a reason that means the profile genuinely is not there clears the gate. Losing the Home
     * role does not delete the user's private space — it only stops Duo seeing it — and Duo stays
     * reachable from its own launcher icon while that is true, so its apps must stay hidden.
     */
    private fun unsupported(reason: PrivateSpaceUnsupportedReason): PrivateSpaceState {
        lockedPrivateSerial = when (reason) {
            PrivateSpaceUnsupportedReason.NOT_DEFAULT_HOME -> lastKnownPrivateSerial
            else -> {
                lastKnownPrivateSerial = null
                null
            }
        }
        return PrivateSpaceState.Unsupported(reason)
    }

    override fun setLocked(locked: Boolean) {
        val serial = when (val probe = system.probe()) {
            is PrivateSpaceProbe.Present -> probe.userSerial
            is PrivateSpaceProbe.Unsupported -> return
            // No serial could be established, so there is no profile to safely act on. Refusing
            // leaves the gate closed rather than sending quiet-mode requests at a guessed user.
            PrivateSpaceProbe.Unreadable -> return
        }
        // The broadcast normally drives the refresh; refreshing here too keeps the state correct on
        // devices that do not broadcast for a self-initiated change.
        if (system.requestLocked(serial, locked)) refresh()
    }
}

/**
 * The real platform boundary.
 *
 * Every entry point returns early on API < 35, so on Android 12–14 this class touches no private
 * profile API, registers no receiver and logs nothing — it simply reports
 * [PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15] (NFR-C2).
 */
class AndroidPrivateSpaceSystem(context: Context) : PrivateSpaceSystem {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val userManager = appContext.getSystemService(UserManager::class.java)
    private val personalSerial: Long by lazy {
        runCatching { userManager?.getSerialNumberForUser(Process.myUserHandle()) }.getOrNull() ?: 0L
    }
    private var receiver: BroadcastReceiver? = null

    override fun probe(): PrivateSpaceProbe {
        if (Build.VERSION.SDK_INT < PRIVATE_SPACE_SDK) {
            return PrivateSpaceProbe.Unsupported(PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15)
        }
        // Android grants ACCESS_HIDDEN_PROFILES to the default Home app only, so a launcher that is
        // not Home cannot see the profile at all and must say so rather than report "no profile".
        // A role check that fails to answer is not the same as one that answers "no".
        val home = isDefaultHome() ?: return PrivateSpaceProbe.Unreadable
        if (!home) {
            return PrivateSpaceProbe.Unsupported(PrivateSpaceUnsupportedReason.NOT_DEFAULT_HOME)
        }
        // A failed enumeration is unreadable; a successful one that finds nothing is "no profile".
        val user = privateProfile().getOrElse { return PrivateSpaceProbe.Unreadable }
            ?: return PrivateSpaceProbe.Unsupported(PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE)
        val serial = runCatching { userManager?.getSerialNumberForUser(user) }.getOrNull()
            ?.takeIf { it >= 0 }
            ?: return PrivateSpaceProbe.Unreadable
        // A profile that cannot be read is treated as locked: the safe direction for FR-77.
        val locked = runCatching { userManager?.isQuietModeEnabled(user) }.getOrNull() ?: true
        return PrivateSpaceProbe.Present(serial, locked)
    }

    override fun appsFor(userSerial: Long): List<PrivateSpaceApp> {
        if (Build.VERSION.SDK_INT < PRIVATE_SPACE_SDK) return emptyList()
        val user = runCatching { userManager?.getUserForSerialNumber(userSerial) }.getOrNull()
            ?: return emptyList()
        return runCatching { launcherApps?.getActivityList(null, user) }.getOrNull().orEmpty()
            .map { info ->
                val component = info.componentName
                PrivateSpaceApp(
                    id = profileAppId(component.flattenToString(), userSerial, personalSerial),
                    label = info.label.toString(),
                    packageName = component.packageName,
                    userSerial = userSerial,
                )
            }
    }

    override fun requestLocked(userSerial: Long, locked: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < PRIVATE_SPACE_SDK) return false
        return runCatching {
            val user = userManager?.getUserForSerialNumber(userSerial) ?: return false
            userManager.requestQuietModeEnabled(locked, user)
        }.getOrDefault(false)
    }

    override fun observe(onProfileAvailabilityChanged: () -> Unit) {
        if (Build.VERSION.SDK_INT < PRIVATE_SPACE_SDK || receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = onProfileAvailabilityChanged()
        }
        // Both are protected system broadcasts carrying EXTRA_USER; the repository re-probes rather
        // than trusting the extra, so a spoofed or unrelated profile cannot unlock anything.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
        }
        runCatching {
            appContext.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
        }.onSuccess { receiver = created }
    }

    /** null means the role could not be determined, which is not the same as not holding it. */
    private fun isDefaultHome(): Boolean? = runCatching {
        appContext.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME)
    }.getOrNull()

    /**
     * The private profile among the profiles this launcher can see, if any.
     *
     * Success carrying null means "enumerated the profiles, none of them is private"; a failure
     * means the enumeration itself did not answer. [probe] maps those to opposite outcomes, so they
     * are kept apart here rather than both becoming null.
     *
     * The API-35 guard is repeated here rather than relied on from [probe] so the version check is
     * local to the call, which is what keeps this off the code path — and out of lint's way — on
     * API 31–34.
     */
    private fun privateProfile(): Result<UserHandle?> {
        if (Build.VERSION.SDK_INT < PRIVATE_SPACE_SDK) return Result.success(null)
        val apps = launcherApps ?: return Result.failure(IllegalStateException("no LauncherApps"))
        return runCatching {
            apps.profiles.firstOrNull { user ->
                apps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
            }
        }
    }
}

/**
 * The process-wide repository, so every surface reads one state and one gate.
 *
 * Mirrors `DuoBadges`: the first caller wires the platform boundary, later callers get the same
 * instance.
 */
object DuoPrivateSpace {
    private var instance: DuoPrivateSpaceRepository? = null

    @Synchronized
    fun repository(context: Context): DuoPrivateSpaceRepository =
        instance ?: DuoPrivateSpaceRepository(AndroidPrivateSpaceSystem(context))
            .also { it.attach(); instance = it }
}
