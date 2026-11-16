package com.sangar.gal.guard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.sangar.gal.service.NagLog
import com.sangar.gal.sidekick.BallView
import com.sangar.gal.sidekick.Mood
import com.sangar.gal.sidekick.Prop
import com.sangar.gal.sidekick.SidekickView
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the full-screen pause says. */
data class BlockContent(
    /** "TIME'S UP · YOUTUBE" */
    val label: String,
    /** The one-line roast. */
    val line: String,
    /** "32 min of YouTube today" */
    val detail: String,
    val scene: BlockScene,
    val seconds: Int = GuardRules.BLOCK_SECONDS,
)

/** What the short reminder says when a chosen app is opened. */
data class ReminderContent(val title: String, val detail: String, val stayMillis: Long = GuardRules.REMINDER_MILLIS)

/**
 * GAL's two full-screen moments, drawn over whatever app is open: the reminder when a chosen app is opened, and
 * the pause when the day's time is used up. Plain views in an overlay window, like everything else GAL draws
 * outside its own app.
 *
 * Both run on one frame loop that reads the clock, never on animators: a phone with animations switched off
 * would end an animator at once, and a ten second pause would last no time at all.
 */
internal class GuardScreens(private val context: Context) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var root: View? = null
    private var loop: Runnable? = null

    var blockShowing = false
        private set

    val isShowing: Boolean get() = root != null

    private fun dp(value: Float) = (value * density).roundToInt()

    // ---- The pause ---------------------------------------------------------------------------------------

    /** Shows the pause. [onServed] runs only if it stayed up for its full time. Returns false if it could not be drawn. */
    fun showBlock(content: BlockContent, onServed: () -> Unit): Boolean {
        dismiss()
        val metrics = context.resources.displayMetrics
        val stageSize = min(dp(280f), min((metrics.heightPixels * 0.36f).roundToInt(), (metrics.widthPixels * 0.72f).roundToInt()))

        val ring = RingView(context)
        val elephant = SidekickView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            mood = Mood.NONE
        }
        val ball = BallView(context).apply { visibility = if (content.scene == BlockScene.FOOTBALL) View.VISIBLE else View.GONE }
        val elephantSize = (stageSize * 0.66f).roundToInt()
        val ballSize = (stageSize * 0.15f).roundToInt()
        val stage = FrameLayout(context).apply {
            clipChildren = false
            addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(elephant, FrameLayout.LayoutParams(elephantSize, elephantSize, Gravity.CENTER))
            addView(ball, FrameLayout.LayoutParams(ballSize, ballSize, Gravity.CENTER))
        }

        val seconds = text("${content.seconds}", 44f, PINK_SOFT, heavy = true)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28f), dp(56f), dp(28f), dp(36f))
            addView(text(content.label, 14f, PINK_SOFT, heavy = true).apply { letterSpacing = 0.08f })
            addView(stage, LinearLayout.LayoutParams(stageSize, stageSize).apply { topMargin = dp(28f) })
            addView(text(content.line, 26f, TEXT, heavy = true), matchWidth(top = 28f))
            addView(text(content.detail, 16f, MUTED), matchWidth(top = 10f))
            addView(Space(context), LinearLayout.LayoutParams(0, dp(24f), 1f))
            addView(seconds)
            addView(text("seconds to go", 14f, MUTED))
        }
        val view = frame(column, "${content.label}. ${content.line}")
        if (!attach(view, "GAL pause")) return false
        blockShowing = true

        val totalMillis = content.seconds * 1000L
        val startedAt = SystemClock.uptimeMillis()
        val scene = SceneDriver(content.scene, elephant, ball, elephantSize)
        var lastShown = -1
        loop = object : Runnable {
            override fun run() {
                if (root !== view) return
                val elapsed = SystemClock.uptimeMillis() - startedAt
                if (elapsed >= totalMillis) {
                    dismiss()
                    onServed()
                    return
                }
                ring.remaining = 1f - elapsed.toFloat() / totalMillis
                val left = ceil((totalMillis - elapsed) / 1000.0).toInt()
                if (left != lastShown) {
                    lastShown = left
                    seconds.text = left.toString()
                }
                scene.pose(elapsed / 1000f)
                view.postOnAnimation(this)
            }
        }.also { view.postOnAnimation(it) }
        return true
    }

    // ---- The reminder ------------------------------------------------------------------------------------

    /** Shows the reminder. It leaves by itself, or when "I promise" is tapped. */
    fun showReminder(content: ReminderContent): Boolean {
        dismiss()
        val elephant = SidekickView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            mood = Mood.NONE
        }
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CIRCLE)
            }
            addView(elephant, FrameLayout.LayoutParams(dp(124f), dp(124f), Gravity.CENTER))
        }
        val bar = BarView(context)
        val promise = text("I promise", 18f, ON_PINK, heavy = true).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(29f).toFloat()
                setColor(PINK)
            }
            minHeight = dp(58f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28f), dp(40f), dp(28f), dp(40f))
            addView(circle, LinearLayout.LayoutParams(dp(156f), dp(156f)))
            addView(text("REMINDER", 14f, PINK_SOFT, heavy = true).apply { letterSpacing = 0.08f }, matchWidth(top = 28f))
            addView(text(content.title, 28f, TEXT, heavy = true), matchWidth(top = 10f))
            addView(text(content.detail, 17f, MUTED), matchWidth(top = 10f))
            addView(promise, matchWidth(top = 28f))
            addView(bar, LinearLayout.LayoutParams(dp(120f), dp(4f)).apply { topMargin = dp(24f) })
        }
        val view = frame(column, "Reminder. ${content.title}. ${content.detail}")
        if (!attach(view, "GAL reminder")) return false

        val startedAt = SystemClock.uptimeMillis()
        var hopped = false
        loop = object : Runnable {
            override fun run() {
                if (root !== view) return
                val elapsed = SystemClock.uptimeMillis() - startedAt
                if (elapsed >= content.stayMillis) {
                    dismiss()
                    return
                }
                if (!hopped && elapsed > 250L) {
                    hopped = true
                    elephant.hop()
                }
                bar.filled = elapsed.toFloat() / content.stayMillis
                view.postOnAnimation(this)
            }
        }.also { view.postOnAnimation(it) }
        return true
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        loop = null
        blockShowing = false
        runCatching { windowManager?.removeView(view) }
    }

    // ---- Plumbing ----------------------------------------------------------------------------------------

    /** The dark full-screen ground. It takes every touch and the back key, so nothing reaches the app beneath. */
    private fun frame(content: View, spoken: String): View {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return GuardRoot(context).apply {
            setBackgroundColor(GROUND)
            isClickable = true
            isFocusableInTouchMode = true
            contentDescription = spoken
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Drawn edge to edge, so the content keeps clear of the status bar, the notch and the gesture bar.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
    }

    private fun attach(view: View, title: String): Boolean {
        val manager = windowManager ?: return false
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable on purpose: the window takes the back key, so "back" cannot dig under the pause.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.title = title
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            manager.addView(view, params)
            root = view
            view.requestFocus()
            view.announceForAccessibility(view.contentDescription)
            true
        } catch (e: RuntimeException) {
            // "Display over other apps" was switched off, or the window token is gone.
            NagLog.e("Guard", "could not show $title", e)
            false
        }
    }

    private fun text(value: String, sizeSp: Float, color: Int, heavy: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER
        typeface = if (heavy) Typeface.create(Typeface.DEFAULT, 800, false) else Typeface.DEFAULT
        setLineSpacing(0f, 1.15f)
    }

    private fun matchWidth(top: Float) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private class GuardRoot(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            if (event.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(event)
    }

    /** The countdown ring: a full circle that empties as the pause runs out. */
    private class RingView(context: Context) : View(context) {
        var remaining = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = TRACK
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = PINK
        }
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            val stroke = width * 0.036f
            track.strokeWidth = stroke
            arc.strokeWidth = stroke
            box.set(stroke, stroke, width - stroke, height - stroke)
            canvas.drawOval(box, track)
            if (remaining > 0f) canvas.drawArc(box, -90f, 360f * remaining, false, arc)
        }
    }

    /** The thin bar under the reminder that shows it is about to leave. */
    private class BarView(context: Context) : View(context) {
        var filled = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PINK_SOFT }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
            canvas.drawRoundRect(0f, 0f, width * filled, height.toFloat(), r, r, fill)
        }
    }

    /** Moves the elephant (and the ball) through one scene. [pose] is called every frame with the seconds so far. */
    private class SceneDriver(
        private val scene: BlockScene,
        private val elephant: SidekickView,
        private val ball: BallView,
        private val elephantSize: Int,
    ) {
        private var started = false
        private var asleep = false

        fun pose(seconds: Float) {
            if (!started) {
                started = true
                start()
            }
            when (scene) {
                BlockScene.SKIP -> {
                    val turn = (seconds / 0.56f) % 1f
                    elephant.propPhase = turn
                    // The rope passes under her feet half way through a turn: that is when she is in the air.
                    elephant.jump = cos((turn - 0.5f) * 2f * PI.toFloat()).coerceAtLeast(0f) * 0.55f
                }
                BlockScene.FOOTBALL -> {
                    // Keepy-uppies: the ball goes up off her head and comes back down, and she bobs under it.
                    val u = (seconds / 0.9f) % 1f
                    val height = 4f * u * (1f - u)
                    ball.translationX = elephantSize * 0.20f
                    ball.translationY = -elephantSize * (0.30f + 0.28f * height)
                    ball.spin = seconds * 5f
                    elephant.jump = (1f - u / 0.18f).coerceAtLeast(0f) * 0.22f
                    elephant.trunkBoost = 40f + 50f * height
                }
                BlockScene.READ -> elephant.headTilt = 2.5f * sin(seconds * 2f * PI.toFloat() * 0.4f)
                BlockScene.STRETCH -> {
                    val t = (seconds % 5f) / 5f
                    // Up over the first fifth, hold, down over the last fifth.
                    val up = when {
                        t < 0.2f -> t / 0.2f
                        t > 0.8f -> (1f - t) / 0.2f
                        else -> 1f
                    }
                    elephant.stretch = up
                    elephant.trunkBoost = 150f * up
                    elephant.headTilt = if (t in 0.3f..0.7f) 5f * sin((t - 0.3f) / 0.4f * 2f * PI.toFloat() * 2f) else 0f
                }
                BlockScene.SWEEP -> elephant.propPhase = (seconds / 0.62f) % 1f
                BlockScene.BED -> if (!asleep && seconds > 2.4f) {
                    asleep = true
                    elephant.napping = true
                    elephant.nightcap = true
                }
            }
        }

        private fun start() {
            when (scene) {
                BlockScene.SKIP -> {
                    elephant.prop = Prop.ROPE
                    elephant.trunkBoost = 70f
                }
                BlockScene.READ -> {
                    elephant.prop = Prop.BOOK
                    elephant.trunkBoost = 50f
                    elephant.lookAt(0.5f, 0.9f)
                }
                BlockScene.SWEEP -> {
                    elephant.prop = Prop.BROOM
                    elephant.trunkBoost = 55f
                }
                BlockScene.BED -> elephant.yawnNow()
                BlockScene.FOOTBALL, BlockScene.STRETCH -> Unit
            }
        }
    }

    private companion object {
        const val GROUND = 0xFF0D0A10.toInt()
        const val CIRCLE = 0xFF2A1C2C.toInt()
        const val TRACK = 0xFF2A2230.toInt()
        const val TEXT = 0xFFF7EEF3.toInt()
        const val MUTED = 0xFFB9A9B4.toInt()
        const val PINK = 0xFFFF6B9A.toInt()
        const val PINK_SOFT = 0xFFFF8FB1.toInt()
        const val ON_PINK = 0xFF1A0B12.toInt()
    }
}
