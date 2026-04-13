package com.nanosolver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
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
import com.nanosolver.service.NanoAccessibilityService
import com.nanosolver.service.OverlayService

/**
 * MainActivity — Phase 5
 *
 * Five-step readiness chain before the solver runs at full capability:
 *
 *   Step 1 — SYSTEM_ALERT_WINDOW        (overlay, Settings redirect)
 *   Step 2 — POST_NOTIFICATIONS         (Android 13+, runtime dialog)
 *   Step 3 — Accessibility service      (separate Settings redirect — Phase 5)
 *   Step 4 — Screen capture consent     (MediaProjection dialog)
 *   Step 5 — Start OverlayService       (with the token)
 *
 * NOTE on accessibility service ordering:
 *   The accessibility service can be enabled independently at any time — it's
 *   not a blocking prerequisite for starting the solver. OCR + display still
 *   works without it. We guide the user toward enabling it but don't force it.
 */
class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // Permission launchers
    // -------------------------------------------------------------------------

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateUI() }

    /**
     * Accessibility Settings launcher.
     *
     * WHY a separate Settings screen (again)?
     * Like SYSTEM_ALERT_WINDOW, accessibility services cannot be enabled
     * programmatically — Android requires explicit user consent via Settings.
     * This is a security boundary: no app can grant itself the ability to read
     * and control other apps' UIs without the user's deliberate action.
     *
     * We open Settings.ACTION_ACCESSIBILITY_SETTINGS which shows the list of
     * installed accessibility services. The user taps "Nano Solver" → toggle ON.
     */
    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startSolverService(result.resultCode, result.data!!)
        } else {
            updateUI()
        }
    }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnToggleService).setOnClickListener { handleToggleClick() }

        // Dedicated button that always opens Accessibility Settings regardless of other state.
        // The user may want to enable/disable the service independently.
        findViewById<Button>(R.id.btnAccessibilitySettings).setOnClickListener {
            openAccessibilitySettings()
        }
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
            !Settings.canDrawOverlays(this) -> requestOverlayPermission()
            needsNotificationPermission()   -> requestNotificationPermission()
            OverlayService.isRunning        -> stopSolverService()
            else                            -> launchScreenCaptureConsent()
        }
        // Note: accessibility service is NOT a blocking step in this chain.
        // Injection won't work without it, but OCR + display still runs.
        // The status row and dedicated button guide the user to enable it.
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
     * Opens the system Accessibility Settings list.
     *
     * There is no API to jump directly to our service's toggle — we can only
     * open the top-level Accessibility Settings page. The user finds "Nano Solver"
     * in the list and enables it manually.
     *
     * On some manufacturer skins (MIUI, One UI) the path may be different:
     *   MIUI:   Settings → Additional Settings → Accessibility → Installed services
     *   One UI: Settings → Accessibility → Installed apps
     */
    private fun openAccessibilitySettings() {
        accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun launchScreenCaptureConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startSolverService(resultCode: Int, data: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_PROJECTION_DATA, data)
        }
        startForegroundService(intent)
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
        val hasOverlay       = Settings.canDrawOverlays(this)
        val hasNotification  = !needsNotificationPermission()
        val hasAccessibility = isAccessibilityServiceEnabled()
        val running          = OverlayService.isRunning
        val capturing        = OverlayService.captureActive

        findViewById<TextView>(R.id.tvOverlayStatus).text =
            "Overlay permission: ${if (hasOverlay) "✓ Granted" else "✗ Not granted"}"

        findViewById<TextView>(R.id.tvNotifStatus).text =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                "Notification permission: ${if (hasNotification) "✓ Granted" else "✗ Not granted"}"
            else
                "Notification permission: ✓ Not required (API < 33)"

        findViewById<TextView>(R.id.tvServiceStatus).text =
            "Overlay service: ${if (running) "✓ Running" else "○ Stopped"}"

        findViewById<TextView>(R.id.tvCaptureStatus).text =
            "Screen capture: ${if (capturing) "✓ Active" else "○ Inactive"}"

        // Accessibility service status — shown with a note about what it enables
        findViewById<TextView>(R.id.tvAccessibilityStatus).text =
            if (hasAccessibility)
                "Accessibility service: ✓ Enabled — auto-fill active"
            else
                "Accessibility service: ✗ Disabled — answers shown only, not auto-filled"

        // Primary button
        findViewById<Button>(R.id.btnToggleService).text = when {
            !hasOverlay      -> "Grant Overlay Permission"
            !hasNotification -> "Grant Notification Permission"
            running          -> "Stop Solver"
            else             -> "Start Solver"
        }

        // Secondary button: always visible, label changes with state
        findViewById<Button>(R.id.btnAccessibilitySettings).text =
            if (hasAccessibility) "Accessibility Settings" else "Enable Accessibility Service"
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks whether NanoAccessibilityService is currently enabled in system settings.
     *
     * The system stores enabled accessibility services as a colon-separated string
     * in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, e.g.:
     *   "com.foo/.BarService:in.matiks/.GameService:com.nanosolver/.service.NanoAccessibilityService"
     *
     * We flatten our ComponentName to "com.nanosolver/.service.NanoAccessibilityService"
     * and check if it appears anywhere in that string.
     *
     * WHY NOT just check NanoAccessibilityService.isEnabled?
     *   The static flag is only set when the service process is running.
     *   Settings.Secure reflects what the user toggled — even before the service
     *   starts (e.g., on first launch after enabling it). Reading Settings is the
     *   authoritative source.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, NanoAccessibilityService::class.java)
        return enabledServices.contains(component.flattenToString(), ignoreCase = true)
    }
}
