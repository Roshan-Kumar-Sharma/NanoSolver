package com.nanosolver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nanosolver.service.OverlayService

class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // Permission launchers
    //
    // Android's modern permission API uses ActivityResultLauncher instead of
    // onActivityResult(). Each launcher is tied to a specific contract type.
    // -------------------------------------------------------------------------

    /**
     * Launcher for SYSTEM_ALERT_WINDOW (overlay permission).
     *
     * WHY a separate Settings screen?
     * SYSTEM_ALERT_WINDOW is a "special" permission — unlike camera or location,
     * it cannot be granted via the standard runtime dialog. The user must manually
     * toggle it in Settings. This is because an overlay can cover the entire screen,
     * making it a higher-risk permission that Android wants explicit user intent for.
     */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The result code is always RESULT_CANCELED for Settings screens, so we
        // re-check the actual permission state rather than trusting resultCode.
        updateUI()
    }

    /**
     * Launcher for POST_NOTIFICATIONS (Android 13+).
     *
     * WHY is this needed?
     * On API 33+, showing any notification requires explicit user permission.
     * Our foreground service needs a notification to keep the service alive
     * (Android mandates this as a user-visible signal that the app is running).
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnToggleService).setOnClickListener {
            handleToggleClick()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh UI every time the Activity comes back to the foreground.
        // This covers the case where the user returns from the Settings screen.
        updateUI()
    }

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    private fun handleToggleClick() {
        when {
            // Step 1: Ensure overlay permission is granted first.
            !Settings.canDrawOverlays(this) -> requestOverlayPermission()

            // Step 2: On Android 13+, ensure notification permission is granted.
            // Without a notification, startForegroundService() will crash.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED -> requestNotificationPermission()

            // Step 3: All permissions granted — toggle the service.
            else -> toggleService()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toggleService() {
        val intent = Intent(this, OverlayService::class.java)
        if (OverlayService.isRunning) {
            stopService(intent)
        } else {
            // startForegroundService() is required on API 26+ when the service
            // will call startForeground() within 5 seconds of starting.
            // Using startService() instead would cause an ANR crash on API 26+.
            startForegroundService(intent)
        }
        // Give the service a moment to update its isRunning flag, then refresh.
        findViewById<Button>(R.id.btnToggleService).postDelayed({ updateUI() }, 200)
    }

    // -------------------------------------------------------------------------
    // UI state
    // -------------------------------------------------------------------------

    // @SuppressLint: these are developer-facing status strings, not user-visible
    // translated content — moving them to string resources adds no real value here.
    @SuppressLint("SetTextI18n")
    private fun updateUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val running = OverlayService.isRunning

        // Permission status lines
        findViewById<TextView>(R.id.tvOverlayStatus).text =
            "Overlay permission: ${if (hasOverlay) "Granted" else "Not granted"}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            findViewById<TextView>(R.id.tvNotifStatus).text =
                "Notification permission: ${if (hasNotification) "Granted" else "Not granted"}"
        } else {
            findViewById<TextView>(R.id.tvNotifStatus).text =
                "Notification permission: Not required (API < 33)"
        }

        findViewById<TextView>(R.id.tvServiceStatus).text =
            "Overlay service: ${if (running) "Running" else "Stopped"}"

        // Button label reflects the next action the user should take
        findViewById<Button>(R.id.btnToggleService).text = when {
            !hasOverlay       -> "Grant Overlay Permission"
            !hasNotification  -> "Grant Notification Permission"
            running           -> "Stop Overlay"
            else              -> "Start Overlay"
        }
    }
}
