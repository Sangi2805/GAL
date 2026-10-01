package com.sangar.gal.sidekick

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.sangar.gal.R
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What the character is currently doing. Drives which animators run. */
enum class SidekickState { IDLE, LISTENING, SPEAKING, THINKING }

/**
 * The face Sidekick pulls while a roast card is on screen: GetALife's four faces in escalation order, drawn
 * live on the blob. The card's picture (res/drawable/mascot_*.xml, from tools/mascot/generate_blob_faces.py)
 * shows the same four, so keep the two in step. [NONE] is Sidekick's own face.
 */
enum class Mood { NONE, SMUG, BORED, DISAPPOINTED, HORRIFIED }

/**
 * The view cannot move its own window, so the component that owns the
 * WindowManager LayoutParams (SidekickService) supplies these.
 */
interface SidekickHost {
    /** Current top-left of the overlay window, in window coordinates. */
    fun overlayX(): Int

    fun overlayY(): Int

    /** Move the overlay window's top-left corner. */
    fun moveOverlay(x: Int, y: Int)

    /** Rectangle the overlay's top-left corner is allowed to travel in. */
    fun overlayBounds(): Rect

    fun onSidekickTapped()
}

/**
 * The character itself: a plain custom View drawing to Canvas.
 *
 * Deliberately not a ComposeView — an overlay window has no
 * ViewTreeLifecycleOwner / SavedStateRegistryOwner, and bolting those on for a
 * blob with three animators is not worth the plumbing.
 */
class SidekickView(context: Context) : View(context) {

    var host: SidekickHost? = null

    var state: SidekickState = SidekickState.IDLE
        set(value) {
            if (field == value) return
            field = value
            onStateChanged(value)
        }

    var mood: Mood = Mood.NONE
        set(value) {
            if (field == value) return
            field = value
            onMoodChanged(value)
        }

    // ---- Drawing helpers ----------------------------------------------------

    private val painter = BlobPainter()
    private val pose = BlobPose()

    // ---- Animated state ---------------------------------------------------

    /** 0..1 sine phase of the breathing cycle. */
    private var breathe = 0f

    /** 1 = eyes open, 0 = shut. Multiplied into the eye height. */
    private var blink = 1f

    /** 0 = normal eyes, 1 = wide (LISTENING). */
    private var wideEyes = 0f

    /** 0..1 progress through a yawn; 0 when not yawning. */
    private var yawn = 0f

    /** Extra mouth opening driven by SPEAKING. */
    private var chatter = 0f

    /** 0..1 linear phase for the LISTENING rings and THINKING dots. */
    private var phase = 0f

    /** Squash/stretch kick applied when tapped. */
    private var poke = 0f

    private var gazeX = 0f
    private var gazeY = 0f

    // ---- Animators --------------------------------------------------------

    private val breatheAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            breathe = it.animatedValue as Float
            invalidate()
        }
    }

    private val phaseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val chatterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            // Two detuned sines so the mouth does not fall into an obvious loop.
            val t = it.animatedValue as Float
            val fast = sin(t * 2f * PI.toFloat() * 3.7f)
            val slow = sin(t * 2f * PI.toFloat() * 1.3f + 1.1f)
            chatter = (0.55f + 0.30f * fast + 0.15f * slow).coerceIn(0.12f, 1f)
            invalidate()
        }
    }

    private var wideEyesAnimator: ValueAnimator? = null
    private var blinkAnimator: ValueAnimator? = null
    private var yawnAnimator: ValueAnimator? = null
    private var gazeAnimator: ValueAnimator? = null
    private var pokeAnimator: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null

    private val blinkRunnable = Runnable { playBlink() }
    private val yawnRunnable = Runnable { playYawn() }

    // ---- Drag state -------------------------------------------------------

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var downOverlayX = 0
    private var downOverlayY = 0
    private var dragging = false

    init {
        contentDescription = context.getString(R.string.sidekick_content_description)
    }

    // ---- Lifecycle --------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        breatheAnimator.start()
        applyStateAnimators(state)
        scheduleBlink()
        scheduleYawn()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        removeCallbacks(yawnRunnable)
        breatheAnimator.cancel()
        phaseAnimator.cancel()
        chatterAnimator.cancel()
        wideEyesAnimator?.cancel()
        blinkAnimator?.cancel()
        yawnAnimator?.cancel()
        gazeAnimator?.cancel()
        pokeAnimator?.cancel()
        snapAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    // ---- State transitions ------------------------------------------------

    private fun onStateChanged(next: SidekickState) {
        applyStateAnimators(next)

        // Yawning through a command would look careless. Drop it mid-animation.
        if (next != SidekickState.IDLE && yawn > 0f) {
            yawnAnimator?.cancel()
            yawn = 0f
        }

        animateWideEyes(wideEyesTarget())

        when (next) {
            SidekickState.IDLE -> {
                scheduleBlink()
                scheduleYawn()
                animateGaze(moodGazeX(), moodGazeY())
            }
            SidekickState.LISTENING -> {
                // Eyes wide and locked on the user; no blinking, no yawning.
                removeCallbacks(blinkRunnable)
                removeCallbacks(yawnRunnable)
                blinkAnimator?.cancel()
                blink = 1f
                animateGaze(0f, -0.1f)
            }
            SidekickState.SPEAKING -> {
                removeCallbacks(yawnRunnable)
                scheduleBlink()
                animateGaze(0f, 0f)
            }
            SidekickState.THINKING -> {
                removeCallbacks(yawnRunnable)
                scheduleBlink()
                // Look up and away, the universal "hang on" pose.
                animateGaze(0.55f, -0.6f)
            }
        }
        invalidate()
    }

    private fun onMoodChanged(next: Mood) {
        if (next != Mood.NONE && yawn > 0f) {
            yawnAnimator?.cancel()
            yawn = 0f
        }
        animateWideEyes(wideEyesTarget())
        if (state == SidekickState.IDLE) animateGaze(moodGazeX(), moodGazeY())
        // A little hop so the change of face is noticed out of the corner of an eye.
        if (next != Mood.NONE) playPoke()
        invalidate()
    }

    private fun wideEyesTarget(): Float =
        if (state == SidekickState.LISTENING || mood == Mood.HORRIFIED) 1f else 0f

    /** Where each roast face looks: sideways for smug, at the floor for bored and disappointed. */
    private fun moodGazeX(): Float = if (mood == Mood.SMUG) 0.9f else 0f

    private fun moodGazeY(): Float = when (mood) {
        Mood.SMUG -> 0.3f
        Mood.BORED, Mood.DISAPPOINTED -> 0.7f
        else -> 0f
    }

    private fun applyStateAnimators(next: SidekickState) {
        if (!isAttachedToWindow) return

        if (next == SidekickState.LISTENING || next == SidekickState.THINKING) {
            if (!phaseAnimator.isStarted) phaseAnimator.start()
        } else {
            phaseAnimator.cancel()
            phase = 0f
        }

        if (next == SidekickState.SPEAKING) {
            if (!chatterAnimator.isStarted) chatterAnimator.start()
        } else {
            chatterAnimator.cancel()
            chatter = 0f
        }
    }

    // ---- Idle tics --------------------------------------------------------

    private fun scheduleBlink() {
        removeCallbacks(blinkRunnable)
        postDelayed(blinkRunnable, Random.nextLong(BLINK_MIN_MS, BLINK_MAX_MS))
    }

    private fun scheduleYawn() {
        removeCallbacks(yawnRunnable)
        postDelayed(yawnRunnable, Random.nextLong(YAWN_MIN_MS, YAWN_MAX_MS))
    }

    private fun playBlink() {
        if (state == SidekickState.LISTENING || yawn > 0f) {
            scheduleBlink()
            return
        }
        blinkAnimator?.cancel()
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170L
            interpolator = LinearInterpolator()
            addUpdateListener {
                // 1 -> 0 -> 1 over the run.
                val p = it.animatedValue as Float
                blink = 1f - sin(p * PI.toFloat())
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    blink = 1f
                    // A saccade right after a blink reads as "alive".
                    if (state == SidekickState.IDLE && mood == Mood.NONE && Random.nextFloat() < 0.5f) {
                        animateGaze(
                            Random.nextFloat() * 1.2f - 0.6f,
                            Random.nextFloat() * 0.8f - 0.4f,
                        )
                    }
                    scheduleBlink()
                }
            })
            start()
        }
    }

    private fun playYawn() {
        if (state != SidekickState.IDLE || mood != Mood.NONE) {
            scheduleYawn()
            return
        }
        yawnAnimator?.cancel()
        yawnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            interpolator = LinearInterpolator()
            addUpdateListener {
                yawn = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    yawn = 0f
                    scheduleYawn()
                }
            })
            start()
        }
    }

    private fun animateWideEyes(target: Float) {
        wideEyesAnimator?.cancel()
        wideEyesAnimator = ValueAnimator.ofFloat(wideEyes, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                wideEyes = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateGaze(targetX: Float, targetY: Float) {
        gazeAnimator?.cancel()
        val fromX = gazeX
        val fromY = gazeY
        gazeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                gazeX = fromX + (targetX - fromX) * p
                gazeY = fromY + (targetY - fromY) * p
                invalidate()
            }
            start()
        }
    }

    private fun playPoke() {
        pokeAnimator?.cancel()
        pokeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 420L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                poke = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ---- Touch: drag, tap, snap to edge -----------------------------------

    @SuppressLint("ClickableViewAccessibility") // performClick() is called below.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val h = host ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                downRawX = event.rawX
                downRawY = event.rawY
                downOverlayX = h.overlayX()
                downOverlayY = h.overlayY()
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                if (dragging) {
                    val bounds = h.overlayBounds()
                    val x = (downOverlayX + dx).roundToInt()
                        .coerceIn(bounds.left, maxOf(bounds.left, bounds.right - width))
                    val y = (downOverlayY + dy).roundToInt()
                        .coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - height))
                    h.moveOverlay(x, y)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) snapToNearestEdge() else performClick()
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) snapToNearestEdge()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        playPoke()
        host?.onSidekickTapped()
        return true
    }

    private fun snapToNearestEdge() {
        val h = host ?: return
        val bounds = h.overlayBounds()
        val startX = h.overlayX()
        val y = h.overlayY()
        val rightMost = maxOf(bounds.left, bounds.right - width)
        val centerX = startX + width / 2
        val targetX = if (centerX < bounds.centerX()) bounds.left else rightMost
        if (startX == targetX) return

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(startX.toFloat(), targetX.toFloat()).apply {
            duration = 260L
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { anim ->
                val x = (anim.animatedValue as Float).roundToInt()
                    .coerceIn(bounds.left, rightMost)
                h.moveOverlay(x, y)
            }
            start()
        }
    }

    // ---- Drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val hgt = height.toFloat()
        if (w <= 0f || hgt <= 0f) return

        val cx = w / 2f
        val cy = hgt / 2f
        val base = min(w, hgt) * BlobPainter.BODY_FILL

        // Breathing: a slow sine. Volume is conserved-ish, so the blob widens as
        // it flattens, which reads as squash-and-stretch rather than a zoom.
        val breathSine = sin(breathe * 2f * PI.toFloat())
        var scaleX = 1f - 0.030f * breathSine
        var scaleY = 1f + 0.045f * breathSine

        // Listening adds a faster pulse on top of the breath.
        if (state == SidekickState.LISTENING) {
            val p = sin(phase * 2f * PI.toFloat())
            scaleX += 0.022f * p
            scaleY += 0.022f * p
        }

        // Yawn stretches the whole body upward at the peak.
        val yawnAmount = yawnCurve(yawn)
        scaleY += 0.07f * yawnAmount
        scaleX -= 0.02f * yawnAmount

        // Tap squash: wide and short, springing back.
        scaleX += 0.10f * poke
        scaleY -= 0.10f * poke

        pose.blink = blink
        pose.wideEyes = wideEyes
        pose.yawn = yawnAmount
        pose.chatter = chatter
        pose.gazeX = gazeX
        pose.gazeY = gazeY
        pose.mood = mood
        pose.speaking = state == SidekickState.SPEAKING
        pose.listeningRings = state == SidekickState.LISTENING
        pose.thinkingDots = state == SidekickState.THINKING
        pose.phase = phase
        painter.draw(canvas, cx, cy, base * scaleX, base * scaleY, base, pose)
    }

    /** Ease a raw 0..1 yawn timeline into open -> hold -> close. */
    private fun yawnCurve(t: Float): Float {
        if (t <= 0f) return 0f
        return when {
            t < 0.35f -> smoothStep(t / 0.35f)
            t < 0.65f -> 1f
            else -> smoothStep(1f - (t - 0.65f) / 0.35f)
        }
    }

    private fun smoothStep(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private companion object {
        const val BLINK_MIN_MS = 3_000L
        const val BLINK_MAX_MS = 6_000L
        const val YAWN_MIN_MS = 20_000L
        const val YAWN_MAX_MS = 40_000L
    }
}
