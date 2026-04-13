package com.nanosolver.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect

/**
 * ImagePreprocessor — Phase 3
 *
 * Transforms a raw screen-capture Bitmap into a clean black-and-white image
 * that ML Kit's text recognizer can read more accurately and faster.
 *
 * The pipeline has three stages:
 *
 *   1. CROP    — isolate the region of interest (optional; full frame if null).
 *   2. GRAYSCALE — remove color information; keep only luminance.
 *   3. THRESHOLD — convert every pixel to pure black or pure white.
 *
 * WHY PREPROCESS AT ALL?
 *   OCR engines are trained on clean text documents. A game UI has:
 *   - Colored backgrounds (Matiks uses gradient blues/purples)
 *   - Drop shadows, glows, and anti-aliasing artifacts on digits
 *   - Animations between problems
 *
 *   After thresholding, every pixel is either 0 or 255. The neural net sees
 *   crisp digit strokes with zero color noise — yielding faster inference and
 *   fewer misreads (e.g. confusing "3" with "8", or "1" with "7").
 *
 * WHY OTSU'S THRESHOLD INSTEAD OF A FIXED VALUE?
 *   A fixed threshold (e.g. 128) fails when the game changes background color
 *   or the device screen brightness changes. Otsu's method analyzes the actual
 *   pixel distribution of each frame and picks the optimal split point between
 *   "dark" and "light" pixels. It adapts automatically to every frame.
 */
class ImagePreprocessor {

    /**
     * Preprocesses [source] for OCR.
     *
     * @param source  Input bitmap (not recycled by this method — caller owns it).
     * @param region  Optional crop rectangle. Pass null to process the full frame.
     *                In Phase 7 we will pass the Matiks question bounding box here.
     * @return        A new ARGB_8888 bitmap with pure black text on a white background.
     *                Caller is responsible for recycling it when done.
     */
    fun preprocess(source: Bitmap, region: Rect? = null): Bitmap {
        // Stage 1: Crop
        val cropped = if (region != null) {
            Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height())
        } else {
            source
        }

        // Stage 2: Grayscale — using Canvas + ColorMatrix (GPU-accelerated path on most devices)
        val gray = toGrayscale(cropped)
        if (cropped !== source) cropped.recycle()

        // Stage 3: Binary threshold using Otsu's method
        val binary = applyOtsuThreshold(gray)
        gray.recycle()

        return binary
    }

    // -------------------------------------------------------------------------
    // Stage 2: Grayscale
    // -------------------------------------------------------------------------

    /**
     * Converts [src] to grayscale by desaturating with a [ColorMatrix].
     *
     * WHY Canvas + Paint instead of pixel-by-pixel?
     *   Canvas operations are dispatched to Skia, Android's 2D graphics engine,
     *   which on hardware-accelerated devices runs on the GPU. Pixel-by-pixel
     *   loops run on the CPU and are ~5–10× slower for large bitmaps.
     *
     * ColorMatrix.setSaturation(0f) sets the saturation component to zero,
     * meaning all colors collapse to their luminance value. The output is still
     * ARGB_8888 (same format) but R == G == B for every pixel.
     */
    private fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    // -------------------------------------------------------------------------
    // Stage 3: Otsu's threshold
    // -------------------------------------------------------------------------

    /**
     * Binarizes [gray] using the threshold computed by [otsuThreshold].
     * Pixels with luminance > threshold → WHITE; otherwise → BLACK.
     */
    private fun applyOtsuThreshold(gray: Bitmap): Bitmap {
        val w = gray.width
        val h = gray.height
        val pixels = IntArray(w * h)
        gray.getPixels(pixels, 0, w, 0, 0, w, h)

        val threshold = otsuThreshold(pixels)

        for (i in pixels.indices) {
            // After grayscale, R == G == B, so we can read any channel.
            // We AND with 0xFF to get an unsigned 0–255 value from the int.
            val luma = pixels[i] and 0xFF
            pixels[i] = if (luma > threshold) Color.WHITE else Color.BLACK
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Otsu's Method — finds the threshold that maximises between-class variance.
     *
     * INTUITION:
     *   Imagine the image has two populations: dark pixels (background / shadows)
     *   and light pixels (digits / text). The histogram shows two bumps. Otsu's
     *   method finds the valley between the bumps — the split point where the
     *   two groups are most separated from each other.
     *
     * ALGORITHM (single-pass O(256) after building the histogram):
     *   For every candidate threshold t (0–255):
     *     - wB = weight (fraction) of pixels ≤ t  (background class)
     *     - wF = weight of pixels > t             (foreground class)
     *     - mB, mF = mean luminance of each class
     *     - between-class variance = wB × wF × (mB − mF)²
     *   The t that maximises this variance is the Otsu threshold.
     *
     * @param pixels  Grayscale pixel array (R == G == B for each element).
     * @return        Optimal threshold in [0, 255].
     */
    private fun otsuThreshold(pixels: IntArray): Int {
        // Build luminance histogram
        val hist = IntArray(256)
        for (px in pixels) hist[px and 0xFF]++

        val total = pixels.size.toDouble()

        // Pre-compute the global mean (weighted sum of all intensities)
        var globalSum = 0.0
        for (i in 0..255) globalSum += i * hist[i]

        var wB = 0.0          // cumulative weight of background class
        var sumB = 0.0        // cumulative weighted sum of background class
        var maxVariance = 0.0
        var threshold = 128   // fallback if image is uniform

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue

            val wF = total - wB
            if (wF == 0.0) break

            sumB += t * hist[t]

            val mB = sumB / wB                   // mean of background
            val mF = (globalSum - sumB) / wF     // mean of foreground

            val variance = wB * wF * (mB - mF) * (mB - mF)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = t
            }
        }

        return threshold
    }
}
