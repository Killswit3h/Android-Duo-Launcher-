package com.jake.duolauncher.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CHAT = "com.example.chat"
private const val PERSONAL = 0L
private const val WORK = 42L
private const val CHAT_ID = "com.example.chat/.MainActivity"
private const val CHAT_ALIAS_ID = "com.example.chat/.AliasActivity"
private const val WORK_CHAT_ID = "duo-profile:v1:42:com.example.chat/.MainActivity"

private fun posted(
    packageName: String = CHAT,
    userSerial: Long = PERSONAL,
    number: Int = 0,
    channelCanShowBadge: Boolean = true,
    ongoing: Boolean = false,
    groupSummary: Boolean = false,
) = BadgeNotification(packageName, userSerial, number, channelCanShowBadge, ongoing, groupSummary)

private fun personalCount(counts: Map<BadgeAppKey, BadgeCount>): Int =
    counts[BadgeAppKey(CHAT, PERSONAL)]?.count ?: 0

class BadgeRulesTest {
    @Test fun eachPostedNotificationCountsOnce() {
        val counts = BadgeRules.countsByApp(listOf(posted(), posted(), posted()))
        assertEquals(BadgeCount(3), counts.getValue(BadgeAppKey(CHAT, PERSONAL)))
        assertTrue(counts.getValue(BadgeAppKey(CHAT, PERSONAL)).hasBadge)
    }

    @Test fun appSuppliedNumberBecomesTheCount() {
        assertEquals(5, personalCount(BadgeRules.countsByApp(listOf(posted(number = 5)))))
        assertEquals(1, personalCount(BadgeRules.countsByApp(listOf(posted(number = 1)))))
        assertEquals(1, personalCount(BadgeRules.countsByApp(listOf(posted(number = 0)))))
        assertEquals(6, personalCount(BadgeRules.countsByApp(listOf(posted(number = 5), posted()))))
    }

    @Test fun channelThatCannotShowBadgesIsIgnored() {
        assertFalse(BadgeRules.isEligible(posted(channelCanShowBadge = false)))
        val counts = BadgeRules.countsByApp(listOf(posted(channelCanShowBadge = false), posted()))
        assertEquals(1, personalCount(counts))
    }

    @Test fun persistentRunningNotificationNeverBadges() {
        assertFalse(BadgeRules.isEligible(posted(ongoing = true)))
        assertTrue(BadgeRules.countsByApp(listOf(posted(ongoing = true), posted(ongoing = true))).isEmpty())
    }

    @Test fun groupSummaryIsNotCountedAlongsideItsChildren() {
        val counts = BadgeRules.countsByApp(listOf(posted(groupSummary = true), posted(), posted()))
        assertEquals(2, personalCount(counts))
    }

    @Test fun eachProfileKeepsItsOwnCount() {
        val counts = BadgeRules.countsByApp(
            listOf(posted(), posted(userSerial = WORK), posted(userSerial = WORK)),
        )
        assertEquals(BadgeCount(1), counts.getValue(BadgeAppKey(CHAT, PERSONAL)))
        assertEquals(BadgeCount(2), counts.getValue(BadgeAppKey(CHAT, WORK)))
    }

    @Test fun hostileNumbersCannotOverflowTheCount() {
        val counts = BadgeRules.countsByApp(
            listOf(posted(number = Int.MAX_VALUE), posted(number = Int.MAX_VALUE)),
        )
        assertEquals(BadgeCount.MAX, personalCount(counts))
    }

    @Test fun malformedNotificationsAreRejected() {
        assertFalse(BadgeRules.isEligible(posted(packageName = " ")))
        assertFalse(BadgeRules.isEligible(posted(userSerial = -1)))
        assertTrue(BadgeRules.countsByApp(listOf(posted(packageName = ""), posted(userSerial = -7))).isEmpty())
    }

    @Test fun packageBadgesEveryLauncherEntryItOwns() {
        val catalog = BadgeAppCatalog { packageName, serial ->
            if (packageName == CHAT && serial == PERSONAL) listOf(CHAT_ID, CHAT_ALIAS_ID) else emptyList()
        }
        val badges = BadgeRules.keyedByAppId(BadgeRules.countsByApp(listOf(posted(), posted())), catalog)
        assertEquals(mapOf(CHAT_ID to BadgeCount(2), CHAT_ALIAS_ID to BadgeCount(2)), badges)
    }

    @Test fun workProfileAppBadgesItsOwnEntry() {
        val catalog = BadgeAppCatalog { packageName, serial ->
            when {
                packageName != CHAT -> emptyList()
                serial == PERSONAL -> listOf(CHAT_ID)
                serial == WORK -> listOf(WORK_CHAT_ID)
                else -> emptyList()
            }
        }
        val badges = BadgeRules.keyedByAppId(
            BadgeRules.countsByApp(listOf(posted(), posted(userSerial = WORK), posted(userSerial = WORK))),
            catalog,
        )
        assertEquals(mapOf(CHAT_ID to BadgeCount(1), WORK_CHAT_ID to BadgeCount(2)), badges)
    }

    @Test fun appsTheCatalogDoesNotListProduceNoBadge() {
        val badges = BadgeRules.keyedByAppId(
            BadgeRules.countsByApp(listOf(posted())),
            BadgeAppCatalog.Empty,
        )
        assertTrue(badges.isEmpty())
    }

    @Test fun folderBadgeSumsItsApps() {
        val badges = mapOf(CHAT_ID to BadgeCount(2), WORK_CHAT_ID to BadgeCount(3))
        assertEquals(BadgeCount(5), BadgeRules.folderBadge(listOf(CHAT_ID, WORK_CHAT_ID), badges))
        assertTrue(BadgeRules.folderBadge(listOf(CHAT_ID), badges).hasBadge)
    }

    @Test fun folderBadgeIgnoresRepeatedAndUnbadgedMembers() {
        val badges = mapOf(CHAT_ID to BadgeCount(2))
        assertEquals(
            BadgeCount(2),
            BadgeRules.folderBadge(listOf(CHAT_ID, CHAT_ID, CHAT_ALIAS_ID), badges),
        )
    }

    @Test fun folderWithNoBadgedAppsHasNoBadge() {
        assertEquals(BadgeCount.None, BadgeRules.folderBadge(emptyList(), mapOf(CHAT_ID to BadgeCount(2))))
        assertEquals(BadgeCount.None, BadgeRules.folderBadge(listOf(CHAT_ALIAS_ID), mapOf(CHAT_ID to BadgeCount(2))))
        assertFalse(BadgeCount.None.hasBadge)
    }
}
