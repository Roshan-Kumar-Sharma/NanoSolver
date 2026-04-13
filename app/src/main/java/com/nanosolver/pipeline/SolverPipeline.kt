package com.nanosolver.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Trace
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
    /** Raw frame in → preprocessed bitmap out (includes crop + scale + grayscale + threshold) */
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
 * SolverPipeline — Phase 7
 *
 * Orchestrates all four pipeline stages with two Phase 7 additions:
 *
 *   Stage 1 — Preprocess : raw Bitmap → crop → scale → grayscale → threshold
 *   Stage 2 — OCR        : preprocessed Bitmap → expression text
 *   Stage 3 — Solve      : expression text → Long answer (cache-first)
 *   Stage 4 — Inject     : answer → AccessibilityService IPC
 *
 * ────────────────────────────────────────────────────────────────────
 * PHASE 7 ADDITIONS
 *
 * 1. CAPTURE CONFIG ([config])
 *    Passes the question region rect and OCR target width to ImagePreprocessor,
 *    enabling the two new preprocess stages (crop + scale). The question region
 *    is computed once from [screenWidth]/[screenHeight] and reused every frame.
 *
 *    Latency impact (typical 1080p device):
 *      Before: preprocess + OCR ~ 130ms   (full 1080×2400 bitmap)
 *      After:  preprocess + OCR ~  40ms   (cropped 1026×432 → scaled 720×303)
 *
 * 2. SYSTRACE MARKERS (android.os.Trace)
 *    Each stage is wrapped with Trace.beginSection / endSection. These appear
 *    as named slices in Android Studio CPU Profiler → System Trace view.
 *
 *    HOW TO PROFILE:
 *      a. In Android Studio: Run → Profile → CPU Profiler
 *      b. Select "System Trace" recording configuration
 *      c. Start recording, run Matiks Sprint mode for 10–20 problems
 *      d. Stop recording and look for the NanoSolver/ stage slices
 *      e. The widest slice is the bottleneck. Typical finding:
 *           OCR ~80ms, Preprocess ~8ms, Solve ~0.1ms, Inject ~2ms
 *
 *    Command line alternative:
 *      adb shell atrace -t 10 -b 32768 --app com.nanosolver > trace.ftrace
 *      Open trace.ftrace in Perfetto UI (ui.perfetto.dev)
 *
 * ────────────────────────────────────────────────────────────────────
 * PIPELINING (unchanged from Phase 6)
 *
 *   Frame N:  [pre] → [OCR] → [solve] → gate released → [inject]
 *   Frame N+1:                         [pre] → [OCR] → …
 *
 * Gate released before inject so the next frame's OCR overlaps with
 * this frame's injection.
 *
 * @param scope        CoroutineScope tied to the service lifetime (SupervisorJob).
 * @param preprocessor Crop + scale + grayscale + Otsu threshold transformer.
 * @param extractor    ML Kit OCR wrapper (suspend function).
 * @param parser       Recursive descent math parser (includes LRU cache).
 * @param config       Capture region fractions and OCR target width (Phase 7).
 * @param screenWidth  Device screen width in pixels (from DisplayMetrics).
 * @param screenHeight Device screen height in pixels (from DisplayMetrics).
 * @param onAnswer     Called after inject with the answer and latency breakdown.
 *                     Runs on the coroutine thread — post to main if UI work needed.
 */
class SolverPipeline(
    private val scope: CoroutineScope,
    private val preprocessor: ImagePreprocessor,
    private val extractor: TextExtractor,
    private val parser: MathParser,
    private val config: CaptureConfig = CaptureConfig(),
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onAnswer: (answer: Long, stats: LatencyStats) -> Unit
) {

    companion object {
        private const val TAG = "SolverPipeline"

        /** Log a rolling latency summary every this many successfully solved frames. */
        private const val STATS_LOG_INTERVAL = 10

        // Systrace section names — visible in CPU Profiler and Perfetto.
        private const val TRACE_PREPROCESS = "NanoSolver/Preprocess"
        private const val TRACE_OCR        = "NanoSolver/OCR"
        private const val TRACE_SOLVE      = "NanoSolver/Solve"
        private const val TRACE_INJECT     = "NanoSolver/Inject"
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
     * Gate: at most one OCR job runs at a time (always-latest-frame policy).
     * Released BEFORE inject so the next frame's OCR can overlap with injection.
     */
    private val pipelineBusy = AtomicBoolean(false)

    /**
     * Precomputed question region in absolute pixels.
     *
     * Computed once at construction time from [config] fractions and device
     * [screenWidth]/[screenHeight]. Reused every frame — no allocation per frame.
     */
    private val questionRegion: Rect = config.questionRegion(screenWidth, screenHeight)

    // Rolling stats — single writer (pipeline coroutine after gate), no sync needed.
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
        if (!enabled) {
            bitmap.recycle()
            return
        }
        if (!pipelineBusy.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        scope.launch { runPipeline(bitmap) }
    }

    // -------------------------------------------------------------------------
    // Pipeline execution
    // -------------------------------------------------------------------------

    private suspend fun runPipeline(bitmap: Bitmap) {
        val t0 = System.nanoTime()

        try {
            // ── Stage 1: Preprocess ────────────────────────────────────────
            // Now does four sub-steps: crop → scale → grayscale → threshold.
            //
            // questionRegion crops to the Matiks question band (~75% pixel reduction).
            // ocrTargetWidth downscales to 720px wide (~30-40% further reduction).
            //
            // Total preprocess time: ~5–10ms (was ~5ms before Phase 7 additions,
            // but OCR time drops from ~120ms to ~40ms — a net win of ~70ms).
            Trace.beginSection(TRACE_PREPROCESS)
            val preprocessed = preprocessor.preprocess(
                source         = bitmap,
                region         = questionRegion,
                ocrTargetWidth = config.ocrTargetWidth
            )
            bitmap.recycle()
            Trace.endSection()
            val t1 = System.nanoTime()

            // ── Stage 2: OCR ───────────────────────────────────────────────
            // ML Kit TFLite inference. The suspend fun yields the thread while
            // the model runs — other coroutines can execute during this time.
            Trace.beginSection(TRACE_OCR)
            val rawText = extractor.extract(preprocessed)
            preprocessed.recycle()
            Trace.endSection()
            val t2 = System.nanoTime()

            if (rawText.isBlank()) return  // no legible text; gate released in finally

            // ── Stage 3: Solve ─────────────────────────────────────────────
            // Custom recursive descent parser + LRU cache. ~0.1ms parse,
            // ~0.01ms cache hit. MathParser.solve() consults SolverCache internally.
            // Cache-hit detection: if solve finishes in < 0.5ms, it was a cache hit.
            Trace.beginSection(TRACE_SOLVE)
            val t2b      = System.nanoTime()
            val answer   = parser.solve(rawText)
            val t3       = System.nanoTime()
            val cacheHit = (t3 - t2b) < 500_000L  // < 0.5ms ⟹ cache hit
            Trace.endSection()

            // ── PIPELINING: release gate before inject ─────────────────────
            // Next frame can now enter Preprocess + OCR concurrently with
            // this frame's Binder IPC inject call.
            pipelineBusy.set(false)

            answer ?: return

            // ── Stage 4: Inject ────────────────────────────────────────────
            // Phase 7: NanoAccessibilityService uses cached node references —
            // fast path skips tree traversal entirely (~1–3ms vs ~5–15ms).
            Trace.beginSection(TRACE_INJECT)
            NanoAccessibilityService.injectAnswer(answer)
            val t4 = System.nanoTime()
            Trace.endSection()

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
        } finally {
            // Safety net: always clear the gate, even on early return or exception.
            // set(false) on an already-false AtomicBoolean is a no-op — safe to double-call.
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

        // Per-frame detail (Debug level — filtered out in release builds).
        Log.d(TAG,
            "Frame #$solvedFrames | " +
            "pre=${stats.preprocessMs}ms " +
            "ocr=${stats.ocrMs}ms " +
            "solve=${stats.solveMs}ms${if (stats.cacheHit) "(cache)" else ""} " +
            "inject=${stats.injectMs}ms | " +
            "total=${stats.totalMs}ms"
        )

        // Rolling summary every STATS_LOG_INTERVAL solved frames.
        if (solvedFrames % STATS_LOG_INTERVAL == 0) {
            val avg     = totalMs / solvedFrames
            val hitRate = (cacheHits * 100) / solvedFrames
            Log.i(TAG,
                "══ Latency summary [$solvedFrames frames solved] ══ " +
                "avg=${avg}ms  min=${minMs}ms  max=${maxMs}ms  " +
                "cache_hit_rate=${hitRate}%"
            )
        }
    }
}
