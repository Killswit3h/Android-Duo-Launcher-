package com.jake.duolauncher.shortcuts

/**
 * The shortcut rules, kept pure so every one of them is unit-testable without an emulator.
 *
 * Nothing here touches `LauncherApps`, a `Context` or a `UserHandle`; the Android edges live in
 * [ShortcutSource] and its `LauncherApps` implementation.
 */
object ShortcutRules {

    /**
     * Resolves the launcher's app identity to the package/user pair `LauncherApps` needs (FR-25).
     *
     * A personal-profile id carries no serial of its own, so it takes [personalSerial]; a work or
     * private-profile id carries its serial and keeps it. That is what makes the work copy of an
     * app query the work profile's shortcuts rather than the personal copy's.
     */
    fun targetOf(app: ProfileAppId, personalSerial: Long): ShortcutTarget? {
        if (personalSerial < 0) return null
        val identity = parseAppId(app) ?: return null
        val packageName = identity.component.substringBefore('/').takeIf { it.isNotBlank() }
            ?: return null
        if (':' in packageName) return null
        val serial = identity.userSerial ?: personalSerial
        if (serial < 0) return null
        return ShortcutTarget(packageName, serial)
    }

    /**
     * The context menu's shortcut list (FR-25): manifest and dynamic shortcuts, ordered by rank,
     * capped at [limit].
     *
     * Manifest shortcuts sort ahead of dynamic ones because `rank` is only meaningful *within* each
     * set — an app's manifest rank 0 and its dynamic rank 0 are two different "first" shortcuts,
     * and interleaving them by the raw number would order them arbitrarily. Ties fall back to the
     * shortcut id so the menu never reshuffles between two openings.
     *
     * Disabled shortcuts are dropped: offering one that cannot start is worse than not offering it.
     * Pinned-only shortcuts are dropped too, since the menu lists what the app publishes now.
     */
    fun order(shortcuts: List<DuoShortcut>, limit: Int = SHORTCUT_MENU_LIMIT): List<DuoShortcut> {
        if (limit <= 0 || shortcuts.isEmpty()) return emptyList()
        return shortcuts.asSequence()
            .filter { it.id.isNotBlank() && it.packageName.isNotBlank() }
            .filter { it.isEnabled }
            .filter { it.isDeclaredInManifest || it.isDynamic }
            .distinctBy { it.packageName to it.id }
            .sortedWith(
                compareBy<DuoShortcut> { if (it.isDeclaredInManifest) 0 else 1 }
                    .thenBy(DuoShortcut::rank)
                    .thenBy(DuoShortcut::id),
            )
            .take(limit)
            .toList()
    }

    /**
     * What a stored placement resolves to right now (error table).
     *
     * [found] is the shortcut the system still reports for the key, or null when the app removed
     * it. Host permission is checked first because without it every query returns nothing, and
     * reporting that as "removed" would be a lie that the **Remove** action would act on.
     */
    fun resolve(
        key: PinnedShortcutKey,
        hostPermission: Boolean,
        found: DuoShortcut?,
    ): PinnedShortcutState = when {
        !hostPermission -> PinnedShortcutState.Unavailable(
            key,
            ShortcutUnavailableReason.NO_HOST_PERMISSION,
        )
        found == null -> PinnedShortcutState.Unavailable(key, ShortcutUnavailableReason.REMOVED)
        !found.isEnabled -> PinnedShortcutState.Unavailable(
            key,
            ShortcutUnavailableReason.DISABLED_BY_APP,
            found.disabledMessage?.takeIf(String::isNotBlank),
        )
        else -> PinnedShortcutState.Available(key, found)
    }

    /**
     * What tapping a pinned shortcut should say, or null when it simply launches.
     *
     * A disabled shortcut shows the app's own message when it supplied one, because the app is the
     * only party that can explain why ("Sign in to use this shortcut").
     */
    fun tapMessage(state: PinnedShortcutState): String? = when (state) {
        is PinnedShortcutState.Available -> null
        is PinnedShortcutState.Unavailable -> when (state.reason) {
            ShortcutUnavailableReason.NO_HOST_PERMISSION -> OPEN_SHORTCUT_NEEDS_HOME
            ShortcutUnavailableReason.DISABLED_BY_APP -> state.message ?: SHORTCUT_UNAVAILABLE
            ShortcutUnavailableReason.REMOVED -> SHORTCUT_UNAVAILABLE
        }
    }

    /** Indirection so this object stays free of the root package's Android-touching neighbours. */
    private fun parseAppId(app: ProfileAppId) = com.jake.duolauncher.parseProfileAppId(app)
}
