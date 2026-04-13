package com.nanosolver.solver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * MathParserTest — Phase 4
 *
 * Pure JVM tests (no Android device/emulator needed).
 * Run via: Android Studio → right-click MathParserTest → Run, or
 *          Gradle: ./gradlew :app:test
 *
 * Test categories:
 *   1. Basic arithmetic (each operator)
 *   2. Unicode operators (×, ÷)
 *   3. OCR 'x'/'X' variant for multiplication
 *   4. Operator precedence (PEMDAS/BODMAS)
 *   5. Parentheses
 *   6. Chained operators
 *   7. OCR noise stripping
 *   8. Edge cases (zero, large numbers, negatives)
 *   9. Cache behaviour
 *  10. Cleaner internals
 */
class MathParserTest {

    private lateinit var parser: MathParser

    @Before
    fun setUp() {
        parser = MathParser()
        SolverCache.clear()  // ensure a clean slate between tests
    }

    // =========================================================================
    // 1. Addition
    // =========================================================================

    @Test fun `1 + 1 = 2`()           = assertSolve(2,    "1 + 1")
    @Test fun `35 + 27 = 62`()        = assertSolve(62,   "35 + 27")
    @Test fun `100 + 200 = 300`()     = assertSolve(300,  "100 + 200")
    @Test fun `0 + 0 = 0`()           = assertSolve(0,    "0 + 0")
    @Test fun `999 + 1 = 1000`()      = assertSolve(1000, "999 + 1")
    @Test fun `no spaces in add`()    = assertSolve(62,   "35+27")
    @Test fun `spaces around add`()   = assertSolve(62,   "  35 + 27  ")

    // =========================================================================
    // 2. Subtraction
    // =========================================================================

    @Test fun `10 - 3 = 7`()          = assertSolve(7,   "10 - 3")
    @Test fun `100 - 1 = 99`()        = assertSolve(99,  "100 - 1")
    @Test fun `5 - 10 = -5`()         = assertSolve(-5,  "5 - 10")
    @Test fun `0 - 0 = 0 sub`()       = assertSolve(0,   "0 - 0")
    @Test fun `50 - 25 = 25`()        = assertSolve(25,  "50 - 25")
    @Test fun `1000 - 999 = 1`()      = assertSolve(1,   "1000 - 999")
    @Test fun `no spaces in sub`()    = assertSolve(7,   "10-3")

    // =========================================================================
    // 3. Multiplication (ASCII *)
    // =========================================================================

    @Test fun `6 * 7 = 42`()          = assertSolve(42,  "6 * 7")
    @Test fun `12 * 12 = 144`()       = assertSolve(144, "12 * 12")
    @Test fun `0 * 99 = 0`()          = assertSolve(0,   "0 * 99")
    @Test fun `1 * 1 = 1`()           = assertSolve(1,   "1 * 1")
    @Test fun `25 * 4 = 100`()        = assertSolve(100, "25 * 4")

    // =========================================================================
    // 4. Unicode × operator (what Matiks actually shows)
    // =========================================================================

    @Test fun `6 × 7 = 42`()          = assertSolve(42,  "6 × 7")
    @Test fun `12 × 12 = 144`()       = assertSolve(144, "12 × 12")
    @Test fun `9 × 9 = 81`()          = assertSolve(81,  "9 × 9")
    @Test fun `25 × 4 = 100`()        = assertSolve(100, "25 × 4")

    // =========================================================================
    // 5. OCR 'x' and 'X' as multiplication
    // =========================================================================

    @Test fun `6 x 7 = 42`()          = assertSolve(42,  "6 x 7")
    @Test fun `6 X 7 = 42`()          = assertSolve(42,  "6 X 7")
    @Test fun `12 x 12 = 144`()       = assertSolve(144, "12 x 12")
    @Test fun `no spaces x`()         = assertSolve(42,  "6x7")

    // =========================================================================
    // 6. Division (ASCII /)
    // =========================================================================

    @Test fun `10 div 2 = 5`()        = assertSolve(5,  "10 / 2")
    @Test fun `100 div 4 = 25`()      = assertSolve(25, "100 / 4")
    @Test fun `0 div 5 = 0`()         = assertSolve(0,  "0 / 5")
    @Test fun `7 div 2 truncates`()   = assertSolve(3,  "7 / 2")   // integer division

    // =========================================================================
    // 7. Unicode ÷ operator
    // =========================================================================

    @Test fun `144 ÷ 12 = 12`()       = assertSolve(12, "144 ÷ 12")
    @Test fun `10 ÷ 2 = 5`()          = assertSolve(5,  "10 ÷ 2")
    @Test fun `100 ÷ 4 = 25`()        = assertSolve(25, "100 ÷ 4")

    // =========================================================================
    // 8. Operator precedence (PEMDAS/BODMAS)
    //    × and ÷ must bind tighter than + and −
    // =========================================================================

    @Test fun `2 + 3 × 4 = 14 not 20`()  = assertSolve(14, "2 + 3 × 4")
    @Test fun `10 - 2 × 3 = 4`()         = assertSolve(4,  "10 - 2 × 3")
    @Test fun `12 ÷ 3 + 1 = 5`()         = assertSolve(5,  "12 ÷ 3 + 1")
    @Test fun `2 × 3 + 4 × 5 = 26`()     = assertSolve(26, "2 × 3 + 4 × 5")
    @Test fun `10 - 4 ÷ 2 = 8`()         = assertSolve(8,  "10 - 4 ÷ 2")
    @Test fun `1 + 2 + 3 × 4 - 5 = 10`() = assertSolve(10, "1 + 2 + 3 × 4 - 5")

    // =========================================================================
    // 9. Parentheses (override default precedence)
    // =========================================================================

    @Test fun `(2 + 3) × 4 = 20`()         = assertSolve(20, "(2 + 3) × 4")
    @Test fun `(10 - 2) × 3 = 24`()        = assertSolve(24, "(10 - 2) × 3")
    @Test fun `12 ÷ (2 + 1) = 4`()         = assertSolve(4,  "12 ÷ (2 + 1)")
    @Test fun `(2 + 3) × (4 + 5) = 45`()   = assertSolve(45, "(2 + 3) × (4 + 5)")
    @Test fun `nested parens`()             = assertSolve(20, "((2 + 3) × 4)")

    // =========================================================================
    // 10. Chained operators (left-to-right associativity)
    // =========================================================================

    @Test fun `1 + 2 + 3 = 6`()            = assertSolve(6,  "1 + 2 + 3")
    @Test fun `10 - 3 - 2 = 5`()           = assertSolve(5,  "10 - 3 - 2")
    @Test fun `3 × 4 × 5 = 60`()           = assertSolve(60, "3 × 4 × 5")
    @Test fun `100 ÷ 5 ÷ 4 = 5`()          = assertSolve(5,  "100 ÷ 5 ÷ 4")

    // =========================================================================
    // 11. OCR noise stripping
    // =========================================================================

    @Test fun `strip equals question mark`()   = assertSolve(62, "35 + 27 = ?")
    @Test fun `strip equals blank`()           = assertSolve(62, "35 + 27 = __")
    @Test fun `strip multiline equals`()       = assertSolve(62, "35 + 27\n= ?")
    @Test fun `strip prefix text`()            = assertSolve(62, "Q: 35 + 27 = ?")
    @Test fun `strip equals no space`()        = assertSolve(62, "35+27=?")
    @Test fun `x with equals noise`()          = assertSolve(42, "6 x 7 = ?")
    @Test fun `unicode mul with equals noise`() = assertSolve(42, "6 × 7 = ?")
    @Test fun `extra internal spaces`()        = assertSolve(62, "  35  +  27  ")

    // =========================================================================
    // 12. Large numbers
    // =========================================================================

    @Test fun `large addition`()    = assertSolve(1998,    "999 + 999")
    @Test fun `large multiply`()    = assertSolve(1000000, "1000 × 1000")
    @Test fun `large subtract`()    = assertSolve(8888,    "9999 - 1111")

    // =========================================================================
    // 13. Edge cases that return null
    // =========================================================================

    @Test fun `empty string is null`()   = assertNull(parser.solve(""))
    @Test fun `blank string is null`()   = assertNull(parser.solve("   "))
    @Test fun `no digits is null`()      = assertNull(parser.solve("abc"))
    @Test fun `just equals is null`()    = assertNull(parser.solve("= ?"))
    @Test fun `division by zero null`()  = assertNull(parser.solve("5 / 0"))

    // =========================================================================
    // 14. Cache behaviour
    // =========================================================================

    @Test fun `cache hit returns same result`() {
        val first  = parser.solve("35 + 27")
        val second = parser.solve("35 + 27")  // must come from cache
        assertEquals(first, second)
        assertEquals(1, SolverCache.size)
    }

    @Test fun `different expressions cached independently`() {
        val a = parser.solve("10 + 5")
        val b = parser.solve("20 - 3")
        assertEquals(15L, a)
        assertEquals(17L, b)
        assertEquals(2, SolverCache.size)
    }

    @Test fun `cache survives noise variants of same expression`() {
        val clean  = parser.solve("35 + 27")
        val noisy  = parser.solve("35 + 27 = ?")   // same cleaned expression → same cache key
        assertEquals(clean, noisy)
        assertEquals(1, SolverCache.size)           // stored once, not twice
    }

    // =========================================================================
    // 15. Cleaner internals (internal visibility allows direct testing)
    // =========================================================================

    @Test fun `clean strips equals suffix`() {
        assertEquals("35 + 27", parser.clean("35 + 27 = ?"))
    }

    @Test fun `clean normalises x to multiply`() {
        assertEquals("6 × 7", parser.clean("6 x 7"))
    }

    @Test fun `clean trims whitespace`() {
        assertEquals("35 + 27", parser.clean("  35 + 27  "))
    }

    @Test fun `clean returns null for no digits`() {
        assertNull(parser.clean("abc"))
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private fun assertSolve(expected: Long, input: String) =
        assertEquals("solve(\"$input\")", expected, parser.solve(input))
}
