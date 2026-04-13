package com.nanosolver.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * ScreenCaptureManager — Phase 2
 *
 * Owns the MediaProjection → VirtualDisplay → ImageReader pipeline.
 * Converts raw screen frames to Bitmaps and delivers them via [onFrame].
 *
 * KEY CONCEPTS in this file:
 *
 * 1. MEDIAPROJECTION
 *    The token Android gives you after the user approves screen sharing.
 *    It's the authority that lets you create a VirtualDisplay.
 *
 * 2. VIRTUALDISPLAY
 *    A synthetic, invisible display that MediaProjection renders the real
 *    screen into. You provide a Surface as its render target — we use the
 *    ImageReader's surface so each rendered frame lands in our buffer.
 *
 * 3. IMAGEREADER
 *    A queue of Image buffers. The VirtualDisplay writes into it; we read
 *    out of it. ACQUIRE_LATEST_IMAGE always gives the freshest frame and
 *    discards anything older — critical for low latency.
 *
 * 4. HANDLERTHREAD
 *    The ImageReader fires onImageAvailable() on whichever Handler you give
 *    it. Using a dedicated HandlerThread keeps this work off the main thread,
 *    so the UI stays responsive even if frame processing takes a moment.
 *
 * 5. ROW PADDING
 *    GPU hardware aligns each row of pixels to a memory boundary (e.g. 64
 *    bytes). rowStride (bytes/row in the buffer) ≥ pixelStride × width.
 *    The difference is wasted padding. Ignoring it produces a corrupted,
 *    sheared image — see [imageToBitmap] for the fix.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {

    companion object {
        private const val TAG = "ScreenCapture"
        // Double-buffer: one Image being processed, one being written by VirtualDisplay.
        // Fewer buffers = less memory; more = less risk of "max images acquired" errors.
        private const val MAX_IMAGES = 2
    }

    /**
     * Invoked on the [CaptureThread] background thread for every new screen frame.
     * Receive the Bitmap, use it, then call [Bitmap.recycle] when done.
     * Phase 3 will pass this bitmap to the OCR engine.
     */
    var onFrame: ((Bitmap) -> Unit)? = null

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Dedicated background thread so ImageReader callbacks never touch the main thread.
    private val captureThread = HandlerThread("CaptureThread")
    private lateinit var captureHandler: Handler

    // FPS measurement state
    private var frameCount = 0
    private var fpsWindowStart = 0L

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun start() {
        captureThread.start()
        captureHandler = Handler(captureThread.looper)

        val metrics = context.resources.displayMetrics
        val width   = metrics.widthPixels
        val height  = metrics.heightPixels
        val density = metrics.densityDpi

        // PixelFormat.RGBA_8888: 4 bytes per pixel.
        // This is the standard format for MediaProjection screen capture.
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)

        fpsWindowStart = System.currentTimeMillis()

        imageReader!!.setOnImageAvailableListener({ reader ->
            // acquireLatestImage() discards any intermediate frames that arrived
            // since the last call. This is the key to low latency — we never
            // process a frame that's already stale.
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                logFps()
                val bitmap = imageToBitmap(image)
                onFrame?.invoke(bitmap)
            } finally {
                // MUST close the Image to return its buffer to the pool.
                // Forgetting this causes "maxImages (2) has already been acquired" crashes
                // and memory leaks. This is the most common ImageReader mistake.
                image.close()
            }
        }, captureHandler)

        // Create the VirtualDisplay that renders the real screen into our ImageReader surface.
        // VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR: mirrors the device's primary display.
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "NanoSolverCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,  // no VirtualDisplay.Callback needed for now
            null   // no Handler — VirtualDisplay events are rare, main thread is fine
        )

        Log.i(TAG, "Screen capture started: ${width}x${height} @ ${density}dpi")
    }

    fun stop() {
        // Release in reverse order of creation to avoid use-after-free.
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection.stop()
        captureThread.quitSafely()  // finishes pending messages, then stops the thread
        virtualDisplay = null
        imageReader = null
        Log.i(TAG, "Screen capture stopped")
    }

    // -------------------------------------------------------------------------
    // Frame conversion
    // -------------------------------------------------------------------------

    /**
     * Converts a raw [Image] plane to a [Bitmap].
     *
     * The buffer from ImageReader may contain row padding due to GPU memory alignment
     * requirements. If we ignore the padding and create a Bitmap of exactly
     * (image.width × image.height), copyPixelsFromBuffer() misaligns every row
     * past the first — producing a corrupted image that looks sheared to the right.
     *
     * Fix: create a Bitmap wide enough to hold the padded rows, copy the buffer,
     * then crop away the padding columns.
     *
     * @param image  The acquired [Image] — caller must call [Image.close] after this returns.
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane       = image.planes[0]
        val pixelStride = plane.pixelStride          // bytes per pixel (4 for RGBA_8888)
        val rowStride   = plane.rowStride            // bytes per row including alignment padding
        val rowPadding  = rowStride - pixelStride * image.width  // extra bytes at end of each row

        // Create bitmap wide enough to absorb the padding bytes
        val paddedBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(plane.buffer)

        // Crop to the true screen dimensions if padding was present
        return if (rowPadding == 0) {
            paddedBitmap
        } else {
            val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
            paddedBitmap.recycle()
            cropped
        }
    }

    // -------------------------------------------------------------------------
    // FPS measurement
    // -------------------------------------------------------------------------

    /**
     * Tracks frames-per-second over a 2-second rolling window and logs it.
     *
     * WHY measure FPS?
     * The screen capture rate directly affects how quickly we see a new math
     * problem after it appears. If FPS is low (< 10), the capture pipeline is
     * the bottleneck, not the OCR or solver. This tells us where to optimize.
     *
     * Typical values:
     *  - 30–60 fps  → excellent, not the bottleneck
     *  - 10–30 fps  → acceptable
     *  - < 10 fps   → investigate ImageReader buffer exhaustion or heavy onFrame work
     */
    private fun logFps() {
        frameCount++
        val now     = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 2_000) {
            val fps = frameCount * 1_000f / elapsed
            Log.d(TAG, "Capture FPS: ${"%.1f".format(fps)}")
            frameCount    = 0
            fpsWindowStart = now
        }
    }
}
