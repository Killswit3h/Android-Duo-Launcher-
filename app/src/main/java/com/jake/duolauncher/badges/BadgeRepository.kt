package com.jake.duolauncher.badges

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The badge data layer the UI reads (FR-20 to FR-23).
 *
 * Both flows are safe to collect before notification access exists: [badges] is empty until access
 * is granted and a listener is connected, and it empties again the moment access is revoked.
 */
interface BadgeRepository {
    /** Current badge per app. Empty when access is off. */
    val badges: StateFlow<Map<ProfileAppId, BadgeCount>>

    /** Whether notification access is granted. Drives the **Turn on** row in Badge settings. */
    val accessGranted: StateFlow<Boolean>

    /** The badge for a folder tile or a stack's app-icon badge. */
    fun badgeForFolder(apps: List<ProfileAppId>): BadgeCount
}

/**
 * Reports whether notification access is granted, and calls back when that changes.
 *
 * Kept as a seam so the repository holds no Android types and the access rules stay unit-testable.
 * [isGranted] returns null when the state cannot be read at all, which is different from "denied".
 */
interface NotificationAccessSource {
    fun isGranted(): Boolean?

    fun observe(onChanged: () -> Unit)
}

/**
 * In-memory badge state. Nothing here is ever written to disk, to `SharedPreferences`, or to a log,
 * and the app declares `android:allowBackup="false"`, so no badge data can reach a backup (NFR-S3,
 * NFR-S7). Losing the state on process death is intentional: the listener rebuilds it on connect.
 *
 * Callbacks arrive on the main thread from [DuoNotificationListener] and from the settings
 * observer; the mutators are synchronized so an attach from another thread cannot interleave.
 */
class DuoBadgeRepository : BadgeRepository {
    private val mutableBadges = MutableStateFlow<Map<ProfileAppId, BadgeCount>>(emptyMap())
    override val badges: StateFlow<Map<ProfileAppId, BadgeCount>> = mutableBadges.asStateFlow()

    private val mutableAccess = MutableStateFlow(false)
    override val accessGranted: StateFlow<Boolean> = mutableAccess.asStateFlow()

    private var source: NotificationAccessSource? = null
    /** null means "could not read the setting", in which case the binding state decides. */
    private var settingsGranted: Boolean? = null
    private var listenerConnected = false
    private var counts: Map<BadgeAppKey, BadgeCount> = emptyMap()
    private var catalog: BadgeAppCatalog = BadgeAppCatalog.Empty

    /** Idempotent: the first source wins and is observed for revocation. */
    @Synchronized
    fun attach(source: NotificationAccessSource) {
        if (this.source != null) return
        this.source = source
        source.observe(::refreshAccess)
        refreshAccess()
    }

    /** Swaps in the launcher's authoritative app list (private space and hidden apps excluded). */
    @Synchronized
    fun setCatalog(catalog: BadgeAppCatalog) {
        this.catalog = catalog
        publish()
    }

    /**
     * Re-reads access. Revocation empties [badges] on this call, which the settings observer makes
     * immediate and well inside the 2 second budget in the error table.
     */
    @Synchronized
    fun refreshAccess() {
        settingsGranted = source?.isGranted()
        publish()
    }

    @Synchronized
    fun onListenerConnected() {
        listenerConnected = true
        settingsGranted = source?.isGranted()
        publish()
    }

    /** The system unbinds the listener when access is withdrawn, so drop everything. */
    @Synchronized
    fun onListenerDisconnected() {
        listenerConnected = false
        counts = emptyMap()
        settingsGranted = source?.isGranted()
        publish()
    }

    /** The full set of currently posted, badge-relevant notifications. Replaces prior state. */
    @Synchronized
    fun onNotifications(notifications: List<BadgeNotification>) {
        counts = BadgeRules.countsByApp(notifications)
        publish()
    }

    override fun badgeForFolder(apps: List<ProfileAppId>): BadgeCount =
        BadgeRules.folderBadge(apps, mutableBadges.value)

    private fun publish() {
        val granted = settingsGranted ?: listenerConnected
        if (!granted) counts = emptyMap()
        mutableAccess.value = granted
        mutableBadges.value = if (granted) BadgeRules.keyedByAppId(counts, catalog) else emptyMap()
    }
}
