package com.jake.duolauncher.icons

import java.io.IOException
import java.io.InputStream

/**
 * Parsing rules for an ADW/Nova-compatible icon pack's `appfilter.xml` (FR-18).
 *
 * **Everything in this file treats its input as hostile** (NFR-S5). An icon pack is an ordinary
 * third-party app: its XML, its resource names and its drawables are attacker-controlled as far as
 * the launcher is concerned, and a malformed or malicious pack must be ignored with a clear state
 * rather than take Home down with it.
 *
 * The defences, in the order they apply:
 *
 * 1. **A byte ceiling before anything is parsed** ([IconPackLimits.maxBytes], 2 MiB). A pack cannot
 *    make the launcher allocate an unbounded buffer.
 * 2. **A declaration pre-scan** ([containsUnsafeDeclaration]). Any `<!DOCTYPE` or `<!ENTITY` and the
 *    document is rejected outright, so external entities and entity-expansion bombs never reach the
 *    parser at all. This runs in addition to, not instead of, disabling `DOCDECL` on the parser.
 * 3. **An item ceiling** ([IconPackLimits.maxItems]) and an attribute length ceiling, so a
 *    well-formed document cannot exhaust memory through sheer repetition.
 * 4. **Strict component and drawable-name validation**. A drawable name is about to be handed to
 *    `Resources.getIdentifier`, so anything outside `[A-Za-z0-9_]` is rejected rather than escaped;
 *    that is what stops a name like `other.package:drawable/x` from reaching for a resource in a
 *    namespace the pack does not own.
 * 5. **Failure isolation**. Every exit is a value, never a throw: the caller gets [AppFilterResult]
 *    and the user gets "Couldn't load icon pack" (error handling table).
 */

/** Resource ceilings applied to an untrusted icon pack (NFR-S5). */
data class IconPackLimits(
    /** The spec's hard limit on `appfilter.xml` (NFR-S5, error handling table). */
    val maxBytes: Long = 2L * 1024L * 1024L,
    /** Far more mappings than any real pack ships, and a bound on a hostile one. */
    val maxItems: Int = 20_000,
    /** No legitimate component or drawable attribute is anywhere near this long. */
    val maxAttributeLength: Int = 512,
) {
    companion object {
        val Default = IconPackLimits()
    }
}

/** Why an icon pack could not be used. Each maps to one user-visible state in the error table. */
enum class IconPackFailure {
    /** The selected pack's app is no longer installed: "Icon pack not installed", with Reset. */
    NOT_INSTALLED,

    /** Installed, but it ships no `appfilter.xml` we can read. */
    NO_APPFILTER,

    /** Over [IconPackLimits.maxBytes]: "Couldn't load icon pack". */
    TOO_LARGE,

    /** Declares a DOCTYPE or an ENTITY, or is not well-formed: "Couldn't load icon pack". */
    MALFORMED,
}

/**
 * One icon pack's mappings.
 *
 * [components] is keyed by the normalized `package/activity` string. [packages] is the
 * package-level fallback most packs rely on implicitly: an app whose launcher activity was renamed
 * between releases still gets its pack icon as long as the pack mapped any activity of that
 * package.
 */
data class AppFilterMap(
    val components: Map<String, String> = emptyMap(),
    val packages: Map<String, String> = emptyMap(),
) {
    val size: Int get() = components.size

    val isEmpty: Boolean get() = components.isEmpty()

    /** The drawable this pack maps [component] to, or null when the pack does not cover the app. */
    fun drawableFor(component: String): String? {
        components[component]?.let { return it }
        val packageName = component.substringBefore('/').takeIf(String::isNotBlank) ?: return null
        return packages[packageName]
    }
}

/** The outcome of reading one pack's `appfilter.xml`. Never a thrown exception. */
sealed interface AppFilterResult {
    /** [truncated] is true when the pack hit [IconPackLimits.maxItems] and was cut short. */
    data class Loaded(val map: AppFilterMap, val truncated: Boolean = false) : AppFilterResult

    data class Failed(val reason: IconPackFailure) : AppFilterResult
}

/**
 * The few pull-parser events the `appfilter` grammar actually needs.
 *
 * The real implementation wraps an `XmlPullParser` configured with no external entities (NFR-S5).
 * Keeping the parse loop behind this seam is what lets the loop, its ceilings and its failure
 * isolation all be unit-tested on the JVM, where no XML implementation is available.
 */
internal interface AppFilterTagSource {
    /**
     * Advances to the next start tag and returns it, or null at the end of the document.
     *
     * May throw: a malformed document is exactly what this is expected to do, and [parseAppFilter]
     * turns that into [IconPackFailure.MALFORMED].
     */
    fun nextTag(): AppFilterTag?
}

/** One start tag: its name, and its attributes by name. */
internal data class AppFilterTag(val name: String, val attributes: Map<String, String>)

/**
 * Walks an `appfilter.xml` into an [AppFilterMap].
 *
 * Unknown tags (`iconback`, `iconmask`, `scale`, vendor extensions) are skipped rather than
 * rejected: packs in the wild carry all sorts of extra elements and refusing them would make most
 * real packs unusable for no security gain. The first mapping for a component wins, which matches
 * how ADW-compatible launchers resolve the duplicates packs routinely ship.
 */
internal fun parseAppFilter(
    source: AppFilterTagSource,
    limits: IconPackLimits = IconPackLimits.Default,
): AppFilterResult {
    val components = LinkedHashMap<String, String>()
    val packages = LinkedHashMap<String, String>()
    var truncated = false
    try {
        while (true) {
            val tag = source.nextTag() ?: break
            if (!tag.name.equals(ITEM_TAG, ignoreCase = true)) continue
            if (components.size >= limits.maxItems) {
                truncated = true
                break
            }
            val component = parseComponent(tag.attributes[COMPONENT_ATTRIBUTE], limits) ?: continue
            val drawable = sanitizeDrawableName(tag.attributes[DRAWABLE_ATTRIBUTE], limits) ?: continue
            if (components.putIfAbsent(component, drawable) == null) {
                packages.putIfAbsent(component.substringBefore('/'), drawable)
            }
        }
    } catch (_: Exception) {
        // A pack that cannot be parsed is ignored, never fatal (NFR-S5, error handling table).
        return AppFilterResult.Failed(IconPackFailure.MALFORMED)
    }
    return AppFilterResult.Loaded(AppFilterMap(components, packages), truncated)
}

private const val ITEM_TAG = "item"
private const val COMPONENT_ATTRIBUTE = "component"
private const val DRAWABLE_ATTRIBUTE = "drawable"

/**
 * Normalizes an appfilter `component` attribute to `package/activity`.
 *
 * Accepts the `ComponentInfo{pkg/cls}` form every pack writes, and the bare `pkg/cls` form some
 * use. Anything else — including the `:CALENDAR` dynamic-calendar suffix packs sometimes append,
 * which names no single drawable — returns null and is skipped.
 */
internal fun parseComponent(raw: String?, limits: IconPackLimits = IconPackLimits.Default): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= limits.maxAttributeLength } ?: return null
    val inner = when {
        value.startsWith(COMPONENT_INFO_PREFIX, ignoreCase = true) && value.endsWith('}') ->
            value.substring(COMPONENT_INFO_PREFIX.length, value.length - 1)
        else -> value
    }.trim()
    val separator = inner.indexOf('/')
    if (separator <= 0 || separator == inner.lastIndex) return null
    val packageName = inner.substring(0, separator)
    val rawActivity = inner.substring(separator + 1)
    if (!isJavaIdentifierPath(packageName)) return null
    val activity = if (rawActivity.startsWith('.')) packageName + rawActivity else rawActivity
    if (!isJavaIdentifierPath(activity)) return null
    return "$packageName/$activity"
}

private const val COMPONENT_INFO_PREFIX = "ComponentInfo{"

/** Dotted Java identifiers only: no wildcards, no separators, no whitespace, no control characters. */
private fun isJavaIdentifierPath(value: String): Boolean {
    if (value.isEmpty() || value.startsWith('.') || value.endsWith('.') || value.contains("..")) return false
    return value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }
}

/**
 * Validates a `drawable` attribute as an Android resource entry name.
 *
 * This value is about to be passed to `Resources.getIdentifier`, so it is restricted to the
 * characters a resource entry name can legally contain. A name carrying a `:` or a `/` would let a
 * pack address a resource type or package other than its own drawables, and is rejected outright.
 */
internal fun sanitizeDrawableName(raw: String?, limits: IconPackLimits = IconPackLimits.Default): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= limits.maxAttributeLength } ?: return null
    if (value.length > MAX_RESOURCE_NAME_LENGTH) return null
    if (value.first().isDigit()) return null
    if (!value.all { it.isLetterOrDigit() && it.code < 128 || it == '_' }) return null
    return value
}

private const val MAX_RESOURCE_NAME_LENGTH = 128

/**
 * Reads at most [limit] bytes, or returns null when the stream is longer than that.
 *
 * Deliberately returns null rather than the truncated prefix: half an XML document is not a
 * document, and the error table's "malformed or oversized (>2 MiB)" row wants the pack ignored,
 * not partially applied.
 */
internal fun readBoundedBytes(input: InputStream, limit: Long): ByteArray? {
    if (limit <= 0L) return null
    val buffer = ByteArray(READ_CHUNK)
    val output = java.io.ByteArrayOutputStream(READ_CHUNK)
    var total = 0L
    try {
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
    } catch (_: IOException) {
        return null
    }
    return output.toByteArray()
}

private const val READ_CHUNK = 16 * 1024

/**
 * Whether the document declares a DOCTYPE or an ENTITY.
 *
 * This is the XXE and billion-laughs guard, applied to the raw bytes before any parser sees them.
 * The launcher has no INTERNET permission, so an external entity could not exfiltrate anything,
 * but it could still read local files into the parse and an internal entity could still expand
 * into gigabytes — so both are refused.
 */
internal fun containsUnsafeDeclaration(bytes: ByteArray): Boolean {
    val text = String(bytes, Charsets.ISO_8859_1)
    return text.contains("<!DOCTYPE", ignoreCase = true) ||
        text.contains("<!ENTITY", ignoreCase = true) ||
        text.contains("<!doctype", ignoreCase = true)
}

// ---------------------------------------------------------------------------
// Bounded drawable decoding (NFR-S5)
// ---------------------------------------------------------------------------

/** No pack drawable is ever decoded larger than this, whatever the pack claims its size is. */
const val MAX_PACK_DRAWABLE_PX = 1024

/**
 * The `BitmapFactory` sample size for a pack drawable of [width] x [height] rendered at [targetPx].
 *
 * A pack can ship a 10000 x 10000 PNG; decoding it at full size to draw a 200px icon is both a
 * memory hazard and pointless. The result is always a power of two, as `BitmapFactory` requires,
 * and never below 1.
 */
fun packDrawableSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/** Whether a declared drawable size is plausible enough to decode at all. */
fun isDecodablePackDrawable(width: Int, height: Int): Boolean =
    width in 1..MAX_DECLARED_DRAWABLE_PX && height in 1..MAX_DECLARED_DRAWABLE_PX

/** A declared dimension beyond this is a corrupt or hostile image header; refuse it. */
private const val MAX_DECLARED_DRAWABLE_PX = 16_384
