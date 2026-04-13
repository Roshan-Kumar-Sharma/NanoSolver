package com.nanosolver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.nanosolver.service.OverlayService

/**
 * MainActivity — Phase 2
 *
 * Manages a four-step permission chain before starting the solver:
 *
 *   Step 1 — SYSTEM_ALERT_WINDOW   (overlay, Settings redirect)
 *   Step 2 — POST_NOTIFICATIONS    (Android 13+, runtime dialog)
 *   Step 3 — Screen capture consent (MediaProjection dialog)
 *   Step 4 — Start OverlayService with the MediaProjection token
 *
 * WHY is MediaProjection consent done here and not in the service?
 *   createScreenCaptureIntent() must be launched from an Activity.
 *   Services have no window token and cannot show system dialogs.
 *   The Activity collects the token and hands it to the service via Intent extras.
 */
class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // Permission launchers
    // -------------------------------------------------------------------------

    /** SYSTEM_ALERT_WINDOW: must be granted via a dedicated Settings screen. */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    /** POST_NOTIFICATIONS: standard runtime dialog (Android 13+ only). */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateUI() }

    /**
     * Screen capture consent — the most important launcher in Phase 2.
     *
     * createScreenCaptureIntent() shows Android's system-level "Start recording?"
     * dialog. If the user approves:
     *   result.resultCode == RESULT_OK
     *   result.data       == the MediaProjection grant Intent (the "token")
     *
     * We pass both to the service. The service calls
     * MediaProjectionManager.getMediaProjection(resultCode, data) to turn them
     * into a live MediaProjection instance.
     *
     * If the user denies: resultCode == RESULT_CANCELED, data == null.
     * We do nothing — the service is not started.
     */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startSolverService(result.resultCode, result.data!!)
        } else {
            updateUI()  // re-render button without starting anything
        }
    }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnToggleService).setOnClickListener { handleToggleClick() }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // -------------------------------------------------------------------------
    // Permission chain
    // -------------------------------------------------------------------------

    private fun handleToggleClick() {
        when {
            // Step 1: overlay permission (Settings redirect)
            !Settings.canDrawOverlays(this) -> requestOverlayPermission()

            // Step 2: notification permission (Android 13+ only)
            needsNotificationPermission() -> requestNotificationPermission()

            // If service is already running — stop it
            OverlayService.isRunning -> stopSolverService()

            // Step 3: screen capture consent → Step 4 happens in the launcher callback
            else -> launchScreenCaptureConsent()
        }
    }

    private fun requestOverlayPermission() {
        overlayPermissionLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Shows the system "Start recording?" consent dialog.
     *
     * The MediaProjectionManager is a system service — it owns the consent
     * dialog and hands back a signed Intent (the token) that only it can verify.
     * There's no way to fake this grant, which is why screen capture is
     * considered safe even without a root check.
     */
    private fun launchScreenCaptureConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    // Step 4: start the service with the token
    private fun startSolverService(resultCode: Int, data: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_PROJECTION_DATA, data)
        }
        startForegroundService(intent)
        // Small delay so isRunning flag has time to be set before we refresh the UI
        findViewById<Button>(R.id.btnToggleService).postDelayed({ updateUI() }, 300)
    }

    private fun stopSolverService() {
        stopService(Intent(this, OverlayService::class.java))
        findViewById<Button>(R.id.btnToggleService).postDelayed({ updateUI() }, 200)
    }

    // -------------------------------------------------------------------------
    // UI state
    // -------------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun updateUI() {
        val hasOverlay      = Settings.canDrawOverlays(this)
        val hasNotification = !needsNotificationPermission()
        val running         = OverlayService.isRunning
        val capturing       = OverlayService.captureActive

        findViewById<TextView>(R.id.tvOverlayStatus).text =
            "Overlay permission: ${if (hasOverlay) "Granted" else "Not granted"}"

        findViewById<TextView>(R.id.tvNotifStatus).text =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                "Notification permission: ${if (hasNotification) "Granted" else "Not granted"}"
            else
                "Notification permission: Not required (API < 33)"

        findViewById<TextView>(R.id.tvServiceStatus).text =
            "Overlay service: ${if (running) "Running" else "Stopped"}"

        findViewById<TextView>(R.id.tvCaptureStatus).text =
            "Screen capture: ${if (capturing) "Active" else "Inactive"}"

        findViewById<Button>(R.id.btnToggleService).text = when {
            !hasOverlay      -> "Grant Overlay Permission"
            !hasNotification -> "Grant Notification Permission"
            running          -> "Stop Solver"
            else             -> "Start Solver"
        }
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }
}
