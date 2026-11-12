package com.sangar.gal.sidekick

import android.animation.TimeAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A paper plane GAL throws when you open something useful. It glides across the screen in a shallow arc and
 * leaves. Its own tiny window that never takes touches, so nothing underneath is blocked.
 */
internal class PaperPlane(private val context: Context, private val windowManager: WindowManager) {

    private val density = context.resources.displayMetrics.density
    private val size = (PLANE_DP * density).roundToInt()
    private var view: PlaneView? = null
    private var loop: TimeAnimator? = null

    /** Throws it from [from] (GAL's window) towards the far side of [bounds]. */
    fun fly(from: Rect, bounds: Rect) {
        cancel()
        val dir = if (from.centerX() < bounds.centerX()) 1 else -1
        val startX = from.centerX().toFloat()
        val startY = from.top + from.height() * 0.3f
        val distance = if (dir > 0) bounds.right - startX + size else startX - bounds.left + size
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX.roundToInt()
            y = startY.roundToInt()
            title = "GAL paper plane"
        }
        val plane = PlaneView(context).apply { facing = dir }
        if (runCatching { windowManager.addView(plane, p) }.isFailure) return
        view = plane
        var travelled = 0f
        loop = TimeAnimator().apply {
            setTimeListener { _, _, deltaMs ->
                val dt = (deltaMs / 1000f).coerceAtMost(0.05f)
                travelled += SPEED_DP * density * dt
                val t = (travelled / distance).coerceIn(0f, 1f)
                // Up a little, then a long glide down, with a slight wobble.
                val lift = sin(t * PI.toFloat()) * 70f * density - t * 40f * density
                p.x = (startX + dir * travelled).roundToInt()
                p.y = (startY - lift).roundToInt()
                plane.tilt = (-18f + 30f * t) + 4f * sin(travelled / (22f * density))
                runCatching { windowManager.updateViewLayout(plane, p) }
                if (t >= 1f) cancel()
            }
            start()
        }
    }

    fun cancel() {
        loop?.cancel()
        loop = null
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private class PlaneView(context: Context) : View(context) {
        var facing = 1
        var tilt = 0f
            set(value) {
                field = value
                invalidate()
            }
        private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val fold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE3E0EA.toInt() }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9C3D6E.toInt()
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val shape = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val c = w / 2f
            rim.strokeWidth = w * 0.035f
            canvas.save()
            canvas.scale(facing.toFloat(), 1f, c, c)
            canvas.rotate(tilt, c, c)
            // The far wing, then the near wing with its fold.
            shape.reset()
            shape.moveTo(w * 0.92f, c)
            shape.lineTo(w * 0.10f, w * 0.26f)
            shape.lineTo(w * 0.30f, c)
            shape.close()
            canvas.drawPath(shape, paper)
            canvas.drawPath(shape, rim)
            shape.reset()
            shape.moveTo(w * 0.92f, c)
            shape.lineTo(w * 0.30f, c)
            shape.lineTo(w * 0.22f, w * 0.72f)
            shape.close()
            canvas.drawPath(shape, fold)
            canvas.drawPath(shape, rim)
            canvas.restore()
        }
    }

    private companion object {
        const val PLANE_DP = 40f
        const val SPEED_DP = 300f
    }
}
