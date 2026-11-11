package com.sangar.gal.sidekick

import android.animation.TimeAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The ball's physics, in pixels and seconds. Pure, so the bounces are unit tested: gravity, a floor, two walls,
 * a little energy lost at every bounce and rolling friction on the floor.
 */
class BallPhysics(
    var x: Float,
    var y: Float,
    private val radius: Float,
    private val gravity: Float,
) {
    var vx = 0f
    var vy = 0f

    /** Total distance rolled, for spinning the picture. */
    var travelled = 0f
        private set

    /** True for the step in which it hit the floor or a wall hard enough to feel. */
    var bumped = false
        private set

    /** With the walls off (the last, big kick) the ball is allowed to fly out of the screen. */
    var walls = true

    fun onFloor(floor: Float): Boolean = y >= floor - radius - 0.5f

    fun atRest(floor: Float, slow: Float): Boolean = onFloor(floor) && abs(vy) < slow && abs(vx) < slow

    fun step(dt: Float, left: Float, right: Float, floor: Float) {
        bumped = false
        vy += gravity * dt
        x += vx * dt
        y += vy * dt
        travelled += vx * dt
        if (y > floor - radius) {
            y = floor - radius
            // Small bounces die out instead of buzzing on the floor for ever.
            if (abs(vy) < gravity * 0.06f) {
                vy = 0f
            } else {
                // A real bounce: some height and some pace are lost. Rolling is handled below.
                bumped = abs(vy) > gravity * 0.2f
                vy = -vy * FLOOR_BOUNCE
                vx *= FLOOR_GRIP
            }
        }
        if (onFloor(floor)) vx *= (1f - ROLL_FRICTION * dt).coerceAtLeast(0f)
        if (walls) {
            if (x < left + radius) {
                x = left + radius
                bumped = true
                vx = abs(vx) * WALL_BOUNCE
            } else if (x > right - radius) {
                x = right - radius
                bumped = true
                vx = -abs(vx) * WALL_BOUNCE
            }
        }
    }

    fun gone(left: Float, right: Float): Boolean = x < left - radius * 2 || x > right + radius * 2

    companion object {
        const val FLOOR_BOUNCE = 0.55f
        const val WALL_BOUNCE = 0.65f
        const val FLOOR_GRIP = 0.85f
        const val ROLL_FRICTION = 1.4f
    }
}

/** The football: its own small window, so it can roll over whatever app is open. A tap flicks it up. */
internal class BallView(context: Context) : View(context) {
    var spin = 0f
        set(value) {
            field = value
            invalidate()
        }
    var onTapped: (() -> Unit)? = null

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A1530.toInt() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A1530.toInt()
        style = Paint.Style.STROKE
    }
    private val patch = Path()
    private val clip = Path()

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val r = c * 0.92f
        rim.strokeWidth = r * 0.10f
        canvas.drawCircle(c, c, r, white)
        canvas.save()
        canvas.rotate(Math.toDegrees(spin.toDouble()).toFloat(), c, c)
        pentagon(c, c, r * 0.36f)
        canvas.drawPath(patch, ink)
        // Five patches peeking in around the edge, cut off by the ball's outline.
        clip.reset()
        clip.addCircle(c, c, r, Path.Direction.CW)
        canvas.clipPath(clip)
        for (k in 0 until 5) {
            val a = (k * 72f - 90f + 36f) * PI.toFloat() / 180f
            pentagon(c + cos(a) * r * 0.86f, c + sin(a) * r * 0.86f, r * 0.26f)
            canvas.drawPath(patch, ink)
        }
        canvas.restore()
        canvas.drawCircle(c, c, r, rim)
    }

    private fun pentagon(cx: Float, cy: Float, r: Float) {
        patch.reset()
        for (k in 0 until 5) {
            val a = (k * 72f - 90f) * PI.toFloat() / 180f
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            if (k == 0) patch.moveTo(px, py) else patch.lineTo(px, py)
        }
        patch.close()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTapped?.invoke()
        return true
    }
}

/**
 * A kickabout: a ball drops in, GAL walks to it and kicks it, it bounces off the screen edge and comes back,
 * and on the third kick she boots it clean off the screen and celebrates. No full-screen layer: only her own
 * small window and the ball's are on top of the app, so everything underneath stays usable.
 */
internal class Football(
    private val context: Context,
    private val windowManager: WindowManager,
    private val host: Host,
) {
    /** What the scene needs from GAL's window. */
    interface Host {
        /** GAL's window on screen. */
        fun elephant(): Rect
        fun bounds(): Rect

        /** Walk her window's left edge to [x]; [then] runs on arrival (not if she is interrupted). */
        fun walk(x: Int, facing: Int, then: () -> Unit)

        /** A hop and a flick of the trunk. */
        fun kickPose(facing: Int)

        /** The ball is at [x], [y] on screen, so she can keep her eyes on it. */
        fun watch(x: Float, y: Float)

        /** The scene is over. [scored] is false when it was cut short. */
        fun finished(scored: Boolean)
    }

    private val density = context.resources.displayMetrics.density
    private val size = (BALL_DP * density).roundToInt()
    private var ball: BallView? = null
    private var params: WindowManager.LayoutParams? = null
    private var physics: BallPhysics? = null
    private var loop: TimeAnimator? = null
    private var kicks = 0
    private var chasing = false
    private var lastKick = false
    private var elapsed = 0f
    private var over = false

    private fun floor(): Float {
        val e = host.elephant()
        // Her feet stand a little above the bottom of her window.
        return e.top + e.height() * FEET_AT
    }

    fun start(): Boolean {
        val bounds = host.bounds()
        val e = host.elephant()
        // Drop the ball in on the roomier side of her.
        val toRight = e.centerX() < bounds.centerX()
        val startX = (e.centerX() + (if (toRight) 1 else -1) * bounds.width() * 0.32f)
            .coerceIn(bounds.left + size.toFloat(), bounds.right - size.toFloat())
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "GAL football"
        }
        val view = BallView(context)
        val body = BallPhysics(startX, floor() - size * 4f, size / 2f, GRAVITY_DP * density)
        body.vx = (if (toRight) -1 else 1) * 40f * density
        p.x = (body.x - size / 2f).roundToInt()
        p.y = (body.y - size / 2f).roundToInt()
        if (runCatching { windowManager.addView(view, p) }.isFailure) return false
        view.onTapped = {
            // A flick from the user: up and a little sideways. She will chase it again.
            body.vy = -FLICK_DP * density
            body.vx += (Random.nextFloat() - 0.5f) * 260f * density
        }
        ball = view
        params = p
        physics = body
        loop = TimeAnimator().apply {
            setTimeListener { _, _, deltaMs -> tick((deltaMs / 1000f).coerceAtMost(0.05f)) }
            start()
        }
        return true
    }

    private fun tick(dt: Float) {
        val body = physics ?: return
        val view = ball ?: return
        val p = params ?: return
        if (dt <= 0f) return
        val bounds = host.bounds()
        val floor = floor()
        body.step(dt, bounds.left.toFloat(), bounds.right.toFloat(), floor)
        view.spin = body.travelled / (size / 2f)
        // A light tick you can feel when the ball lands or hits the edge (follows the phone's touch-feedback setting).
        if (body.bumped) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        host.watch(body.x, body.y)
        p.x = (body.x - size / 2f).roundToInt()
        p.y = (body.y - size / 2f).roundToInt()
        runCatching { windowManager.updateViewLayout(view, p) }

        elapsed += dt
        if (lastKick && body.gone(bounds.left.toFloat(), bounds.right.toFloat())) {
            end(scored = true)
            return
        }
        if (elapsed > MAX_SECONDS) {
            end(scored = lastKick)
            return
        }
        if (!chasing && !lastKick && body.atRest(floor, SLOW_DP * density)) chase(body, bounds)
    }

    /** Walks her to the ball so that her trunk end is at it, facing the roomier side, then kicks. */
    private fun chase(body: BallPhysics, bounds: Rect) {
        chasing = true
        val e = host.elephant()
        val dir = if (body.x < bounds.centerX()) 1 else -1
        val front = e.width() * FRONT_AT
        val target = if (dir > 0) body.x - front else body.x - (e.width() - front)
        val x = target.roundToInt().coerceIn(bounds.left, maxOf(bounds.left, bounds.right - e.width()))
        val walkFacing = if (x >= e.left) 1 else -1
        host.walk(x, walkFacing) {
            if (over) return@walk
            host.kickPose(dir)
            kicks++
            lastKick = kicks >= KICKS
            body.walls = !lastKick
            val power = if (lastKick) LAST_KICK_DP else KICK_DP
            body.vx = dir * power * density
            body.vy = -(if (lastKick) 420f else 520f) * density
            chasing = false
        }
    }

    private fun end(scored: Boolean) {
        if (over) return
        over = true
        removeBall()
        host.finished(scored)
    }

    /** Cut short (she was grabbed, a roast came up, the screen went off). Does not call back. */
    fun cancel() {
        over = true
        removeBall()
    }

    private fun removeBall() {
        loop?.cancel()
        loop = null
        ball?.let { runCatching { windowManager.removeView(it) } }
        ball = null
        params = null
        physics = null
    }

    private companion object {
        const val BALL_DP = 34f
        const val GRAVITY_DP = 1_500f
        const val KICK_DP = 430f
        const val LAST_KICK_DP = 1_100f
        const val FLICK_DP = 620f
        const val SLOW_DP = 26f
        const val KICKS = 3
        const val MAX_SECONDS = 25f

        /** Where her feet and the end of her trunk are inside her window, as shares of its height and width. */
        const val FEET_AT = 0.93f
        const val FRONT_AT = 0.88f
    }
}
