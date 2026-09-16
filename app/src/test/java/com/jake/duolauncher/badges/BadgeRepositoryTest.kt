package com.jake.duolauncher.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAIL = "com.example.mail"
private const val MAIL_ID = "com.example.mail/.MainActivity"
private const val MAIL_WORK_ID = "duo-profile:v1:42:com.example.mail/.MainActivity"
private const val MAIL_PRIVATE_ID = "duo-profile:v1:1000:com.example.mail/.MainActivity"
private const val WORK_SERIAL = 42L
private const val PRIVATE_SERIAL = 1000L

private val mailCatalog = BadgeAppCatalog { packageName, serial ->
    when {
        packageName != MAIL -> emptyList()
        serial == 0L -> listOf(MAIL_ID)
        serial == WORK_SERIAL -> listOf(MAIL_WORK_ID)
        else -> emptyList()
    }
}

private fun mail(userSerial: Long = 0L, number: Int = 0) =
    BadgeNotification(packageName = MAIL, userSerial = userSerial, number = number)

private class FakeAccess(private var granted: Boolean?) : NotificationAccessSource {
    private var listener: (() -> Unit)? = null
    override fun isGranted(): Boolean? = granted
    override fun observe(onChanged: () -> Unit) { listener = onChanged }
    fun change(granted: Boolean?) {
        this.granted = granted
        listener?.invoke()
    }
}

private fun repositoryWith(access: NotificationAccessSource): DuoBadgeRepository =
    DuoBadgeRepository().apply {
        setCatalog(mailCatalog)
        attach(access)
    }

class BadgeRepositoryTest {
    @Test fun noBadgesWhileAccessIsDenied() {
        val repository = repositoryWith(FakeAccess(false))
        repository.onNotifications(listOf(mail(), mail()))
        assertFalse(repository.accessGranted.value)
        assertTrue(repository.badges.value.isEmpty())
    }

    @Test fun grantedAccessPublishesBadgesKeyedByLauncherIdentity() {
        val repository = repositoryWith(FakeAccess(true))
        repository.onListenerConnected()
        repository.onNotifications(listOf(mail(), mail(), mail(userSerial = WORK_SERIAL)))
        assertTrue(repository.accessGranted.value)
        assertEquals(
            mapOf(MAIL_ID to BadgeCount(2), MAIL_WORK_ID to BadgeCount(1)),
            repository.badges.value,
        )
    }

    @Test fun revokingAccessClearsBadgesAndIgnoresLaterDeliveries() {
        val access = FakeAccess(true)
        val repository = repositoryWith(access)
        repository.onListenerConnected()
        repository.onNotifications(listOf(mail(number = 4)))
        assertEquals(BadgeCount(4), repository.badges.value.getValue(MAIL_ID))

        access.change(false)

        assertFalse(repository.accessGranted.value)
        assertTrue(repository.badges.value.isEmpty())
        repository.onNotifications(listOf(mail()))
        assertTrue(repository.badges.value.isEmpty())
    }

    @Test fun listenerDisconnectDropsEveryBadge() {
        val access = FakeAccess(true)
        val repository = repositoryWith(access)
        repository.onListenerConnected()
        repository.onNotifications(listOf(mail()))
        assertTrue(repository.badges.value.isNotEmpty())

        repository.onListenerDisconnected()

        assertTrue(repository.badges.value.isEmpty())
    }

    @Test fun removingTheLastNotificationRemovesTheBadge() {
        val repository = repositoryWith(FakeAccess(true))
        repository.onListenerConnected()
        repository.onNotifications(listOf(mail(), mail()))
        repository.onNotifications(listOf(mail()))
        assertEquals(BadgeCount(1), repository.badges.value.getValue(MAIL_ID))
        repository.onNotifications(emptyList())
        assertTrue(repository.badges.value.isEmpty())
    }

    @Test fun swappingTheCatalogReprojectsTheCurrentCounts() {
        val repository = repositoryWith(FakeAccess(true))
        repository.onListenerConnected()
        repository.onNotifications(listOf(mail()))
        assertTrue(repository.badges.value.containsKey(MAIL_ID))

        repository.setCatalog(BadgeAppCatalog.Empty)

        assertTrue(repository.badges.value.isEmpty())
        assertTrue(repository.accessGranted.value)
    }

    @Test fun unreadableSettingFallsBackToTheListenerBinding() {
        val repository = repositoryWith(FakeAccess(null))
        assertFalse(repository.accessGranted.value)

        repository.onListenerConnected()

        assertTrue(repository.accessGranted.value)
        repository.onNotifications(listOf(mail()))
        assertEquals(BadgeCount(1), repository.badges.value.getValue(MAIL_ID))
    }

    /**
     * FR-77: locking the private space clears its badges at that moment.
     *
     * The catalog is the private-space boundary, and its answer changes on its own when the user
     * locks the space from system UI. Without a re-projection the badges already on screen would
     * keep showing a private app's notification count until the next notification happened to
     * arrive — which, with the profile's apps stopped, may be never.
     */
    @Test fun lockingThePrivateSpaceClearsItsBadgesWithoutANewNotification() {
        var privateLocked = false
        val repository = DuoBadgeRepository().apply {
            setCatalog(
                BadgeAppCatalog { packageName, serial ->
                    when {
                        packageName != MAIL -> emptyList()
                        serial != PRIVATE_SERIAL -> emptyList()
                        privateLocked -> emptyList()
                        else -> listOf(MAIL_PRIVATE_ID)
                    }
                },
            )
            attach(FakeAccess(true))
        }
        repository.onListenerConnected()
        repository.onNotifications(listOf(mail(userSerial = PRIVATE_SERIAL)))
        assertEquals(BadgeCount(1), repository.badges.value.getValue(MAIL_PRIVATE_ID))

        privateLocked = true
        repository.republish()

        assertTrue(repository.badges.value.isEmpty())
        // Access itself is untouched; only the private profile's projection went away.
        assertTrue(repository.accessGranted.value)
    }

    @Test fun folderBadgeReadsThePublishedBadges() {
        val repository = repositoryWith(FakeAccess(true))
        repository.onListenerConnected()
        repository.onNotifications(listOf(mail(), mail(userSerial = WORK_SERIAL), mail(userSerial = WORK_SERIAL)))
        assertEquals(BadgeCount(3), repository.badgeForFolder(listOf(MAIL_ID, MAIL_WORK_ID)))
        assertEquals(BadgeCount.None, repository.badgeForFolder(emptyList()))
    }
}
