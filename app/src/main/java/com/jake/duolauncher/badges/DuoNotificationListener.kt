package com.jake.duolauncher.badges

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification

/** Coalesces bursts of posts. Far inside the 1 second budget in FR-21. */
private const val COALESCE_MS = 100L

/**
 * Turns posted notifications into badge counts, and nothing else (FR-20, FR-21, NFR-S3).
 *
 * The platform hands this service the complete notification, including its title, text, images and
 * extras. Duo reads five facts from it and discards the rest without copying it anywhere:
 * the package, the Android user, the channel's badge flag, `Notification.number`, and the
 * ongoing/group-summary flags. `Notification.extras` is never touched, notification text never
 * enters a [BadgeNotification], nothing is written to disk, and nothing here logs.
 *
 * The badge state lives only in [DuoBadges]'s in-memory repository, so it cannot reach a backup
 * and it disappears with the process.
 */
class DuoNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val publish = Runnable { publishActive() }

    override fun onListenerConnected() {
        repository().onListenerConnected()
        schedule()
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(publish)
        repository().onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = schedule()

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) =
        schedule()

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = schedule()

    override fun onDestroy() {
        handler.removeCallbacks(publish)
        super.onDestroy()
    }

    private fun repository() = DuoBadges.repository(this)

    private fun schedule() {
        handler.removeCallbacks(publish)
        handler.postDelayed(publish, COALESCE_MS)
    }

    /**
     * Rebuilds the badge snapshot from what is currently posted, rather than tracking individual
     * notifications. That keeps removal correct for free and means no per-notification identifier
     * is ever retained.
     */
    private fun publishActive() {
        val active = runCatching { activeNotifications }.getOrNull()
        val ranking = runCatching { currentRanking }.getOrNull()
        val users = runCatching { getSystemService(UserManager::class.java) }.getOrNull()
        repository().onNotifications(active.orEmpty().mapNotNull { describe(it, ranking, users) })
    }

    /** Extracts the badge-relevant facts. Every other field of the notification is left alone. */
    private fun describe(
        sbn: StatusBarNotification,
        ranking: RankingMap?,
        users: UserManager?,
    ): BadgeNotification? {
        val packageName = sbn.packageName?.takeIf(String::isNotBlank) ?: return null
        val user = sbn.user ?: return null
        val serial = users?.let { runCatching { it.getSerialNumberForUser(user) }.getOrNull() } ?: return null
        if (serial < 0) return null
        val notification = sbn.notification ?: return null
        val flags = notification.flags
        return BadgeNotification(
            packageName = packageName,
            userSerial = serial,
            number = notification.number.coerceIn(0, BadgeCount.MAX),
            channelCanShowBadge = channelAllowsBadge(sbn.key, ranking),
            ongoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
                (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
        )
    }

    /**
     * `NotificationChannel.canShowBadge()` for this notification's channel. The key is used as a
     * lookup token for this call only and is never stored. Unknown means "allowed", matching how
     * the platform treats a notification with no ranking entry.
     */
    private fun channelAllowsBadge(key: String?, ranking: RankingMap?): Boolean {
        if (key == null || ranking == null) return true
        val entry = Ranking()
        if (!runCatching { ranking.getRanking(key, entry) }.getOrDefault(false)) return true
        val channelFlag = runCatching { entry.channel?.canShowBadge() }.getOrNull()
        return channelFlag ?: runCatching { entry.canShowBadge() }.getOrDefault(true)
    }
}
