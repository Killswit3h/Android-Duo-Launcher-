package com.jake.duolauncher

/**
 * A small, strict JSON reader and writer used by the schema-9 layout codec.
 *
 * ## Why this exists rather than `org.json`
 *
 * `org.json` ships with Android but is *stubbed* in JVM unit tests: every method throws
 * "not mocked" unless the project pulls in Robolectric or a JSON dependency, and this project has
 * neither (and may not add one). The saved layout is the user's real Home screen, so its codec and
 * its v8 migration have to be covered by tests that run on every build, against real JSON text,
 * not only by instrumented tests that need a device. Keeping the codec on a pure-Kotlin parser is
 * what makes that possible.
 *
 * ## What it must accept
 *
 * The critical direction is *reading what `org.json` previously wrote*, because every existing
 * install's `launcher/state` and every `state_vN_backup` was produced by `org.json`. That output
 * uses only objects, arrays, strings, integers, doubles, booleans and null, but strings are user
 * text (folder titles, app labels) and may contain any escape. So the string reader implements the
 * full JSON escape set including `\uXXXX` surrogate pairs.
 *
 * It is deliberately strict: trailing content, unterminated strings, bad escapes, control
 * characters in strings and over-deep nesting are all errors rather than silent recoveries. A
 * layout that does not parse must fail loudly so the caller can fall back to a backup, never be
 * half-read into a layout that quietly loses placements.
 */
internal sealed interface DuoJson {

    data class Obj(val values: Map<String, DuoJson>) : DuoJson {
        operator fun get(key: String): DuoJson? = values[key]
        fun obj(key: String): Obj? = values[key] as? Obj
        fun array(key: String): Arr? = values[key] as? Arr

        /** Present and non-null. A JSON `null` reads as absent for every typed accessor. */
        fun has(key: String): Boolean = values[key] != null && values[key] !is Null

        fun string(key: String): String? = (values[key] as? Str)?.value
        fun boolean(key: String, default: Boolean): Boolean = (values[key] as? Bool)?.value ?: default
        fun int(key: String): Int? = (values[key] as? Num)?.asInt()
        fun int(key: String, default: Int): Int = int(key) ?: default
        fun long(key: String): Long? = (values[key] as? Num)?.asLong()
        fun double(key: String, default: Double): Double = (values[key] as? Num)?.value ?: default
        fun float(key: String, default: Float): Float = (values[key] as? Num)?.value?.toFloat() ?: default
    }

    data class Arr(val values: List<DuoJson>) : DuoJson {
        val size: Int get() = values.size
        operator fun get(index: Int): DuoJson? = values.getOrNull(index)
        fun obj(index: Int): Obj? = values.getOrNull(index) as? Obj

        /** A JSON `null` element, or a non-string element, reads as null. */
        fun stringOrNull(index: Int): String? = (values.getOrNull(index) as? Str)?.value

        /** Every element as a nullable string, which is how slot arrays are stored. */
        fun stringsOrNulls(): List<String?> = values.map { (it as? Str)?.value }

        /** Every element that is a string, skipping nulls and other types. */
        fun strings(): List<String> = values.mapNotNull { (it as? Str)?.value }

        fun objects(): List<Obj> = values.filterIsInstance<Obj>()
    }

    data class Str(val value: String) : DuoJson

    data class Num(val value: Double) : DuoJson {
        /** Null unless the value is a whole number inside `Int` range. */
        fun asInt(): Int? = asLong()?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()

        /** Null unless the value is a whole number exactly representable as a `Long`. */
        fun asLong(): Long? = value.takeIf {
            it.isFinite() && it % 1.0 == 0.0 && it >= -9.007199254740992E15 && it <= 9.007199254740992E15
        }?.toLong()
    }

    data class Bool(val value: Boolean) : DuoJson

    data object Null : DuoJson

    companion object {
        /**
         * Guards against a corrupt or hostile payload recursing until the stack overflows. Real
         * layouts nest four deep; 64 is far beyond anything the writer can produce.
         */
        private const val MAX_DEPTH = 64

        /** Parses [text], or throws [IllegalArgumentException] for anything malformed. */
        fun parse(text: String): DuoJson = DuoJsonReader(text).parseDocument()

        fun obj(vararg entries: Pair<String, DuoJson>): Obj = Obj(linkedMapOf(*entries))

        fun of(value: String): Str = Str(value)
        fun of(value: Int): Num = Num(value.toDouble())
        fun of(value: Long): Num = Num(value.toDouble())
        fun of(value: Float): Num = Num(value.toDouble())
        fun of(value: Boolean): Bool = Bool(value)

        /** A slot list, where an empty cell is a JSON `null`. */
        fun ofNullableStrings(values: List<String?>): Arr =
            Arr(values.map { if (it == null) Null else Str(it) })

        fun ofStrings(values: List<String>): Arr = Arr(values.map(::Str))

        fun ofInts(values: List<Int>): Arr = Arr(values.map { of(it) })
    }
}

/** Serializes to compact JSON text that [DuoJson.parse] reads back identically. */
internal fun DuoJson.write(): String = StringBuilder().also { writeTo(it) }.toString()

private fun DuoJson.writeTo(out: StringBuilder) {
    when (this) {
        is DuoJson.Null -> out.append("null")
        is DuoJson.Bool -> out.append(if (value) "true" else "false")
        is DuoJson.Num -> out.append(formatNumber(value))
        is DuoJson.Str -> writeJsonString(value, out)
        is DuoJson.Arr -> {
            out.append('[')
            values.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                item.writeTo(out)
            }
            out.append(']')
        }
        is DuoJson.Obj -> {
            out.append('{')
            var first = true
            values.forEach { (key, item) ->
                if (!first) out.append(',')
                first = false
                writeJsonString(key, out)
                out.append(':')
                item.writeTo(out)
            }
            out.append('}')
        }
    }
}

/**
 * Whole numbers are written without a fractional part so integer fields round-trip as integers,
 * which keeps slot, span and page values readable and byte-stable across saves.
 */
private fun formatNumber(value: Double): String {
    require(value.isFinite()) { "JSON cannot represent a non-finite number" }
    if (value % 1.0 == 0.0 && value >= -9.007199254740992E15 && value <= 9.007199254740992E15) {
        return value.toLong().toString()
    }
    return value.toString()
}

private fun writeJsonString(value: String, out: StringBuilder) {
    out.append('"')
    value.forEach { character ->
        when (character) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '' -> out.append("\\f")
            else ->
                // Control characters are not legal raw in a JSON string, and U+2028/U+2029 break
                // some consumers, so both are escaped.
                if (character < ' ' || character == ' ' || character == ' ') {
                    out.append("\\u").append("%04x".format(character.code))
                } else {
                    out.append(character)
                }
        }
    }
    out.append('"')
}

/** A single-pass recursive-descent reader. Strict by construction: anything unexpected throws. */
private class DuoJsonReader(private val text: String) {
    private var index = 0

    fun parseDocument(): DuoJson {
        skipWhitespace()
        val value = parseValue(depth = 0)
        skipWhitespace()
        require(index >= text.length) { "Unexpected trailing JSON content at $index" }
        return value
    }

    private fun parseValue(depth: Int): DuoJson {
        require(depth <= 64) { "JSON is nested too deeply" }
        require(index < text.length) { "Unexpected end of JSON" }
        return when (val character = text[index]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> DuoJson.Str(parseString())
            't' -> literal("true", DuoJson.Bool(true))
            'f' -> literal("false", DuoJson.Bool(false))
            'n' -> literal("null", DuoJson.Null)
            else -> {
                require(character == '-' || character in '0'..'9') { "Unexpected character '$character' at $index" }
                parseNumber()
            }
        }
    }

    private fun <T : DuoJson> literal(word: String, value: T): T {
        require(text.startsWith(word, index)) { "Invalid JSON literal at $index" }
        index += word.length
        return value
    }

    private fun parseObject(depth: Int): DuoJson.Obj {
        index++ // '{'
        val values = LinkedHashMap<String, DuoJson>()
        skipWhitespace()
        if (peek() == '}') { index++; return DuoJson.Obj(values) }
        while (true) {
            skipWhitespace()
            require(peek() == '"') { "Expected an object key at $index" }
            val key = parseString()
            skipWhitespace()
            require(peek() == ':') { "Expected ':' at $index" }
            index++
            skipWhitespace()
            // A duplicate key keeps the last value, matching org.json's behaviour on re-put.
            values[key] = parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> { index++; return DuoJson.Obj(values) }
                else -> throw IllegalArgumentException("Expected ',' or '}' at $index")
            }
        }
    }

    private fun parseArray(depth: Int): DuoJson.Arr {
        index++ // '['
        val values = mutableListOf<DuoJson>()
        skipWhitespace()
        if (peek() == ']') { index++; return DuoJson.Arr(values) }
        while (true) {
            skipWhitespace()
            values += parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> { index++; return DuoJson.Arr(values) }
                else -> throw IllegalArgumentException("Expected ',' or ']' at $index")
            }
        }
    }

    private fun parseString(): String {
        index++ // opening quote
        val out = StringBuilder()
        while (true) {
            require(index < text.length) { "Unterminated JSON string" }
            when (val character = text[index]) {
                '"' -> { index++; return out.toString() }
                '\\' -> {
                    index++
                    require(index < text.length) { "Unterminated JSON escape" }
                    when (val escape = text[index]) {
                        '"' -> { out.append('"'); index++ }
                        '\\' -> { out.append('\\'); index++ }
                        '/' -> { out.append('/'); index++ }
                        'b' -> { out.append('\b'); index++ }
                        'f' -> { out.append(''); index++ }
                        'n' -> { out.append('\n'); index++ }
                        'r' -> { out.append('\r'); index++ }
                        't' -> { out.append('\t'); index++ }
                        'u' -> {
                            require(index + 4 < text.length) { "Truncated \\u escape" }
                            val hex = text.substring(index + 1, index + 5)
                            require(hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                                "Invalid \\u escape"
                            }
                            out.append(hex.toInt(16).toChar())
                            index += 5
                        }
                        else -> throw IllegalArgumentException("Invalid escape '\\$escape'")
                    }
                }
                else -> {
                    require(character >= ' ') { "Unescaped control character in a JSON string" }
                    out.append(character)
                    index++
                }
            }
        }
    }

    private fun parseNumber(): DuoJson.Num {
        val start = index
        if (peek() == '-') index++
        require(index < text.length && text[index] in '0'..'9') { "Invalid number at $start" }
        // JSON's integer grammar is `0` or `[1-9][0-9]*`, so a leading zero is never followed by
        // another digit. Accepting `01` would not merely be lax: a payload that reached here with a
        // number the writer could not have produced is corrupt, and reading it as 1 would quietly
        // turn a damaged slot, span or widget id into a plausible-looking wrong one. Rejecting it
        // sends the caller to a backup instead, which is the whole point of a strict codec.
        if (text[index] == '0') {
            index++
            require(index >= text.length || text[index] !in '0'..'9') { "Leading zero in a number at $start" }
        } else {
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] == '.') {
            index++
            require(index < text.length && text[index] in '0'..'9') { "Invalid fraction at $start" }
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            require(index < text.length && text[index] in '0'..'9') { "Invalid exponent at $start" }
            while (index < text.length && text[index] in '0'..'9') index++
        }
        val value = text.substring(start, index).toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid number at $start")
        require(value.isFinite()) { "Non-finite number at $start" }
        return DuoJson.Num(value)
    }

    private fun peek(): Char = if (index < text.length) text[index] else ' '

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\t', '\n', '\r' -> index++
                else -> return
            }
        }
    }
}
