package com.nanosolver.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.nanosolver.pipeline.CaptureConfig
import com.nanosolver.pipeline.LatencyStats
import com.nanosolver.pipeline.SolverPipeline
import com.nanosolver.solver.MathParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * OverlayService — Phase 7
 *
 * Hosts the SolverPipeline and overlay UI. Phase 7 changes:
 *
 *  - Passes [CaptureConfig] (question region fractions + OCR target width)
 *    and screen dimensions to SolverPipeline, enabling targeted cropping
 *    and resolution scaling before OCR (~70ms faster per frame).
 *
 *  - Screen dimensions are read once in onCreate() from DisplayMetrics
 *    and forwarded to the pipeline; no per-frame allocation.
 *
 * Earlier phases:
 *  - Phase 6: SolverPipeline wiring, toggle button, latency HUD.
 *  - Phase 5: AccessibilityService injection.
 *  - Phase 3: OCR pipeline + coroutine scope.
 *  - Phase 1: Overlay floating button via WindowManager.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID     = "nano_solver_overlay"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_RESULT_CODE     = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        @Volatile var isRunning:     Boolean = false; private set
        @Volatile var captureActive: Boolean = false; private set
    }

    // CoroutineScope tied to this service's lifetime.
    // SupervisorJob: if one pipeline coroutine throws, others keep running.
    // Dispatchers.Default: off the main thread for CPU + IO work.
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var windowManager:    WindowManager
    private lateinit var imagePreprocessor: ImagePreprocessor
    private lateinit var textExtractor:    TextExtractor
    private lateinit var mathParser:       MathParser
    private lateinit var solverPipeline:   SolverPipeline

    private var overlayButton:  View? = null
    private var captureManager: ScreenCaptureManager? = null
    private var mediaProjection: MediaProjection? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        isRunning         = true
        windowManager     = getSystemService(WINDOW_SERVICE) as WindowManager
        imagePreprocessor = ImagePreprocessor()
        textExtractor     = TextExtractor()
        mathParser        = MathParser()

        // Screen dimensions for the capture region rect computation (Phase 7).
        // These are stable for the service lifetime — display metrics don't change
        // unless the user rotates, which stops the capture anyway.
        val metrics = resources.displayMetrics

        // Build the pipeline. Injection happens inside SolverPipeline (Stage 4).
        // The onAnswer callback runs on Dispatchers.Default after inject completes;
        // we only need to post to the main thread here for the overlay UI update.
        solverPipeline = SolverPipeline(
            scope         = serviceScope,
            preprocessor  = imagePreprocessor,
            extractor     = textExtractor,
            parser        = mathParser,
            config        = CaptureConfig(),   // defaults: 25–55% of screen, 720px OCR width
            screenWidth   = metrics.widthPixels,
            screenHeight  = metrics.heightPixels
        ) { answer, stats ->
            Handler(Looper.getMainLooper()).post {
                showAnswer(answer, stats)
            }
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning     = false
        captureActive = false
        serviceScope.cancel()
        textExtractor.close()
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

        mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)

        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by user")
                Handler(Looper.getMainLooper()).post {
                    captureActive  = false
                    captureManager = null
                    mediaProjection = null
                    updateOverlayButtonState()
                }
            }
        }, Handler(Looper.getMainLooper()))

        captureManager = ScreenCaptureManager(this, mediaProjection!!).apply {
            // Each frame is handed directly to SolverPipeline.
            // processFrame() owns the bitmap from this point.
            onFrame = { bitmap -> solverPipeline.processFrame(bitmap) }
            start()
        }

        captureActive = true
        updateNotification(capturing = true)
        updateOverlayButtonState()
        Log.i(TAG, "Screen capture pipeline started")
    }

    private fun stopCapture() {
        captureManager?.stop()
        captureManager   = null
        mediaProjection  = null
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nano Solver",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active while Nano Solver overlay is running" }
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(capturing))
    }

    // -------------------------------------------------------------------------
    // Overlay window
    // -------------------------------------------------------------------------

    private fun showOverlay() {
        if (overlayButton != null) return
        val params = buildLayoutParams()
        val button = buildOverlayButton(params)
        overlayButton = button
        windowManager.addView(button, params)
    }

    /**
     * Syncs button appearance to the current pipeline state.
     *
     * Three states:
     *   ● Solving   — capture active, solver enabled  (green)
     *   ⏸ Paused    — capture active, solver paused   (amber)
     *   ▶ Solver    — capture not active              (blue)
     */
    private fun updateOverlayButtonState() {
        (overlayButton as? Button)?.apply {
            when {
                !captureActive           -> { text = "▶ Solver";  setBackgroundColor(Color.parseColor("#1565C0")) }
                solverPipeline.enabled   -> { text = "● Solving"; setBackgroundColor(Color.parseColor("#2E7D32")) }
                else                     -> { text = "⏸ Paused";  setBackgroundColor(Color.parseColor("#E65100")) }
            }
        }
    }

    /**
     * Displays the answer and total pipeline latency on the overlay button for 1.5s.
     *
     * Example: "= 42  [87ms]"
     *
     * The latency figure is the end-to-end wall-clock time from frame-in to inject-complete.
     * This gives immediate real-world feedback on pipeline performance.
     *
     * Must be called on the main thread.
     */
    private fun showAnswer(answer: Long, stats: LatencyStats) {
        val button = overlayButton as? Button ?: return
        button.text = "= $answer  [${stats.totalMs}ms]"
        button.setBackgroundColor(Color.parseColor("#6A1B9A"))  // purple = answer ready

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

        var downX = 0;     var downY = 0
        var downRawX = 0f; var downRawY = 0f
        var dragged = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX    = params.x;      downY    = params.y
                    downRawX = event.rawX;    downRawY = event.rawY
                    dragged  = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        dragged  = true
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
            // Phase 6 toggle: flip the pipeline enabled flag.
            //
            // The ScreenCaptureManager keeps running — no re-consent needed to resume.
            // Frames that arrive while disabled are recycled immediately in processFrame().
            //
            // This is the fix for the Phase 5 TODO:
            //   // TODO Phase 6: toggle solver pipeline on/off
            if (captureActive) {
                solverPipeline.enabled = !solverPipeline.enabled
                Log.i(TAG, "Solver toggled: enabled=${solverPipeline.enabled}")
            }
            updateOverlayButtonState()
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
