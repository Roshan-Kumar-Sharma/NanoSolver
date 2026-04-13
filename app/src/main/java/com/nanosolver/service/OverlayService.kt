package com.nanosolver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat

/**
 * OverlayService — Phase 1
 *
 * This is the heart of the overlay system. It runs as a foreground service so
 * Android doesn't kill it while Matiks is in the foreground.
 *
 * KEY CONCEPTS to understand here:
 *
 * 1. FOREGROUND SERVICE
 *    A regular Service can be killed when the system needs memory. A foreground
 *    service persists but MUST show a notification — this is Android's way of
 *    making sure the user knows something is running in the background.
 *
 * 2. WINDOW MANAGER + TYPE_APPLICATION_OVERLAY
 *    WindowManager lets you add views directly to the system window layer,
 *    bypassing the normal Activity/Fragment hierarchy. TYPE_APPLICATION_OVERLAY
 *    places our view above all apps but below the system status bar.
 *
 * 3. FLAG_NOT_FOCUSABLE
 *    Without this flag, our overlay would steal touch events from Matiks.
 *    The overlay button only captures its own touch events; everything else
 *    passes through to the game below.
 *
 * 4. STATIC isRunning FLAG
 *    Instead of using the deprecated ActivityManager.getRunningServices(), we
 *    maintain a companion object flag. It's set in onCreate/onDestroy which are
 *    guaranteed to be called on the main thread.
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "nano_solver_overlay"
        private const val NOTIFICATION_ID = 1

        /**
         * True while the service is alive. MainActivity reads this to show
         * correct button state without polling ActivityManager.
         */
        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager

    // The single view we add to the window. Kept as a field so onDestroy()
    // can remove it cleanly and avoid a WindowManager leak.
    private var overlayButton: View? = null

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
        // startForeground() MUST be called within 5 seconds of onStartCommand().
        // It associates this service with the notification, preventing the
        // "Context.startForegroundService() did not then call startForeground()" crash.
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay()
        // START_STICKY: if the OS kills the service, restart it with a null intent.
        // This keeps the overlay alive even if memory is temporarily tight.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        removeOverlay()
    }

    // Services that don't support binding return null here.
    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Notification (required for foreground service)
    // -------------------------------------------------------------------------

    /**
     * NotificationChannel is required on API 26+. Creating a channel that already
     * exists is a no-op, so it's safe to call this every time onCreate() runs.
     *
     * IMPORTANCE_LOW = no sound, no heads-up — just a quiet persistent icon.
     * This is appropriate for a tool that runs continuously in the background.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nano Solver Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while the solver overlay is active"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nano Solver")
            .setContentText("Overlay is active — tap to open")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)   // Prevents the user from swiping it away
            .build()
    }

    // -------------------------------------------------------------------------
    // Overlay window
    // -------------------------------------------------------------------------

    private fun showOverlay() {
        // Guard against double-add if onStartCommand is called twice (e.g. START_STICKY restart)
        if (overlayButton != null) return

        val params = buildLayoutParams()
        val button = buildOverlayButton(params)

        overlayButton = button
        windowManager.addView(button, params)
    }

    /**
     * WindowManager.LayoutParams controls HOW our view sits in the system window stack.
     *
     * TYPE_APPLICATION_OVERLAY: The correct overlay type since API 26.
     *   The old TYPE_PHONE / TYPE_SYSTEM_ALERT are deprecated and rejected on modern Android.
     *
     * FLAG_NOT_FOCUSABLE: Our overlay doesn't intercept keyboard or non-button touch events.
     *   Without this, tapping anywhere in Matiks would be eaten by our window.
     *
     * FLAG_LAYOUT_IN_SCREEN: Lets us position the button even in areas the
     *   system normally reserves (e.g., near the status bar).
     *
     * TRANSLUCENT pixel format: Allows the button's rounded corners / transparency
     *   to actually be transparent rather than a black box.
     */
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
            x = 50    // px from left edge
            y = 300   // px from top edge (below status bar)
        }
    }

    /**
     * Creates the floating "▶ Solver" button and wires up drag behavior.
     *
     * DRAG IMPLEMENTATION:
     *   On ACTION_DOWN  — record the button's current position AND the raw
     *                     finger position. Both are needed to compute delta.
     *   On ACTION_MOVE  — new position = original position + (current finger - original finger).
     *                     windowManager.updateViewLayout() applies the change instantly.
     *   On ACTION_UP    — if finger barely moved, treat it as a click (future: open panel).
     *
     * WHY track raw coordinates?
     *   event.x/y are relative to the view itself (always near 0,0 for a small button).
     *   event.rawX/rawY are screen-absolute and give us the true delta across moves.
     */
    private fun buildOverlayButton(params: WindowManager.LayoutParams): Button {
        val button = Button(this).apply {
            text = "▶ Solver"
            setBackgroundColor(Color.parseColor("#1565C0"))  // Material Blue 800
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(40, 20, 40, 20)
        }

        var downX = 0; var downY = 0          // params.x/y at finger-down
        var downRawX = 0f; var downRawY = 0f  // screen-absolute finger position at finger-down
        var dragged = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = params.x;   downY = params.y
                    downRawX = event.rawX; downRawY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    // Only start dragging after a 10px threshold to avoid
                    // accidental moves on taps.
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        dragged = true
                        params.x = downX + dx
                        params.y = downY + dy
                        windowManager.updateViewLayout(button, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        // TODO Phase 6: toggle solver pipeline on/off
                        button.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        button.setOnClickListener {
            // Placeholder for Phase 6 pipeline toggle.
            // For now, just pulse the button color as visual feedback.
            button.setBackgroundColor(Color.parseColor("#0D47A1"))  // darker blue
            button.postDelayed({
                button.setBackgroundColor(Color.parseColor("#1565C0"))
            }, 150)
        }

        return button
    }

    private fun removeOverlay() {
        overlayButton?.let {
            // removeView() will throw if the view was never added, so guard with try/catch.
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayButton = null
    }
}
