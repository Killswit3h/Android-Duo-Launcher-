package com.jake.duolauncher.icons

import com.jake.duolauncher.parseProfileAppId

/**
 * A persisted app identity, as produced by `profileAppId` in the root package: the flattened
 * component for personal apps, and a profile-qualified string for every other profile. The build
 * plan's internal contract names this type `ProfileAppId`.
 */
typealias ProfileAppId = String

/**
 * How an icon is themed (FR-14).
 *
 * [DEFAULT] is the app's own artwork. [DARK] keeps that artwork but darkens the adaptive
 * background layer. [CLEAR] and [TINTED] both reduce the icon to a single monochrome glyph, which
 * is the app's own monochrome layer where it ships one and a generated one where it does not
 * (FR-15).
 */
enum class IconAppearance {
    DEFAULT,
    DARK,
    CLEAR,
    TINTED;

    /** Clear and Tinted are the two appearances that need a monochrome glyph (FR-15). */
    val isMonochrome: Boolean get() = this == CLEAR || this == TINTED
}

/** The mask applied to adaptive icon layers (FR-16). Squircle is the default. */
enum class IconShape {
    SQUIRCLE,
    CIRCLE,
    ROUNDED_SQUARE,
    SQUARE,
    SCALLOP,
}

/**
 * Everything that changes what an icon *looks like*, and therefore everything that has to take
 * part in the cache key alongside the app identity and the pixel size (FR-12).
 *
 * This shape is frozen by the build plan's internal contract (section 2.2); the default values are
 * additive so callers that only care about one field stay readable.
 */
data class IconStyle(
    val appearance: IconAppearance = IconAppearance.DEFAULT,
    val tint: Int = DEFAULT_TINT,
    val tintIntensity: Int = MAX_TINT_INTENSITY,
    val shape: IconShape = IconShape.SQUIRCLE,
    val pack: String? = null,
    val dark: Boolean = false,
) {
    /**
     * Clamps the fields a settings screen can drive out of range, and drops the parts of the style
     * that the chosen appearance ignores, so that two styles which render identically also *are*
     * identical and therefore share one cache entry.
     */
    fun normalized(): IconStyle {
        val intensity = tintIntensity.coerceIn(0, MAX_TINT_INTENSITY)
        val pack = pack?.takeIf { it.isNotBlank() }
        return when (appearance) {
            IconAppearance.TINTED -> copy(tintIntensity = intensity, pack = pack)
            // Only Tinted reads the tint colour and its intensity.
            else -> copy(tint = DEFAULT_TINT, tintIntensity = MAX_TINT_INTENSITY, pack = pack)
        }
    }

    companion object {
        const val MAX_TINT_INTENSITY = 100
        const val DEFAULT_TINT = 0
    }
}

/**
 * The cache identity of one rendered icon: user, package, activity, style and size (FR-12).
 *
 * The component is split into package and activity rather than kept as one string so that a
 * package-scoped invalidation (an app updated, an icon pack uninstalled) can be expressed without
 * re-parsing every key.
 */
data class IconKey(
    val userSerial: Long,
    val packageName: String,
    val activity: String,
    val sizePx: Int,
    val style: IconStyle,
) {
    /** The bytes one ARGB_8888 bitmap of this size occupies, for the memory bound (NFR-P4). */
    val bytes: Long get() = iconBytes(sizePx)
}

/** The serial recorded for an identity that carries no profile of its own (the personal profile). */
const val PERSONAL_USER_SERIAL = 0L

/**
 * Builds the cache key for [appId] at [sizePx] in [style].
 *
 * Returns null for an identity the launcher could not parse, which is the same answer the rest of
 * the launcher gives such an id: it is skipped rather than rendered as a broken entry.
 */
fun iconKeyFor(
    appId: ProfileAppId,
    sizePx: Int,
    style: IconStyle,
    personalSerial: Long = PERSONAL_USER_SERIAL,
): IconKey? {
    if (sizePx <= 0) return null
    val identity = parseProfileAppId(appId) ?: return null
    val separator = identity.component.indexOf('/')
    if (separator <= 0 || separator == identity.component.lastIndex) return null
    val packageName = identity.component.substring(0, separator)
    val rawActivity = identity.component.substring(separator + 1)
    if (packageName.isBlank() || rawActivity.isBlank()) return null
    // ComponentName's short form: ".Main" means "<package>.Main".
    val activity = if (rawActivity.startsWith('.')) packageName + rawActivity else rawActivity
    return IconKey(identity.userSerial ?: personalSerial, packageName, activity, sizePx, style.normalized())
}

// ---------------------------------------------------------------------------
// Sizing (FR-12, FR-17)
// ---------------------------------------------------------------------------

/** The Large icons multiplier: labels are hidden and icons grow by 20% (FR-17). */
const val LARGE_ICON_SCALE = 1.2f

/** Nothing smaller is worth a cache entry, and nothing larger is worth the memory. */
const val MIN_ICON_PX = 16
const val MAX_ICON_PX = 512

/**
 * The real pixel size to rasterize for an icon displayed at [sizeDp] (FR-12).
 *
 * This is the whole point of the pipeline: the previous implementation rasterized every icon into
 * a fixed 144px bitmap, so a 68dp icon on an xxxhdpi display (272px) was upscaled and visibly soft
 * (AC-10). Rounding is half-up, so a fractional density never loses a pixel off the edge.
 */
fun iconSizePx(sizeDp: Float, density: Float, large: Boolean = false): Int {
    if (sizeDp <= 0f || density <= 0f || !sizeDp.isFinite() || !density.isFinite()) return MIN_ICON_PX
    val scaled = sizeDp * density * (if (large) LARGE_ICON_SCALE else 1f)
    return Math.round(scaled).coerceIn(MIN_ICON_PX, MAX_ICON_PX)
}

/** The memory one rendered icon occupies: ARGB_8888, so four bytes per pixel. */
fun iconBytes(sizePx: Int): Long = sizePx.toLong() * sizePx.toLong() * 4L

/** The hard ceiling on the icon cache (NFR-P4). */
const val MAX_ICON_CACHE_BYTES = 64L * 1024L * 1024L

/** The smallest budget worth keeping: below this, scrolling would re-render constantly. */
const val MIN_ICON_CACHE_BYTES = 4L * 1024L * 1024L

/**
 * The cache budget for a device whose per-process heap is [availableHeapBytes].
 *
 * An eighth of the heap keeps the cache proportional to the device rather than to the Fold 8, and
 * the NFR-P4 ceiling applies on top of that however much memory the device has.
 */
fun iconCacheBudgetBytes(availableHeapBytes: Long): Long =
    (availableHeapBytes / 8L).coerceIn(MIN_ICON_CACHE_BYTES, MAX_ICON_CACHE_BYTES)
