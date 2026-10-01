package com.sangar.gal.sidekick

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One frame of what Sidekick is doing: the eyes, the mouth, the face she pulls and where her trunk is. Mutable
 * and reused, so a frame never allocates. The floating Sidekick ([SidekickView]) and the stage both fill one of
 * these and hand it to a [BlobPainter], which is what keeps the two looking identical.
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

    /** Trunk angle in degrees: 0 hangs straight down, 90 points towards [facing], 180 points straight up. */
    var trunkAngle = REST_TRUNK

    /** Which side the trunk swings to: 1 right, -1 left. */
    var facing = 1

    /** 0..1: how far the tip curls back in, like a little hook. */
    var trunkCurl = 0.35f

    /** 0..1: ears lifted out, for jumps and surprise. */
    var earFlap = 0f

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
        trunkAngle = REST_TRUNK
        facing = 1
        trunkCurl = 0.35f
        earFlap = 0f
    }

    companion object {
        const val REST_TRUNK = 50f
    }
}

/**
 * Draws Sidekick, our pink elephant: big round ears, a bow, lashes, rosy cheeks and a bendy trunk that does the
 * work in the scenes (boops, smacks, waves). The roast faces bring their brows, lids, mouths and the sweat drop.
 * All paints and paths are allocated once. (The class keeps its old name from when Sidekick was a green blob.)
 *
 * Sizes are relative to the head: [draw] takes the head's width and height for this frame (already squashed or
 * stretched) and a [nominal] size, the resting size, which fixes the stroke widths so a squash does not make
 * the outline breathe. The ears and the trunk reach a little outside that box.
 */
class BlobPainter {

    private val skinGradient = LinearGradient(0f, 0f, 0f, 1f, COLOR_SKIN_LIGHT, COLOR_SKIN_DARK, Shader.TileMode.CLAMP)
    private val gradientMatrix = Matrix()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = skinGradient }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RIM
        strokeJoin = Paint.Join.ROUND
    }
    private val innerEarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_INNER_EAR }
    private val trunkOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RIM
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trunkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_SKIN_MID
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val wrinklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RIM
        strokeCap = Paint.Cap.ROUND
    }
    private val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SCLERA }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_INK }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_INK }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SKIN_DARK }
    private val inkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_INK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_INK
        strokeCap = Paint.Cap.ROUND
    }
    private val lidLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_INK
        strokeCap = Paint.Cap.ROUND
    }
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CHEEK }
    private val bowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BOW }
    private val bowKnotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BOW_KNOT }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
    private val sweatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SWEAT }
    private val tonguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TONGUE }

    private val rect = RectF()
    private val eyeRect = RectF()
    private val mouthPath = Path()
    private val moodPath = Path()
    private val trunkPath = Path()
    private val earPath = Path()

    private val fadePaints = arrayOf(
        bodyPaint, rimPaint, innerEarPaint, trunkOutlinePaint, trunkPaint, wrinklePaint, scleraPaint, pupilPaint,
        mouthPaint, dotPaint, inkStrokePaint, lashPaint, lidLinePaint, bowPaint, bowKnotPaint, sweatPaint, tonguePaint,
    )

    /** Multiplies every colour's alpha, for fading the stage elephant in and out. */
    var alpha: Int = 255
        set(value) {
            val clamped = value.coerceIn(0, 255)
            if (clamped == field) return
            field = clamped
            for (p in fadePaints) p.alpha = clamped
            cheekPaint.alpha = (CHEEK_ALPHA * clamped / 255f).roundToInt()
            highlightPaint.alpha = (HIGHLIGHT_ALPHA * clamped / 255f).roundToInt()
        }

    /**
     * Draws one frame. [cx], [cy] is the head's centre, [bw] x [bh] its size this frame and [nominal] its
     * resting size. Draws nothing for a zero-sized head.
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, nominal: Float, pose: BlobPose) {
        if (bw <= 0f || bh <= 0f || nominal <= 0f) return
        val unit = nominal / BODY_FILL
        rimPaint.strokeWidth = unit * 0.022f
        ringPaint.strokeWidth = unit * 0.020f
        inkStrokePaint.strokeWidth = unit * 0.030f
        lashPaint.strokeWidth = unit * 0.016f
        lidLinePaint.strokeWidth = unit * 0.016f
        wrinklePaint.strokeWidth = unit * 0.012f
        val trunkWidth = nominal * 0.19f
        trunkPaint.strokeWidth = trunkWidth
        trunkOutlinePaint.strokeWidth = trunkWidth + rimPaint.strokeWidth * 2f

        val span = bh / BODY_FILL
        gradientMatrix.setScale(1f, span)
        gradientMatrix.postTranslate(0f, cy - span / 2f)
        skinGradient.setLocalMatrix(gradientMatrix)

        if (pose.listeningRings) drawListeningRings(canvas, cx, cy, bw, bh, pose.phase)
        if (pose.thinkingDots) drawThinkingDots(canvas, cx, cy, bh, pose.phase)

        drawEars(canvas, cx, cy, bw, bh, pose.earFlap)

        // Head.
        rect.set(cx - bw * HEAD_RX, cy - bh * HEAD_RY - bh * 0.02f, cx + bw * HEAD_RX, cy + bh * HEAD_RY - bh * 0.02f)
        canvas.drawOval(rect, bodyPaint)
        canvas.drawOval(rect, rimPaint)
        canvas.save()
        canvas.rotate(-25f, cx - bw * 0.20f, cy - bh * 0.30f)
        rect.set(cx - bw * 0.31f, cy - bh * 0.35f, cx - bw * 0.09f, cy - bh * 0.25f)
        canvas.drawOval(rect, highlightPaint)
        canvas.restore()

        // Cheeks.
        for (side in SIDES) {
            val x = cx + side * bw * 0.27f
            rect.set(x - bw * 0.085f, cy + bh * 0.07f, x + bw * 0.085f, cy + bh * 0.14f)
            canvas.drawOval(rect, cheekPaint)
        }

        if (pose.eyesShut) drawShutEyes(canvas, cx, cy, bw, bh) else drawEyes(canvas, cx, cy, bw, bh, pose)
        if (pose.mood != Mood.NONE) drawBrows(canvas, cx, cy, bw, bh, pose.mood)
        // The mouth sits beside the trunk, on the side away from where it swings.
        val mouthX = cx - pose.facing * bw * 0.20f
        if (pose.mood != Mood.NONE && !pose.speaking) {
            drawMoodMouth(canvas, mouthX, cy, bw, bh, pose.mood)
        } else {
            drawMouth(canvas, mouthX, cy, bw, bh, pose)
        }

        drawTrunk(canvas, cx, cy, bw, bh, nominal, pose)
        drawBow(canvas, cx, cy, bw, bh)
        if (pose.mood == Mood.HORRIFIED) drawSweatDrop(canvas, cx, cy, bw, bh)
    }

    /** Two big round ears behind the head. [flap] lifts them out and up. */
    private fun drawEars(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, flap: Float) {
        for (side in SIDES) {
            val ex = cx + side * bw * (0.36f + 0.04f * flap)
            val ey = cy - bh * (0.04f + 0.06f * flap)
            canvas.save()
            canvas.rotate(side * (-12f - 18f * flap), ex, ey)
            rect.set(ex - bw * 0.25f, ey - bh * 0.32f, ex + bw * 0.25f, ey + bh * 0.30f)
            earPath.reset()
            earPath.addOval(rect, Path.Direction.CW)
            canvas.drawPath(earPath, bodyPaint)
            canvas.drawPath(earPath, rimPaint)
            val inset = bw * 0.07f
            rect.set(rect.left + inset + (if (side > 0) inset * 0.6f else 0f), rect.top + inset, rect.right - inset - (if (side < 0) inset * 0.6f else 0f), rect.bottom - inset)
            canvas.drawOval(rect, innerEarPaint)
            canvas.restore()
        }
    }

    /**
     * The trunk: a thick curve from between the eyes. It leaves the face heading down, then bends towards
     * [BlobPose.trunkAngle], so raising the angle sweeps it out in front of the face and up, the way a real
     * trunk lifts. A small hook at the tip ([BlobPose.trunkCurl]) curls back towards the face.
     */
    private fun drawTrunk(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, nominal: Float, pose: BlobPose) {
        val f = if (pose.facing < 0) -1f else 1f
        val angle = pose.trunkAngle.coerceIn(-40f, 200f)
        val length = nominal * 0.56f
        val bx = cx
        val by = cy - bh * 0.03f
        val a1 = Math.toRadians((angle / 3f).toDouble())
        val a3 = Math.toRadians(angle.toDouble())
        val p1x = bx + f * length * 0.40f * sin(a1).toFloat()
        val p1y = by + length * 0.40f * cos(a1).toFloat()
        // Past pointing forward the tip keeps out to the side, so a raised trunk goes up beside the face,
        // not across the eyes.
        val high = ((angle - 90f) / 90f).coerceIn(0f, 1f)
        val lateral = sin(a3).toFloat() * 0.92f + 0.62f * high * high * (3f - 2f * high)
        val tipX = bx + f * length * lateral
        val tipY = by + length * 0.35f * cos(a1).toFloat() + length * 0.75f * cos(a3).toFloat()
        val a2 = Math.toRadians((angle * 0.8f).toDouble())
        val p2x = tipX - f * length * 0.35f * sin(a2).toFloat()
        val p2y = tipY - length * 0.35f * cos(a2).toFloat()
        trunkPath.reset()
        trunkPath.moveTo(bx, by)
        trunkPath.cubicTo(p1x, p1y, p2x, p2y, tipX, tipY)
        // The hook: a short turn back towards the face at the tip.
        val curl = pose.trunkCurl.coerceIn(0f, 1f)
        if (curl > 0.01f) {
            val dirX = tipX - p2x
            val dirY = tipY - p2y
            val d = sqrt(dirX * dirX + dirY * dirY).coerceAtLeast(1f)
            val ux = dirX / d
            val uy = dirY / d
            val hook = length * 0.16f * curl
            // Turn back: perpendicular towards the face side.
            val nx = -uy * f
            val ny = ux * f
            trunkPath.quadTo(tipX + ux * hook, tipY + uy * hook, tipX + ux * hook * 0.6f - nx * hook, tipY + uy * hook * 0.6f - ny * hook)
        }
        canvas.drawPath(trunkPath, trunkOutlinePaint)
        canvas.drawPath(trunkPath, trunkPaint)
        // Two wrinkles across the trunk, a third of the way down.
        for (k in 1..2) {
            val t = 0.22f + 0.14f * k
            val x = cubic(bx, p1x, p2x, tipX, t)
            val y = cubic(by, p1y, p2y, tipY, t)
            val tx = cubicD(bx, p1x, p2x, tipX, t)
            val ty = cubicD(by, p1y, p2y, tipY, t)
            val tl = sqrt(tx * tx + ty * ty).coerceAtLeast(1f)
            val half = trunkPaint.strokeWidth * 0.30f
            canvas.drawLine(x - ty / tl * half, y + tx / tl * half, x + ty / tl * half, y - tx / tl * half, wrinklePaint)
        }
    }

    /** A bow on top of the head, tilted to one side. */
    private fun drawBow(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float) {
        val x = cx - bw * 0.22f
        val y = cy - bh * 0.40f
        val s = bw * 0.13f
        canvas.save()
        canvas.rotate(-18f, x, y)
        for (side in SIDES) {
            moodPath.reset()
            moodPath.moveTo(x, y)
            moodPath.lineTo(x + side * s * 1.5f, y - s * 0.9f)
            moodPath.quadTo(x + side * s * 2.0f, y, x + side * s * 1.5f, y + s * 0.9f)
            moodPath.close()
            canvas.drawPath(moodPath, bowPaint)
            canvas.drawPath(moodPath, rimPaint)
        }
        canvas.drawCircle(x, y, s * 0.45f, bowKnotPaint)
        canvas.drawCircle(x, y, s * 0.45f, rimPaint)
        canvas.restore()
    }

    private fun drawListeningRings(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, phase: Float) {
        val maxRadius = min(bw, bh) * 0.92f
        val minRadius = min(bw, bh) * 0.52f
        for (i in 0 until 2) {
            val p = (phase + i * 0.5f) % 1f
            val radius = minRadius + (maxRadius - minRadius) * p
            ringPaint.color = COLOR_SKIN_DARK
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
        val eyeOffsetX = bw * 0.19f
        val eyeCenterY = cy - bh * 0.12f
        val eyeRx = bw * 0.085f * (1f + 0.10f * pose.wideEyes)
        val openness = (pose.blink * (1f + 0.28f * pose.wideEyes) * (1f - 0.85f * pose.yawn)).coerceIn(0.04f, 1.6f)
        val eyeRy = bw * 0.105f * openness

        for (side in SIDES) {
            val ex = cx + side * eyeOffsetX
            // Big dark eyes with a white sparkle: the cute look. A white rim only when wide open.
            eyeRect.set(ex - eyeRx, eyeCenterY - eyeRy, ex + eyeRx, eyeCenterY + eyeRy)
            if (pose.wideEyes > 0.3f || pose.mood == Mood.HORRIFIED) {
                val grow = eyeRx * 0.35f
                rect.set(eyeRect.left - grow, eyeRect.top - grow, eyeRect.right + grow, eyeRect.bottom + grow)
                canvas.drawOval(rect, scleraPaint)
            }
            val pupilScale = if (pose.mood == Mood.HORRIFIED) 0.55f else 1f
            rect.set(
                ex + pose.gazeX * eyeRx * 0.25f - eyeRx * pupilScale,
                eyeCenterY + pose.gazeY * eyeRy * 0.25f - eyeRy * pupilScale,
                ex + pose.gazeX * eyeRx * 0.25f + eyeRx * pupilScale,
                eyeCenterY + pose.gazeY * eyeRy * 0.25f + eyeRy * pupilScale,
            )
            canvas.drawOval(rect, pupilPaint)
            if (eyeRy > eyeRx * 0.4f) {
                canvas.drawCircle(rect.centerX() + eyeRx * 0.32f, rect.centerY() - eyeRy * 0.35f, eyeRx * 0.32f, scleraPaint)
            }
            // Two lashes at the outer corner.
            val lx = ex + side * eyeRx * 0.75f
            val ly = eyeCenterY - eyeRy * 0.6f
            canvas.drawLine(lx, ly, lx + side * eyeRx * 0.6f, ly - eyeRx * 0.35f, lashPaint)
            canvas.drawLine(lx - side * eyeRx * 0.15f, ly - eyeRy * 0.25f, lx + side * eyeRx * 0.35f, ly - eyeRy * 0.25f - eyeRx * 0.6f, lashPaint)

            // Roast faces bring the upper lid down with the skin's own gradient and a thin lid line.
            val lid = moodLid(pose.mood)
            if (lid > 0f && eyeRy > 1f) {
                val edge = eyeRect.top + eyeRect.height() * lid
                canvas.drawRect(eyeRect.left - 2f, eyeRect.top - eyeRy, eyeRect.right + 2f, edge, bodyPaint)
                val dy = (edge - eyeCenterY) / eyeRy
                val half = eyeRx * sqrt((1f - dy * dy).coerceAtLeast(0f))
                canvas.drawLine(ex - half, edge, ex + half, edge, lidLinePaint)
            }
        }
    }

    /** Sleeping: two calm arcs bowing downward, lashes still on. */
    private fun drawShutEyes(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float) {
        val eyeOffsetX = bw * 0.19f
        val eyeCenterY = cy - bh * 0.10f
        val half = bw * 0.09f
        for (side in SIDES) {
            val ex = cx + side * eyeOffsetX
            moodPath.reset()
            moodPath.moveTo(ex - half, eyeCenterY)
            moodPath.quadTo(ex, eyeCenterY + bh * 0.08f, ex + half, eyeCenterY)
            canvas.drawPath(moodPath, inkStrokePaint)
            canvas.drawLine(ex + side * half, eyeCenterY, ex + side * half * 1.5f, eyeCenterY - half * 0.4f, lashPaint)
        }
    }

    private fun drawMouth(canvas: Canvas, mx: Float, cy: Float, bw: Float, bh: Float, pose: BlobPose) {
        val open = (0.06f + 0.55f * pose.chatter + 0.94f * pose.yawn).coerceIn(0f, 1f)
        val mouthW = bw * (0.14f + 0.05f * open)
        val mouthDepth = bh * (0.03f + 0.20f * open)
        val mouthY = cy + bh * 0.19f
        mouthPath.reset()
        mouthPath.moveTo(mx - mouthW / 2f, mouthY)
        mouthPath.quadTo(mx, mouthY - mouthDepth * 0.42f * open, mx + mouthW / 2f, mouthY)
        mouthPath.quadTo(mx, mouthY + mouthDepth * 1.7f, mx - mouthW / 2f, mouthY)
        mouthPath.close()
        canvas.drawPath(mouthPath, mouthPaint)
    }

    /** Brows: the most expressive part of each roast face. */
    private fun drawBrows(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, mood: Mood) {
        val eyeOffsetX = bw * 0.19f
        val baseY = cy - bh * 0.12f - bw * 0.105f - bh * 0.07f
        val half = bw * 0.09f
        for (side in SIDES) {
            val ex = cx + side * eyeOffsetX
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

    private fun drawMoodMouth(canvas: Canvas, mx: Float, cy: Float, bw: Float, bh: Float, mood: Mood) {
        val my = cy + bh * 0.20f
        val w = bw * 0.55f
        moodPath.reset()
        when (mood) {
            Mood.SMUG -> {
                moodPath.moveTo(mx - w * 0.16f, my + bh * 0.01f)
                moodPath.quadTo(mx, my + bh * 0.06f, mx + w * 0.17f, my - bh * 0.03f)
                canvas.drawPath(moodPath, inkStrokePaint)
            }
            Mood.BORED -> canvas.drawLine(mx - w * 0.12f, my + bh * 0.02f, mx + w * 0.12f, my + bh * 0.02f, inkStrokePaint)
            Mood.DISAPPOINTED -> {
                moodPath.moveTo(mx - w * 0.14f, my + bh * 0.05f)
                moodPath.quadTo(mx, my - bh * 0.03f, mx + w * 0.14f, my + bh * 0.05f)
                canvas.drawPath(moodPath, inkStrokePaint)
            }
            Mood.HORRIFIED -> {
                val rx = bw * 0.055f
                val ry = bh * 0.075f
                val oy = my + bh * 0.03f
                eyeRect.set(mx - rx, oy - ry, mx + rx, oy + ry)
                canvas.drawOval(eyeRect, mouthPaint)
                eyeRect.set(mx - rx * 0.6f, oy + ry * 0.2f, mx + rx * 0.6f, oy + ry * 0.9f)
                canvas.drawOval(eyeRect, tonguePaint)
            }
            Mood.NONE -> Unit
        }
    }

    private fun drawSweatDrop(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float) {
        val x = cx + bw * 0.33f
        val top = cy - bh * 0.42f
        val size = bw * 0.08f
        moodPath.reset()
        moodPath.moveTo(x, top)
        moodPath.quadTo(x + size, top + size * 1.4f, x, top + size * 1.9f)
        moodPath.quadTo(x - size, top + size * 1.4f, x, top)
        moodPath.close()
        canvas.drawPath(moodPath, sweatPaint)
    }

    private fun cubic(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
    }

    private fun cubicD(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * (b - a) + 6f * u * t * (c - b) + 3f * t * t * (d - c)
    }

    companion object {
        /** The floating window's head fills this share of the window; the ears and trunk use the rest. */
        const val BODY_FILL = 0.62f

        private const val HEAD_RX = 0.42f
        private const val HEAD_RY = 0.46f
        private const val CHEEK_ALPHA = 150
        private const val HIGHLIGHT_ALPHA = 0x88

        private val SIDES = intArrayOf(-1, 1)

        val COLOR_SKIN_LIGHT = 0xFFFFD0E4.toInt()
        val COLOR_SKIN_MID = 0xFFF8B2D1.toInt()
        val COLOR_SKIN_DARK = 0xFFEE8DB9.toInt()
        val COLOR_INNER_EAR = 0xFFFF9EC4.toInt()
        val COLOR_RIM = 0xFF9C3D6E.toInt()
        val COLOR_SCLERA = 0xFFFFFFFF.toInt()
        val COLOR_INK = 0xFF3A1530.toInt()
        val COLOR_CHEEK = 0x96FF6F9F.toInt()
        val COLOR_BOW = 0xFFFF5C8A.toInt()
        val COLOR_BOW_KNOT = 0xFFFF7FA3.toInt()
        val COLOR_SWEAT = 0xFF7EC8F0.toInt()
        val COLOR_TONGUE = 0xFFFF7A8A.toInt()

        /** How far the upper lids come down for a roast face, 0 open to 1 shut. */
        fun moodLid(mood: Mood): Float = when (mood) {
            Mood.SMUG -> 0.42f
            Mood.BORED -> 0.58f
            Mood.DISAPPOINTED -> 0.26f
            else -> 0f
        }
    }
}
