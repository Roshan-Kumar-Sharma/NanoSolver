package com.nanosolver.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * NanoAccessibilityService — Phase 7
 *
 * Phase 7 adds NODE REFERENCE CACHING to cut inject latency from ~5–15ms
 * to ~1–3ms after the first problem in a session.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * WHY NODE CACHING MATTERS
 *
 * The Phase 5 implementation called rootInActiveWindow → tree traversal on
 * every single inject. For Matiks Sprint mode (30–60 problems/minute), this
 * means 30–60 full DFS traversals of the UI tree per minute. Each traversal
 * takes 3–12ms depending on tree depth.
 *
 * In Sprint mode, the answer input and submit button are the SAME physical
 * views for the entire game session — Matiks reuses them and just changes
 * the question text. So once we've located these nodes, we can hold the
 * reference and reuse it directly, skipping the traversal.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * CACHE INVALIDATION
 *
 * The cache is invalidated in two cases:
 *
 *   1. TYPE_WINDOW_STATE_CHANGED — the user navigated away from the game
 *      (e.g. went to the home screen or a different Activity). The cached
 *      nodes would belong to the old window and would fail on access.
 *
 *   2. node.refresh() returns false — the cached node's underlying view was
 *      detached from the window (e.g. Matiks recreated the layout). The
 *      refresh() call is cheap (one Binder round-trip) and is the documented
 *      way to check node liveness without re-traversing the tree.
 *
 * On invalidation we fall back to the slow path (full tree traversal) and
 * re-populate the cache with the newly found nodes.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * THREAD SAFETY
 *
 * onAccessibilityEvent() fires on the service's main looper thread.
 * inject() is called from SolverPipeline running on Dispatchers.Default.
 *
 * Both paths access [cachedInputNode] and [cachedSubmitNode]. We protect
 * them with [nodeCacheLock] using synchronized{} blocks. The lock is held
 * only for the brief moment of cache read/write — never during the Binder
 * IPC calls (performAction, refresh) so contention is negligible.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * DEBUGGING TIPS
 *
 * Fast path vs slow path: adb logcat -s NanoA11y:D
 *   "fast path" = cached node reused (target: >95% of injects after problem 1)
 *   "slow path" = full tree search (expected only on session start + window changes)
 *
 * If you see "slow path" on every inject, the cache is being invalidated too
 * aggressively. Check whether Matiks fires TYPE_WINDOW_STATE_CHANGED between
 * problems (it shouldn't in Sprint mode — only in menu navigation).
 *
 * Node tree dump: adb logcat -s NanoA11y:V
 * UI XML dump:   adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
 */
class NanoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NanoA11y"

        /**
         * Action ID for pressing the IME action button (Enter / Submit / Go on the soft keyboard).
         *
         * AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER was added to the platform
         * in API 30. For API 27–29 (our minSdk), the AndroidX compat backport maps this action
         * to the value 0x01000000. Using the literal avoids a compat API that doesn't resolve
         * cleanly as an Int constant across all supported API levels.
         */
        private const val ACTION_IME_ENTER = 0x01000000

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
         * Clears the cached input/submit node references.
         *
         * Called by SolverPipeline.reset() when the user taps the Reset button
         * between questions. Forces the next inject to re-discover nodes via the
         * slow path, ensuring stale references from the previous question are dropped.
         */
        fun resetCache() {
            instance?.invalidateNodeCache()
        }

        /**
         * Called by SolverPipeline when a new answer is computed.
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
    // Node cache
    // -------------------------------------------------------------------------

    /**
     * Lock protecting [cachedInputNode] and [cachedSubmitNode].
     * Held only for cache reads/writes, never across Binder IPC calls.
     */
    private val nodeCacheLock = Any()

    /**
     * Cached reference to the answer input node.
     *
     * After the first successful inject, we hold this reference (without recycling
     * it) so future injects can skip the tree traversal. Invalidated on:
     *   - TYPE_WINDOW_STATE_CHANGED (window navigation)
     *   - node.refresh() returning false (view was detached)
     *   - A failed inject attempt (safety reset)
     */
    private var cachedInputNode: AccessibilityNodeInfo?  = null

    /**
     * Cached reference to the submit button node.
     * Same lifecycle as [cachedInputNode].
     */
    private var cachedSubmitNode: AccessibilityNodeInfo? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected — ready to inject")
    }

    /**
     * Handles accessibility events.
     *
     * TYPE_WINDOW_STATE_CHANGED fires when the user navigates to a different
     * Activity/Dialog. In Sprint mode this fires when:
     *   - The user opens or closes the game
     *   - Matiks shows an interstitial (round end, score screen)
     *   - The user presses Back / Home
     *
     * It does NOT fire between individual problems within the same round —
     * that's why caching is safe across the full Sprint session.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        Log.v(TAG, "Event: type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
            "pkg=${event.packageName}")

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            invalidateNodeCache()
            Log.d(TAG, "Node cache invalidated (window state changed pkg=${event.packageName})")
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        invalidateNodeCache()
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
    }

    // -------------------------------------------------------------------------
    // Injection — public entry point
    // -------------------------------------------------------------------------

    /**
     * Main injection entry point. Attempts the fast path (cached nodes) before
     * falling back to the slow path (full tree traversal).
     *
     * Note: rootInActiveWindow is only fetched on the slow path, saving a
     * Binder call on fast-path hits.
     */
    private fun inject(answer: Long): Boolean {
        // ── Fast path: try cached nodes without fetching the root ─────────────
        val (cachedInput, cachedSubmit) = synchronized(nodeCacheLock) {
            Pair(cachedInputNode, cachedSubmitNode)
        }

        if (cachedInput != null && cachedInput.refresh() &&
            cachedInput.isVisibleToUser && cachedInput.isEnabled) {

            Log.d(TAG, "inject($answer): fast path (cached node)")
            val ok = performInjection(cachedInput, cachedSubmit, answer)
            if (ok) return true

            // The cached node was alive (refresh() returned true) but the inject
            // action was rejected. Reset cache and fall through to re-discover.
            Log.w(TAG, "inject: cached node rejected — cache reset, retrying with tree search")
            invalidateNodeCache()
        }

        // ── Slow path: fetch root and traverse ────────────────────────────────
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "inject: rootInActiveWindow is null — is Matiks in the foreground?")
            return false
        }
        return try {
            slowPathInject(root, answer)
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // Injection helpers
    // -------------------------------------------------------------------------

    /**
     * Fast-path injection using pre-validated cached nodes.
     *
     * Does NOT recycle [inputNode] or [submitNode] — they are owned by the
     * cache and must remain valid for future fast-path injects.
     */
    private fun performInjection(
        inputNode: AccessibilityNodeInfo,
        submitNode: AccessibilityNodeInfo?,
        answer: Long
    ): Boolean {
        val textSet = setAnswerText(inputNode, answer)
        if (!textSet) return false

        if (submitNode != null && submitNode.refresh() && submitNode.isEnabled) {
            val clicked = submitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                Log.d(TAG, "inject: submit clicked (cached button)")
                return true
            }
        }
        // Submit node is stale or missing — fall back to IME action on the input
        return inputNode.performAction(ACTION_IME_ENTER)
    }

    /**
     * Slow-path injection: searches the tree, injects, then caches discovered nodes.
     *
     * Injection strategy (in order):
     *   1. Find an editable input field → ACTION_SET_TEXT / clipboard paste.
     *   2. Find a digit keypad (0–9 clickable buttons) → tap digits in sequence.
     */
    private fun slowPathInject(root: AccessibilityNodeInfo, answer: Long): Boolean {
        Log.d(TAG, "inject: slow path (tree search)")

        val inputNode = findAnswerInput(root)
        if (inputNode != null) {
            var submitNode: AccessibilityNodeInfo? = null
            return try {
                val textSet = setAnswerText(inputNode, answer)
                if (!textSet) return false

                submitNode = findSubmitNode(root)
                val submitted = if (submitNode != null) {
                    val clicked = submitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "inject: submit clicked (slow path): $clicked")
                    clicked
                } else {
                    Log.d(TAG, "inject: no submit button found — pressing IME action")
                    inputNode.performAction(ACTION_IME_ENTER)
                }

                if (submitted) {
                    synchronized(nodeCacheLock) {
                        clearCachedNodesLocked()
                        @Suppress("DEPRECATION")
                        cachedInputNode  = AccessibilityNodeInfo.obtain(inputNode)
                        @Suppress("DEPRECATION")
                        cachedSubmitNode = submitNode?.let { AccessibilityNodeInfo.obtain(it) }
                    }
                    Log.d(TAG, "inject: nodes cached for future fast-path use")
                }
                submitted
            } finally {
                @Suppress("DEPRECATION") inputNode.recycle()
                @Suppress("DEPRECATION") submitNode?.recycle()
            }
        }

        // No editable input found — try the on-screen digit keypad (Matiks Sprint mode).
        val digitMap = findDigitKeypad(root)
        if (digitMap != null) {
            Log.d(TAG, "inject: digit keypad found (${digitMap.size} buttons)")
            return try {
                injectViaDigitButtons(digitMap, answer, root)
            } finally {
                digitMap.values.forEach { @Suppress("DEPRECATION") it.recycle() }
            }
        }

        Log.w(TAG, "inject: no input field or digit keypad found — enable VERBOSE for tree dump")
        logNodeTree(root, label = "WINDOW TREE")
        return false
    }

    // -------------------------------------------------------------------------
    // Digit keypad injection
    // -------------------------------------------------------------------------

    /**
     * Searches [root] for 10 distinct single-digit clickable buttons (0–9).
     *
     * Returns a map from '0'..'9' to the corresponding node if all 10 are found,
     * or null if the keypad is not present. The returned nodes are new copies
     * (via obtain) — the caller MUST recycle them.
     */
    private fun findDigitKeypad(root: AccessibilityNodeInfo): Map<Char, AccessibilityNodeInfo>? {
        val map = mutableMapOf<Char, AccessibilityNodeInfo>()
        collectDigitButtons(root, map)

        if (map.size < 10) {
            // Not a full keypad — recycle partial results and signal absence.
            map.values.forEach { @Suppress("DEPRECATION") it.recycle() }
            return null
        }
        return map
    }

    /** DFS that populates [map] with '0'..'9' → node entries for clickable digit buttons. */
    private fun collectDigitButtons(
        node: AccessibilityNodeInfo,
        map: MutableMap<Char, AccessibilityNodeInfo>
    ) {
        if (node.isClickable) {
            val label = (node.text?.toString()?.trim()
                ?: node.contentDescription?.toString()?.trim())
            if (label?.length == 1 && label[0].isDigit() && !map.containsKey(label[0])) {
                @Suppress("DEPRECATION")
                map[label[0]] = AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectDigitButtons(child, map)
            @Suppress("DEPRECATION") child.recycle()
        }
    }

    /**
     * Injects [answer] by clicking the digit buttons in [digitMap] one at a time.
     *
     * Steps:
     *   1. Click the clear button (C/CE/DEL/⌫) if present — prevents appending to a
     *      prior answer.
     *   2. Click each digit of the answer string with a 20ms pause between taps.
     *      20ms is enough for the UI to register the click; too short risks missed taps.
     *   3. Click the submit button.
     *
     * Note: Matiks never shows negative answers, so negative [answer] values should
     * not reach this path — but we guard anyway by returning false for answer < 0.
     */
    private fun injectViaDigitButtons(
        digitMap: Map<Char, AccessibilityNodeInfo>,
        answer: Long,
        root: AccessibilityNodeInfo
    ): Boolean {
        if (answer < 0) {
            Log.w(TAG, "injectViaDigitButtons: negative answer ($answer) — skipping")
            return false
        }

        // Step 1: Clear any prior input.
        val clearNode = findClearButton(root)
        if (clearNode != null) {
            clearNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "digit keypad: clear button clicked")
            @Suppress("DEPRECATION") clearNode.recycle()
        }

        // Step 2: Tap each digit.
        for (ch in answer.toString()) {
            val digitNode = digitMap[ch]
            if (digitNode == null) {
                Log.w(TAG, "digit keypad: no button found for '$ch' — aborting")
                return false
            }
            val clicked = digitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "digit keypad: '$ch' clicked=$clicked")
            if (!clicked) return false
            Thread.sleep(20)
        }

        // Step 3: Submit.
        val submitNode = findSubmitNode(root)
        val submitted = if (submitNode != null) {
            val ok = submitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "digit keypad: submit clicked=$ok")
            @Suppress("DEPRECATION") submitNode.recycle()
            ok
        } else {
            Log.d(TAG, "digit keypad: no submit button — answer typed but not submitted")
            true  // digits were entered; submit may be automatic
        }
        return submitted
    }

    /**
     * Finds a clear/delete button near the digit keypad.
     *
     * Returns a new node owned by the caller (must be recycled), or null.
     */
    private fun findClearButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val clearLabels = setOf("C", "CE", "DEL", "⌫", "CLR")
        return findNodeWithPredicate(root) { node ->
            if (!node.isClickable || !node.isEnabled) return@findNodeWithPredicate false
            val label = node.text?.toString()?.trim()
                ?: node.contentDescription?.toString()?.trim()
                ?: return@findNodeWithPredicate false
            label.uppercase() in clearLabels
        }
    }

    // -------------------------------------------------------------------------
    // Text injection
    // -------------------------------------------------------------------------

    /**
     * Sets [answer] as the text of [inputNode] via ACTION_SET_TEXT.
     * Falls back to clipboard paste for WebViews that reject ACTION_SET_TEXT.
     */
    private fun setAnswerText(inputNode: AccessibilityNodeInfo, answer: Long): Boolean {
        val bundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                answer.toString()
            )
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        Log.d(TAG, "ACTION_SET_TEXT '$answer': success=$textSet")

        if (!textSet) {
            Log.w(TAG, "SET_TEXT failed — trying clipboard paste fallback")
            return pasteViaClipboard(inputNode, answer)
        }
        return true
    }

    // -------------------------------------------------------------------------
    // Finding nodes
    // -------------------------------------------------------------------------

    /**
     * Finds the answer input field using three strategies in order of specificity.
     *
     * The returned node is a new copy owned by the caller — MUST be recycled.
     */
    private fun findAnswerInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Strategy 1: Known Matiks view IDs (fastest — O(1) lookup in system node cache)
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
        val editable = findNodeWithPredicate(root) { node ->
            node.isEditable && node.isEnabled && node.isVisibleToUser
        }
        if (editable != null) {
            Log.d(TAG, "Input found by editable predicate: class=${editable.className}")
            return editable
        }

        // Strategy 3: Hint text matching — for inputs with placeholder text
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
     * Finds the submit button using three strategies.
     * Returns a node owned by the caller (must be recycled), or null.
     *
     * Extracted from [clickSubmit] in Phase 5 so the result can be
     * cached separately from the action of clicking.
     */
    private fun findSubmitNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Strategy 1: Known IDs
        val submitIds = listOf("in.matiks:id/submit", "in.matiks:id/submitButton",
                               "in.matiks:id/btn_submit", "in.matiks:id/check")
        for (id in submitIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val node = nodes[0]
                nodes.drop(1).forEach { @Suppress("DEPRECATION") it.recycle() }
                Log.d(TAG, "Submit found by ID: $id")
                return node
            }
        }

        // Strategy 2: Button with common submit label
        for (label in listOf("Submit", "Enter", "Check", "OK", "✓", "→")) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            val btn   = nodes.firstOrNull { it.isClickable && it.isEnabled }
            nodes.forEach { if (it !== btn) @Suppress("DEPRECATION") it.recycle() }
            if (btn != null) {
                Log.d(TAG, "Submit found by label: '$label'")
                return btn
            }
        }

        // Strategy 3: Generic clickable, non-editable node
        val btn = findNodeWithPredicate(root) { node ->
            node.isClickable && node.isEnabled && node.isVisibleToUser && !node.isEditable
        }
        if (btn != null) Log.d(TAG, "Submit found by generic button predicate")
        return btn
    }

    // -------------------------------------------------------------------------
    // Cache management
    // -------------------------------------------------------------------------

    /**
     * Invalidates the cached node references and recycles them.
     *
     * Thread-safe: called from both the main thread (onAccessibilityEvent, onDestroy)
     * and the coroutine thread (after a failed inject).
     */
    private fun invalidateNodeCache() {
        synchronized(nodeCacheLock) {
            clearCachedNodesLocked()
        }
    }

    /** Must be called with [nodeCacheLock] held. */
    private fun clearCachedNodesLocked() {
        @Suppress("DEPRECATION") cachedInputNode?.recycle()
        @Suppress("DEPRECATION") cachedSubmitNode?.recycle()
        cachedInputNode  = null
        cachedSubmitNode = null
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
     *   3. Select all existing text (so paste replaces, not appends).
     *   4. Paste from clipboard via ACTION_PASTE.
     */
    private fun pasteViaClipboard(inputNode: AccessibilityNodeInfo, answer: Long): Boolean {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("nano_answer", answer.toString()))

        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // Select all existing text via ACTION_SET_SELECTION with full range,
        // so paste replaces the content rather than appending.
        // (ACTION_SELECT_ALL is not on AccessibilityNodeInfo; this is the equivalent.)
        val selBundle = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
        }
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selBundle)
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
            @Suppress("DEPRECATION") child.recycle()
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
     */
    private fun logNodeTree(node: AccessibilityNodeInfo, depth: Int = 0,
                            maxDepth: Int = 6, label: String = "") {
        if (depth == 0 && label.isNotEmpty()) Log.v(TAG, "── $label ──")
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth)
        Log.v(TAG,
            "$indent[${node.className?.toString()?.substringAfterLast('.')}]" +
            " id=${node.viewIdResourceName}" +
            " edit=${node.isEditable}" +
            " click=${node.isClickable}" +
            " text='${node.text}'" +
            " hint='${node.hintText}'"
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logNodeTree(child, depth + 1, maxDepth)
            @Suppress("DEPRECATION") child.recycle()
        }
    }
}
