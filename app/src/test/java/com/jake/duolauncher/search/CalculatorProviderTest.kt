package com.jake.duolauncher.search

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val US: Locale = Locale.US

private fun calculate(input: String): String? = CalculatorProvider(US).result(input)?.formatted

class CalculatorProviderTest {

    @Test fun `the spec's examples`() {
        assertEquals("42", calculate("2*21"))
        assertEquals("3.5", calculate("(3+4)/2"))
        assertEquals("12", calculate("15% of 80"))
    }

    @Test fun `precedence and brackets`() {
        assertEquals("7", calculate("1 + 2*3"))
        assertEquals("9", calculate("(1+2)*3"))
        assertEquals("1", calculate("10 - 3*3"))
        assertEquals("14", calculate("2*(3+4)"))
        assertEquals("-5", calculate("5 - 10"))
    }

    @Test fun `unary signs`() {
        assertEquals("2", calculate("-3+5"))
        assertEquals("-8", calculate("-(3+5)"))
        assertEquals("8", calculate("-(3+5)*-1"))
    }

    @Test fun `powers are right associative`() {
        assertEquals("1,024", calculate("2^10"))
        assertEquals("512", calculate("2^3^2"))
    }

    @Test fun `percent divides by a hundred`() {
        assertEquals("0.15", calculate("15%*1"))
        assertEquals("12", calculate("80*15%"))
        assertEquals("12", calculate("15 % of 80"))
        assertEquals("1.2", calculate("15% of 8"))
    }

    @Test fun `typographic operators and separators`() {
        assertEquals("42", calculate("2×21"))
        assertEquals("21", calculate("42÷2"))
        assertEquals("2", calculate("5 − 3"))
        assertEquals("42", calculate("2 x 21"))
        assertEquals("2,469,134", calculate("1,234,567*2"))
    }

    @Test fun `answers are rounded, not dumped`() {
        assertEquals("0.3333333333", calculate("1/3"))
        assertEquals("0.1", calculate("0.1*1"))
        assertEquals("2.5", calculate("10/4"))
    }

    @Test fun `input that is not arithmetic produces no row`() {
        assertNull(calculate(""))
        assertNull(calculate("   "))
        assertNull(calculate("chrome"))
        assertNull(calculate("42"))
        assertNull(calculate("-5"))
        assertNull(calculate("zzqx"))
        assertNull(calculate("weather tomorrow"))
    }

    @Test fun `malformed arithmetic produces no row`() {
        assertNull(calculate("2+"))
        assertNull(calculate("*2"))
        assertNull(calculate("(2+3"))
        assertNull(calculate("2+3)"))
        assertNull(calculate("2//3"))
        assertNull(calculate("2 3 +"))
        assertNull(calculate("1.2.3+1"))
        assertNull(calculate("()+1"))
    }

    @Test fun `division by zero produces no row`() {
        assertNull(calculate("1/0"))
        assertNull(calculate("1/(3-3)"))
    }

    @Test fun `nothing outside the arithmetic alphabet is ever evaluated`() {
        assertNull(calculate("2 + a"))
        assertNull(calculate("System.exit(1)"))
        assertNull(calculate("1+1; drop table apps"))
        assertNull(calculate("\${2+2}"))
        assertNull(calculate("[1,2]+1"))
    }

    @Test fun `input is length capped`() {
        val long = List(MAX_EXPRESSION_LENGTH) { "1" }.joinToString("+")
        assertNull(calculate(long))
        assertEquals("3", calculate("1+1+1"))
    }

    @Test fun `overflow and other non-finite answers produce no row`() {
        assertNull(calculate("9^9^9"))
        assertNull(evaluateExpression("9^9^9"))
    }

    @Test fun `the result carries the expression and the number`() {
        val result = CalculatorProvider(US).result("  2*21 ")!!
        assertEquals("2*21", result.expression)
        assertEquals("42", result.formatted)
        assertEquals(42.0, result.value, 0.0)
        assertEquals(SearchSection.CALCULATION, result.section)
        assertEquals("42", result.title)
    }

    @Test fun `formatting follows the locale`() {
        assertEquals("1,024", formatCalculation(1024.0, Locale.US))
        assertEquals("1.024", formatCalculation(1024.0, Locale.GERMANY))
    }
}
