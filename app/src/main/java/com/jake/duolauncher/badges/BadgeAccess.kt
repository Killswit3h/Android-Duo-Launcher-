package com.jake.duolauncher.badges

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import com.jake.duolauncher.profileAppId
import com.jake.duolauncher.profiles.DuoPrivateSpace
import java.util.concurrent.ConcurrentHashMap

/** The Settings.Secure key listing every notification listener the user has enabled. */
private const val ENABLED_LISTENERS = "enabled_notification_listeners"
private const val CATALOG_CACHE_LIMIT = 256

private fun enabledForDuo(context: Context): Boolean? = runCatching {
    val enabled = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS)
        ?: return@runCatching false
    val component = NotificationAccess.listenerComponent(context)
    enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == component }
}.getOrNull()

/**
 * Notification access as the user sees it: granted or not, and where to go to change it.
 *
 * The access itself is opt-in and revocable at any time from system Settings, and the launcher
 * works fully without it, which is the standing promise in `PRIVACY.md` (NFR-S2).
 */
object NotificationAccess {
    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context.packageName, DuoNotificationListener::class.java.name)

    fun isGranted(context: Context): Boolean = enabledForDuo(context) == true

    /**
     * Deep link to Duo's own notification-access toggle. Some builds do not expose the per-app
     * screen, so callers start this and fall back to [listSettingsIntent] on
     * `ActivityNotFoundException`.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                listenerComponent(context).flattenToString(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The full "Device & app notifications" list. */
    fun listSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Duo's App info page. A sideloaded install must first use ⋮ → **Allow restricted settings**
     * there before Android will let the notification-access toggle move (FR-23, error table).
     */
    fun appInfoIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Watches the secure setting that lists enabled listeners, so a revocation reaches the repository
 * as soon as the user makes it rather than when Duo next looks.
 */
internal class SecureSettingsAccess(private val context: Context) : NotificationAccessSource {
    private var observer: ContentObserver? = null

    override fun isGranted(): Boolean? = enabledForDuo(context)

    override fun observe(onChanged: () -> Unit) {
        if (observer != null) return
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChanged()
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(ENABLED_LISTENERS),
                false,
                created,
            )
        }.onSuccess { observer = created }
    }
}

/**
 * Resolves a notification's package to the launcher identities for that package in that profile,
 * using the same `profileAppId` scheme `LauncherModel` stores.
 *
 * This is the default wiring so badges work on their own; the launcher can replace it with its own
 * app list through [DuoBadgeRepository.setCatalog] to also honor hidden apps and a locked private
 * space.
 */
internal class LauncherAppsBadgeCatalog(
    context: Context,
    /** FR-77: true while [userSerial] names a private profile that is locked right now. */
    private val isLockedProfile: (Long) -> Boolean = { false },
) : BadgeAppCatalog {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val cache = ConcurrentHashMap<BadgeAppKey, List<ProfileAppId>>()
    private val personalSerial: Long by lazy {
        runCatching { userManager?.getSerialNumberForUser(Process.myUserHandle()) }.getOrNull() ?: 0L
    }

    override fun appIdsFor(packageName: String, userSerial: Long): List<ProfileAppId> {
        // A locked private profile badges nothing (FR-77). This is checked ahead of the cache, so
        // ids resolved while the space was unlocked cannot be replayed out of it once it locks, and
        // a gate that cannot answer counts as locked.
        if (runCatching { isLockedProfile(userSerial) }.getOrDefault(true)) return emptyList()
        val key = BadgeAppKey(packageName, userSerial)
        cache[key]?.let { return it }
        val user = runCatching { userManager?.getUserForSerialNumber(userSerial) }.getOrNull()
            ?: return emptyList()
        val ids = runCatching { launcherApps?.getActivityList(packageName, user) }.getOrNull().orEmpty()
            .map { profileAppId(it.componentName.flattenToString(), userSerial, personalSerial) }
        // An empty answer usually means "not a launchable package"; cache it too so a chatty
        // background package does not cause a binder call per notification.
        if (cache.size >= CATALOG_CACHE_LIMIT) cache.clear()
        cache[key] = ids
        return ids
    }
}

/**
 * The process-wide badge repository. [DuoNotificationListener] and the UI reach the same instance,
 * which owns no state beyond the in-memory badge map.
 */
object DuoBadges {
    private val instance = DuoBadgeRepository()
    private var wired = false

    @Synchronized
    fun repository(context: Context): DuoBadgeRepository {
        if (!wired) {
            wired = true
            val application = context.applicationContext
            val privateSpace = DuoPrivateSpace.repository(application)
            instance.setCatalog(
                LauncherAppsBadgeCatalog(application) { serial ->
                    privateSpace.gate.isLockedProfile(serial)
                },
            )
            // Locking the space from system UI has to clear its badges at that moment, not at
            // whatever time the next notification happens to arrive (FR-77).
            privateSpace.addOnChanged { instance.republish() }
            instance.attach(SecureSettingsAccess(application))
        }
        return instance
    }
}
