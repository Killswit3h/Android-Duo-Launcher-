package com.jake.duolauncher.icons

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Xml
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** An icon pack installed on the device, as offered under **Icon pack** (FR-18). */
data class InstalledIconPack(val packageName: String, val label: String)

/** What the launcher currently knows about the selected icon pack. */
sealed interface IconPackState {
    /** No pack selected: icons use the chosen appearance. */
    data object None : IconPackState

    data class Ready(val packPackage: String, val mappings: Int) : IconPackState

    /** Selected but unusable. Drives "Icon pack not installed" and "Couldn't load icon pack". */
    data class Unavailable(val packPackage: String, val reason: IconPackFailure) : IconPackState
}

/**
 * Discovers installed ADW/Nova-compatible icon packs and reads their `appfilter.xml` (FR-18).
 *
 * **This class is the launcher's trust boundary with third-party app content** (NFR-S5). The rules
 * that make it safe live in `AppFilter.kt`; this class is the Android plumbing that applies them:
 *
 * - Nothing is parsed on the main thread ([io]).
 * - Nothing throws out of a public method. Every failure becomes an [IconPackState.Unavailable]
 *   with a reason, so a hostile or broken pack degrades to "no pack" instead of taking Home down.
 * - The asset path reads bounded bytes and refuses documents declaring entities *before* handing
 *   anything to a parser, and the parser itself is configured with document declarations off.
 * - Pack drawables are decoded within bounds, so a pack cannot allocate an arbitrarily large
 *   bitmap in the launcher's process.
 */
class IconPackRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limits: IconPackLimits = IconPackLimits.Default,
) {
    private val appContext = context.applicationContext

    private val mutableLoadedPacks = MutableStateFlow<Map<String, AppFilterMap>>(emptyMap())

    /** The mappings of every successfully loaded pack, keyed by the pack's package name. */
    val loadedPacks: StateFlow<Map<String, AppFilterMap>> = mutableLoadedPacks.asStateFlow()

    private val mutableState = MutableStateFlow<IconPackState>(IconPackState.None)

    /** The selected pack's state, for the Icon pack setting row. */
    val state: StateFlow<IconPackState> = mutableState.asStateFlow()

    /**
     * Every installed pack that declares an ADW/Nova-compatible theme entry point.
     *
     * A pack that declares one of these actions is only *claiming* to be an icon pack; whether it
     * has a usable `appfilter.xml` is not known until [select] reads it.
     */
    suspend fun installed(): List<InstalledIconPack> = withContext(io) {
        val manager = appContext.packageManager
        val found = LinkedHashMap<String, InstalledIconPack>()
        for (action in THEME_ACTIONS) {
            val resolved = runCatching {
                @Suppress("DEPRECATION")
                manager.queryIntentActivities(Intent(action), 0)
            }.getOrDefault(emptyList())
            for (info in resolved) {
                val packageName = info.activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: continue
                if (packageName == appContext.packageName) continue
                if (found.containsKey(packageName)) continue
                val label = runCatching { info.loadLabel(manager).toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
                found[packageName] = InstalledIconPack(packageName, label)
            }
        }
        found.values.sortedBy { it.label.lowercase() }
    }

    /**
     * Loads [packPackage] and makes it the selected pack, or reports why it cannot be used.
     *
     * A null or blank package clears the selection, which is also the Reset path from the error
     * table's "Icon pack not installed" row.
     */
    suspend fun select(packPackage: String?): IconPackState = withContext(io) {
        val pack = packPackage?.takeIf { it.isNotBlank() }
        if (pack == null) {
            mutableState.value = IconPackState.None
            return@withContext IconPackState.None
        }
        val result = load(pack)
        val next = when (result) {
            is AppFilterResult.Loaded -> {
                mutableLoadedPacks.value = mutableLoadedPacks.value + (pack to result.map)
                IconPackState.Ready(pack, result.map.size)
            }
            is AppFilterResult.Failed -> {
                mutableLoadedPacks.value = mutableLoadedPacks.value - pack
                IconPackState.Unavailable(pack, result.reason)
            }
        }
        mutableState.value = next
        next
    }

    /** Drops a pack from the loaded set, for an uninstall broadcast. */
    fun forget(packPackage: String) {
        mutableLoadedPacks.value = mutableLoadedPacks.value - packPackage
        val current = mutableState.value
        if (current is IconPackState.Ready && current.packPackage == packPackage) {
            mutableState.value = IconPackState.Unavailable(packPackage, IconPackFailure.NOT_INSTALLED)
        }
    }

    /**
     * Reads one pack's `appfilter.xml`. Never throws.
     *
     * Two sources are tried, in order of trustworthiness. A compiled `res/xml/appfilter.xml` is
     * read through the platform's binary XML parser, where entity declarations cannot exist at all
     * because the compiler has already resolved the document. A raw `assets/appfilter.xml` is
     * plain text straight from the pack, so it gets the full treatment: a byte ceiling, an entity
     * pre-scan and a parser with document declarations disabled.
     */
    suspend fun load(packPackage: String): AppFilterResult = withContext(io) {
        val resources = runCatching { appContext.packageManager.getResourcesForApplication(packPackage) }
            .getOrNull() ?: return@withContext AppFilterResult.Failed(IconPackFailure.NOT_INSTALLED)
        loadFromResources(resources, packPackage)?.let { return@withContext it }
        loadFromAssets(resources)?.let { return@withContext it }
        AppFilterResult.Failed(IconPackFailure.NO_APPFILTER)
    }

    private fun loadFromResources(resources: Resources, packPackage: String): AppFilterResult? {
        val id = runCatching { resources.getIdentifier(APPFILTER, "xml", packPackage) }.getOrDefault(0)
        if (id == 0) return null
        return runCatching {
            resources.getXml(id).use { parser ->
                parseAppFilter(XmlPullAppFilterSource(parser, limits), limits)
            }
        }.getOrElse { AppFilterResult.Failed(IconPackFailure.MALFORMED) }
    }

    private fun loadFromAssets(resources: Resources): AppFilterResult? {
        val bytes = runCatching {
            resources.assets.open("$APPFILTER.xml").use { readBoundedBytes(it, limits.maxBytes) }
        }.getOrNull() ?: return oversizedOrMissing(resources)
        if (containsUnsafeDeclaration(bytes)) return AppFilterResult.Failed(IconPackFailure.MALFORMED)
        return runCatching {
            val parser = Xml.newPullParser()
            // Defence in depth: the pre-scan already refused any DOCTYPE, and the parser is told
            // not to process one either (NFR-S5).
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            runCatching { parser.setFeature(XmlPullParser.FEATURE_VALIDATION, false) }
            parser.setInput(bytes.inputStream(), null)
            parseAppFilter(XmlPullAppFilterSource(parser, limits), limits)
        }.getOrElse { AppFilterResult.Failed(IconPackFailure.MALFORMED) }
    }

    /**
     * Distinguishes "no appfilter here" from "one that is too big to read".
     *
     * [readBoundedBytes] returns null for both, so the asset is probed separately: an asset that
     * opens but will not read within the ceiling is the error table's oversized pack.
     */
    private fun oversizedOrMissing(resources: Resources): AppFilterResult? {
        val exists = runCatching { resources.assets.open("$APPFILTER.xml").use { it.read() >= 0 } }
            .getOrDefault(false)
        return if (exists) AppFilterResult.Failed(IconPackFailure.TOO_LARGE) else null
    }

    /**
     * A pack drawable, decoded within bounds (NFR-S5), or null when the pack does not have it.
     *
     * [targetPx] is the size the icon will be drawn at, so a pack shipping oversized artwork is
     * downsampled during the decode rather than after it.
     */
    fun drawable(packPackage: String, drawableName: String, targetPx: Int): Drawable? {
        val name = sanitizeDrawableName(drawableName, limits) ?: return null
        return runCatching {
            val resources = appContext.packageManager.getResourcesForApplication(packPackage)
            val id = resources.getIdentifier(name, "drawable", packPackage)
            if (id == 0) return null
            decodeBounded(resources, id, targetPx)
        }.getOrNull()
    }

    private fun decodeBounded(resources: Resources, id: Int, targetPx: Int): Drawable? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeResource(resources, id, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            if (!isDecodablePackDrawable(bounds.outWidth, bounds.outHeight)) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = packDrawableSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            }
            val bitmap = runCatching { BitmapFactory.decodeResource(resources, id, options) }.getOrNull()
                ?: return null
            return BitmapDrawable(resources, bitmap)
        }
        // Not a bitmap resource: a vector or an XML drawable, which has no decode to bound. Its
        // intrinsic size is still checked so a pathological vector cannot be rasterized huge.
        val drawable = runCatching { resources.getDrawable(id, null) }.getOrNull() ?: return null
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width > MAX_PACK_DRAWABLE_PX * 4 || height > MAX_PACK_DRAWABLE_PX * 4) return null
        return drawable
    }

    private companion object {
        const val APPFILTER = "appfilter"

        /** The entry points an ADW/Nova-compatible pack declares to advertise itself. */
        val THEME_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
            "com.anddoes.launcher.THEME",
        )
    }
}

/**
 * Adapts an [XmlPullParser] to the [AppFilterTagSource] the parse rules consume.
 *
 * Attribute reads are bounded here rather than in the loop so that an absurd attribute count on a
 * single tag costs nothing beyond the tag itself.
 */
internal class XmlPullAppFilterSource(
    private val parser: XmlPullParser,
    private val limits: IconPackLimits = IconPackLimits.Default,
) : AppFilterTagSource {

    override fun nextTag(): AppFilterTag? {
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return null
            if (event != XmlPullParser.START_TAG) continue
            val name = parser.name ?: continue
            val count = parser.attributeCount.coerceAtMost(MAX_ATTRIBUTES)
            val attributes = LinkedHashMap<String, String>(count.coerceAtLeast(1))
            for (index in 0 until count) {
                val attribute = parser.getAttributeName(index) ?: continue
                val value = parser.getAttributeValue(index) ?: continue
                if (value.length > limits.maxAttributeLength) continue
                attributes[attribute] = value
            }
            return AppFilterTag(name, attributes)
        }
    }

    private companion object {
        const val MAX_ATTRIBUTES = 16
    }
}
