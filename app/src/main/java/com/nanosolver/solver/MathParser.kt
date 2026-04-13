package com.nanosolver.solver

import android.util.Log

/**
 * MathParser — Phase 4
 *
 * Converts a raw OCR string (e.g. "35 + 27 = ?") into a numeric answer (62).
 *
 * The implementation has three layers:
 *
 *   1. CLEANER   — strips OCR noise, normalises operator symbols.
 *   2. LEXER     — turns the cleaned string into a list of typed Tokens.
 *   3. PARSER    — recursive descent grammar; produces the numeric result.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * WHY A RECURSIVE DESCENT PARSER INSTEAD OF eval() OR A REGEX SPLIT?
 *
 *   eval():       Doesn't exist in Kotlin/JVM without a scripting engine, which
 *                 adds ~50MB and 300ms startup to every solve call. Not viable.
 *
 *   Regex split:  split("\\+") gives ["35 ", " 27"] — works for single-operator
 *                 expressions but breaks completely on mixed operators, negative
 *                 numbers, and parentheses. Operator precedence is impossible.
 *
 *   Recursive descent: ~80 lines of pure Kotlin. Handles any valid expression,
 *                 operator precedence, parentheses, and unary minus correctly.
 *                 Runs in < 0.1ms. You also learn a technique used in every
 *                 real compiler and interpreter ever written.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * THE GRAMMAR (expressed as BNF):
 *
 *   expression  ::= term ( ('+' | '-') term )*
 *   term        ::= factor ( ('×' | '÷') factor )*
 *   factor      ::= '-' factor            ← unary minus
 *                 | NUMBER
 *                 | '(' expression ')'
 *
 * Lower rules bind more tightly (higher precedence). factor is evaluated first,
 * so × and ÷ always execute before + and −, matching standard PEMDAS/BODMAS.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * EXAMPLE TRACE for "2 + 3 × 4":
 *
 *   Tokens: [2, +, 3, ×, 4]
 *
 *   parseExpression()
 *     parseTerm()         → parseFactor() → 2       result = 2
 *     peek() == '+'       → consume
 *     parseTerm()
 *       parseFactor() → 3    result = 3
 *       peek() == '×'  → consume
 *       parseFactor() → 4
 *       result = 3 × 4 = 12
 *     result = 2 + 12 = 14   ← correct: × binds tighter than +
 */
class MathParser {

    companion object {
        private const val TAG = "MathParser"
    }

    // -------------------------------------------------------------------------
    // Token types
    // -------------------------------------------------------------------------

    /**
     * A sealed class lets the Kotlin compiler verify that every `when` expression
     * is exhaustive — no token type can be accidentally forgotten.
     */
    private sealed class Token {
        data class Number(val value: Long) : Token()
        object Plus     : Token() { override fun toString() = "+" }
        object Minus    : Token() { override fun toString() = "-" }
        object Multiply : Token() { override fun toString() = "×" }
        object Divide   : Token() { override fun toString() = "÷" }
        object LParen   : Token() { override fun toString() = "(" }
        object RParen   : Token() { override fun toString() = ")" }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Solves [raw] OCR text and returns the integer answer, or null if the
     * text doesn't contain a parseable math expression.
     *
     * Cache is checked before parsing. If the same expression appears again,
     * the answer is returned in ~0.01ms with no re-parsing.
     */
    fun solve(raw: String): Long? {
        val expr = clean(raw) ?: return null

        // Cache hit — free answer
        SolverCache.get(expr)?.let { cached ->
            Log.d(TAG, "'$expr' = $cached  [cached]")
            return cached
        }

        return try {
            val tokens = tokenize(expr)
            val result = ParseContext(tokens).parseExpression()
            SolverCache.put(expr, result)
            Log.d(TAG, "'$expr' = $result")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Parse failed for '$expr': ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Stage 1: Cleaner
    // -------------------------------------------------------------------------

    /**
     * Strips OCR noise from [raw] and normalises operator symbols.
     *
     * Handles these real-world OCR outputs from Matiks:
     *   "35 + 27 = ?"     → "35 + 27"       drop answer placeholder
     *   "35 + 27\n= ?"    → "35 + 27"       drop multi-line artefacts
     *   "6 x 7"           → "6 × 7"         OCR reads × as x
     *   "6 X 7"           → "6 × 7"         capital X variant
     *   "Q: 35 + 27 = ?"  → "35 + 27"       drop non-math prefix text
     *
     * @return cleaned expression string, or null if no parseable content found.
     */
    internal fun clean(raw: String): String? {
        if (raw.isBlank()) return null

        var s = raw
            .replace('\n', ' ')
            .replace('\r', ' ')

        // Drop "= ?" / "= __" / "=?" and everything after the "="
        val eqIdx = s.indexOf('=')
        if (eqIdx >= 0) s = s.substring(0, eqIdx)

        // Normalise OCR multiplication variants.
        // Regex: a run of digits, optional spaces, literal x or X, optional spaces, a run of digits.
        // Capture groups preserve the surrounding numbers: "62 x 7" → "62 × 7".
        s = s.replace(Regex("(\\d+)\\s*[xX]\\s*(\\d+)"), "$1 × $2")

        // Strip anything that isn't a digit, operator, parenthesis, or whitespace.
        // This removes stray letters, punctuation, and other OCR artefacts.
        s = s.replace(Regex("[^0-9+\\-×÷*/().\\s]"), "").trim()

        return s.takeIf { it.isNotBlank() && it.any { c -> c.isDigit() } }
    }

    // -------------------------------------------------------------------------
    // Stage 2: Lexer (tokenizer)
    // -------------------------------------------------------------------------

    /**
     * Converts a cleaned expression string into a list of [Token]s.
     *
     * TOKENIZATION: the lexer scans left-to-right, character by character.
     * When it sees the start of a multi-character token (a digit), it keeps
     * advancing until the token ends, then emits one NUMBER token.
     * Single-character tokens (operators, parens) emit immediately.
     *
     * Unknown characters are silently skipped — the cleaner should have
     * removed them, but the lexer is defensive in case any slipped through.
     */
    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                ch.isWhitespace() -> i++
                ch.isDigit()      -> {
                    // Consume all consecutive digits into one NUMBER token
                    val start = i
                    while (i < input.length && input[i].isDigit()) i++
                    tokens += Token.Number(input.substring(start, i).toLong())
                }
                ch == '+'         -> { tokens += Token.Plus;     i++ }
                ch == '-'         -> { tokens += Token.Minus;    i++ }
                ch == '*' || ch == '×' -> { tokens += Token.Multiply; i++ }
                ch == '/' || ch == '÷' -> { tokens += Token.Divide;   i++ }
                ch == '('         -> { tokens += Token.LParen;  i++ }
                ch == ')'         -> { tokens += Token.RParen;  i++ }
                else              -> i++ // skip unknown character
            }
        }
        return tokens
    }

    // -------------------------------------------------------------------------
    // Stage 3: Recursive descent parser
    // -------------------------------------------------------------------------

    /**
     * Holds the token list and current position for a single parse invocation.
     *
     * A separate class (rather than instance fields on MathParser) makes each
     * call to solve() fully independent and therefore thread-safe.
     */
    private class ParseContext(private val tokens: List<Token>) {
        private var pos = 0

        /** Look at the current token without consuming it. */
        private fun peek(): Token? = tokens.getOrNull(pos)

        /** Consume and return the current token, advancing pos. */
        private fun consume(): Token = tokens[pos++]

        /**
         * expression ::= term ( ('+' | '-') term )*
         *
         * Lowest-precedence rule — evaluated last, so + and − bind loosely.
         * The while loop handles chained additions/subtractions left-to-right:
         *   1 + 2 + 3  →  (1 + 2) + 3  →  6
         */
        fun parseExpression(): Long {
            var result = parseTerm()
            while (true) {
                result = when (peek()) {
                    Token.Plus  -> { consume(); result + parseTerm() }
                    Token.Minus -> { consume(); result - parseTerm() }
                    else        -> return result
                }
            }
        }

        /**
         * term ::= factor ( ('×' | '÷') factor )*
         *
         * Middle-precedence rule — evaluated before + and −, so × and ÷ bind tightly.
         * Division truncates toward zero (integer semantics). Division by zero throws
         * ArithmeticException, which solve() catches and converts to null.
         */
        private fun parseTerm(): Long {
            var result = parseFactor()
            while (true) {
                result = when (peek()) {
                    Token.Multiply -> { consume(); result * parseFactor() }
                    Token.Divide   -> {
                        consume()
                        val divisor = parseFactor()
                        if (divisor == 0L) throw ArithmeticException("Division by zero")
                        result / divisor
                    }
                    else -> return result
                }
            }
        }

        /**
         * factor ::= '-' factor | NUMBER | '(' expression ')'
         *
         * Highest-precedence rule — evaluated first.
         * The unary minus case is recursive: - - 5 → -(-5) → 5.
         */
        private fun parseFactor(): Long {
            return when (val t = peek()) {
                Token.Minus  -> { consume(); -parseFactor() }           // unary minus
                is Token.Number -> { consume(); t.value }
                Token.LParen -> {
                    consume()                                           // consume '('
                    val result = parseExpression()
                    if (peek() == Token.RParen) consume()               // consume ')'
                    result
                }
                else -> throw IllegalStateException(
                    "Unexpected token '$t' at position $pos. Tokens: $tokens"
                )
            }
        }
    }
}
