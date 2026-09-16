package com.jake.duolauncher.search

import java.math.BigDecimal
import java.math.MathContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.pow

/**
 * The Calculation section of FR-72: "2*21" answers 42, "(3+4)/2" answers 3.5, "15% of 80" answers 12.
 *
 * This is a hand-written recursive-descent parser over a fixed grammar of numbers, brackets and the
 * five arithmetic operators. There is no scripting engine, no `eval`, no identifiers and no function
 * calls — the parser cannot name anything, so there is nothing for a crafted query to reach. Input
 * is length-capped, which also caps recursion depth, and anything outside the allowed character set
 * is rejected before parsing rather than sanitized.
 *
 * Grammar (precedence low to high):
 * ```
 * expression := term (('+' | '-') term)*
 * term       := power (('*' | '/') power)*
 * power      := unary ('^' power)?          // right-associative
 * unary      := ('+' | '-')* postfix
 * postfix    := primary '%'*                // '%' divides by 100
 * primary    := number | '(' expression ')'
 * ```
 */
class CalculatorProvider(private val locale: Locale = Locale.getDefault()) {

    /** The row for this input, or `null` when the input is not arithmetic or cannot be evaluated. */
    fun result(input: String): CalculationResult? {
        val expression = input.trim()
        val value = evaluateExpression(expression) ?: return null
        return CalculationResult(expression, formatCalculation(value, locale), value)
    }
}

/** How many significant digits an answer keeps. Beyond this, double arithmetic is noise anyway. */
const val CALCULATION_PRECISION = 10

/** Longer input is not a calculation, and the cap bounds both parse time and recursion depth. */
const val MAX_EXPRESSION_LENGTH = 64

/**
 * Evaluates one arithmetic expression, or returns `null` for anything that is not one: text, an
 * empty string, a bare number, unbalanced brackets, a trailing operator, division by zero, or a
 * result that is infinite or not a number.
 */
fun evaluateExpression(input: String): Double? {
    val normalized = normalizeExpression(input) ?: return null
    val value = try {
        Parser(normalized).parse()
    } catch (_: CalculatorSyntaxException) {
        return null
    }
    return value.takeIf(Double::isFinite)
}

/** Formats an answer for display: grouped, trimmed, and never in scientific notation by surprise. */
fun formatCalculation(value: Double, locale: Locale = Locale.getDefault()): String {
    val rounded = BigDecimal(value).round(MathContext(CALCULATION_PRECISION)).stripTrailingZeros()
    val format = NumberFormat.getInstance(locale).apply {
        isGroupingUsed = true
        maximumFractionDigits = CALCULATION_PRECISION
    }
    return format.format(rounded)
}

/** Thrown and caught inside this file only; stackless because it is control flow, not a failure. */
internal class CalculatorSyntaxException : RuntimeException(null, null, false, false)

/** The one word form FR-72 asks for: "15% of 80". */
private val OF_KEYWORD = Regex("""\bof\b""", RegexOption.IGNORE_CASE)

private const val OPERATORS = "+-*/^%"

/**
 * Canonicalizes input and rejects everything that is not arithmetic.
 *
 * "of" becomes "*", the usual typographic variants (×, ÷, en dash) become their ASCII forms, "x"
 * between numbers means multiply, and group separators are dropped. What survives must contain a
 * digit, contain an operator somewhere other than the first character — so a bare "42" or "-5" is
 * *not* a calculation — and consist only of digits, `.`, spaces, brackets and operators.
 */
private fun normalizeExpression(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_EXPRESSION_LENGTH) return null
    if (trimmed.none(Char::isDigit)) return null

    val withoutWords = OF_KEYWORD.replace(trimmed, "*")
    val builder = StringBuilder(withoutWords.length)
    for (character in withoutWords) {
        when (character) {
            '×', 'x', 'X', '·' -> builder.append('*')
            '÷' -> builder.append('/')
            '−', '–', '—' -> builder.append('-')
            ',', ' ', ' ' -> Unit // group separators and thin spaces carry no meaning
            in '0'..'9', '.', ' ', '(', ')', '+', '-', '*', '/', '^', '%' -> builder.append(character)
            else -> return null
        }
    }

    val expression = builder.toString().trim()
    if (expression.isEmpty() || expression.none(Char::isDigit)) return null
    if (expression.drop(1).none { it in OPERATORS }) return null
    return expression
}

private class Parser(private val text: String) {
    private var position = 0

    fun parse(): Double {
        val value = parseExpression()
        skipSpaces()
        if (position != text.length) throw CalculatorSyntaxException()
        return value
    }

    private fun parseExpression(): Double {
        var value = parseTerm()
        while (true) {
            skipSpaces()
            when (peek()) {
                '+' -> { position++; value += parseTerm() }
                '-' -> { position++; value -= parseTerm() }
                else -> return value
            }
        }
    }

    private fun parseTerm(): Double {
        var value = parsePower()
        while (true) {
            skipSpaces()
            when (peek()) {
                '*' -> { position++; value *= parsePower() }
                '/' -> {
                    position++
                    val divisor = parsePower()
                    if (divisor == 0.0) throw CalculatorSyntaxException()
                    value /= divisor
                }
                else -> return value
            }
        }
    }

    private fun parsePower(): Double {
        val base = parseUnary()
        skipSpaces()
        if (peek() != '^') return base
        position++
        return base.pow(parsePower())
    }

    private fun parseUnary(): Double {
        skipSpaces()
        return when (peek()) {
            '-' -> { position++; -parseUnary() }
            '+' -> { position++; parseUnary() }
            else -> parsePostfix()
        }
    }

    /** A trailing `%` always means "per cent"; this launcher's users type "15% of 80", not modulo. */
    private fun parsePostfix(): Double {
        var value = parsePrimary()
        skipSpaces()
        while (peek() == '%') {
            value /= 100.0
            position++
            skipSpaces()
        }
        return value
    }

    private fun parsePrimary(): Double {
        skipSpaces()
        if (peek() == '(') {
            position++
            val value = parseExpression()
            skipSpaces()
            if (peek() != ')') throw CalculatorSyntaxException()
            position++
            return value
        }
        return parseNumber()
    }

    private fun parseNumber(): Double {
        val start = position
        var digits = 0
        var points = 0
        while (position < text.length) {
            val character = text[position]
            when {
                character.isDigit() -> { digits++; position++ }
                character == '.' && points == 0 -> { points++; position++ }
                else -> break
            }
        }
        if (digits == 0) throw CalculatorSyntaxException()
        return text.substring(start, position).toDoubleOrNull() ?: throw CalculatorSyntaxException()
    }

    private fun peek(): Char? = if (position < text.length) text[position] else null

    private fun skipSpaces() {
        while (position < text.length && text[position] == ' ') position++
    }
}
