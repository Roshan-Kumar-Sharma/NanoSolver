package com.nanosolver.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.nanosolver.pipeline.RegionConfig

/**
 * RegionSelectorOverlay — Plan 2 Phase 3
 *
 * Draws a full-screen overlay over the Matiks app so the user can visually drag
 * a rectangle to define exactly which portion of the screen the OCR pipeline
 * should crop to. Eliminates the hardcoded 25–55% vertical crop fractions.
 *
 * LAYOUT (all added to WindowManager as separate TYPE_APPLICATION_OVERLAY views):
 *
 *   1. Semi-transparent backdrop  — 40% black, covers the full screen.
 *   2. Four edge line views        — 2px cyan lines indicating crop boundaries.
 *   3. Four corner handle views    — 44×44dp white circles, draggable.
 *   4. "✓ Done" + "✗ Cancel" buttons — anchored at the bottom of the screen.
 *
 * DRAG LOGIC:
 *   Each corner handle converts its rawX/rawY touch position to screen fractions,
 *   clamps them to a valid range (preventing overlapping or zero-area regions),
 *   and calls updateEdgeLines() to reposition the four edge views to match.
 *
 * USAGE:
 *   val overlay = RegionSelectorOverlay(
 *       context, windowManager, screenWidth, screenHeight,
 *       initial   = RegionConfig.load(context),
 *       onConfirm = { config -> applyNewRegion(config) },
 *       onCancel  = { exitRegionSelectMode() }
 *   )
 *   overlay.show()
 *   // later (always): overlay.hide()
 */
class RegionSelectorOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val screenWidth: Int,
    private val screenHeight: Int,
    initial: RegionConfig,
    private val onConfirm: (RegionConfig) -> Unit,
    private val onCancel: () -> Unit
) {

    // Current region fractions — mutated as the user drags handles.
    private var topFrac    = initial.topFraction
    private var bottomFrac = initial.bottomFraction
    private var leftFrac   = initial.leftFraction
    private var rightFrac  = initial.rightFraction

    // Minimum region size on each axis (10% of screen, per Plan 2 spec).
    private val minFrac = 0.10f

    // All views managed by this overlay — tracked for cleanup in hide().
    private val views = mutableListOf<View>()

    // Edge line views — stored so updateEdgeLines() can reposition them.
    private lateinit var topLine:    View
    private lateinit var bottomLine: View
    private lateinit var leftLine:   View
    private lateinit var rightLine:  View

    // Persistent LayoutParams per line — mutated in place during drag to avoid
    // allocating four new objects per frame (smooth 60fps drag).
    private lateinit var topLineParams:    WindowManager.LayoutParams
    private lateinit var bottomLineParams: WindowManager.LayoutParams
    private lateinit var leftLineParams:   WindowManager.LayoutParams
    private lateinit var rightLineParams:  WindowManager.LayoutParams

    // ── Public API ──────────────────────────────────────────────────────────────

    fun show() {
        addBackdrop()
        addEdgeLines()
        addCornerHandles()
        addActionButtons()
    }

    fun hide() {
        views.forEach { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        views.clear()
    }

    // ── Backdrop ────────────────────────────────────────────────────────────────

    private fun addBackdrop() {
        val backdrop = View(context).apply {
            setBackgroundColor(Color.argb(102, 0, 0, 0))  // 40% black
        }
        val params = fullScreenParams()
        wm.addView(backdrop, params)
        views += backdrop
    }

    // ── Edge lines ──────────────────────────────────────────────────────────────

    private fun addEdgeLines() {
        topLine    = makeHorizontalLine()
        bottomLine = makeHorizontalLine()
        leftLine   = makeVerticalLine()
        rightLine  = makeVerticalLine()

        topLineParams    = lineParams()
        bottomLineParams = lineParams()
        leftLineParams   = lineParams()
        rightLineParams  = lineParams()

        wm.addView(topLine,    topLineParams);    views += topLine
        wm.addView(bottomLine, bottomLineParams); views += bottomLine
        wm.addView(leftLine,   leftLineParams);   views += leftLine
        wm.addView(rightLine,  rightLineParams);  views += rightLine

        updateEdgeLines()
    }

    private fun makeHorizontalLine(): View = View(context).apply {
        setBackgroundColor(Color.parseColor("#00E5FF"))  // cyan
    }

    private fun makeVerticalLine(): View = View(context).apply {
        setBackgroundColor(Color.parseColor("#00E5FF"))
    }

    /**
     * Repositions the four edge lines to match the current fraction state.
     * Called after every drag event on a corner handle.
     *
     * Mutates persistent LayoutParams in place rather than allocating new
     * instances — at 60fps drag with 4 lines that's 240 saved allocations/sec.
     */
    private fun updateEdgeLines() {
        val lineThickness = dpToPx(2)

        val topPx    = (topFrac    * screenHeight).toInt()
        val bottomPx = (bottomFrac * screenHeight).toInt()
        val leftPx   = (leftFrac   * screenWidth).toInt()
        val rightPx  = (rightFrac  * screenWidth).toInt()
        val rectW    = rightPx - leftPx
        val rectH    = bottomPx - topPx

        applyLineLayout(topLine,    topLineParams,    leftPx,  topPx,    rectW,         lineThickness)
        applyLineLayout(bottomLine, bottomLineParams, leftPx,  bottomPx, rectW,         lineThickness)
        applyLineLayout(leftLine,   leftLineParams,   leftPx,  topPx,    lineThickness, rectH)
        applyLineLayout(rightLine,  rightLineParams,  rightPx, topPx,    lineThickness, rectH)
    }

    private fun applyLineLayout(
        view: View, params: WindowManager.LayoutParams,
        x: Int, y: Int, w: Int, h: Int
    ) {
        params.x = x; params.y = y; params.width = w; params.height = h
        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    // ── Corner handles ──────────────────────────────────────────────────────────

    private fun addCornerHandles() {
        // Four corners: which fraction pair each handle controls.
        // Each lambda receives (newXFrac, newYFrac) and updates the fields.
        data class Corner(val label: String, val update: (Float, Float) -> Unit)

        val corners = listOf(
            Corner("TL") { xf, yf ->
                leftFrac = xf.coerceIn(0.02f, rightFrac  - minFrac)
                topFrac  = yf.coerceIn(0.02f, bottomFrac - minFrac)
            },
            Corner("TR") { xf, yf ->
                rightFrac = xf.coerceIn(leftFrac  + minFrac, 0.98f)
                topFrac   = yf.coerceIn(0.02f,     bottomFrac - minFrac)
            },
            Corner("BL") { xf, yf ->
                leftFrac   = xf.coerceIn(0.02f,  rightFrac  - minFrac)
                bottomFrac = yf.coerceIn(topFrac + minFrac, 0.98f)
            },
            Corner("BR") { xf, yf ->
                rightFrac  = xf.coerceIn(leftFrac + minFrac, 0.98f)
                bottomFrac = yf.coerceIn(topFrac  + minFrac, 0.98f)
            }
        )

        corners.forEach { corner ->
            val handle = makeHandle()
            val params = handleParams()
            positionHandle(params, corner.label)

            // Offset between finger touch point and the corner anchor on ACTION_DOWN.
            // Keeping this offset stable during ACTION_MOVE prevents the "jump-to-finger"
            // effect: if the user grabs the edge of the handle, the corner stays where
            // they grabbed it instead of snapping to be centered under the finger.
            var grabOffsetX = 0f
            var grabOffsetY = 0f

            handle.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Anchor in screen pixels at touch start.
                        val anchorX = cornerAnchorX(corner.label).toFloat()
                        val anchorY = cornerAnchorY(corner.label).toFloat()
                        grabOffsetX = event.rawX - anchorX
                        grabOffsetY = event.rawY - anchorY
                        // CRITICAL: must return true so subsequent ACTION_MOVE / UP
                        // events in this gesture are delivered to this listener.
                        // Returning false here was the reason drag was completely broken.
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val targetX = event.rawX - grabOffsetX
                        val targetY = event.rawY - grabOffsetY
                        val xFrac = (targetX / screenWidth).coerceIn(0f, 1f)
                        val yFrac = (targetY / screenHeight).coerceIn(0f, 1f)
                        corner.update(xFrac, yFrac)
                        positionHandle(params, corner.label)
                        try { wm.updateViewLayout(handle, params) } catch (_: Exception) {}
                        updateEdgeLines()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
            wm.addView(handle, params)
            views += handle
        }
    }

    /** Current X pixel for [label]'s corner anchor (the point the handle is centered on). */
    private fun cornerAnchorX(label: String): Int = when (label) {
        "TL", "BL" -> (leftFrac  * screenWidth).toInt()
        "TR", "BR" -> (rightFrac * screenWidth).toInt()
        else -> 0
    }

    /** Current Y pixel for [label]'s corner anchor. */
    private fun cornerAnchorY(label: String): Int = when (label) {
        "TL", "TR" -> (topFrac    * screenHeight).toInt()
        "BL", "BR" -> (bottomFrac * screenHeight).toInt()
        else -> 0
    }

    private fun makeHandle(): View {
        val size = dpToPx(44)
        val drawable = ShapeDrawable(OvalShape()).apply {
            paint.color = Color.WHITE
        }
        return View(context).apply {
            background = drawable
            minimumWidth  = size
            minimumHeight = size
        }
    }

    /** Positions [params] to the correct corner based on the current fractions. */
    private fun positionHandle(params: WindowManager.LayoutParams, corner: String) {
        val half = dpToPx(44) / 2
        params.x = cornerAnchorX(corner) - half
        params.y = cornerAnchorY(corner) - half
    }

    // ── Action buttons ──────────────────────────────────────────────────────────

    private fun addActionButtons() {
        val doneBtn = Button(context).apply {
            text = "✓ Done"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12))
            setOnClickListener {
                onConfirm(RegionConfig(topFrac, bottomFrac, leftFrac, rightFrac))
            }
        }
        val cancelBtn = Button(context).apply {
            text = "✗ Cancel"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12))
            setOnClickListener { onCancel() }
        }

        // Done on left, Cancel on right — both anchored near the bottom of the screen.
        val bottomY = screenHeight - dpToPx(80)

        val doneParams = buttonParams().apply {
            x = dpToPx(40); y = bottomY
        }
        val cancelParams = buttonParams().apply {
            x = screenWidth / 2 + dpToPx(8); y = bottomY
        }

        wm.addView(doneBtn,   doneParams)
        wm.addView(cancelBtn, cancelParams)
        views += doneBtn
        views += cancelBtn
    }

    // ── WindowManager.LayoutParams factories ────────────────────────────────────

    private fun fullScreenParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            screenWidth, screenHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

    private fun lineParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            1, 1,  // actual size set in updateEdgeLines()
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun handleParams(): WindowManager.LayoutParams {
        val size = dpToPx(44)
        return WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun buttonParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    // ── Utility ─────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()
}
