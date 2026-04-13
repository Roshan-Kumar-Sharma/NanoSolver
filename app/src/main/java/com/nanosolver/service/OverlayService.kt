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

/**
 * OverlayService — Phase 2
 *
 * Now does two things:
 *  1. Shows the draggable floating "▶ Solver" overlay (from Phase 1).
 *  2. Runs the MediaProjection → VirtualDisplay → ImageReader capture pipeline.
 *
 * PHASE 2 ADDITIONS vs PHASE 1:
 *
 * A. The service is ONLY started after the user approves screen sharing.
 *    MainActivity launches the consent dialog, gets back a result code + data Intent,
 *    and passes both as extras when calling startForegroundService().
 *
 * B. startForeground() now passes FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION on API 29+.
 *    On API 34 this is what satisfies the "project_media" permission requirement.
 *    It MUST be called before MediaProjectionManager.getMediaProjection().
 *
 * C. MediaProjection.Callback handles the case where the user revokes capture
 *    mid-session (by dismissing the screen-share notification from the shade).
 *
 * D. START_NOT_STICKY: the service must NOT auto-restart. If the OS kills it,
 *    a new MediaProjection token is required — the old one becomes invalid.
 *    The user must tap "Start Solver" again to re-consent.
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

    private lateinit var windowManager: WindowManager
    private var overlayButton: View? = null
    private var captureManager: ScreenCaptureManager? = null
    private var mediaProjection: MediaProjection? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
     * Called on the CaptureThread for each new frame.
     *
     * Phase 2: just log dimensions to confirm frames are arriving.
     * Phase 3: pass [bitmap] to TextExtractor for OCR.
     *
     * IMPORTANT: always recycle the bitmap when done. If you don't, the heap
     * fills up within seconds at 30+ fps.
     */
    private fun handleFrame(bitmap: Bitmap) {
        Log.v(TAG, "Frame: ${bitmap.width}x${bitmap.height}")
        // Phase 3 will replace this with: textExtractor.process(bitmap)
        bitmap.recycle()
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
                if (captureActive) Color.parseColor("#2E7D32")   // green when capturing
                else               Color.parseColor("#1565C0")   // blue when idle
            )
        }
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
