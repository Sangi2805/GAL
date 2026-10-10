package com.sangar.gal.sidekick

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The round "X" that appears at the bottom of the screen while the elephant is being dragged, like the close
 * target for chat bubbles. Its own little overlay window that never takes touches, so the drag keeps going.
 */
internal class DismissTarget(private val context: Context, private val windowManager: WindowManager) {

    private val density = context.resources.displayMetrics.density
    private val sizePx = (TARGET_DP * density).roundToInt()
    private var view: TargetView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Screen-space centre of the target, set when it is shown. */
    private var centerX = 0f
    private var centerY = 0f
    private val location = IntArray(2)

    fun show(bounds: Rect) {
        if (view != null) return
        val v = TargetView(context)
        val p = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.centerX() - sizePx / 2
            y = bounds.bottom - sizePx - (BOTTOM_MARGIN_DP * density).roundToInt()
            title = "GAL hide target"
        }
        val added = runCatching { windowManager.addView(v, p) }.isSuccess
        if (!added) return
        view = v
        params = p
        centerX = p.x + sizePx / 2f
        centerY = p.y + sizePx / 2f
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(150L).start()
    }

    /** Lights the target up when [x], [y] (the finger, in screen coordinates) is over it. Returns whether it is. */
    fun track(x: Float, y: Float): Boolean {
        val v = view ?: return false
        if (v.width > 0) {
            // Where it really landed on screen, whatever the system bars did to the window's position.
            v.getLocationOnScreen(location)
            centerX = location[0] + v.width / 2f
            centerY = location[1] + v.height / 2f
        }
        val over = hypot(x - centerX, y - centerY) < sizePx * CATCH_RADIUS
        v.setActive(over)
        return over
    }

    fun hide() {
        val v = view ?: return
        view = null
        params = null
        runCatching { windowManager.removeView(v) }
    }

    private class TargetView(context: Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2B2B3A.toInt() }
        private val ringActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5484D.toInt() }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var active = false
        private var grow = 0f
        private var growAnimator: ValueAnimator? = null

        fun setActive(on: Boolean) {
            if (on == active) return
            active = on
            if (on) performHapticFeedback(HAPTIC_TICK)
            growAnimator?.cancel()
            growAnimator = ValueAnimator.ofFloat(grow, if (on) 1f else 0f).apply {
                duration = 120L
                addUpdateListener {
                    grow = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val r = c * (0.72f + 0.26f * grow)
            canvas.drawCircle(c, c, r, if (active) ringActive else ring)
            cross.strokeWidth = r * 0.14f
            val arm = r * 0.36f
            canvas.drawLine(c - arm, c - arm, c + arm, c + arm, cross)
            canvas.drawLine(c + arm, c - arm, c - arm, c + arm, cross)
        }

        private companion object {
            // HapticFeedbackConstants.CLOCK_TICK
            const val HAPTIC_TICK = 4
        }
    }

    private companion object {
        const val TARGET_DP = 76f
        const val BOTTOM_MARGIN_DP = 40f

        /** A forgiving catch area, a little bigger than the circle. */
        const val CATCH_RADIUS = 0.9f
    }
}

/**
 * The small card that asks how long to hide her for. Built from plain views because it lives in an overlay
 * window, outside any activity or Compose host.
 */
internal class HidePanel(private val context: Context, private val windowManager: WindowManager) {

    private val density = context.resources.displayMetrics.density
    private var root: View? = null

    val isShowing: Boolean get() = root != null

    fun show(onChoice: (HideFor?) -> Unit) {
        if (root != null) return
        fun dp(v: Float) = (v * density).roundToInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(0xFFFFF7FB.toInt())
                setStroke(dp(2f), 0xFFFF5C8A.toInt())
            }
            elevation = dp(8f).toFloat()
        }
        card.addView(
            TextView(context).apply {
                text = "Hide the elephant?"
                textSize = 20f
                setTextColor(0xFF3A1530.toInt())
                paint.isFakeBoldText = true
            },
        )
        card.addView(
            TextView(context).apply {
                text = "Roast cards keep coming either way."
                textSize = 14f
                setTextColor(0xFF6A3A5A.toInt())
                setPadding(0, dp(4f), 0, dp(10f))
            },
        )
        for (choice in HideFor.entries) {
            card.addView(button(choice.label, filled = choice != HideFor.TURN_OFF, ::dp) { finish(onChoice, choice) })
        }
        card.addView(button("Cancel", filled = false, ::dp, quiet = true) { finish(onChoice, null) })

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            width = minOf(dp(320f), context.resources.displayMetrics.widthPixels - dp(48f))
            title = "GAL hide choices"
        }
        if (runCatching { windowManager.addView(card, p) }.isSuccess) {
            root = card
            card.alpha = 0f
            card.scaleX = 0.92f
            card.scaleY = 0.92f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
        } else {
            onChoice(null)
        }
    }

    fun dismiss() {
        val v = root ?: return
        root = null
        runCatching { windowManager.removeView(v) }
    }

    private fun finish(onChoice: (HideFor?) -> Unit, choice: HideFor?) {
        dismiss()
        onChoice(choice)
    }

    private fun button(
        label: String,
        filled: Boolean,
        dp: (Float) -> Int,
        quiet: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minHeight = dp(46f)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        paint.isFakeBoldText = !quiet
        when {
            quiet -> setTextColor(0xFF6A3A5A.toInt())
            filled -> {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(23f).toFloat()
                    setColor(0xFFFF5C8A.toInt())
                }
            }
            else -> {
                setTextColor(0xFFB3261E.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(23f).toFloat()
                    setStroke(dp(2f), 0xFFB3261E.toInt())
                }
            }
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8f) }
        setOnClickListener { onClick() }
    }
}
