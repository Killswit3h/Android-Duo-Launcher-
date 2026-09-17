package com.jake.duolauncher.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** A label from another app is untrusted text; cap it so it cannot wreck a menu row. */
private const val MAX_LABEL_CHARS = 120

private const val MATCH_PUBLISHED =
    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC

private const val MATCH_ANY = MATCH_PUBLISHED or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

/**
 * The `LauncherApps` implementation of [ShortcutSource].
 *
 * Every call is wrapped: `getShortcuts` throws `SecurityException` the moment Duo stops being the
 * default Home app, and a profile can disappear between the serial lookup and the query. Both read
 * as "nothing available", which is exactly the FR-30 behaviour.
 */
internal class LauncherAppsShortcutSource(context: Context) : ShortcutSource {

    private val app = context.applicationContext
    private val launcherApps = app.getSystemService(LauncherApps::class.java)
    private val userManager = app.getSystemService(UserManager::class.java)

    override fun hasHostPermission(): Boolean =
        runCatching { launcherApps?.hasShortcutHostPermission() }.getOrNull() ?: false

    override fun personalSerial(): Long =
        runCatching { userManager?.getSerialNumberForUser(Process.myUserHandle()) }.getOrNull() ?: 0L

    override fun query(target: ShortcutTarget): List<DuoShortcut> {
        val user = userFor(target.userSerial) ?: return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(target.packageName)
            .setQueryFlags(MATCH_PUBLISHED)
        return shortcuts(query, user, target.userSerial)
    }

    override fun shortcut(key: PinnedShortcutKey): DuoShortcut? {
        val user = userFor(key.userSerial) ?: return null
        val query = LauncherApps.ShortcutQuery()
            .setPackage(key.packageName)
            .setShortcutIds(listOf(key.shortcutId))
            .setQueryFlags(MATCH_ANY)
        return shortcuts(query, user, key.userSerial).firstOrNull { it.id == key.shortcutId }
    }

    override fun pinned(): List<DuoShortcut> = profiles().flatMap { (user, serial) ->
        val query = LauncherApps.ShortcutQuery()
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        shortcuts(query, user, serial)
    }

    override fun start(shortcut: DuoShortcut, bounds: Rect?): Boolean {
        val user = userFor(shortcut.userSerial) ?: return false
        return runCatching {
            // The id goes back to the platform, which owns the shortcut's intent. Duo never builds
            // or fires an intent on another app's behalf.
            launcherApps?.startShortcut(shortcut.packageName, shortcut.id, bounds, null, user)
            true
        }.getOrDefault(false)
    }

    /**
     * `pinShortcuts` *replaces* the pinned set for a package, so the current set is read first and
     * the new id appended. Passing the id alone would silently unpin every other shortcut the user
     * had placed from that app.
     */
    override fun pin(shortcut: DuoShortcut): Boolean {
        val user = userFor(shortcut.userSerial) ?: return false
        return runCatching {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(shortcut.packageName)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val existing = shortcuts(query, user, shortcut.userSerial).map(DuoShortcut::id)
            if (shortcut.id in existing) return@runCatching true
            launcherApps?.pinShortcuts(shortcut.packageName, existing + shortcut.id, user)
            true
        }.getOrDefault(false)
    }

    override fun icon(shortcut: DuoShortcut, sizePx: Int): ImageBitmap? {
        if (sizePx <= 0) return null
        val user = userFor(shortcut.userSerial) ?: return null
        val query = LauncherApps.ShortcutQuery()
            .setPackage(shortcut.packageName)
            .setShortcutIds(listOf(shortcut.id))
            .setQueryFlags(MATCH_ANY)
        val info = runCatching { launcherApps?.getShortcuts(query, user) }.getOrNull()
            .orEmpty().firstOrNull { it.id == shortcut.id } ?: return null
        val drawable = runCatching {
            launcherApps?.getShortcutIconDrawable(info, app.resources.displayMetrics.densityDpi)
        }.getOrNull() ?: return null
        return drawable.rasterize(sizePx)
    }

    private fun userFor(userSerial: Long): UserHandle? =
        runCatching { userManager?.getUserForSerialNumber(userSerial) }.getOrNull()

    private fun profiles(): List<Pair<UserHandle, Long>> =
        runCatching { launcherApps?.profiles }.getOrNull().orEmpty().mapNotNull { handle ->
            val serial = runCatching { userManager?.getSerialNumberForUser(handle) }.getOrNull()
            if (serial == null || serial < 0) null else handle to serial
        }

    private fun shortcuts(
        query: LauncherApps.ShortcutQuery,
        user: UserHandle,
        userSerial: Long,
    ): List<DuoShortcut> =
        runCatching { launcherApps?.getShortcuts(query, user) }.getOrNull().orEmpty()
            .mapNotNull { it.toDuoShortcut(userSerial) }
}

private fun ShortcutInfo.toDuoShortcut(userSerial: Long): DuoShortcut? {
    val shortcutId = id.takeIf { it.isNotBlank() } ?: return null
    val owner = `package`.takeIf { it.isNotBlank() && ':' !in it } ?: return null
    if (userSerial < 0) return null
    val text = (shortLabel ?: longLabel)?.toString()?.trim().orEmpty().take(MAX_LABEL_CHARS)
    return DuoShortcut(
        id = shortcutId,
        packageName = owner,
        userSerial = userSerial,
        label = text.ifBlank { shortcutId },
        rank = rank,
        isDeclaredInManifest = isDeclaredInManifest,
        isDynamic = isDynamic,
        isEnabled = isEnabled,
        disabledMessage = disabledMessage?.toString()?.trim()
            ?.takeIf(String::isNotBlank)?.take(MAX_LABEL_CHARS),
    )
}

private fun Drawable.rasterize(sizePx: Int): ImageBitmap? = runCatching {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, sizePx, sizePx)
    draw(Canvas(bitmap))
    bitmap.asImageBitmap()
}.getOrNull()

/**
 * The process-wide shortcut repository. The context menu, the pin sheet and Home all reach the same
 * instance, so host permission is read once per interaction rather than once per surface.
 */
object DuoShortcuts {
    private var instance: DuoShortcutRepository? = null

    @Synchronized
    fun repository(context: Context): DuoShortcutRepository = instance ?: DuoShortcutRepository(
        LauncherAppsShortcutSource(context.applicationContext),
    ).also {
        instance = it
        it.refreshHostPermission()
    }
}

/**
 * The Android side of the uninstall action (FR-29).
 *
 * Duo never removes a package itself. It asks, the system confirms with the user and does the work,
 * and the launcher learns the result from the package-removal broadcast it already handles.
 */
object DuoUninstall {

    /**
     * Gathers the facts [UninstallRules] decides on.
     *
     * User restrictions are read for the calling user, which is the case that matters: a managed
     * profile that forbids uninstalls has the system refuse the request anyway, so the worst
     * outcome there is an item that leads to a refusal rather than a silent removal.
     */
    fun candidateFor(context: Context, packageName: String, userSerial: Long): UninstallCandidate {
        if (packageName.isBlank()) return UninstallCandidate(packageName = "", isSelf = true)
        val userManager = context.getSystemService(UserManager::class.java)
        val restricted = runCatching {
            userManager?.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL) == true ||
                userManager?.hasUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS) == true
        }.getOrDefault(false)
        return UninstallCandidate(
            packageName = packageName,
            isSystem = isSystem(context, packageName, userSerial),
            isSelf = packageName == context.packageName,
            controlsDisallowed = restricted,
        )
    }

    /** Whether the context menu shows **Uninstall** for this app (FR-25, FR-29). */
    fun canUninstall(context: Context, packageName: String, userSerial: Long): Boolean =
        UninstallRules.canUninstall(candidateFor(context, packageName, userSerial))

    /**
     * Starts Android's uninstall confirmation. Returns false when the capability check fails or no
     * activity handles it, so the caller can leave the UI untouched.
     */
    fun start(context: Context, packageName: String, userSerial: Long): Boolean {
        if (!canUninstall(context, packageName, userSerial)) return false
        val intent = UninstallAction.intentFor(packageName, userSerial) ?: return false
        // Name the target profile explicitly. ACTION_DELETE resolves against the calling user, so
        // without this a work- or private-profile icon uninstalls the personal copy of the package.
        // A serial that cannot be resolved refuses the action rather than guessing a profile.
        val user = runCatching {
            context.getSystemService(UserManager::class.java)?.getUserForSerialNumber(userSerial)
        }.getOrNull() ?: return false
        intent.putExtra(Intent.EXTRA_USER, user)
        // ACTION_DELETE is implicit, so any app may register a filter for it and show a convincing
        // fake uninstall prompt. Resolve who would actually receive this and refuse unless it is
        // part of the system image. An unresolvable handler is refused too: App info and Settings
        // remain routes to the same action, and neither hands the user a dialog Duo cannot vouch for.
        if (!UninstallAction.isTrustedHandler(resolveHandler(context, intent))) return false
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** The activity that would receive [intent], or null when nothing resolves or the lookup fails. */
    private fun resolveHandler(context: Context, intent: Intent): UninstallHandler? = runCatching {
        val packageManager = context.packageManager ?: return@runCatching null
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        val activity = resolved?.activityInfo ?: return@runCatching null
        val flags = activity.applicationInfo?.flags ?: 0
        UninstallHandler(
            packageName = activity.packageName.orEmpty(),
            isSystem = flags and ApplicationInfo.FLAG_SYSTEM != 0,
            isUpdatedSystem = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
        )
    }.getOrNull()

    private fun isSystem(context: Context, packageName: String, userSerial: Long): Boolean =
        runCatching {
            val userManager = context.getSystemService(UserManager::class.java)
            val user = userManager?.getUserForSerialNumber(userSerial) ?: Process.myUserHandle()
            val info = context.getSystemService(LauncherApps::class.java)
                ?.getApplicationInfo(packageName, 0, user)
                ?: return@runCatching true
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            // An app whose info cannot be read is treated as system, i.e. no Uninstall item.
            // Hiding an action is always safer than offering one that may target the wrong thing.
        }.getOrDefault(true)
}
