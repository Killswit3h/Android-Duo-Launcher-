package com.jake.duolauncher.badges

/**
 * The launcher's profile-aware app identity, the value produced by `profileAppId(...)`.
 *
 * Badges are keyed by exactly the identity the rest of the launcher already stores for an app, so
 * the same package installed in the personal and in the work profile badges its own entry.
 */
typealias ProfileAppId = String

/**
 * How many badge-eligible notifications an app (or a folder of apps) currently has.
 *
 * Dot versus Number is a presentation choice: the Dot style renders [hasBadge], the Number style
 * renders [count]. The data layer never decides which one is shown.
 */
data class BadgeCount(val count: Int) {
    init { require(count >= 0) { "A badge count is never negative" } }

    val hasBadge: Boolean get() = count > 0

    companion object {
        val None = BadgeCount(0)

        /** Upper bound so a hostile or buggy `Notification.number` cannot overflow a sum. */
        const val MAX = 9_999

        fun of(total: Long): BadgeCount = BadgeCount(total.coerceIn(0L, MAX.toLong()).toInt())
    }
}

/**
 * A notification belongs to one package in one Android user, and badges follow that pair. Work
 * profile notifications therefore never leak onto the personal copy of the same app.
 */
data class BadgeAppKey(val packageName: String, val userSerial: Long)

/**
 * Everything the launcher is permitted to know about a single posted notification (NFR-S3).
 *
 * The type has no field that can carry notification content. There is no title, text, sub-text,
 * ticker, people, image, channel name or notification key here, because none of those are ever
 * read from the `StatusBarNotification`. Adding such a field to this class would be the whole
 * privacy regression, so it should be treated as a closed shape.
 */
data class BadgeNotification(
    val packageName: String,
    val userSerial: Long,
    /** The app-supplied `Notification.number`; 0 or 1 both mean "this one notification". */
    val number: Int = 0,
    /** `NotificationChannel.canShowBadge()` for this notification's channel. */
    val channelCanShowBadge: Boolean = true,
    /** Ongoing or foreground-service: a persistent "app is running" notice, never a badge. */
    val ongoing: Boolean = false,
    /** A group summary would double-count its own children. */
    val groupSummary: Boolean = false,
)

/**
 * Resolves the launcher app identities that a notification's package maps to.
 *
 * A notification names a package while the launcher keys apps by launcher activity, so the badge
 * layer asks the catalog to bridge the two. It is also the private-space and hidden-app boundary:
 * a catalog that does not list an app produces no badge for it (FR-77).
 */
fun interface BadgeAppCatalog {
    fun appIdsFor(packageName: String, userSerial: Long): List<ProfileAppId>

    companion object {
        val Empty = BadgeAppCatalog { _, _ -> emptyList() }
    }
}

/**
 * The badge rules, kept pure so every one of them is unit-testable without an emulator.
 */
object BadgeRules {
    /**
     * Badge eligibility (FR-20). A notification badges only when its channel allows badges, and
     * never when it is an ongoing/foreground-service notice or a group summary. Excluding ongoing
     * notifications is what stops a persistent "app is running" notification from producing a
     * badge that the user can never clear.
     */
    fun isEligible(notification: BadgeNotification): Boolean =
        notification.packageName.isNotBlank() &&
            notification.userSerial >= 0 &&
            notification.channelCanShowBadge &&
            !notification.ongoing &&
            !notification.groupSummary

    /**
     * What one notification adds to its app's count. Apps that summarize ("5 unread") set
     * `Notification.number`; the rest leave it at 0 and each notification counts once.
     */
    fun contribution(notification: BadgeNotification): Long =
        if (notification.number > 1) notification.number.toLong() else 1L

    /** Aggregates the currently posted notifications into per-app, per-user counts. */
    fun countsByApp(notifications: List<BadgeNotification>): Map<BadgeAppKey, BadgeCount> {
        val totals = LinkedHashMap<BadgeAppKey, Long>()
        notifications.filter(::isEligible).forEach { notification ->
            val key = BadgeAppKey(notification.packageName, notification.userSerial)
            val running = totals[key] ?: 0L
            totals[key] = (running + contribution(notification)).coerceAtMost(BadgeCount.MAX.toLong())
        }
        return totals.mapValues { (_, total) -> BadgeCount.of(total) }
    }

    /** Projects package/user counts onto the launcher's app identities through [catalog]. */
    fun keyedByAppId(
        counts: Map<BadgeAppKey, BadgeCount>,
        catalog: BadgeAppCatalog,
    ): Map<ProfileAppId, BadgeCount> {
        if (counts.isEmpty()) return emptyMap()
        val badges = LinkedHashMap<ProfileAppId, Long>()
        counts.forEach { (key, badge) ->
            if (!badge.hasBadge) return@forEach
            val ids = runCatching { catalog.appIdsFor(key.packageName, key.userSerial) }.getOrDefault(emptyList())
            ids.filter(String::isNotBlank).distinct().forEach { id ->
                badges[id] = ((badges[id] ?: 0L) + badge.count).coerceAtMost(BadgeCount.MAX.toLong())
            }
        }
        return badges.mapValues { (_, total) -> BadgeCount.of(total) }
    }

    /**
     * A folder tile, or a stack's app-icon badge, shows the sum of its apps' badges (FR-22).
     * Dot style renders this as presence, Number style as the total.
     */
    fun folderBadge(apps: List<ProfileAppId>, badges: Map<ProfileAppId, BadgeCount>): BadgeCount {
        if (apps.isEmpty() || badges.isEmpty()) return BadgeCount.None
        var total = 0L
        apps.distinct().forEach { id ->
            total = (total + (badges[id]?.count ?: 0)).coerceAtMost(BadgeCount.MAX.toLong())
        }
        return BadgeCount.of(total)
    }
}
