package com.nanosolver.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * NanoAccessibilityService — Phase 5
 *
 * Injects the computed answer into Matiks' answer input field and clicks Submit.
 * Called by OverlayService after MathParser produces a result.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * HOW ACCESSIBILITYSERVICE DIFFERS FROM EVERYTHING ELSE IN THIS PROJECT
 *
 * MediaProjection reads screen pixels. This service reads and WRITES the
 * UI widget tree of another app. It operates at a higher abstraction level:
 * instead of pixels, it works with semantic nodes (buttons, text fields, etc.)
 *
 * Android's IPC model: every time we call node.performAction(), a Binder
 * transaction fires from our process → the target app's process → back.
 * It's ~0.5–2ms per call. Calling 3 actions (focus, set text, click) costs
 * ~2–6ms total — completely negligible compared to OCR time.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * STATIC INSTANCE PATTERN
 *
 * AccessibilityServices are created and destroyed by Android, not by the
 * application. We can't start or reference them with startService() or
 * binding. The standard pattern is a companion object that captures the
 * live instance in onServiceConnected() and clears it in onDestroy().
 *
 * OverlayService calls NanoAccessibilityService.injectAnswer(answer) — a
 * static function that delegates to the live instance if one exists.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * NODE SEARCH STRATEGY
 *
 * We don't know Matiks' exact view IDs at build time (they may change across
 * app versions). Instead we use three fallback strategies in order:
 *
 *   1. findAccessibilityNodeInfosByViewId() — fastest, but needs the exact ID.
 *      We try common patterns like "in.matiks:id/answer_input".
 *
 *   2. isEditable + isEnabled + isVisibleToUser — finds any active text field.
 *      Works for both native views and WebView inputs in Chrome.
 *
 *   3. Text hint matching — looks for nodes whose hint/placeholder text
 *      contains common input prompts ("answer", "type your answer").
 *
 * For the submit button, we follow the same 3-strategy approach looking for
 * clickable nodes with common submit labels.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * DEBUGGING TIPS
 *
 * If injection doesn't work, enable VERBOSE logging and inspect the tree:
 *   adb logcat -s NanoA11y:V
 *
 * The logNodeTree() call in findAnswerInput() dumps the full accessibility
 * tree of Matiks' window so you can identify the correct node IDs/classes.
 *
 * You can also use: adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
 * to get the full XML view hierarchy of the current screen.
 */
class NanoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NanoA11y"

        /**
         * The live service instance, or null if the user hasn't enabled it.
         *
         * @Volatile ensures visibility across threads without full synchronization.
         * Reads/writes of object references are atomic on JVM, so no lock needed.
         */
        @Volatile
        private var instance: NanoAccessibilityService? = null

        /** True when the service is connected and can inject. */
        val isEnabled: Boolean get() = instance != null

        /**
         * Called by OverlayService when a new answer is computed.
         * Thread-safe: can be called from any thread.
         *
         * @return true if the answer was successfully injected into the input field.
         */
        fun injectAnswer(answer: Long): Boolean {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "injectAnswer($answer) called but service is not enabled")
                return false
            }
            return svc.inject(answer)
        }
    }

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected — ready to inject")
    }

    /**
     * Called for every accessibility event matching our config filters.
     * We don't need events to drive injection (OverlayService does that),
     * but we log them at VERBOSE level for debugging.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        Log.v(TAG, "Event: type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
            "pkg=${event.packageName} class=${event.className}")
    }

    /** Called when the system needs to interrupt our service (e.g. user navigates away). */
    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
    }

    // -------------------------------------------------------------------------
    // Injection
    // -------------------------------------------------------------------------

    /**
     * Main injection entry point. Gets the root window node and delegates
     * to [injectIntoWindow]. Always recycles the root node in a finally block.
     *
     * rootInActiveWindow returns the root [AccessibilityNodeInfo] of whatever
     * app is currently in the foreground — in our case, Matiks. Because we
     * restricted packageNames in the config, this should always be Matiks.
     */
    private fun inject(answer: Long): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow is null — is Matiks in the foreground?")
            return false
        }
        return try {
            injectIntoWindow(root, answer)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    /**
     * Finds the input field, sets the answer text, then clicks Submit.
     *
     * Returns true only if ACTION_SET_TEXT succeeded. A false return means
     * the input field wasn't found or the action was rejected by the app.
     */
    private fun injectIntoWindow(root: AccessibilityNodeInfo, answer: Long): Boolean {
        // ── Step 1: Find the answer input ──────────────────────────────────────
        val inputNode = findAnswerInput(root)
        if (inputNode == null) {
            Log.w(TAG, "Answer input not found. Enable VERBOSE logging to see the node tree.")
            logNodeTree(root, label = "WINDOW TREE")
            return false
        }

        return try {
            // ── Step 2: Set the answer text ────────────────────────────────────
            //
            // ACTION_SET_TEXT is more reliable than simulating keystrokes because:
            //   - It works even if the field has focus restrictions
            //   - It replaces the entire content atomically (no partial typing)
            //   - It fires the app's TextWatcher correctly
            //
            // The argument must be a CharSequence in a Bundle — Android's
            // accessibility framework uses Bundle to pass typed arguments.
            val bundle = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    answer.toString()
                )
            }
            val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            Log.d(TAG, "ACTION_SET_TEXT '$answer': success=$textSet")

            if (!textSet) {
                // Fallback: focus the field then paste from clipboard.
                // Some WebView inputs reject ACTION_SET_TEXT but accept paste.
                Log.w(TAG, "SET_TEXT failed — trying clipboard paste fallback")
                pasteViaClipboard(inputNode, answer)
            }

            // ── Step 3: Submit the answer ──────────────────────────────────────
            val submitted = clickSubmit(root, inputNode)
            Log.d(TAG, "Submit: success=$submitted")

            textSet || submitted  // partial success counts
        } finally {
            @Suppress("DEPRECATION")
            inputNode.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // Finding nodes
    // -------------------------------------------------------------------------

    /**
     * Finds the answer input field using three strategies in order of specificity.
     */
    private fun findAnswerInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Strategy 1: Known Matiks view IDs (fastest — O(1) lookup in the system's node cache)
        // NOTE: Verify these IDs by running:
        //   adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
        // and looking for the answer input element's android:resource-id.
        val knownIds = listOf("in.matiks:id/answer_input", "in.matiks:id/answerInput",
                              "in.matiks:id/answer", "in.matiks:id/input")
        for (id in knownIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val found = nodes[0]
                nodes.drop(1).forEach { @Suppress("DEPRECATION") it.recycle() }
                Log.d(TAG, "Input found by ID: $id")
                return found
            }
        }

        // Strategy 2: Any enabled, editable, visible field in the window.
        // Works for native EditText and Chrome WebView text inputs alike.
        val editable = findNodeWithPredicate(root) { node ->
            node.isEditable && node.isEnabled && node.isVisibleToUser
        }
        if (editable != null) {
            Log.d(TAG, "Input found by editable predicate: class=${editable.className}")
            return editable
        }

        // Strategy 3: Hint text matching — useful for inputs that display placeholder text
        for (hint in listOf("answer", "type your answer", "enter answer", "?")) {
            val nodes = root.findAccessibilityNodeInfosByText(hint)
            val match = nodes.firstOrNull { it.isEditable || it.isEnabled }
            nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
            if (match != null) {
                Log.d(TAG, "Input found by hint text: '$hint'")
                return match
            }
        }

        return null
    }

    /**
     * Finds the submit button with three fallback strategies.
     * If no button is found, tries pressing Enter on the input field itself.
     */
    private fun clickSubmit(root: AccessibilityNodeInfo, inputNode: AccessibilityNodeInfo): Boolean {
        // Strategy 1: Known IDs
        val submitIds = listOf("in.matiks:id/submit", "in.matiks:id/submitButton",
                               "in.matiks:id/btn_submit", "in.matiks:id/check")
        for (id in submitIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val node = nodes[0]
                nodes.drop(1).forEach { @Suppress("DEPRECATION") it.recycle() }
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                @Suppress("DEPRECATION") node.recycle()
                if (clicked) { Log.d(TAG, "Submit clicked by ID: $id"); return true }
            }
        }

        // Strategy 2: Button with common submit label
        for (label in listOf("Submit", "Enter", "Check", "OK", "✓", "→")) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            val btn = nodes.firstOrNull { it.isClickable && it.isEnabled }
            nodes.forEach { if (it !== btn) @Suppress("DEPRECATION") it.recycle() }
            if (btn != null) {
                val clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                @Suppress("DEPRECATION") btn.recycle()
                if (clicked) { Log.d(TAG, "Submit clicked by label: '$label'"); return true }
            }
        }

        // Strategy 3: Clickable non-editable button anywhere in the tree
        val btn = findNodeWithPredicate(root) { node ->
            node.isClickable && node.isEnabled && node.isVisibleToUser && !node.isEditable
        }
        if (btn != null) {
            val clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            @Suppress("DEPRECATION") btn.recycle()
            if (clicked) { Log.d(TAG, "Submit clicked by generic button predicate"); return true }
        }

        // Fallback: press IME action (Enter key) on the input field itself.
        // Many web forms submit on Enter, avoiding the need to find a separate button.
        Log.d(TAG, "No submit button found — pressing IME action on input")
        return inputNode.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
    }

    // -------------------------------------------------------------------------
    // Clipboard fallback
    // -------------------------------------------------------------------------

    /**
     * Fallback for when ACTION_SET_TEXT is rejected (common with some WebViews).
     *
     * Steps:
     *   1. Write the answer to the clipboard.
     *   2. Focus the input node.
     *   3. Select all existing text (so paste replaces it, not appends).
     *   4. Paste from clipboard via ACTION_PASTE.
     */
    private fun pasteViaClipboard(inputNode: AccessibilityNodeInfo, answer: Long): Boolean {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("nano_answer", answer.toString()))

        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)
        val pasted = inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "Clipboard paste: success=$pasted")
        return pasted
    }

    // -------------------------------------------------------------------------
    // Node traversal utility
    // -------------------------------------------------------------------------

    /**
     * Depth-first search for the first node satisfying [predicate].
     *
     * MEMORY MANAGEMENT:
     * Every AccessibilityNodeInfo.getChild() returns a new object that must be
     * recycled (on API < 33) to avoid a native memory leak in the framework.
     * This function recycles every intermediate node it visits. The returned
     * node is a fresh copy (via obtain) that the caller is responsible for
     * recycling when done.
     *
     * On API 33+, recycle() is deprecated and a no-op, but calling it is
     * harmless and keeps the code compatible with API 27+.
     */
    private fun findNodeWithPredicate(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeWithPredicate(child, predicate)
            @Suppress("DEPRECATION")
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Debug logging
    // -------------------------------------------------------------------------

    /**
     * Logs the accessibility node tree up to [maxDepth] levels deep.
     * Enable with: adb logcat -s NanoA11y:V
     *
     * For each node, logs:
     *   - Class name (e.g. android.widget.EditText, android.view.View)
     *   - Resource ID  (e.g. in.matiks:id/answer_input)
     *   - isEditable / isClickable flags
     *   - Text content and hint text
     *
     * Use this output to identify the correct strategy and IDs for your Matiks version.
     */
    private fun logNodeTree(node: AccessibilityNodeInfo, depth: Int = 0,
                            maxDepth: Int = 6, label: String = "") {
        if (depth == 0 && label.isNotEmpty()) Log.v(TAG, "── $label ──")
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth)
        Log.v(TAG,
            "$indent[${node.className?.substringAfterLast('.')}]" +
            " id=${node.viewIdResourceName}" +
            " edit=${node.isEditable}" +
            " click=${node.isClickable}" +
            " text='${node.text}'" +
            " hint='${node.hintText}'"
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logNodeTree(child, depth + 1, maxDepth)
            @Suppress("DEPRECATION")
            child.recycle()
        }
    }
}
