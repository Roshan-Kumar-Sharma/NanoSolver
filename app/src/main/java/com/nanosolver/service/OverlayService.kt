package com.nanosolver.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import com.nanosolver.capture.ScreenCaptureManager
import com.nanosolver.ocr.ImagePreprocessor
import com.nanosolver.ocr.TextExtractor
import com.nanosolver.solver.MathParser
import com.nanosolver.service.NanoAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OverlayService — Phase 3
 *
 * Now does two things:
 *  1. Shows the draggable floating "▶ Solver" overlay (from Phase 1).
 *  2. Runs the MediaProjection → VirtualDisplay → ImageReader capture pipeline.
 *
 * PHASE 3+5 ADDITIONS vs PHASE 2:
 *
 * E. CoroutineScope (serviceScope): Owns all coroutines launched by this service.
 *    Uses SupervisorJob so a failed OCR coroutine doesn't cancel the whole scope.
 *    Cancelled in onDestroy() to stop any in-flight work cleanly.
 *
 * F. ImagePreprocessor: grayscale + Otsu threshold each frame before OCR.
 *
 * G. TextExtractor: ML Kit recognizer wrapped as a suspend function.
 *
 * H. ocrBusy (AtomicBoolean): prevents frame-queuing.
 *    WHY: ML Kit inference takes ~50–120ms. At 30fps a new frame arrives every
 *    ~33ms. Without a gate, we'd launch 3–4 parallel OCR jobs per second, each
 *    holding a Bitmap in memory. The gate ensures at most one OCR job runs at
 *    a time; frames that arrive while OCR is busy are dropped (recycled).
 *    This is intentional — we always want the LATEST frame, not a backlog.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "nano_solver_overlay"
        private const val NOTIFICATION_ID = 1

        /** Intent extras for passing the MediaProjection consent result. */
        const val EXTRA_RESULT_CODE      = "extra_result_code"
        const val EXTRA_PROJECTION_DATA  = "extra_projection_data"

        @Volatile var isRunning:     Boolean = false; private set
        @Volatile var captureActive: Boolean = false; private set
    }

    // CoroutineScope tied to this service's lifetime.
    // SupervisorJob: if one OCR coroutine throws, others keep running.
    // Dispatchers.Default: runs on the shared CPU thread pool — keeps main thread free.
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Prevents more than one OCR job running at once (see phase note H above).
    // AtomicBoolean is thread-safe: compareAndSet(false, true) is an atomic
    // "check + flip" that can't be interrupted between threads.
    private val ocrBusy = AtomicBoolean(false)

    private lateinit var windowManager: WindowManager
    private lateinit var imagePreprocessor: ImagePreprocessor
    private lateinit var textExtractor: TextExtractor
    private lateinit var mathParser: MathParser
    private var overlayButton: View? = null
    private var captureManager: ScreenCaptureManager? = null
    private var mediaProjection: MediaProjection? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager     = getSystemService(WINDOW_SERVICE) as WindowManager
        imagePreprocessor = ImagePreprocessor()
        textExtractor     = TextExtractor()
        mathParser        = MathParser()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the OS delivers a null intent (shouldn't happen with START_NOT_STICKY,
        // but guard anyway), we can't proceed without a fresh token.
        if (intent == null) {
            Log.w(TAG, "onStartCommand received null intent — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode     = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val projectionData = extractProjectionData(intent)

        if (resultCode != Activity.RESULT_OK || projectionData == null) {
            Log.e(TAG, "Invalid MediaProjection consent — resultCode=$resultCode")
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground() MUST be called before getMediaProjection().
        //
        // On API 29+, pass FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
        // This is what satisfies Android's "project_media" permission check on API 34.
        // The 2-arg overload is kept for API 26–28 compatibility.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(capturing = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(capturing = false))
        }

        showOverlay()
        startScreenCapture(resultCode, projectionData)

        // START_NOT_STICKY: do NOT restart if killed. The MediaProjection token is
        // single-use — once the service dies, the user must re-consent.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning     = false
        captureActive = false
        serviceScope.cancel()   // cancels all in-flight OCR coroutines
        textExtractor.close()   // releases TFLite model from memory
        stopCapture()
        removeOverlay()
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Screen capture
    // -------------------------------------------------------------------------

    private fun startScreenCapture(resultCode: Int, projectionData: Intent) {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // getMediaProjection() uses the token from user consent to create the
        // MediaProjection instance. Must be called after startForeground() on API 34.
        mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)

        // Register a callback to handle the user revoking screen share
        // (e.g. dismissing the "You're sharing your screen" notification).
        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by user")
                Handler(Looper.getMainLooper()).post {
                    captureActive = false
                    captureManager = null
                    mediaProjection = null
                    updateOverlayButtonState()
                }
            }
        }, Handler(Looper.getMainLooper()))

        captureManager = ScreenCaptureManager(this, mediaProjection!!).apply {
            onFrame = { bitmap -> handleFrame(bitmap) }
            start()
        }

        captureActive = true
        updateNotification(capturing = true)
        updateOverlayButtonState()
        Log.i(TAG, "Screen capture pipeline started")
    }

    private fun stopCapture() {
        captureManager?.stop()
        captureManager  = null
        mediaProjection = null
    }

    /**
     * Called on the CaptureThread for each new screen frame.
     *
     * The ocrBusy gate prevents frame queuing:
     *   - compareAndSet(false, true) atomically flips the flag to true only if
     *     it was false. If another OCR job is running (flag already true), this
     *     call returns false and we drop the frame immediately.
     *   - The finally block always resets the flag so the next frame can proceed.
     *
     * FULL PIPELINE (Phase 3):
     *   Raw frame → preprocess (grayscale + threshold) → ML Kit OCR → log text
     *   Phase 4 will add:  → MathParser.solve(text) → answer
     *   Phase 5 will add:  → AccessibilityService.inject(answer)
     */
    private fun handleFrame(bitmap: Bitmap) {
        if (!ocrBusy.compareAndSet(false, true)) {
            // OCR is still running from the previous frame. Drop this frame.
            bitmap.recycle()
            return
        }

        // Launch on Dispatchers.Default (already the service scope's dispatcher).
        // The frame bitmap is handed off to this coroutine; we don't touch it after.
        serviceScope.launch {
            try {
                // Step 1: Preprocess — grayscale + Otsu threshold
                // Runs on CPU (Dispatchers.Default). ~5ms for a 1080p frame.
                val preprocessed = imagePreprocessor.preprocess(bitmap)
                bitmap.recycle()   // original no longer needed

                // Step 2: OCR — ML Kit TFLite inference
                // .await() suspends this coroutine until ML Kit finishes (~50–120ms).
                // The thread is NOT blocked — other coroutines can run during this time.
                val rawText = textExtractor.extract(preprocessed)
                preprocessed.recycle()

                // Step 3: Solve — recursive descent parser, ~0.1ms
                if (rawText.isNotBlank()) {
                    val answer = mathParser.solve(rawText)
                    if (answer != null) {
                        // Inject first (before showing on overlay) so the answer is
                        // in the input field as quickly as possible. NanoAccessibilityService
                        // runs its own Binder IPC (~1–3ms) — still much faster than the user.
                        NanoAccessibilityService.injectAnswer(answer)
                        // Update overlay UI (must run on the main thread)
                        Handler(Looper.getMainLooper()).post { showAnswer(answer) }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Frame pipeline error: ${e.message}")
                // Don't crash the service over a single bad frame
            } finally {
                // Always release the gate so the next frame can be processed.
                ocrBusy.set(false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nano Solver",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active while Nano Solver overlay is running"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(capturing: Boolean): Notification {
        val text = if (capturing) "Overlay + capture active" else "Starting…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nano Solver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(capturing: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(capturing))
    }

    // -------------------------------------------------------------------------
    // Overlay window (unchanged from Phase 1)
    // -------------------------------------------------------------------------

    private fun showOverlay() {
        if (overlayButton != null) return
        val params = buildLayoutParams()
        val button = buildOverlayButton(params)
        overlayButton = button
        windowManager.addView(button, params)
    }

    private fun updateOverlayButtonState() {
        (overlayButton as? Button)?.apply {
            text = if (captureActive) "● Solving" else "▶ Solver"
            setBackgroundColor(
                if (captureActive) Color.parseColor("#2E7D32")
                else               Color.parseColor("#1565C0")
            )
        }
    }

    /**
     * Displays the computed answer on the overlay button.
     *
     * Shows the answer for 1.5 seconds (long enough to read and type it),
     * then reverts back to "● Solving" so it's clear the solver is still running.
     *
     * Must be called on the main thread — WindowManager.updateViewLayout() is
     * not thread-safe. We post() to the main handler from the coroutine.
     */
    private fun showAnswer(answer: Long) {
        val button = overlayButton as? Button ?: return
        button.text = "= $answer"
        button.setBackgroundColor(Color.parseColor("#E65100"))  // deep orange = answer ready

        // Revert to "solving" state after 1.5s so the user knows we're still running
        button.postDelayed({
            if (captureActive) updateOverlayButtonState()
        }, 1500)
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }
    }

    private fun buildOverlayButton(params: WindowManager.LayoutParams): Button {
        val button = Button(this).apply {
            text = "▶ Solver"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(40, 20, 40, 20)
        }

        var downX = 0;    var downY = 0
        var downRawX = 0f; var downRawY = 0f
        var dragged = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = params.x;     downY = params.y
                    downRawX = event.rawX; downRawY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        dragged = true
                        params.x = downX + dx
                        params.y = downY + dy
                        windowManager.updateViewLayout(button, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) button.performClick()
                    true
                }
                else -> false
            }
        }

        button.setOnClickListener {
            // TODO Phase 6: toggle solver pipeline on/off
            button.setBackgroundColor(Color.parseColor("#0D47A1"))
            button.postDelayed({ updateOverlayButtonState() }, 150)
        }

        return button
    }

    private fun removeOverlay() {
        overlayButton?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayButton = null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun extractProjectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
    }
}
