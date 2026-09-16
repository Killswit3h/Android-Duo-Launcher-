package com.jake.duolauncher.icons

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The icon pipeline the UI draws from (FR-12, FR-14 to FR-19).
 *
 * Frozen by the build plan's internal contract (section 2.2).
 */
interface IconRenderer {
    /**
     * The icon if it is already rendered, or null.
     *
     * Synchronous and allocation-free on a hit, because this is what a scrolling list calls for
     * every visible item on every frame. It never touches the disk, the package manager or a
     * decoder, so a miss costs a map lookup and nothing else (NFR-P5).
     */
    fun request(app: ProfileAppId, sizePx: Int, style: IconStyle): ImageBitmap?

    /**
     * The icon, rendering it if necessary. Always returns something drawable: an app whose icon
     * cannot be resolved falls back to the platform's default activity icon rather than to null.
     */
    suspend fun load(app: ProfileAppId, sizePx: Int, style: IconStyle): ImageBitmap

    /** Drops cached renders: everything, or only those of one style. */
    fun invalidate(style: IconStyle? = null)
}

/**
 * The real renderer: a memory-bounded cache in front of [IconRasterizer].
 *
 * Three things are worth knowing about how it behaves under load:
 *
 * - **Size is an input, never a constant.** The caller passes the pixel size it will draw at, which
 *   is `dp * density` (FR-12), optionally times the Large icons factor (FR-17). Two sizes of the
 *   same app are two cache entries, because they are two different renders.
 * - **Identical work is done once.** A fling can ask for the same icon from several frames before
 *   the first render finishes; in-flight renders are shared rather than repeated.
 * - **Memory pressure is honoured.** The renderer registers for `onTrimMemory` itself, so nothing
 *   in the activity or the application object has to remember to forward it.
 */
class DuoIconRenderer(
    context: Context,
    private val packs: IconPackRepository? = null,
    private val overrides: IconOverrideStore? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : IconRenderer {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val userManager = appContext.getSystemService(UserManager::class.java)
    private val personalSerial =
        runCatching { userManager.getSerialNumberForUser(Process.myUserHandle()) }.getOrDefault(PERSONAL_USER_SERIAL)

    /** Sized from the device's heap rather than from the Fold 8's, capped at NFR-P4's 64 MB. */
    val cache = IconCache<ImageBitmap>(iconCacheBudgetBytes(heapBytes(appContext)))

    private val inFlight = ConcurrentHashMap<IconKey, Deferred<ImageBitmap>>()

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = cache.onTrimMemory(level)
        override fun onLowMemory() = cache.clear()
        override fun onConfigurationChanged(configuration: Configuration) {
            // A density or locale change invalidates every render, which is why size and
            // configuration are part of what a render means rather than an afterthought.
            if (configuration.densityDpi != density) {
                density = configuration.densityDpi
                invalidate()
            }
        }
    }

    private var density = appContext.resources.configuration.densityDpi

    init {
        appContext.registerComponentCallbacks(trimCallbacks)
    }

    override fun request(app: ProfileAppId, sizePx: Int, style: IconStyle): ImageBitmap? =
        iconKeyFor(app, sizePx, style, personalSerial)?.let(cache::get)

    override suspend fun load(app: ProfileAppId, sizePx: Int, style: IconStyle): ImageBitmap {
        val key = iconKeyFor(app, sizePx, style, personalSerial)
            ?: return withContext(Dispatchers.Default) { fallback(sizePx, style) }
        cache.get(key)?.let { return it }
        val render = inFlight.computeIfAbsent(key) {
            scope.async(Dispatchers.Default) {
                try {
                    cache.get(key) ?: renderIcon(key).also { cache.put(key, it) }
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return render.await()
    }

    override fun invalidate(style: IconStyle?) {
        if (style == null) {
            cache.clear()
            IconRasterizer.clearMaskCache()
            return
        }
        val target = style.normalized()
        cache.invalidate { it.style == target }
    }

    /** Drops one package's renders, for a package update or an icon pack change. */
    fun invalidatePackage(packageName: String, userSerial: Long? = null) {
        cache.invalidate { it.packageName == packageName && (userSerial == null || it.userSerial == userSerial) }
    }

    /** Drops every render that came from [packPackage], for an icon pack that changed or vanished. */
    fun invalidatePack(packPackage: String) {
        cache.invalidate { it.style.pack == packPackage }
    }

    /** Unregisters the memory callbacks. The cached bitmaps are left to the collector by design. */
    fun close() {
        runCatching { appContext.unregisterComponentCallbacks(trimCallbacks) }
        cache.clear()
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private fun renderIcon(key: IconKey): ImageBitmap {
        val component = "${key.packageName}/${key.activity}"
        val source = resolveIconSource(
            component = component,
            override = overrides?.overrides?.value?.get(appIdOf(key)),
            selectedPack = key.style.pack,
            loadedPacks = packs?.loadedPacks?.value ?: emptyMap(),
        )
        val packDrawable = (source as? IconSource.Pack)?.let {
            packs?.drawable(it.packPackage, it.drawableName, key.sizePx)
        }
        val drawable = packDrawable ?: activityIcon(key)
        return runCatching {
            IconRasterizer.rasterize(drawable, key.sizePx, key.style, fromPack = packDrawable != null)
        }.getOrElse { blankBitmap(key.sizePx) }.asImageBitmap()
    }

    /**
     * The identity an override would be stored under. Personal apps keep the bare component string,
     * exactly as `profileAppId` produces it.
     */
    private fun appIdOf(key: IconKey): ProfileAppId {
        val component = "${key.packageName}/${key.activity}"
        return if (key.userSerial == personalSerial) component
        else "duo-profile:v1:${key.userSerial}:$component"
    }

    private fun activityIcon(key: IconKey): Drawable {
        val user = userFor(key.userSerial)
        val resolved = runCatching {
            launcherApps.getActivityList(key.packageName, user)
                ?.firstOrNull { it.componentName == ComponentName(key.packageName, key.activity) }
                ?.getBadgedIcon(0)
        }.getOrNull()
        return resolved ?: appContext.packageManager.defaultActivityIcon
    }

    private fun userFor(serial: Long): UserHandle =
        runCatching { userManager.getUserForSerialNumber(serial) }.getOrNull() ?: Process.myUserHandle()

    private fun fallback(sizePx: Int, style: IconStyle): ImageBitmap = runCatching {
        IconRasterizer.rasterize(appContext.packageManager.defaultActivityIcon, sizePx, style)
    }.getOrElse { blankBitmap(sizePx) }.asImageBitmap()

    private fun blankBitmap(sizePx: Int): Bitmap =
        Bitmap.createBitmap(sizePx.coerceIn(MIN_ICON_PX, MAX_ICON_PX), sizePx.coerceIn(MIN_ICON_PX, MAX_ICON_PX),
            Bitmap.Config.ARGB_8888)

    private companion object {
        /** The per-process heap this device allows, which the cache budget is a fraction of. */
        fun heapBytes(context: Context): Long {
            val manager = context.getSystemService(ActivityManager::class.java)
            val megabytes = runCatching { manager.memoryClass }.getOrDefault(DEFAULT_MEMORY_CLASS_MB)
            return megabytes.coerceAtLeast(DEFAULT_MEMORY_CLASS_MB).toLong() * 1024L * 1024L
        }

        const val DEFAULT_MEMORY_CLASS_MB = 128
    }
}
