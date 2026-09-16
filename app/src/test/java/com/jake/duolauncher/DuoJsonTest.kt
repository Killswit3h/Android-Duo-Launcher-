package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON layer is load-bearing for the saved layout, so it is tested as hard as the layout is.
 *
 * The cases that matter most are the ones a real payload actually contains: folder titles and app
 * labels are user text, so quotes, backslashes, newlines, emoji and non-Latin scripts all have to
 * survive a round trip exactly, and anything malformed has to fail loudly rather than silently
 * truncate a layout.
 */
class DuoJsonTest {

    private fun parseObject(text: String) = DuoJson.parse(text) as DuoJson.Obj

    @Test fun `reads the value shapes a saved layout contains`() {
        val json = parseObject("""{"schema":9,"labels":true,"accent":null,"dockPosition":0.56,"pages":[1,2]}""")
        assertEquals(9, json.int("schema"))
        assertTrue(json.boolean("labels", false))
        assertNull(json.string("accent"))
        assertEquals(0.56f, json.float("dockPosition", 0f), 0.0001f)
        assertEquals(2, json.array("pages")?.size)
    }

    @Test fun `a JSON null reads as absent rather than as a value`() {
        val json = parseObject("""{"iconPack":null}""")
        assertNull(json.string("iconPack"))
        assertTrue(json.values.containsKey("iconPack"))
        // `has` is what the codec uses to tell "set to null" from "set to something".
        assertTrue(!json.has("iconPack"))
    }

    @Test fun `slot arrays keep their empty cells as nulls in position`() {
        val slots = (DuoJson.parse("""["a",null,"b"]""") as DuoJson.Arr).stringsOrNulls()
        assertEquals(listOf("a", null, "b"), slots)
    }

    @Test fun `user text survives a round trip exactly`() {
        val titles = listOf(
            "Work \"stuff\"",
            "back\\slash",
            "line\nbreak",
            "tab\there",
            "emoji 🎧🌍",
            "日本語のフォルダ",
            "quote'andbell",
        )
        titles.forEach { title ->
            val encoded = DuoJson.obj("title" to DuoJson.of(title)).write()
            assertEquals(title, parseObject(encoded).string("title"))
        }
    }

    @Test fun `control characters are escaped rather than written raw`() {
        val encoded = DuoJson.obj("t" to DuoJson.of("ab")).write()
        assertTrue(encoded.contains("\\u0001"))
        assertEquals("ab", parseObject(encoded).string("t"))
    }

    @Test fun `whole numbers round trip as integers`() {
        val encoded = DuoJson.obj(
            "slot" to DuoJson.of(7),
            "serial" to DuoJson.of(1234567890123L),
            "size" to DuoJson.of(66f),
        ).write()
        assertTrue("Integers must not gain a fractional part: $encoded", encoded.contains("\"slot\":7"))
        assertTrue(encoded.contains("\"size\":66"))
        val json = parseObject(encoded)
        assertEquals(7, json.int("slot"))
        assertEquals(1234567890123L, json.long("serial"))
    }

    @Test fun `an integer accessor refuses a fractional number`() {
        val json = parseObject("""{"slot":2.5}""")
        assertNull(json.int("slot"))
    }

    @Test fun `reads the number forms org json can emit`() {
        val json = parseObject("""{"a":66,"b":66.0,"c":-3,"d":1.0E2,"e":0.0}""")
        assertEquals(66f, json.float("a", 0f), 0.0001f)
        assertEquals(66f, json.float("b", 0f), 0.0001f)
        assertEquals(-3, json.int("c"))
        assertEquals(100f, json.float("d", 0f), 0.0001f)
    }

    @Test fun `escapes that org json writes are all understood`() {
        val json = parseObject("""{"t":"\" \\ \/ \b \f \n \r \t é 日"}""")
        assertEquals("\" \\ / \b  \n \r \t é 日", json.string("t"))
    }

    @Test fun `malformed payloads throw instead of decoding partially`() {
        val broken = listOf(
            "",
            "{",
            "}",
            """{"a":}""",
            """{"a" 1}""",
            """{"a":1,}""",
            """{"a":1}trailing""",
            """["unterminated""",
            """{"a":"no end}""",
            // Every invalid JSON number form. A payload containing one is corrupt, and reading
            // `01` as 1 would turn a damaged slot or span into a plausible-looking wrong one.
            """{"a":01}""",
            """{"a":-01}""",
            """{"a":01.2}""",
            """{"a":00}""",
            """{"a":+1}""",
            """{"a":.5}""",
            """{"a":1.}""",
            """{"a":1e}""",
            """{"a":1e+}""",
            """{"a":0x1F}""",
            """{"a":-}""",
            """{"a":"bad\qescape"}""",
            """{"a":"\u12"}""",
            "[1,2",
            "nul",
        )
        broken.forEach { text ->
            assertThrows("\"$text\" must be rejected", Exception::class.java) { DuoJson.parse(text) }
        }
    }

    @Test fun `a raw control character in a string is rejected`() {
        assertThrows(Exception::class.java) { DuoJson.parse("{\"a\":\"x\ny\"}") }
    }

    @Test fun `deep nesting is refused rather than overflowing the stack`() {
        val deep = "[".repeat(500) + "]".repeat(500)
        assertThrows(Exception::class.java) { DuoJson.parse(deep) }
    }

    @Test fun `nesting within the real document depth is accepted`() {
        val nested = "[".repeat(20) + "]".repeat(20)
        assertTrue(DuoJson.parse(nested) is DuoJson.Arr)
    }

    @Test fun `objects and arrays round trip through the writer`() {
        val original = DuoJson.obj(
            "grid" to DuoJson.obj("columns" to DuoJson.of(6), "rows" to DuoJson.of(6)),
            "slots" to DuoJson.ofNullableStrings(listOf("a", null, "b")),
            "flags" to DuoJson.Arr(listOf(DuoJson.of(true), DuoJson.of(false), DuoJson.Null)),
        )
        assertEquals(original, DuoJson.parse(original.write()))
    }

    @Test fun `whitespace between tokens is ignored`() {
        val json = parseObject("  {\n  \"a\" : 1 ,\t\"b\" : [ 1 , 2 ]\r\n}  ")
        assertEquals(1, json.int("a"))
        assertEquals(2, json.array("b")?.size)
    }

    @Test fun `an empty object and array are valid`() {
        assertEquals(0, parseObject("{}").values.size)
        assertEquals(0, (DuoJson.parse("[]") as DuoJson.Arr).size)
    }
}
