package com.sangar.gal.sidekick

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One frame of what the blob is doing: the eyes, the mouth, the face it pulls. Mutable and reused, so a
 * frame never allocates. The floating Sidekick ([SidekickView]) and the stage blob both fill one of these
 * and hand it to a [BlobPainter], which is what keeps the two looking identical.
 */
class BlobPose {
    /** 1 = eyes open, 0 = shut. Multiplied into the eye height. */
    var blink = 1f

    /** 0 = normal eyes, 1 = wide (listening, horrified). */
    var wideEyes = 0f

    /** 0..1 yawn amount, already eased. */
    var yawn = 0f

    /** Extra mouth opening while speaking. */
    var chatter = 0f

    /** Pupil offset, -1..1 on each axis. */
    var gazeX = 0f
    var gazeY = 0f

    var mood = Mood.NONE

    /** Talking: the open mouth wins over the roast face's mouth. */
    var speaking = false

    /** The two pulsing rings while listening. */
    var listeningRings = false

    /** The three bouncing dots while thinking. */
    var thinkingDots = false

    /** 0..1 phase for the rings and the dots. */
    var phase = 0f

    /** Peaceful closed eyes, for sleeping. Overrides [blink]. */
    var eyesShut = false

    fun reset() {
        blink = 1f
        wideEyes = 0f
        yawn = 0f
        chatter = 0f
        gazeX = 0f
        gazeY = 0f
        mood = Mood.NONE
        speaking = false
        listeningRings = false
        thinkingDots = false
        phase = 0f
        eyesShut = false
    }
}

/**
 * Draws the Sidekick blob: a rounded green body with a rim, white eyes, a smile, and the roast faces' brows,
 * lids, mouths and sweat drop. All paints and paths are allocated once.
 *
 * Sizes are relative to the body: [draw] takes the body's width and height for this frame (already squashed or
 * stretched) and a [nominal] size, the body's resting size, which fixes the stroke widths so a squash does not
 * make the outline breathe.
 */
class BlobPainter {

    private val bodyGradient = LinearGradient(0f, 0f, 0f, 1f, COLOR_BODY_LIGHT, COLOR_BODY_DARK, Shader.TileMode.CLAMP)
    private val gradientMatrix = Matrix()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bodyGradient }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RIM
    }
    private val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SCLERA }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_INK }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_INK }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BODY_LIGHT }
    private val inkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_INK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lidLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_INK
        strokeCap = Paint.Cap.ROUND
    }
    private val sweatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SWEAT }
    private val tonguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TANGERINE }

    private val bodyRect = RectF()
    private val eyeRect = RectF()
    private val mouthPath = Path()
    private val moodPath = Path()

    private val fadePaints = arrayOf(
        bodyPaint, rimPaint, scleraPaint, pupilPaint, mouthPaint, dotPaint, inkStrokePaint, lidLinePaint, sweatPaint, tonguePaint,
    )

    /** Multiplies every colour's alpha, for fading the stage blob in and out. */
    var alpha: Int = 255
        set(value) {
            val clamped = value.coerceIn(0, 255)
            if (clamped == field) return
            field = clamped
            for (p in fadePaints) p.alpha = clamped
        }

    /**
     * Draws one frame. [cx], [cy] is the body's centre, [bw] x [bh] its size this frame and [nominal] its
     * resting size. Draws nothing for a zero-sized body.
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, nominal: Float, pose: BlobPose) {
        if (bw <= 0f || bh <= 0f || nominal <= 0f) return
        // The floating Sidekick's body fills 72% of its window, and its stroke widths and gradient were tuned
        // against the window. The same proportions are kept here, relative to the body.
        val unit = nominal / BODY_FILL
        rimPaint.strokeWidth = unit * 0.022f
        ringPaint.strokeWidth = unit * 0.020f
        inkStrokePaint.strokeWidth = unit * 0.034f
        lidLinePaint.strokeWidth = unit * 0.016f

        val span = bh / BODY_FILL
        gradientMatrix.setScale(1f, span)
        gradientMatrix.postTranslate(0f, cy - span / 2f)
        bodyGradient.setLocalMatrix(gradientMatrix)

        if (pose.listeningRings) drawListeningRings(canvas, cx, cy, bw, bh, pose.phase)
        if (pose.thinkingDots) drawThinkingDots(canvas, cx, cy, bh, pose.phase)

        bodyRect.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        val rx = bw * 0.42f
        val ry = bh * 0.42f
        canvas.drawRoundRect(bodyRect, rx, ry, bodyPaint)
        canvas.drawRoundRect(bodyRect, rx, ry, rimPaint)

        if (pose.eyesShut) drawShutEyes(canvas, cx, cy, bw, bh) else drawEyes(canvas, cx, cy, bw, bh, pose)
        if (pose.mood != Mood.NONE) drawBrows(canvas, cx, cy, bw, bh, pose.mood)
        if (pose.mood != Mood.NONE && !pose.speaking) {
            drawMoodMouth(canvas, cx, cy, bw, bh, pose.mood)
        } else {
            drawMouth(canvas, cx, cy, bw, bh, pose)
        }
        if (pose.mood == Mood.HORRIFIED) drawSweatDrop(canvas, cx, cy, bw, bh)
    }

    private fun drawListeningRings(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, phase: Float) {
        val maxRadius = min(bw, bh) * 0.92f
        val minRadius = min(bw, bh) * 0.52f
        for (i in 0 until 2) {
            val p = (phase + i * 0.5f) % 1f
            val radius = minRadius + (maxRadius - minRadius) * p
            ringPaint.color = COLOR_BODY_LIGHT
            ringPaint.alpha = ((1f - p) * 140f * alpha / 255f).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }
    }

    private fun drawThinkingDots(canvas: Canvas, cx: Float, cy: Float, bh: Float, phase: Float) {
        val dotRadius = bh * 0.045f
        val spacing = dotRadius * 3.2f
        val baseY = cy - bh * 0.62f
        for (i in 0 until 3) {
            val p = ((phase - i * 0.16f) % 1f + 1f) % 1f
            val lift = sin(p * PI.toFloat()).coerceAtLeast(0f)
            dotPaint.alpha = ((110 + 145 * lift) * alpha / 255f).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(cx + (i - 1) * spacing, baseY - dotRadius * 1.4f * lift, dotRadius, dotPaint)
        }
        dotPaint.alpha = alpha
    }

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, pose: BlobPose) {
        val eyeOffsetX = bw * 0.215f
        val eyeCenterY = cy - bh * 0.09f
        val eyeRx = bw * 0.118f * (1f + 0.10f * pose.wideEyes)

        // Blink shuts the lids; a yawn squints them most of the way closed.
        val openness = (pose.blink * (1f + 0.28f * pose.wideEyes) * (1f - 0.85f * pose.yawn)).coerceIn(0.04f, 1.6f)
        val eyeRy = bw * 0.135f * openness

        for (side in SIDES) {
            val ex = cx + side * eyeOffsetX
            eyeRect.set(ex - eyeRx, eyeCenterY - eyeRy, ex + eyeRx, eyeCenterY + eyeRy)
            val corner = min(eyeRx, eyeRy)
            canvas.drawRoundRect(eyeRect, corner, corner, scleraPaint)

            // Pupil rides inside the sclera and is clipped by the lid height. Horror shrinks it to a dot.
            val pupilScale = if (pose.mood == Mood.HORRIFIED) 0.34f else 0.55f
            val pupilR = min(eyeRx * pupilScale, eyeRy * 0.92f)
            if (pupilR > 0.5f) {
                canvas.drawCircle(ex + pose.gazeX * eyeRx * 0.38f, eyeCenterY + pose.gazeY * eyeRy * 0.38f, pupilR, pupilPaint)
            }

            // Roast faces bring the upper lid down: skin painted over the top of the eye with the body's own
            // gradient (same shader, so it matches the blob behind it), and a thin lid line.
            val lid = moodLid(pose.mood)
            if (lid > 0f && eyeRy > 1f) {
                val edge = eyeRect.top + eyeRect.height() * lid
                canvas.drawRect(eyeRect.left - 2f, eyeRect.top - 2f, eyeRect.right + 2f, edge, bodyPaint)
                val dy = (edge - eyeCenterY) / eyeRy
                val half = eyeRx * sqrt((1f - dy * dy).coerceAtLeast(0f))
                canvas.drawLine(ex - half, edge, ex + half, edge, lidLinePaint)
            }
        }
    }

    /** Sleeping: two calm arcs bowing downward. */
    private fun drawShutEyes(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float) {
        val eyeOffsetX = bw * 0.215f
        val eyeCenterY = cy - bh * 0.06f
        val half = bw * 0.12f
        for (side in SIDES) {
            val ex = cx + side * eyeOffsetX
            moodPath.reset()
            moodPath.moveTo(ex - half, eyeCenterY)
            moodPath.quadTo(ex, eyeCenterY + bh * 0.10f, ex + half, eyeCenterY)
            canvas.drawPath(moodPath, inkStrokePaint)
        }
    }

    private fun drawMouth(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, pose: BlobPose) {
        // Baseline is a thin smile; speaking and yawning open it up.
        val open = (0.06f + 0.55f * pose.chatter + 0.94f * pose.yawn).coerceIn(0f, 1f)
        val mouthW = bw * (0.30f + 0.10f * open)
        val mouthDepth = bh * (0.05f + 0.34f * open)
        val mouthY = cy + bh * 0.20f

        mouthPath.reset()
        mouthPath.moveTo(cx - mouthW / 2f, mouthY)
        // Upper lip curves up as the mouth opens.
        mouthPath.quadTo(cx, mouthY - mouthDepth * 0.42f * open, cx + mouthW / 2f, mouthY)
        // Lower lip is the smile arc.
        mouthPath.quadTo(cx, mouthY + mouthDepth * 1.7f, cx - mouthW / 2f, mouthY)
        mouthPath.close()
        canvas.drawPath(mouthPath, mouthPaint)
    }

    /** Thick brows, the most expressive part of each roast face. */
    private fun drawBrows(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, mood: Mood) {
        val eyeOffsetX = bw * 0.215f
        val baseY = cy - bh * 0.09f - bw * 0.135f - bh * 0.07f
        val half = bw * 0.12f
        for (side in SIDES) {
            val ex = cx + side * eyeOffsetX
            // Offsets in body heights, positive is down: inner end (towards the middle), outer end, arch.
            val inner: Float
            val outer: Float
            val arch: Float
            when (mood) {
                Mood.SMUG -> if (side < 0) {
                    inner = 0.01f; outer = 0.01f; arch = -0.01f
                } else {
                    inner = -0.04f; outer = -0.02f; arch = -0.06f
                }
                Mood.BORED -> {
                    inner = 0.035f; outer = 0.035f; arch = 0f
                }
                Mood.DISAPPOINTED -> {
                    inner = -0.05f; outer = 0.03f; arch = 0f
                }
                Mood.HORRIFIED -> {
                    inner = -0.07f; outer = -0.04f; arch = -0.06f
                }
                Mood.NONE -> return
            }
            val innerX = ex - side * half
            val outerX = ex + side * half
            moodPath.reset()
            moodPath.moveTo(innerX, baseY + inner * bh)
            moodPath.quadTo(ex, baseY + (inner + outer) / 2f * bh + arch * bh, outerX, baseY + outer * bh)
            canvas.drawPath(moodPath, inkStrokePaint)
        }
    }

    private fun drawMoodMouth(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, mood: Mood) {
        val my = cy + bh * 0.22f
        moodPath.reset()
        when (mood) {
            Mood.SMUG -> {
                moodPath.moveTo(cx - bw * 0.16f, my + bh * 0.02f)
                moodPath.quadTo(cx, my + bh * 0.10f, cx + bw * 0.17f, my - bh * 0.04f)
                moodPath.lineTo(cx + bw * 0.20f, my - bh * 0.07f)
                canvas.drawPath(moodPath, inkStrokePaint)
            }
            Mood.BORED -> canvas.drawLine(cx - bw * 0.11f, my + bh * 0.03f, cx + bw * 0.11f, my + bh * 0.03f, inkStrokePaint)
            Mood.DISAPPOINTED -> {
                moodPath.moveTo(cx - bw * 0.14f, my + bh * 0.08f)
                moodPath.quadTo(cx, my - bh * 0.05f, cx + bw * 0.14f, my + bh * 0.08f)
                canvas.drawPath(moodPath, inkStrokePaint)
            }
            Mood.HORRIFIED -> {
                val rx = bw * 0.085f
                val ry = bh * 0.115f
                val oy = my + bh * 0.05f
                eyeRect.set(cx - rx, oy - ry, cx + rx, oy + ry)
                canvas.drawOval(eyeRect, mouthPaint)
                eyeRect.set(cx - rx * 0.6f, oy + ry * 0.2f, cx + rx * 0.6f, oy + ry * 0.9f)
                canvas.drawOval(eyeRect, tonguePaint)
            }
            Mood.NONE -> Unit
        }
    }

    private fun drawSweatDrop(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float) {
        val x = cx + bw * 0.43f
        val top = cy - bh * 0.50f
        val size = bw * 0.09f
        moodPath.reset()
        moodPath.moveTo(x, top)
        moodPath.quadTo(x + size, top + size * 1.4f, x, top + size * 1.9f)
        moodPath.quadTo(x - size, top + size * 1.4f, x, top)
        moodPath.close()
        canvas.drawPath(moodPath, sweatPaint)
    }

    companion object {
        /** The floating window's body fills this share of the window. */
        const val BODY_FILL = 0.72f

        private val SIDES = intArrayOf(-1, 1)

        val COLOR_BODY_LIGHT = 0xFF5CE79B.toInt()
        val COLOR_BODY_DARK = 0xFF1FA463.toInt()
        val COLOR_RIM = 0xFF14663C.toInt()
        val COLOR_SCLERA = 0xFFFFFFFF.toInt()
        val COLOR_INK = 0xFF0E2B1B.toInt()
        val COLOR_SWEAT = 0xFF7EC8F0.toInt()
        val COLOR_TANGERINE = 0xFFF28C28.toInt()

        /** How far the upper lids come down for a roast face, 0 open to 1 shut. */
        fun moodLid(mood: Mood): Float = when (mood) {
            Mood.SMUG -> 0.42f
            Mood.BORED -> 0.58f
            Mood.DISAPPOINTED -> 0.26f
            else -> 0f
        }
    }
}
