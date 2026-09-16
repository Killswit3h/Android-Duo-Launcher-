package com.jake.duolauncher.shortcuts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallRulesTest {

    @Test fun aUserInstalledAppCanBeUninstalled() {
        assertTrue(UninstallRules.canUninstall(UninstallCandidate("com.example.game")))
    }

    @Test fun aSystemAppNeverShowsTheItem() {
        assertFalse(
            UninstallRules.canUninstall(
                UninstallCandidate("com.android.settings", isSystem = true),
            ),
        )
    }

    @Test fun duoNeverOffersToUninstallItself() {
        assertFalse(
            UninstallRules.canUninstall(
                UninstallCandidate("com.jake.duolauncher", isSelf = true),
            ),
        )
    }

    @Test fun aPolicyThatForbidsAppControlHidesTheItem() {
        assertFalse(
            UninstallRules.canUninstall(
                UninstallCandidate("com.example.work", controlsDisallowed = true),
            ),
        )
    }

    @Test fun aNamelessPackageIsNeverATarget() {
        assertFalse(UninstallRules.canUninstall(UninstallCandidate("")))
    }

    @Test fun aSystemAppIsHiddenEvenWhenNothingElseObjects() {
        // The error table is absolute: for a system app the item is never shown, and App info
        // remains the route to anything the user can still do.
        val candidate = UninstallCandidate(
            packageName = "com.android.chrome",
            isSystem = true,
            isSelf = false,
            controlsDisallowed = false,
        )
        assertFalse(UninstallRules.canUninstall(candidate))
    }

    // ACTION_DELETE is implicit, so any app may register a filter for it. Without this rule the
    // best-matching handler could be an ordinary third-party app showing a fake uninstall prompt,
    // which is a credible place to ask for a password. Duo cannot tell the user the dialog is fake.

    @Test fun aSystemHandlerIsTrusted() {
        assertTrue(
            UninstallAction.isTrustedHandler(
                UninstallHandler("com.android.packageinstaller", isSystem = true),
            ),
        )
    }

    @Test fun anUpdatedSystemHandlerIsTrusted() {
        // The platform package installer is commonly updated, and an updated system app still
        // occupies a slot an ordinary app cannot take.
        assertTrue(
            UninstallAction.isTrustedHandler(
                UninstallHandler("com.google.android.packageinstaller", isUpdatedSystem = true),
            ),
        )
    }

    @Test fun anOrdinaryAppIsNeverHandedTheUninstall() {
        assertFalse(
            UninstallAction.isTrustedHandler(UninstallHandler("com.evil.fakeinstaller")),
        )
    }

    @Test fun anUnresolvableHandlerIsRefused() {
        // Refusing costs an uninstall the user can still perform from App info or Settings.
        assertFalse(UninstallAction.isTrustedHandler(null))
        assertFalse(UninstallAction.isTrustedHandler(UninstallHandler("", isSystem = true)))
        assertFalse(UninstallAction.isTrustedHandler(UninstallHandler("   ", isSystem = true)))
    }

    @Test fun onlyAPlainPackageNameMayEnterAPackageUri() {
        assertTrue(UninstallAction.isUsablePackageName("com.example.game"))
        assertFalse(UninstallAction.isUsablePackageName(""))
        assertFalse(UninstallAction.isUsablePackageName("   "))
        // A name carrying a separator could otherwise be read as a different URI shape.
        assertFalse(UninstallAction.isUsablePackageName("com.example/.Main"))
        assertFalse(UninstallAction.isUsablePackageName("com.example:evil"))
    }
}
