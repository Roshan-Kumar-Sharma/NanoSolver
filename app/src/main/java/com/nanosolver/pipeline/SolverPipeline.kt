package com.nanosolver.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.nanosolver.ocr.ImagePreprocessor
import com.nanosolver.ocr.TextExtractor
import com.nanosolver.service.NanoAccessibilityService
import com.nanosolver.solver.MathParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LatencyStats — per-frame timing snapshot.
 *
 * Every field is the wall-clock milliseconds for that stage.
 * Capturing the start of each stage with System.nanoTime() and
 * dividing by 1_000_000 gives millisecond granularity without
 * floating-point overhead.
 */
data class LatencyStats(
    /** Raw frame in → preprocessed bitmap out */
    val preprocessMs: Long,
    /** Preprocessed bitmap in → OCR text out */
    val ocrMs: Long,
    /** OCR text in → answer out (includes cache lookup) */
    val solveMs: Long,
    /** Answer in → AccessibilityService inject complete */
    val injectMs: Long,
    /** End-to-end: frame in → inject complete */
    val totalMs: Long,
    /** True if the solve step was served from the LRU cache */
    val cacheHit: Boolean
)

/**
 * SolverPipeline — Phase 6
 *
 * Orchestrates all four pipeline stages:
 *
 *   Stage 1 — Preprocess : raw Bitmap → grayscale + Otsu threshold  (~5ms)
 *   Stage 2 — OCR        : preprocessed Bitmap → expression text    (~50–120ms)
 *   Stage 3 — Solve      : expression text → Long answer            (~0.1ms, or 0 on cache hit)
 *   Stage 4 — Inject     : answer → AccessibilityService IPC        (~1–5ms)
 *
 * ────────────────────────────────────────────────────────────────────
 * PIPELINING
 *
 * The key insight: injection is the LAST step, and it is fast (~1–5ms).
 * If we hold the pipeline gate until injection completes, the next frame
 * is blocked for 1–5ms of pure waiting after the slow OCR stage finishes.
 *
 * Instead we release the gate BEFORE injection:
 *
 *   Frame N:  [preprocess] → [OCR] → [solve] → gate released → [inject]
 *   Frame N+1:                               [preprocess] → [OCR] → …
 *                                             ↑ starts here, overlapping inject
 *
 * This is the same principle as CPU instruction pipelining: no stage idles
 * waiting for a downstream stage to finish.
 *
 * ────────────────────────────────────────────────────────────────────
 * TOGGLE
 *
 * [enabled] can be flipped at any time from the overlay button.
 * When false, incoming frames are immediately recycled — no CPU, memory,
 * or GPU work is wasted. The ScreenCaptureManager keeps running so that
 * re-enabling resumes instantly without re-consent.
 *
 * ────────────────────────────────────────────────────────────────────
 * GATE DESIGN (pipelineBusy)
 *
 * AtomicBoolean with compareAndSet(false, true):
 *   - Atomic "check + flip" that can't be interrupted between threads.
 *   - Prevents more than one OCR job in-flight at a time.
 *   - Frames that arrive while a job is running are dropped (latest-frame policy).
 *   - Without this gate, at 30fps we'd launch ~3–4 parallel OCR jobs per second,
 *     each holding a ~2MB Bitmap, causing GC pressure and memory spikes.
 *
 * @param scope       CoroutineScope tied to the service lifetime (SupervisorJob).
 * @param preprocessor Grayscale + Otsu threshold transformer.
 * @param extractor   ML Kit OCR wrapper (suspend function).
 * @param parser      Recursive descent math parser (includes LRU cache).
 * @param onAnswer    Called after inject with the answer and latency breakdown.
 *                    Runs on the coroutine thread — caller must post to main if needed.
 */
class SolverPipeline(
    private val scope: CoroutineScope,
    private val preprocessor: ImagePreprocessor,
    private val extractor: TextExtractor,
    private val parser: MathParser,
    private val onAnswer: (answer: Long, stats: LatencyStats) -> Unit
) {

    companion object {
        private const val TAG = "SolverPipeline"

        /** Log a rolling latency summary every this many successfully solved frames. */
        private const val STATS_LOG_INTERVAL = 10
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /**
     * When false, processFrame() recycles the bitmap and returns immediately.
     * Flip this to pause/resume without stopping the capture pipeline.
     */
    @Volatile var enabled: Boolean = true

    /**
     * Gate: at most one OCR job runs at a time.
     *
     * Released BEFORE the inject step so the next frame's preprocess+OCR
     * can run concurrently with the current frame's inject call.
     */
    private val pipelineBusy = AtomicBoolean(false)

    // Rolling stats — updated from the coroutine thread only (single writer after gate).
    private var solvedFrames = 0
    private var totalMs      = 0L
    private var minMs        = Long.MAX_VALUE
    private var maxMs        = 0L
    private var cacheHits    = 0

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Submits a new screen frame into the pipeline.
     *
     * Called from CaptureThread (off the main thread). Non-blocking: either
     * launches a coroutine and returns, or recycles the bitmap and returns.
     *
     * The bitmap is owned by this call once submitted — do NOT use it after.
     */
    fun processFrame(bitmap: Bitmap) {
        // Paused: drop and recycle immediately.
        if (!enabled) {
            bitmap.recycle()
            return
        }

        // Gate: if OCR is already running, drop this frame (always-latest policy).
        if (!pipelineBusy.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }

        scope.launch {
            runPipeline(bitmap)
        }
    }

    // -------------------------------------------------------------------------
    // Pipeline execution
    // -------------------------------------------------------------------------

    private suspend fun runPipeline(bitmap: Bitmap) {
        val t0 = System.nanoTime()

        try {
            // ── Stage 1: Preprocess ────────────────────────────────────────
            // CPU-bound (~5ms). Runs on Dispatchers.Default (inherited from scope).
            val preprocessed = preprocessor.preprocess(bitmap)
            bitmap.recycle()   // original no longer needed
            val t1 = System.nanoTime()

            // ── Stage 2: OCR ───────────────────────────────────────────────
            // TFLite inference via ML Kit (~50–120ms). The suspend fun yields the
            // thread while the model runs — other coroutines can execute during this.
            val rawText = extractor.extract(preprocessed)
            preprocessed.recycle()
            val t2 = System.nanoTime()

            if (rawText.isBlank()) {
                // No legible text — drop frame quietly. Gate released in finally.
                return
            }

            // ── Stage 3: Solve ─────────────────────────────────────────────
            // Recursive descent parse + LRU cache lookup. ~0.1ms parse, ~0.01ms cache hit.
            // MathParser.solve() already checks SolverCache internally, so we detect a
            // cache hit by comparing solve time: if it's < 1ms AND the result is non-null,
            // it was almost certainly a cache hit (parser alone takes ~0.1ms too, so we
            // use the nanoTime delta to discriminate).
            val t2b      = System.nanoTime()  // narrow window around solve only
            val answer   = parser.solve(rawText)
            val t3       = System.nanoTime()
            val solveNs  = t3 - t2b
            val cacheHit = solveNs < 500_000L  // < 0.5ms ⟹ cache hit (parse takes ~100µs)

            // ── PIPELINING: release gate before inject ─────────────────────
            // The next frame can now start Preprocess + OCR while we call into
            // AccessibilityService below. Inject is ~1–5ms Binder IPC — short but
            // non-zero. Releasing here eliminates that dead-wait for the next frame.
            pipelineBusy.set(false)

            answer ?: return  // expression parsed but had no valid answer (null from parser)

            // ── Stage 4: Inject ────────────────────────────────────────────
            // AccessibilityService IPC: find input node → ACTION_SET_TEXT → click submit.
            NanoAccessibilityService.injectAnswer(answer)
            val t4 = System.nanoTime()

            // ── Latency stats ──────────────────────────────────────────────
            val stats = LatencyStats(
                preprocessMs = (t1  - t0)  / 1_000_000L,
                ocrMs        = (t2  - t1)  / 1_000_000L,
                solveMs      = (t3  - t2b) / 1_000_000L,
                injectMs     = (t4  - t3)  / 1_000_000L,
                totalMs      = (t4  - t0)  / 1_000_000L,
                cacheHit     = cacheHit
            )

            recordStats(stats)
            onAnswer(answer, stats)

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error on frame #${solvedFrames + 1}: ${e.message}")
            // Gate may not have been released yet if exception is before Stage 4.
            // The finally block below ensures it's always cleared.
        } finally {
            // Safety net: if we returned early or threw before the pipelining release
            // point above, the gate is still held. Set false unconditionally — calling
            // set(false) when already false is a no-op, so this is safe to double-call.
            pipelineBusy.set(false)
        }
    }

    // -------------------------------------------------------------------------
    // Stats logging
    // -------------------------------------------------------------------------

    private fun recordStats(stats: LatencyStats) {
        solvedFrames++
        totalMs += stats.totalMs
        if (stats.totalMs < minMs) minMs = stats.totalMs
        if (stats.totalMs > maxMs) maxMs = stats.totalMs
        if (stats.cacheHit) cacheHits++

        // Per-frame detail log (Debug level — filtered out in release builds).
        Log.d(TAG,
            "Frame #$solvedFrames | " +
            "pre=${stats.preprocessMs}ms " +
            "ocr=${stats.ocrMs}ms " +
            "solve=${stats.solveMs}ms${if (stats.cacheHit) "(cache)" else ""} " +
            "inject=${stats.injectMs}ms | " +
            "total=${stats.totalMs}ms"
        )

        // Rolling summary at every STATS_LOG_INTERVAL successfully solved frames.
        if (solvedFrames % STATS_LOG_INTERVAL == 0) {
            val avg       = totalMs / solvedFrames
            val hitRate   = (cacheHits * 100) / solvedFrames
            Log.i(TAG,
                "══ Latency summary [$solvedFrames frames solved] ══ " +
                "avg=${avg}ms  min=${minMs}ms  max=${maxMs}ms  " +
                "cache_hit_rate=${hitRate}%"
            )
        }
    }
}
