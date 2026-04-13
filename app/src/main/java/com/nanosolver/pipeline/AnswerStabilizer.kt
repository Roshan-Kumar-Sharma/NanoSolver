package com.nanosolver.pipeline

/**
 * AnswerStabilizer — Plan 2 Phase 2
 *
 * Requires the same answer on [requiredConsecutive] consecutive frames before
 * confirming it for injection. This eliminates single-frame OCR glitches —
 * where a stray digit from the score or timer briefly matches a number —
 * without adding perceptible latency.
 *
 * WHY CONSECUTIVE FRAMES?
 *   A correct answer is stable: the math expression and the answer don't change
 *   frame-to-frame unless a new question has appeared. An OCR glitch is typically
 *   a one or two-frame artifact (blur, partial text in motion, mis-segmented glyph).
 *   Requiring 3 identical consecutive frames (~120–300ms at typical OCR rate)
 *   rejects virtually all glitches while still being imperceptibly fast for the user.
 *
 * THREAD SAFETY:
 *   Not thread-safe by design — SolverPipeline calls feed() only from the single
 *   coroutine that passes the [pipelineBusy] gate, so no concurrent access is possible.
 *
 * @param requiredConsecutive  Number of identical consecutive answers required before
 *                             [feed] returns non-null. Default 3.
 */
class AnswerStabilizer(private val requiredConsecutive: Int = 3) {

    private var candidate: Long? = null
    private var count: Int = 0

    /**
     * Feeds a new candidate answer into the stabilizer.
     *
     * @return the confirmed answer once [requiredConsecutive] identical answers
     *         have been seen in a row; null otherwise.
     */
    fun feed(answer: Long): Long? {
        if (answer == candidate) {
            count++
        } else {
            candidate = answer
            count = 1
        }
        return if (count >= requiredConsecutive) answer else null
    }

    /**
     * Resets the stabilizer state.
     *
     * Call this whenever the pipeline resets (new question, user-triggered reset).
     */
    fun reset() {
        candidate = null
        count = 0
    }
}
