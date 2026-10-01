package com.sangar.gal.sidekick.stage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets

/**
 * The full-screen view the stage window holds. It runs the [World] at a fixed 60 steps a second off the
 * display's frame clock (so a 120 Hz phone draws twice per step and a slow frame catches up with extra steps),
 * draws it with a [StageRenderer], and hands each frame's [StageEvent]s to [listener].
 *
 * The frame loop only runs while a scene plays. A tap skips the scene, except in the playground, where the
 * screen is a controller.
 */
@SuppressLint("ViewConstructor")
class StageView(
    context: Context,
    private val world: World,
    private val renderer: StageRenderer,
    private val listener: Listener,
    private val playground: Boolean,
) : View(context), Choreographer.FrameCallback {

    interface Listener {
        /** The world knows its size; the scene can start. Called once. */
        fun onStageReady()

        /** This frame's events, oldest first. The list is reused: copy what you keep. */
        fun onStageEvents(events: List<StageEvent>)
    }

    private val events = ArrayList<StageEvent>(8)
    private var running = false
    private var lastFrameNanos = 0L
    private var accumulator = 0f
    private var announcedReady = false

    private var insetLeft = 0f
    private var insetTop = 0f
    private var insetRight = 0f
    private var insetBottom = 0f

    init {
        contentDescription = if (playground) "Sidekick playground" else "Sidekick scene. Tap to skip."
        isClickable = true
    }

    fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        accumulator = 0f
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopLoop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
        val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, MAX_FRAME_SECONDS)
        lastFrameNanos = frameTimeNanos
        accumulator += dt
        var steps = 0
        while (accumulator >= STEP_SECONDS && steps < MAX_STEPS_PER_FRAME) {
            world.step(STEP_SECONDS)
            accumulator -= STEP_SECONDS
            steps++
        }
        if (steps == MAX_STEPS_PER_FRAME) accumulator = 0f
        world.drainEvents(events)
        if (events.isNotEmpty()) {
            listener.onStageEvents(events)
            events.clear()
        }
        invalidate()
        if (running && !world.finished) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetLeft = bars.left.toFloat()
            insetTop = bars.top.toFloat()
            insetRight = bars.right.toFloat()
            insetBottom = bars.bottom.toFloat()
        } else {
            @Suppress("DEPRECATION")
            run {
                insetLeft = insets.systemWindowInsetLeft.toFloat()
                insetTop = insets.systemWindowInsetTop.toFloat()
                insetRight = insets.systemWindowInsetRight.toFloat()
                insetBottom = insets.systemWindowInsetBottom.toFloat()
            }
        }
        updateBounds()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBounds()
    }

    private fun updateBounds() {
        if (width <= 0 || height <= 0) return
        world.setBounds(width.toFloat(), height.toFloat(), insetLeft, insetTop, insetRight, insetBottom)
        if (!announcedReady && world.ready) {
            announcedReady = true
            listener.onStageReady()
        }
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, world)
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() is called for the skip tap.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (playground) {
            handleController(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        world.skip()
        return true
    }

    /** Left third runs left, right third runs right, the middle jumps, the top strip closes. Multi-touch. */
    private fun handleController(event: MotionEvent) {
        val input = world.input
        input.left = false
        input.right = false
        input.jump = false
        val up = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        val liftedIndex = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        if (up) return
        val strip = world.top + StageRenderer.CLOSE_STRIP_DP * resources.displayMetrics.density
        for (i in 0 until event.pointerCount) {
            if (i == liftedIndex) continue
            val x = event.getX(i)
            val y = event.getY(i)
            when {
                y < strip -> if (event.actionMasked == MotionEvent.ACTION_DOWN) input.close = true
                x < width / 3f -> input.left = true
                x > width * 2f / 3f -> input.right = true
                else -> input.jump = true
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    companion object {
        const val STEP_SECONDS = 1f / 60f
        private const val MAX_FRAME_SECONDS = 0.1f
        private const val MAX_STEPS_PER_FRAME = 6
    }
}
