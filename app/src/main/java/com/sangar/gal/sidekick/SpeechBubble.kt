package com.sangar.gal.sidekick

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.sangar.gal.overlay.Mascot
import kotlin.math.roundToInt

/** What GAL is asked to say. A roast has buttons and stays longer; an activity line is just a quick remark. */
data class Speech(
    val text: String,
    /** Small grey line above the text, for example "GAL · 30 min on screen". */
    val label: String? = null,
    /** The face she wears while she says it. */
    val face: Mascot? = null,
    val onOk: (() -> Unit)? = null,
    val onSnooze: (() -> Unit)? = null,
    val stayMillis: Long = QUICK_MILLIS,
) {
    val isRoast: Boolean get() = onOk != null || onSnooze != null

    companion object {
        const val QUICK_MILLIS = 3_500L
        const val ROAST_MILLIS = 12_000L
    }
}

/**
 * The way for the rest of the app to make GAL speak. The service's roast timer calls [say]; if GAL is on screen
 * she says the line in a bubble, and the caller falls back to the old card when this returns false (she is
 * hidden, switched off or stepped out). Main thread only; same process as the service.
 */
object GalVoice {
    internal var host: ((Speech) -> Boolean)? = null
    internal var hush: ((String) -> Unit)? = null

    fun say(speech: Speech): Boolean = host?.invoke(speech) ?: false

    /** Takes a roast bubble down, for example when the screen goes off. */
    fun hush(reason: String) {
        hush?.invoke(reason)
    }
}

/**
 * The speech bubble: a small rounded card in its own overlay window that sits above GAL (below her when she is
 * near the top) and follows her as she moves. Plain views, because it lives outside any activity.
 */
internal class SpeechBubble(private val context: Context, private val windowManager: WindowManager) {

    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var onGone: (() -> Unit)? = null
    private val timeout = Runnable { dismiss() }

    val isShowing: Boolean get() = root != null
    var isRoast = false
        private set

    private fun dp(v: Float) = (v * density).roundToInt()

    /** [anchor] is GAL's window on screen; [bounds] is where windows may go. [gone] runs once when it leaves. */
    fun show(speech: Speech, anchor: Rect, bounds: Rect, gone: () -> Unit): Boolean {
        dismiss()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(0xFFFFF7FB.toInt())
                setStroke(dp(2f), 0xFF9C3D6E.toInt())
            }
            elevation = dp(6f).toFloat()
        }
        speech.label?.let {
            card.addView(
                TextView(context).apply {
                    text = it
                    textSize = 11f
                    setTextColor(0xFF8A5A78.toInt())
                    paint.isFakeBoldText = true
                },
            )
        }
        card.addView(
            TextView(context).apply {
                text = speech.text
                textSize = if (speech.text.length > 60) 15f else 16f
                setTextColor(0xFF3A1530.toInt())
                setLineSpacing(0f, 1.1f)
            },
        )
        if (speech.isRoast) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(6f), 0, 0)
            }
            speech.onSnooze?.let { snooze ->
                row.addView(action("Snooze 10 min", filled = false) {
                    snooze()
                    dismiss()
                })
            }
            row.addView(action("OK", filled = true) {
                speech.onOk?.invoke()
                dismiss()
            })
            card.addView(row)
        } else {
            // A quick remark goes away on a tap.
            card.setOnClickListener { dismiss() }
        }

        val maxWidth = minOf(dp(280f), bounds.width() - dp(24f))
        card.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val p = WindowManager.LayoutParams(
            card.measuredWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "GAL speech bubble"
        }
        place(p, card.measuredWidth, card.measuredHeight, anchor, bounds)
        if (runCatching { windowManager.addView(card, p) }.isFailure) return false
        root = card
        params = p
        size = intArrayOf(card.measuredWidth, card.measuredHeight)
        onGone = gone
        isRoast = speech.isRoast
        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
        handler.postDelayed(timeout, speech.stayMillis)
        return true
    }

    private var size = intArrayOf(0, 0)

    /** Keeps the bubble with her while she walks. */
    fun follow(anchor: Rect, bounds: Rect) {
        val v = root ?: return
        val p = params ?: return
        place(p, size[0], size[1], anchor, bounds)
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    private fun place(p: WindowManager.LayoutParams, w: Int, h: Int, anchor: Rect, bounds: Rect) {
        val gap = dp(2f)
        p.x = (anchor.centerX() - w / 2).coerceIn(bounds.left + dp(8f), maxOf(bounds.left + dp(8f), bounds.right - w - dp(8f)))
        val above = anchor.top - h - gap
        p.y = if (above >= bounds.top + dp(8f)) above else minOf(anchor.bottom + gap, bounds.bottom - h)
    }

    fun dismiss() {
        handler.removeCallbacks(timeout)
        val v = root ?: return
        root = null
        params = null
        isRoast = false
        runCatching { windowManager.removeView(v) }
        val done = onGone
        onGone = null
        done?.invoke()
    }

    private fun action(label: String, filled: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        isClickable = true
        minHeight = dp(36f)
        setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        paint.isFakeBoldText = true
        if (filled) {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(0xFFFF5C8A.toInt())
            }
        } else {
            setTextColor(0xFF9C3D6E.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(6f) }
        setOnClickListener { onClick() }
    }
}
