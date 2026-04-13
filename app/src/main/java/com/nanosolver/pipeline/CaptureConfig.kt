package com.nanosolver.pipeline

import android.graphics.Rect

/**
 * CaptureConfig — Phase 7
 *
 * Defines the region of the screen that contains the math question, and the
 * target resolution to feed into ML Kit OCR. These are the two highest-impact
 * tuning knobs available without changing OCR engines.
 *
 * ────────────────────────────────────────────────────────────────────
 * WHY A TARGETED REGION?
 *
 * Phase 6 passes the FULL screen bitmap (e.g. 1080 × 2400 px) to OCR.
 * ML Kit inference time scales roughly with pixel count: a 1080p frame
 * takes ~120ms; cropping to just the question area (~1080 × 400 px, 1/6th
 * of the screen) cuts the pixel count to ~17% of the original, dropping
 * OCR time to ~25–40ms on the same device. No model change required.
 *
 * The fractions below represent the question band in Matiks:
 *   top:    25% down from the top of the screen
 *   bottom: 55% down (covers the expression + any multi-line format)
 *   left/right: 5%/95% to trim navigation bars and score elements
 *
 * Adjust these by running:
 *   adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
 * and measuring the question element's bounds as fractions of screen height.
 *
 * ────────────────────────────────────────────────────────────────────
 * WHY AN OCR TARGET WIDTH?
 *
 * ML Kit's TFLite model has an internal input size (typically 640–720px wide).
 * Feeding it an image wider than its sweet spot wastes time on upsampling
 * inside the model and provides no accuracy benefit.
 *
 * After cropping to the question region, the bitmap might still be 1080px
 * wide (most devices). Downscaling to [ocrTargetWidth] = 720px cuts pixel
 * count by ~56% with zero accuracy loss, shaving another ~15–30ms from OCR.
 *
 * If the crop is already SMALLER than [ocrTargetWidth], no scaling happens —
 * downscaling small text would reduce OCR accuracy.
 *
 * ────────────────────────────────────────────────────────────────────
 * TUNING WORKFLOW (Phase 7)
 *
 * 1. Run the app in Sprint mode for 30 problems.
 * 2. Check Logcat for: adb logcat -s SolverPipeline:I
 *    Look at the "ocr=" field in the per-frame latency lines.
 * 3. If ocr > 80ms: narrow the region (reduce bottom fraction)
 *    or reduce ocrTargetWidth to 480.
 * 4. If ocr < 40ms: the region is well-tuned; shift focus to inject latency.
 * 5. If inject > 10ms: the node cache in NanoAccessibilityService isn't hitting.
 *    Run: adb logcat -s NanoA11y:D and look for "fast path" vs "slow path" logs.
 */
data class CaptureConfig(

    /**
     * Top edge of the question region, as a fraction of screen height [0.0, 1.0].
     * Typical Matiks layout: problem text starts around 25% from the top.
     */
    val regionTopFraction: Float = 0.25f,

    /**
     * Bottom edge of the question region, as a fraction of screen height.
     * 55% covers the expression and any two-line wrapping in Matiks.
     */
    val regionBottomFraction: Float = 0.55f,

    /**
     * Left edge, as a fraction of screen width.
     * 5% trims the left navigation shadow / score overlay.
     */
    val regionLeftFraction: Float = 0.05f,

    /**
     * Right edge, as a fraction of screen width.
     * 95% trims the right-side decorations.
     */
    val regionRightFraction: Float = 0.95f,

    /**
     * Target width for the bitmap fed into ML Kit OCR, in pixels.
     *
     * The bitmap is downscaled to this width (preserving aspect ratio) only if
     * it is currently WIDER than this value. Set to 0 to disable scaling.
     *
     * 720px is a good default: matches ML Kit's internal processing resolution
     * and cuts OCR time by ~30–40% vs full 1080p input.
     */
    val ocrTargetWidth: Int = 720

) {
    /**
     * Converts the fractional region into a concrete pixel [Rect] for the given
     * screen dimensions.
     *
     * Called once per pipeline run, before the preprocess stage.
     * Both [screenWidth] and [screenHeight] come from [DisplayMetrics].
     *
     * The resulting Rect is clamped to screen bounds automatically because
     * the fractions are defined in [0, 1]. If fractions are misconfigured
     * (e.g. top > bottom), [ImagePreprocessor] will see a zero-height region
     * and Bitmap.createBitmap will throw — a fast-fail for misconfiguration.
     */
    fun questionRegion(screenWidth: Int, screenHeight: Int): Rect = Rect(
        (regionLeftFraction   * screenWidth ).toInt(),
        (regionTopFraction    * screenHeight).toInt(),
        (regionRightFraction  * screenWidth ).toInt(),
        (regionBottomFraction * screenHeight).toInt()
    )
}
