package com.nanosolver.pipeline

import android.content.Context

/**
 * RegionConfig — Plan 2 Phase 3
 *
 * Persists the user-selected OCR crop region as fractional screen coordinates
 * in SharedPreferences. Fractions (0.0–1.0) are device-independent — the same
 * config works correctly regardless of screen resolution.
 *
 * Defaults match the Phase 7 hardcoded values (25–55% vertical, 5–95% horizontal).
 *
 * @param topFraction    Top edge of the question region as a fraction of screen height.
 * @param bottomFraction Bottom edge of the question region as a fraction of screen height.
 * @param leftFraction   Left edge of the question region as a fraction of screen width.
 * @param rightFraction  Right edge of the question region as a fraction of screen width.
 */
data class RegionConfig(
    val topFraction:    Float = 0.25f,
    val bottomFraction: Float = 0.55f,
    val leftFraction:   Float = 0.05f,
    val rightFraction:  Float = 0.95f
) {
    companion object {
        private const val PREFS_NAME    = "nano_solver_region"
        private const val KEY_TOP       = "region_top"
        private const val KEY_BOTTOM    = "region_bottom"
        private const val KEY_LEFT      = "region_left"
        private const val KEY_RIGHT     = "region_right"

        fun load(context: Context): RegionConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return RegionConfig(
                topFraction    = prefs.getFloat(KEY_TOP,    0.25f),
                bottomFraction = prefs.getFloat(KEY_BOTTOM, 0.55f),
                leftFraction   = prefs.getFloat(KEY_LEFT,   0.05f),
                rightFraction  = prefs.getFloat(KEY_RIGHT,  0.95f)
            )
        }

        fun save(context: Context, config: RegionConfig) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_TOP,    config.topFraction)
                .putFloat(KEY_BOTTOM, config.bottomFraction)
                .putFloat(KEY_LEFT,   config.leftFraction)
                .putFloat(KEY_RIGHT,  config.rightFraction)
                .apply()
        }
    }

    /**
     * Converts this config to a [CaptureConfig] for the pipeline.
     *
     * @param ocrTargetWidth Downscale width for OCR (default 720px, Phase 7 value).
     */
    fun toCaptureConfig(ocrTargetWidth: Int = 720): CaptureConfig = CaptureConfig(
        regionTopFraction    = topFraction,
        regionBottomFraction = bottomFraction,
        regionLeftFraction   = leftFraction,
        regionRightFraction  = rightFraction,
        ocrTargetWidth       = ocrTargetWidth
    )
}
