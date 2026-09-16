package com.jake.duolauncher.icons

/**
 * Per-app icon and label overrides (FR-19), and the rule that decides which icon an app actually
 * gets (FR-18, FR-19).
 *
 * An override is keyed by `ProfileAppId`, which is the identity every launcher surface already
 * uses, so "Edit icon" on Home applies just as much in the App Library, in Search and in a folder
 * — the spec's "Overrides apply wherever that app appears" falls out of the key choice.
 */

/**
 * One app's override. Both halves are independent: a user may replace the icon, the label, or one
 * without the other, and "Reset" clears whichever they cleared.
 */
data class IconOverride(
    /** The package of the icon pack the replacement drawable came from. */
    val pack: String? = null,
    /** The drawable entry name inside [pack]. */
    val drawable: String? = null,
    /** The replacement label. */
    val label: String? = null,
) {
    val hasIcon: Boolean get() = !pack.isNullOrBlank() && !drawable.isNullOrBlank()

    val hasLabel: Boolean get() = !label.isNullOrBlank()

    /** An override with nothing left in it is removed rather than stored. */
    val isEmpty: Boolean get() = !hasIcon && !hasLabel

    /** Drops anything that could not be stored or rendered safely. */
    fun sanitized(): IconOverride {
        val cleanPack = pack?.trim()?.takeIf { it.isNotEmpty() && isStorableField(it) }
        val cleanDrawable = sanitizeDrawableName(drawable)
        val cleanLabel = sanitizeLabel(label)
        // An icon override needs both halves; one without the other names no drawable.
        return if (cleanPack == null || cleanDrawable == null) IconOverride(null, null, cleanLabel)
        else IconOverride(cleanPack, cleanDrawable, cleanLabel)
    }
}

/** The longest replacement label kept. A Home-screen label far beyond this is never readable. */
const val MAX_OVERRIDE_LABEL_LENGTH = 64

/**
 * Strips control characters and clamps the length of a user-typed label.
 *
 * The control characters matter for two reasons: they are the field separators of the storage
 * format below, and a label carrying a newline or a bidi override would corrupt every surface that
 * draws it.
 */
fun sanitizeLabel(raw: String?): String? {
    val cleaned = raw?.filter { it.code >= 0x20 && it.code != 0x7F && !it.isBidiControl() }?.trim() ?: return null
    if (cleaned.isEmpty()) return null
    return cleaned.take(MAX_OVERRIDE_LABEL_LENGTH)
}

private fun Char.isBidiControl(): Boolean = code in 0x202A..0x202E || code in 0x2066..0x2069

/** The full override set, immutable, with the storage form attached. */
data class IconOverrides(val byApp: Map<ProfileAppId, IconOverride> = emptyMap()) {

    val size: Int get() = byApp.size

    operator fun get(appId: ProfileAppId): IconOverride? = byApp[appId]

    /** Returns a copy with [appId]'s override set, or removed when it holds nothing. */
    fun with(appId: ProfileAppId, override: IconOverride?): IconOverrides {
        if (appId.isBlank() || !isStorableField(appId)) return this
        val sanitized = override?.sanitized()
        val next = LinkedHashMap(byApp)
        if (sanitized == null || sanitized.isEmpty) next.remove(appId) else next[appId] = sanitized
        return IconOverrides(next)
    }

    fun without(appId: ProfileAppId): IconOverrides = with(appId, null)

    companion object {
        val Empty = IconOverrides()
    }
}

// ---------------------------------------------------------------------------
// Storage form
// ---------------------------------------------------------------------------

private const val FIELD_SEPARATOR = ''
private const val RECORD_SEPARATOR = '\n'

private fun isStorableField(value: String): Boolean =
    value.none { it == FIELD_SEPARATOR || it == RECORD_SEPARATOR }

/**
 * Line-per-override storage form: `appId, pack, drawable, label`.
 *
 * The same shape the launch history uses, and for the same reason: profile identities contain ':'
 * and '/', so the fields are separated by a control character no component name, resource name or
 * sanitized label can hold. Anything unrepresentable is dropped rather than escaped.
 */
internal fun encodeIconOverrides(overrides: IconOverrides): String = buildString {
    for ((appId, override) in overrides.byApp) {
        val sanitized = override.sanitized()
        if (sanitized.isEmpty || !isStorableField(appId)) continue
        if (isNotEmpty()) append(RECORD_SEPARATOR)
        append(appId).append(FIELD_SEPARATOR)
            .append(sanitized.pack.orEmpty()).append(FIELD_SEPARATOR)
            .append(sanitized.drawable.orEmpty()).append(FIELD_SEPARATOR)
            .append(sanitized.label.orEmpty())
    }
}

/** Reads [encodeIconOverrides] output, skipping anything malformed rather than failing the load. */
internal fun decodeIconOverrides(stored: String?): IconOverrides {
    if (stored.isNullOrBlank()) return IconOverrides.Empty
    val result = LinkedHashMap<ProfileAppId, IconOverride>()
    for (line in stored.split(RECORD_SEPARATOR)) {
        if (line.isBlank()) continue
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != 4) continue
        val appId = fields[0].takeIf { it.isNotBlank() } ?: continue
        val override = IconOverride(
            pack = fields[1].takeIf { it.isNotBlank() },
            drawable = fields[2].takeIf { it.isNotBlank() },
            label = fields[3].takeIf { it.isNotBlank() },
        ).sanitized()
        if (!override.isEmpty) result[appId] = override
    }
    return IconOverrides(result)
}

// ---------------------------------------------------------------------------
// Resolution (FR-18, FR-19)
// ---------------------------------------------------------------------------

/** Where an icon's artwork comes from, once every rule has been applied. */
sealed interface IconSource {
    /** A drawable from an installed icon pack. */
    data class Pack(val packPackage: String, val drawableName: String) : IconSource

    /** The app's own icon, themed by the selected appearance and masked by the selected shape. */
    data object App : IconSource
}

/**
 * Resolves the artwork for one app: **override beats pack beats appearance**.
 *
 * The per-app override wins because the user chose it explicitly for this app (FR-19); the
 * selected pack applies to everything the pack covers (FR-18); everything left falls through to
 * the app's own icon, which the appearance and shape then theme.
 *
 * A pack that is named but not in [loadedPacks] is treated as absent at every level. That is the
 * single rule behind two rows of the error handling table: an uninstalled pack falls back to the
 * chosen appearance, and a pack whose XML would not load behaves exactly as if it were not
 * installed, rather than leaving the app with no icon at all.
 */
fun resolveIconSource(
    component: String,
    override: IconOverride?,
    selectedPack: String?,
    loadedPacks: Map<String, AppFilterMap>,
): IconSource {
    val sanitized = override?.sanitized()
    if (sanitized != null && sanitized.hasIcon && loadedPacks.containsKey(sanitized.pack)) {
        return IconSource.Pack(sanitized.pack!!, sanitized.drawable!!)
    }
    val pack = selectedPack?.takeIf { it.isNotBlank() } ?: return IconSource.App
    val mapped = loadedPacks[pack]?.drawableFor(component) ?: return IconSource.App
    return IconSource.Pack(pack, mapped)
}

/** The same resolution, starting from the launcher's app identity. */
fun resolveIconSource(
    appId: ProfileAppId,
    component: String,
    overrides: IconOverrides,
    selectedPack: String?,
    loadedPacks: Map<String, AppFilterMap>,
): IconSource = resolveIconSource(component, overrides[appId], selectedPack, loadedPacks)

/** The label to show for an app: the override when there is one, otherwise the app's own (FR-19). */
fun resolveLabel(appId: ProfileAppId, overrides: IconOverrides, appLabel: String): String =
    overrides[appId]?.sanitized()?.label ?: appLabel
