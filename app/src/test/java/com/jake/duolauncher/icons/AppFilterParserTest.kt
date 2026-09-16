package com.jake.duolauncher.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** A scripted tag stream, standing in for a parser over a real document. */
private class FakeTagSource(
    private val tags: List<AppFilterTag>,
    private val failAfter: Int = Int.MAX_VALUE,
) : AppFilterTagSource {
    private var index = 0
    override fun nextTag(): AppFilterTag? {
        if (index >= failAfter) error("malformed document")
        return tags.getOrNull(index++)
    }
}

private fun item(component: String, drawable: String) =
    AppFilterTag("item", mapOf("component" to component, "drawable" to drawable))

private fun componentInfo(component: String) = "ComponentInfo{$component}"

/** FR-18 and NFR-S5: an icon pack's XML is third-party content and is treated as hostile. */
class AppFilterParserTest {

    // --- The happy path -----------------------------------------------------

    @Test fun `a well formed appfilter maps components to drawables`() {
        val result = parseAppFilter(FakeTagSource(listOf(
            AppFilterTag("resources", emptyMap()),
            item(componentInfo("com.android.chrome/com.google.android.apps.chrome.Main"), "chrome"),
            item(componentInfo("com.example.mail/.MainActivity"), "mail_icon"),
        )))
        val map = (result as AppFilterResult.Loaded).map
        assertEquals(2, map.size)
        assertEquals("chrome", map.drawableFor("com.android.chrome/com.google.android.apps.chrome.Main"))
        // The short activity form expands against its own package.
        assertEquals("mail_icon", map.drawableFor("com.example.mail/com.example.mail.MainActivity"))
        assertFalse(result.truncated)
    }

    @Test fun `unmapped apps get nothing, so they fall back to the chosen appearance`() {
        val map = (parseAppFilter(FakeTagSource(listOf(item(componentInfo("a.b/.C"), "x"))))
            as AppFilterResult.Loaded).map
        assertNull(map.drawableFor("other.app/other.app.Main"))
    }

    @Test fun `a package level fallback covers an app whose activity was renamed`() {
        val map = (parseAppFilter(FakeTagSource(listOf(item(componentInfo("com.example.app/.Old"), "app_icon"))))
            as AppFilterResult.Loaded).map
        assertEquals("app_icon", map.drawableFor("com.example.app/com.example.app.Renamed"))
    }

    @Test fun `the first mapping for a component wins over later duplicates`() {
        val map = (parseAppFilter(FakeTagSource(listOf(
            item(componentInfo("a.b/.C"), "first"),
            item(componentInfo("a.b/.C"), "second"),
        ))) as AppFilterResult.Loaded).map
        assertEquals("first", map.drawableFor("a.b/a.b.C"))
    }

    @Test fun `tags a pack ships that we do not understand are skipped, not rejected`() {
        val result = parseAppFilter(FakeTagSource(listOf(
            AppFilterTag("iconback", mapOf("img1" to "back")),
            AppFilterTag("scale", mapOf("factor" to "0.8")),
            item(componentInfo("a.b/.C"), "c"),
            AppFilterTag("iconmask", mapOf("img1" to "mask")),
        )))
        assertEquals(1, (result as AppFilterResult.Loaded).map.size)
    }

    // --- Failure isolation (NFR-S5) -----------------------------------------

    @Test fun `a malformed document is ignored rather than thrown out of`() {
        val result = parseAppFilter(FakeTagSource(listOf(item(componentInfo("a.b/.C"), "c")), failAfter = 1))
        assertEquals(AppFilterResult.Failed(IconPackFailure.MALFORMED), result)
    }

    @Test fun `a document that fails immediately is still only a failed result`() {
        assertEquals(
            AppFilterResult.Failed(IconPackFailure.MALFORMED),
            parseAppFilter(FakeTagSource(emptyList(), failAfter = 0)),
        )
    }

    @Test fun `an empty document loads as an empty pack`() {
        val result = parseAppFilter(FakeTagSource(emptyList()))
        assertTrue((result as AppFilterResult.Loaded).map.isEmpty)
    }

    // --- Resource ceilings (NFR-S5) -----------------------------------------

    @Test fun `a pack with more items than the ceiling is truncated, not allowed to exhaust memory`() {
        val limits = IconPackLimits(maxItems = 10)
        val tags = List(1_000) { item(componentInfo("pkg$it.a/.C"), "icon$it") }
        val result = parseAppFilter(FakeTagSource(tags), limits) as AppFilterResult.Loaded
        assertEquals(10, result.map.size)
        assertTrue("the caller must know the pack was cut short", result.truncated)
    }

    @Test fun `an absurdly long attribute is skipped`() {
        val limits = IconPackLimits(maxAttributeLength = 32)
        val result = parseAppFilter(FakeTagSource(listOf(
            item(componentInfo("a.b/." + "C".repeat(200)), "c"),
            item(componentInfo("a.b/.D"), "d".repeat(200)),
            item(componentInfo("a.b/.E"), "e"),
        )), limits) as AppFilterResult.Loaded
        assertEquals(1, result.map.size)
        assertEquals("e", result.map.drawableFor("a.b/a.b.E"))
    }

    // --- The byte ceiling (NFR-S5, error handling table) --------------------

    @Test fun `a document within the ceiling is read whole`() {
        val bytes = ByteArray(1024) { 'a'.code.toByte() }
        assertEquals(1024, readBoundedBytes(ByteArrayInputStream(bytes), 2L * 1024L * 1024L)?.size)
    }

    @Test fun `a document over two mebibytes is refused outright`() {
        val limit = 2L * 1024L * 1024L
        val oversized = ByteArray((limit + 1).toInt())
        assertNull("an oversized pack is ignored, not truncated", readBoundedBytes(ByteArrayInputStream(oversized), limit))
        assertEquals(limit.toInt(), readBoundedBytes(ByteArrayInputStream(ByteArray(limit.toInt())), limit)?.size)
    }

    @Test fun `a stream that fails mid-read is refused rather than partially trusted`() {
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int = throw IOException("broken")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (served++ > 0) throw IOException("broken")
                return length.coerceAtMost(16)
            }
        }
        assertNull(readBoundedBytes(failing, 1024))
    }

    @Test fun `a zero limit reads nothing`() {
        assertNull(readBoundedBytes(ByteArrayInputStream(ByteArray(10)), 0))
    }

    // --- Entity and DOCTYPE rejection (NFR-S5) ------------------------------

    @Test fun `a document declaring a DOCTYPE never reaches a parser`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE resources SYSTEM "file:///etc/passwd">
            <resources><item component="ComponentInfo{a.b/.C}" drawable="c"/></resources>
        """.trimIndent()
        assertTrue(containsUnsafeDeclaration(xml.toByteArray()))
    }

    @Test fun `an entity expansion bomb never reaches a parser`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;">]>
            <resources>&lol2;</resources>
        """.trimIndent()
        assertTrue(containsUnsafeDeclaration(xml.toByteArray()))
    }

    @Test fun `an entity declaration is refused even without a doctype and whatever its case`() {
        assertTrue(containsUnsafeDeclaration("<!ENTITY x \"y\">".toByteArray()))
        assertTrue(containsUnsafeDeclaration("<!doctype html>".toByteArray()))
        assertTrue(containsUnsafeDeclaration("<!DocType resources>".toByteArray()))
    }

    @Test fun `an ordinary appfilter is not mistaken for a hostile one`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <!-- a comment mentioning entities is harmless -->
                <item component="ComponentInfo{a.b/.C}" drawable="c"/>
            </resources>
        """.trimIndent()
        assertFalse(containsUnsafeDeclaration(xml.toByteArray()))
        assertFalse(containsUnsafeDeclaration(ByteArray(0)))
    }

    // --- Component validation ------------------------------------------------

    @Test fun `both component spellings packs use in the wild are accepted`() {
        assertEquals("a.b/a.b.C", parseComponent("ComponentInfo{a.b/.C}"))
        assertEquals("a.b/a.b.C", parseComponent("a.b/.C"))
        assertEquals("a.b/x.y.Z", parseComponent("ComponentInfo{a.b/x.y.Z}"))
        assertEquals("a.b/a.b.C", parseComponent("  ComponentInfo{a.b/.C}  "))
    }

    @Test fun `a component that is not a component is skipped`() {
        assertNull(parseComponent(null))
        assertNull(parseComponent(""))
        assertNull(parseComponent("   "))
        assertNull(parseComponent("no-slash"))
        assertNull(parseComponent("/no.package"))
        assertNull(parseComponent("no.activity/"))
        assertNull(parseComponent("ComponentInfo{}"))
        // The dynamic-calendar suffix names no single drawable.
        assertNull(parseComponent("ComponentInfo{a.b/.C}:CALENDAR"))
    }

    @Test fun `a component carrying path or wildcard characters is refused`() {
        assertNull(parseComponent("ComponentInfo{../../etc/.C}"))
        assertNull(parseComponent("ComponentInfo{a b/.C}"))
        assertNull(parseComponent("ComponentInfo{a.b/.C*}"))
        assertNull(parseComponent("ComponentInfo{a.b/..C}"))
        assertNull(parseComponent("ComponentInfo{.a.b/.C}"))
        assertNull(parseComponent("ComponentInfo{a.b./.C}"))
    }

    // --- Drawable name validation --------------------------------------------

    @Test fun `a legitimate resource name is accepted`() {
        assertEquals("chrome", sanitizeDrawableName("chrome"))
        assertEquals("ic_mail_2", sanitizeDrawableName("ic_mail_2"))
        assertEquals("chrome", sanitizeDrawableName("  chrome  "))
    }

    @Test fun `a name that could address another namespace is refused`() {
        // This is the one that matters: the value goes straight to Resources#getIdentifier.
        assertNull(sanitizeDrawableName("android:drawable/ic_delete"))
        assertNull(sanitizeDrawableName("drawable/chrome"))
        assertNull(sanitizeDrawableName("../chrome"))
        assertNull(sanitizeDrawableName("chrome.png"))
        assertNull(sanitizeDrawableName("chrome icon"))
        assertNull(sanitizeDrawableName("chrome-icon"))
        assertNull(sanitizeDrawableName("chromé"))
        assertNull(sanitizeDrawableName("2chrome"))
        assertNull(sanitizeDrawableName(""))
        assertNull(sanitizeDrawableName(null))
        assertNull(sanitizeDrawableName("c".repeat(200)))
    }

    // --- Bounded drawable decoding (NFR-S5) ----------------------------------

    @Test fun `an oversized pack drawable is downsampled during the decode`() {
        assertEquals(1, packDrawableSampleSize(200, 200, 200))
        assertEquals(2, packDrawableSampleSize(400, 400, 200))
        assertEquals(4, packDrawableSampleSize(800, 800, 200))
        assertEquals(32, packDrawableSampleSize(10_000, 10_000, 200))
    }

    @Test fun `a degenerate declared size never produces a zero sample`() {
        assertEquals(1, packDrawableSampleSize(0, 0, 200))
        assertEquals(1, packDrawableSampleSize(-5, 100, 200))
        assertEquals(1, packDrawableSampleSize(100, 100, 0))
    }

    @Test fun `an image claiming an impossible size is not decoded at all`() {
        assertTrue(isDecodablePackDrawable(512, 512))
        assertTrue(isDecodablePackDrawable(1, 1))
        assertFalse(isDecodablePackDrawable(0, 512))
        assertFalse(isDecodablePackDrawable(100_000, 100_000))
        assertFalse(isDecodablePackDrawable(-1, -1))
    }
}
