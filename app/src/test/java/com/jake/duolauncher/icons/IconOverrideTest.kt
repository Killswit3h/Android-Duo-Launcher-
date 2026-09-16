package com.jake.duolauncher.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CHROME = "com.android.chrome/com.google.android.apps.chrome.Main"
private const val MAIL_WORK = "duo-profile:v1:42:com.example.mail/com.example.mail.Main"
private const val PACK = "com.example.pack"
private const val OTHER_PACK = "com.other.pack"

private fun packWith(vararg mappings: Pair<String, String>) = AppFilterMap(
    components = mappings.toMap(),
    packages = mappings.associate { (component, drawable) -> component.substringBefore('/') to drawable },
)

/** FR-19: per-app icon and label overrides, and the precedence they take. */
class IconOverrideTest {

    // --- Precedence: override beats pack beats appearance -------------------

    @Test fun `an app with no override and no pack uses its own icon`() {
        assertEquals(
            IconSource.App,
            resolveIconSource(CHROME, override = null, selectedPack = null, loadedPacks = emptyMap()),
        )
    }

    @Test fun `a selected pack supplies the icons it maps`() {
        val packs = mapOf(PACK to packWith(CHROME to "chrome"))
        assertEquals(
            IconSource.Pack(PACK, "chrome"),
            resolveIconSource(CHROME, override = null, selectedPack = PACK, loadedPacks = packs),
        )
    }

    @Test fun `an app the selected pack does not map falls back to the chosen appearance`() {
        val packs = mapOf(PACK to packWith("other.app/other.app.Main" to "other"))
        assertEquals(
            IconSource.App,
            resolveIconSource(CHROME, override = null, selectedPack = PACK, loadedPacks = packs),
        )
    }

    @Test fun `a per-app override beats the selected pack`() {
        val packs = mapOf(
            PACK to packWith(CHROME to "chrome"),
            OTHER_PACK to packWith(CHROME to "ignored"),
        )
        assertEquals(
            IconSource.Pack(OTHER_PACK, "hand_picked"),
            resolveIconSource(
                CHROME,
                override = IconOverride(OTHER_PACK, "hand_picked"),
                selectedPack = PACK,
                loadedPacks = packs,
            ),
        )
    }

    @Test fun `a label-only override leaves the icon to the pack`() {
        val packs = mapOf(PACK to packWith(CHROME to "chrome"))
        assertEquals(
            IconSource.Pack(PACK, "chrome"),
            resolveIconSource(CHROME, IconOverride(label = "Web"), PACK, packs),
        )
    }

    // --- Missing packs (error handling table) --------------------------------

    @Test fun `an uninstalled selected pack falls back to the appearance`() {
        assertEquals(
            IconSource.App,
            resolveIconSource(CHROME, override = null, selectedPack = PACK, loadedPacks = emptyMap()),
        )
    }

    @Test fun `an override naming a pack that is gone falls through rather than losing the icon`() {
        val packs = mapOf(PACK to packWith(CHROME to "chrome"))
        assertEquals(
            IconSource.Pack(PACK, "chrome"),
            resolveIconSource(CHROME, IconOverride(OTHER_PACK, "missing"), PACK, packs),
        )
        assertEquals(
            IconSource.App,
            resolveIconSource(CHROME, IconOverride(OTHER_PACK, "missing"), null, packs),
        )
    }

    @Test fun `an override with only half an icon names no drawable`() {
        val packs = mapOf(PACK to packWith())
        assertEquals(IconSource.App, resolveIconSource(CHROME, IconOverride(pack = PACK), null, packs))
        assertEquals(IconSource.App, resolveIconSource(CHROME, IconOverride(drawable = "x"), null, packs))
    }

    @Test fun `an override carrying an unsafe drawable name is not honoured`() {
        val packs = mapOf(PACK to packWith())
        assertEquals(
            IconSource.App,
            resolveIconSource(CHROME, IconOverride(PACK, "android:drawable/ic_delete"), null, packs),
        )
    }

    // --- Overrides apply wherever the app appears ----------------------------

    @Test fun `an override is keyed by profile identity, so a work app keeps its own`() {
        val overrides = IconOverrides.Empty
            .with(CHROME, IconOverride(PACK, "personal"))
            .with(MAIL_WORK, IconOverride(PACK, "work"))
        val packs = mapOf(PACK to packWith())
        assertEquals(
            IconSource.Pack(PACK, "personal"),
            resolveIconSource(CHROME, CHROME, overrides, null, packs),
        )
        assertEquals(
            IconSource.Pack(PACK, "work"),
            resolveIconSource(MAIL_WORK, "com.example.mail/com.example.mail.Main", overrides, null, packs),
        )
    }

    // --- Labels (FR-19) -------------------------------------------------------

    @Test fun `a renamed app shows its new label and everything else keeps its own`() {
        val overrides = IconOverrides.Empty.with(CHROME, IconOverride(label = "Web"))
        assertEquals("Web", resolveLabel(CHROME, overrides, "Chrome"))
        assertEquals("Mail", resolveLabel(MAIL_WORK, overrides, "Mail"))
    }

    @Test fun `a label is stripped of anything that would corrupt a surface that draws it`() {
        assertEquals("Web", sanitizeLabel("  Web\n  "))
        assertEquals("WebMail", sanitizeLabel("WebMail"))
        assertEquals("Web", sanitizeLabel("Web‮"))
        assertNull(sanitizeLabel(""))
        assertNull(sanitizeLabel("   "))
        assertNull(sanitizeLabel(null))
        assertNull(sanitizeLabel("\n\t"))
        assertEquals(MAX_OVERRIDE_LABEL_LENGTH, sanitizeLabel("x".repeat(500))!!.length)
    }

    // --- Reset (FR-19) --------------------------------------------------------

    @Test fun `resetting both halves removes the entry entirely`() {
        val overrides = IconOverrides.Empty
            .with(CHROME, IconOverride(PACK, "chrome", "Web"))
            .without(CHROME)
        assertEquals(0, overrides.size)
        assertNull(overrides[CHROME])
    }

    @Test fun `an override that sanitizes to nothing is never stored`() {
        assertEquals(0, IconOverrides.Empty.with(CHROME, IconOverride()).size)
        assertEquals(0, IconOverrides.Empty.with(CHROME, IconOverride(label = "   ")).size)
        assertEquals(0, IconOverrides.Empty.with(CHROME, null).size)
        assertEquals(0, IconOverrides.Empty.with("", IconOverride(PACK, "x")).size)
    }

    // --- Storage form ---------------------------------------------------------

    @Test fun `overrides survive a round trip through storage`() {
        val overrides = IconOverrides.Empty
            .with(CHROME, IconOverride(PACK, "chrome", "Web"))
            .with(MAIL_WORK, IconOverride(label = "Work mail"))
        val restored = decodeIconOverrides(encodeIconOverrides(overrides))
        assertEquals(overrides, restored)
        assertEquals("Web", restored[CHROME]?.label)
        assertEquals("chrome", restored[CHROME]?.drawable)
        assertNull(restored[MAIL_WORK]?.pack)
    }

    @Test fun `an empty store reads back empty`() {
        assertEquals(IconOverrides.Empty, decodeIconOverrides(null))
        assertEquals(IconOverrides.Empty, decodeIconOverrides(""))
        assertEquals(IconOverrides.Empty, decodeIconOverrides("   "))
        assertEquals("", encodeIconOverrides(IconOverrides.Empty))
    }

    @Test fun `a corrupt line is skipped rather than failing the whole load`() {
        val good = encodeIconOverrides(IconOverrides.Empty.with(CHROME, IconOverride(PACK, "chrome")))
        val stored = "not a record\n$good\n\nalso bad"
        val restored = decodeIconOverrides(stored)
        assertEquals(1, restored.size)
        assertEquals("chrome", restored[CHROME]?.drawable)
    }

    @Test fun `a stored override is re-sanitized on the way back in`() {
        // Whatever ends up on disk, what comes back is safe to hand to getIdentifier.
        val stored = "$CHROME$PACKandroid:drawable/xWeb"
        val restored = decodeIconOverrides(stored)
        assertNull(restored[CHROME]?.drawable)
        assertEquals("Web", restored[CHROME]?.label)
    }

    @Test fun `an identity that cannot be stored is dropped rather than corrupting the file`() {
        val overrides = IconOverrides(mapOf("bad\nid" to IconOverride(PACK, "x")))
        assertTrue(encodeIconOverrides(overrides).isEmpty())
    }
}
