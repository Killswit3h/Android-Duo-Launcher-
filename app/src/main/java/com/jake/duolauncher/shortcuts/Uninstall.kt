package com.jake.duolauncher.shortcuts

import android.content.Intent
import android.net.Uri

/**
 * Everything the uninstall decision needs, flattened off `ApplicationInfo` and `UserManager`.
 *
 * Gathered on the Android side so the rule itself stays pure and testable.
 */
data class UninstallCandidate(
    val packageName: String,
    /** `ApplicationInfo.FLAG_SYSTEM`: shipped with the OS image. */
    val isSystem: Boolean = false,
    /** Duo itself. Offering "Uninstall" on the running launcher is never useful. */
    val isSelf: Boolean = false,
    /**
     * `UserManager.DISALLOW_APPS_CONTROL` or `DISALLOW_UNINSTALL_APPS` for the app's user. A work
     * profile commonly sets these, and the system would reject the uninstall anyway.
     */
    val controlsDisallowed: Boolean = false,
)

/** The uninstall capability rule (FR-29), kept pure so the menu and the tests agree exactly. */
object UninstallRules {

    /**
     * Whether the context menu shows **Uninstall** at all.
     *
     * The spec's error table is absolute about system apps: "the item is never shown". That covers
     * a system app that has since been updated too — Android would only offer to remove the update,
     * which is a different action from the one the menu item promises, so it stays hidden and
     * **App info** remains the route to it.
     */
    fun canUninstall(candidate: UninstallCandidate): Boolean =
        candidate.packageName.isNotBlank() &&
            !candidate.isSelf &&
            !candidate.isSystem &&
            !candidate.controlsDisallowed
}

/**
 * Hands off to Android's own uninstall confirmation (FR-29).
 *
 * Duo never removes a package itself: it holds `REQUEST_DELETE_PACKAGES`, a normal permission that
 * only buys the right to *ask*, and the system shows the confirmation and does the work. The
 * launcher then learns the outcome through the package-removal broadcast it already handles, which
 * is what removes the placements — so there is no result to route back here.
 */
object UninstallAction {

    /**
     * The intent for [packageName], or null when the package name is unusable.
     *
     * `Uri.fromParts` builds an opaque `package:` URI with no path, so a package name can never be
     * read as a path or authority.
     *
     * [userSerial] is recorded as Duo's own extra and is **not** what selects the profile: the
     * platform uninstaller reads `Intent.EXTRA_USER`, which needs a real `UserHandle` and is
     * therefore attached by [DuoUninstall.start]. Without it an uninstall started from a work- or
     * private-profile icon would silently target the personal copy of the same package.
     */
    fun intentFor(packageName: String, userSerial: Long? = null): Intent? {
        if (!isUsablePackageName(packageName)) return null
        return Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { if (userSerial != null) putExtra(EXTRA_USER_SERIAL, userSerial) }
    }

    /**
     * Whether a package name may go into a `package:` URI at all.
     *
     * A name carrying `:` or `/` could be read as a different URI shape, so it is refused rather
     * than escaped. Kept separate from [intentFor] so the rule is testable without Android.
     */
    fun isUsablePackageName(packageName: String): Boolean =
        packageName.isNotBlank() && ':' !in packageName && '/' !in packageName

    /**
     * Whether the activity that would receive the uninstall intent is one worth handing a user to.
     *
     * `ACTION_DELETE` is an implicit intent, and any app may register a filter for it. Without this
     * check the best-matching handler could be an ordinary third-party app showing a convincing
     * fake uninstall prompt, which is a credible place to ask for a password or a payment. Duo has
     * no way to tell the user that the dialog they are looking at is not Android's.
     *
     * Requiring the handler to be part of the system image removes that. `FLAG_UPDATED_SYSTEM_APP`
     * counts: the platform package installer is commonly updated, and an updated system app still
     * occupies a slot an ordinary app cannot take.
     *
     * An unresolvable handler is **not** trusted. Refusing costs the user an uninstall they can
     * still perform from **App info** or Settings, where starting an unknown activity costs them a
     * dialog they cannot tell from the real one.
     */
    fun isTrustedHandler(handler: UninstallHandler?): Boolean =
        handler != null &&
            handler.packageName.isNotBlank() &&
            (handler.isSystem || handler.isUpdatedSystem)

    /** Duo's own hint extra. Kept distinct from `Intent.EXTRA_USER`, which needs a `UserHandle`. */
    const val EXTRA_USER_SERIAL: String = "com.jake.duolauncher.extra.USER_SERIAL"
}

/**
 * The activity that resolved for `ACTION_DELETE`, flattened off `ResolveInfo`.
 *
 * Gathered on the Android side so [UninstallAction.isTrustedHandler] stays pure and testable, the
 * same split [UninstallCandidate] uses.
 */
data class UninstallHandler(
    val packageName: String,
    /** `ApplicationInfo.FLAG_SYSTEM`: shipped with the OS image. */
    val isSystem: Boolean = false,
    /** `ApplicationInfo.FLAG_UPDATED_SYSTEM_APP`: a system app that has since been updated. */
    val isUpdatedSystem: Boolean = false,
)
