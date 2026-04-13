package com.nanosolver.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * TextExtractor — Phase 3
 *
 * Wraps ML Kit's on-device text recognizer and exposes a single suspend
 * function: [extract]. Callers use it like a blocking call inside a coroutine,
 * even though the underlying ML Kit inference is async.
 *
 * KEY CONCEPTS:
 *
 * 1. ML KIT TASK API vs COROUTINES
 *    ML Kit returns a Task<Text> — Google's equivalent of a Future/Promise.
 *    The old way to consume it: chain .addOnSuccessListener / .addOnFailureListener.
 *    The modern Kotlin way: call .await() from kotlinx-coroutines-play-services.
 *    await() suspends the coroutine until the Task completes, then resumes with
 *    the result (or throws on failure). No callback nesting needed.
 *
 * 2. SUSPENDING vs BLOCKING
 *    await() is a *suspending* call, not a blocking one. The CaptureThread is
 *    NOT frozen while ML Kit runs. The coroutine is suspended (removed from the
 *    thread) and another coroutine can run in its place. When ML Kit finishes,
 *    the coroutine is resumed on the same dispatcher (Dispatchers.Default).
 *
 * 3. TextRecognizerOptions.DEFAULT_OPTIONS
 *    Selects the bundled Latin-script model. This covers digits 0–9 and all
 *    common math operator symbols (+, −, ×, ÷). No internet required.
 *    The model is NNAPI-aware — on devices with a DSP or NPU, inference is
 *    delegated to hardware, cutting latency significantly vs CPU-only.
 *
 * 4. InputImage.fromBitmap(bitmap, rotation)
 *    rotation = 0 because our VirtualDisplay captures the screen upright.
 *    If we captured in landscape or rotated, we'd pass 90/180/270 here and
 *    ML Kit would internally correct the orientation before inference.
 */
class TextExtractor {

    companion object {
        private const val TAG = "TextExtractor"
    }

    // The recognizer is expensive to construct (loads the TFLite model into memory).
    // Create once, reuse for every frame. Thread-safe — ML Kit handles concurrency.
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Runs OCR on [bitmap] and returns the raw text string.
     *
     * Must be called from a coroutine. Suspends until inference completes (~50–120ms).
     * The bitmap is NOT recycled by this function — caller remains the owner.
     *
     * @return  Extracted text, possibly multi-line. Empty string if nothing recognised.
     *          Example outputs for Matiks: "35 + 27", "142 - 89", "6 × 7"
     */
    suspend fun extract(bitmap: Bitmap): String {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        return try {
            // .await() is from kotlinx-coroutines-play-services.
            // It converts Task<Text> into a suspend call — no callbacks needed.
            val result = recognizer.process(inputImage).await()
            result.text.also { text ->
                if (text.isNotBlank()) {
                    Log.d(TAG, "OCR raw: '${text.replace('\n', '|')}'")
                    logBlocks(result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed: ${e.message}")
            ""
        }
    }

    /**
     * Logs the structured output from ML Kit.
     *
     * ML Kit returns a hierarchy: Text → TextBlock → Line → Element.
     *   TextBlock = a paragraph / cluster of nearby text
     *   Line      = a horizontal line within a block
     *   Element   = a single word or symbol
     *
     * For Matiks, the math expression is typically one Block with one Line,
     * containing elements like ["35", "+", "27"]. Logging the structure here
     * helps us decide in Phase 4 which element to parse as the expression.
     */
    private fun logBlocks(result: com.google.mlkit.vision.text.Text) {
        for ((bi, block) in result.textBlocks.withIndex()) {
            for ((li, line) in block.lines.withIndex()) {
                val elements = line.elements.joinToString(" | ") { it.text }
                Log.v(TAG, "  Block[$bi] Line[$li]: $elements  bbox=${line.boundingBox}")
            }
        }
    }

    /**
     * Releases the underlying TFLite model from memory.
     * Call from [OverlayService.onDestroy].
     */
    fun close() {
        recognizer.close()
    }
}
