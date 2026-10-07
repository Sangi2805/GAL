package com.sangar.gal.sidekick

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One frame of what Sidekick is doing: her face, her trunk, her legs. Mutable and reused, so a frame never
 * allocates. The floating Sidekick ([SidekickView]) and the stage both fill one of these and hand it to a
 * [BlobPainter], which is what keeps the two looking identical.
 */
class BlobPose {
    /** 1 = eyes open, 0 = shut. Multiplied into the eye height. */
    var blink = 1f

    /** 0 = normal eyes, 1 = wide (surprised, horrified). */
    var wideEyes = 0f

    /** 0..1 yawn amount, already eased. */
    var yawn = 0f

    /** Extra mouth opening while talking or trumpeting. */
    var chatter = 0f

    /** Pupil offset, -1..1 on each axis. */
    var gazeX = 0f
    var gazeY = 0f

    var mood = Mood.NONE

    /** The open mouth wins over the face's own mouth. */
    var speaking = false

    /** Unused since voice was dropped; kept so old callers still compile. */
    var listeningRings = false
    var thinkingDots = false

    /** 0..1 phase for small loops (steam, sparkles, z's). */
    var phase = 0f

    /** Peaceful closed eyes, for sleeping. Overrides [blink]. */
    var eyesShut = false

    /** Trunk angle in degrees: 0 hangs straight down, 90 points forward, 180 points straight up. */
    var trunkAngle = REST_TRUNK

    /** Which way she faces: 1 right, -1 left. */
    var facing = 1

    /** 0..1: how far the tip curls back in, like a little hook. */
    var trunkCurl = 0.35f

    /** 0..1: ears lifted out, for jumps and surprise. */
    var earFlap = 0f

    /** 0..1 position in the walk cycle, and whether she is walking at all. */
    var walkPhase = 0f
    var walking = false

    /** 0..1: how cross she is. Reddens her, adds steam. */
    var anger = 0f

    /** 0..1: puffed up, strong and pleased with you. */
    var proud = 0f

    /** Head tilt in degrees, for the "no no no" shake. */
    var headTilt = 0f

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
        walkPhase = 0f
        walking = false
        anger = 0f
        proud = 0f
        headTilt = 0f
    }

    companion object {
        const val REST_TRUNK = 20f
    }
}

/**
 * Draws Sidekick, our pink cartoon elephant, whole: a round body on four sturdy legs, a little tail, a big ear,
 * a bow, lashes, rosy cheeks and a bendy trunk. She walks the way elephants do, one leg at a time with a slow,
 * heavy bob. Her faces: the four roast faces plus angry (red, steaming) and proud (puffed up, sparkling).
 *
 * Seen from the side with her head turned to us, facing [BlobPose.facing]. Everything is drawn facing right and
 * mirrored for left. The class keeps its old name from when Sidekick was a green blob.
 *
 * [draw] takes the box she stands in: [bw] x [bh] this frame (already squashed or stretched) and a [nominal]
 * size that fixes the stroke widths, so a squash does not make the outline breathe. Her feet touch the bottom
 * of the box.
 */
class BlobPainter {

    private val skinGradient = LinearGradient(0f, 0f, 0f, 1f, COLOR_SKIN_LIGHT, COLOR_SKIN_DARK, Shader.TileMode.CLAMP)
    private val gradientMatrix = Matrix()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = skinGradient }
    private val farPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SKIN_FAR }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RIM
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val innerEarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_INNER_EAR }
    private val nailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_NAIL }
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
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CHEEK }
    private val bowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BOW }
    private val bowKnotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BOW_KNOT }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
    private val angerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ANGER }
    private val steamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_STEAM }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SPARKLE }
    private val sweatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SWEAT }
    private val tonguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TONGUE }
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_RIM
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rect = RectF()
    private val path = Path()
    private val trunkPath = Path()

    private val fadePaints = arrayOf(
        bodyPaint, farPaint, rimPaint, innerEarPaint, nailPaint, trunkOutlinePaint, trunkPaint, wrinklePaint,
        scleraPaint, pupilPaint, mouthPaint, inkStrokePaint, lashPaint, bowPaint, bowKnotPaint, sweatPaint,
        tonguePaint, zPaint, sparklePaint,
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

    fun draw(canvas: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, nominal: Float, pose: BlobPose) {
        if (bw <= 0f || bh <= 0f || nominal <= 0f) return
        val u = bw
        val unit = nominal
        rimPaint.strokeWidth = unit * 0.016f
        inkStrokePaint.strokeWidth = unit * 0.020f
        lashPaint.strokeWidth = unit * 0.011f
        wrinklePaint.strokeWidth = unit * 0.009f
        zPaint.strokeWidth = unit * 0.012f
        val trunkWidth = nominal * 0.088f
        trunkPaint.strokeWidth = trunkWidth
        trunkOutlinePaint.strokeWidth = trunkWidth + rimPaint.strokeWidth * 2f

        gradientMatrix.setScale(1f, bh)
        gradientMatrix.postTranslate(0f, cy - bh / 2f)
        skinGradient.setLocalMatrix(gradientMatrix)

        val ground = cy + bh / 2f
        val cycle = pose.walkPhase * 2f * PI.toFloat()
        // A slow, heavy bob: down a little as each foot lands.
        val bob = if (pose.walking) u * 0.012f * abs(sin(cycle * 2f)) else 0f
        val puff = 1f + 0.10f * pose.proud

        canvas.save()
        if (pose.facing < 0) canvas.scale(-1f, 1f, cx, cy)

        // Body sits on the legs; everything above the legs moves with the bob.
        val bodyX = cx - u * 0.09f
        val bodyY = ground - bh * 0.36f + bob
        val bodyRx = u * 0.31f * puff
        val bodyRy = bh * 0.225f * puff
        val hipY = bodyY + bodyRy * 0.30f

        // Far legs first, in a darker pink, then the tail, the body and the near legs.
        drawLeg(canvas, bodyX - bodyRx * 0.48f, hipY, ground, u, legSwing(pose, 0.50f), far = true)
        drawLeg(canvas, bodyX + bodyRx * 0.60f, hipY, ground, u, legSwing(pose, 0.25f), far = true)
        drawTail(canvas, bodyX - bodyRx * 0.97f, bodyY - bodyRy * 0.15f, u, pose)
        rect.set(bodyX - bodyRx, bodyY - bodyRy, bodyX + bodyRx, bodyY + bodyRy)
        canvas.drawOval(rect, bodyPaint)
        if (pose.anger > 0.01f) {
            angerPaint.alpha = (60 * pose.anger * alpha / 255f).roundToInt()
            canvas.drawOval(rect, angerPaint)
        }
        canvas.drawOval(rect, rimPaint)
        drawLeg(canvas, bodyX - bodyRx * 0.68f, hipY, ground, u, legSwing(pose, 0.0f), far = false)
        drawLeg(canvas, bodyX + bodyRx * 0.38f, hipY, ground, u, legSwing(pose, 0.75f), far = false)
        // A soft shine on the back.
        canvas.save()
        canvas.rotate(-12f, bodyX - bodyRx * 0.3f, bodyY - bodyRy * 0.6f)
        rect.set(bodyX - bodyRx * 0.55f, bodyY - bodyRy * 0.82f, bodyX - bodyRx * 0.05f, bodyY - bodyRy * 0.55f)
        canvas.drawOval(rect, highlightPaint)
        canvas.restore()

        // Head, turned towards us, up and in front of the body.
        val hx = bodyX + bodyRx * 0.84f
        val hy = bodyY - bodyRy * 0.80f
        val hr = u * 0.20f
        canvas.save()
        canvas.rotate(pose.headTilt, hx, hy + hr)
        drawEar(canvas, hx - hr * 0.70f, hy + hr * 0.10f, hr, pose.earFlap)
        rect.set(hx - hr, hy - hr * 0.95f, hx + hr, hy + hr * 0.95f)
        canvas.drawOval(rect, bodyPaint)
        if (pose.anger > 0.01f) {
            angerPaint.alpha = (110 * pose.anger * alpha / 255f).roundToInt()
            canvas.drawOval(rect, angerPaint)
        }
        canvas.drawOval(rect, rimPaint)
        rect.set(hx - hr * 0.6f, hy - hr * 0.78f, hx - hr * 0.05f, hy - hr * 0.5f)
        canvas.drawOval(rect, highlightPaint)
        // Cheek.
        val cheekRed = pose.anger > 0.5f
        rect.set(hx - hr * 0.35f, hy + hr * 0.22f, hx + hr * 0.05f, hy + hr * 0.42f)
        if (cheekRed) {
            angerPaint.alpha = (200 * alpha / 255f).roundToInt()
            canvas.drawOval(rect, angerPaint)
        } else {
            canvas.drawOval(rect, cheekPaint)
        }

        if (pose.eyesShut) drawShutEyes(canvas, hx, hy, hr) else drawEyes(canvas, hx, hy, hr, pose)
        drawBrows(canvas, hx, hy, hr, pose.mood)
        val mx = hx - hr * 0.08f
        val my = hy + hr * 0.62f
        if (pose.speaking || pose.chatter > 0.05f || pose.yawn > 0.05f) {
            drawOpenMouth(canvas, mx, my, hr, pose)
        } else {
            drawMoodMouth(canvas, mx, my, hr, pose.mood)
        }
        drawTrunk(canvas, hx + hr * 0.55f, hy + hr * 0.30f, u, pose)
        drawBow(canvas, hx - hr * 0.35f, hy - hr * 0.88f, hr)
        canvas.restore()

        if (pose.mood == Mood.HORRIFIED) drawSweatDrop(canvas, hx + hr * 0.75f, hy - hr * 0.9f, hr)
        if (pose.anger > 0.3f) drawSteam(canvas, hx, hy - hr * 1.05f, hr, pose)
        if (pose.proud > 0.3f) drawSparkles(canvas, bodyX, bodyY, bodyRx, bodyRy, pose)
        if (pose.eyesShut) drawZs(canvas, hx + hr * 0.6f, hy - hr * 1.0f, hr, pose.phase)
        canvas.restore()
    }

    /**
     * An elephant's walk: one leg at a time (back, front, back, front), so two or three feet are always on
     * the ground. Each leg swings forward and back; the swing is a little faster than the stance, like a
     * real stride.
     */
    private fun legSwing(pose: BlobPose, offset: Float): Float {
        if (!pose.walking) return 0f
        val p = ((pose.walkPhase + offset) % 1f + 1f) % 1f
        return sin(p * 2f * PI.toFloat()) * 18f
    }

    /** A sturdy leg from [hipY] down to the ground, swung by [swing] degrees about the hip, with toenails. */
    private fun drawLeg(canvas: Canvas, x: Float, hipY: Float, ground: Float, u: Float, swing: Float, far: Boolean) {
        val w = u * 0.13f
        // A foot in the air shortens the leg a touch, so it lifts rather than slides.
        val lift = if (swing > 0f) u * 0.012f * (swing / 18f) else 0f
        val bottom = ground - lift
        canvas.save()
        canvas.rotate(swing, x, hipY)
        rect.set(x - w / 2f, hipY - w * 0.6f, x + w / 2f, bottom)
        canvas.drawRoundRect(rect, w * 0.45f, w * 0.45f, if (far) farPaint else bodyPaint)
        canvas.drawRoundRect(rect, w * 0.45f, w * 0.45f, rimPaint)
        if (!far) {
            for (k in 0 until 3) {
                val nx = x - w * 0.28f + k * w * 0.28f
                rect.set(nx - w * 0.11f, bottom - w * 0.24f, nx + w * 0.11f, bottom - w * 0.04f)
                canvas.drawOval(rect, nailPaint)
            }
        }
        canvas.restore()
    }

    private fun drawTail(canvas: Canvas, x: Float, y: Float, u: Float, pose: BlobPose) {
        val sway = if (pose.walking) sin(pose.walkPhase * 4f * PI.toFloat()) * u * 0.02f else 0f
        path.reset()
        path.moveTo(x, y)
        path.quadTo(x - u * 0.07f, y + u * 0.03f, x - u * 0.06f + sway, y + u * 0.13f)
        canvas.drawPath(path, rimPaint)
        rect.set(x - u * 0.085f + sway, y + u * 0.12f, x - u * 0.035f + sway, y + u * 0.17f)
        canvas.drawOval(rect, bowKnotPaint)
    }

    /** The big ear, behind the head. [flap] lifts it out. */
    private fun drawEar(canvas: Canvas, x: Float, y: Float, hr: Float, flap: Float) {
        canvas.save()
        canvas.rotate(-12f - 22f * flap, x + hr * 0.4f, y - hr * 0.3f)
        rect.set(x - hr * 0.90f, y - hr * 0.80f, x + hr * 0.50f, y + hr * 0.95f)
        canvas.drawOval(rect, bodyPaint)
        canvas.drawOval(rect, rimPaint)
        rect.set(x - hr * 0.70f, y - hr * 0.58f, x + hr * 0.25f, y + hr * 0.75f)
        canvas.drawOval(rect, innerEarPaint)
        canvas.restore()
    }

    private fun drawEyes(canvas: Canvas, hx: Float, hy: Float, hr: Float, pose: BlobPose) {
        val openness = (pose.blink * (1f + 0.28f * pose.wideEyes) * (1f - 0.85f * pose.yawn)).coerceIn(0.05f, 1.6f)
        val rx = hr * 0.16f * (1f + 0.10f * pose.wideEyes)
        val ry = hr * 0.21f * openness
        for ((i, ex) in floatArrayOf(hx - hr * 0.30f, hx + hr * 0.22f).withIndex()) {
            val ey = hy - hr * 0.08f
            if (pose.wideEyes > 0.3f || pose.mood == Mood.HORRIFIED) {
                rect.set(ex - rx * 1.4f, ey - ry * 1.35f, ex + rx * 1.4f, ey + ry * 1.35f)
                canvas.drawOval(rect, scleraPaint)
            }
            val scale = if (pose.mood == Mood.HORRIFIED) 0.6f else 1f
            val px = ex + pose.gazeX * rx * 0.3f
            val py = ey + pose.gazeY * ry * 0.3f
            rect.set(px - rx * scale, py - ry * scale, px + rx * scale, py + ry * scale)
            canvas.drawOval(rect, pupilPaint)
            if (ry > rx * 0.4f) canvas.drawCircle(px + rx * 0.35f, py - ry * 0.35f, rx * 0.35f, scleraPaint)
            // Lashes on the outer corner of each eye.
            val side = if (i == 0) -1f else 1f
            val lx = ex + side * rx * 0.8f
            val ly = ey - ry * 0.55f
            canvas.drawLine(lx, ly, lx + side * rx * 0.7f, ly - rx * 0.4f, lashPaint)
            canvas.drawLine(lx - side * rx * 0.2f, ly - ry * 0.3f, lx + side * rx * 0.35f, ly - ry * 0.3f - rx * 0.7f, lashPaint)
            // Roast faces bring the lid down.
            val lid = moodLid(pose.mood)
            if (lid > 0f && ry > 1f) {
                val top = ey - ry * 1.1f
                val edge = ey - ry + 2f * ry * lid
                canvas.drawRect(ex - rx * 1.2f, top, ex + rx * 1.2f, edge, bodyPaint)
                canvas.drawLine(ex - rx * 1.05f, edge, ex + rx * 1.05f, edge, lashPaint)
            }
        }
    }

    private fun drawShutEyes(canvas: Canvas, hx: Float, hy: Float, hr: Float) {
        for (ex in floatArrayOf(hx - hr * 0.30f, hx + hr * 0.22f)) {
            val ey = hy - hr * 0.05f
            path.reset()
            path.moveTo(ex - hr * 0.15f, ey)
            path.quadTo(ex, ey + hr * 0.14f, ex + hr * 0.15f, ey)
            canvas.drawPath(path, inkStrokePaint)
        }
    }

    /** Brows: one per eye; [mood] decides inner end, outer end and arch, in head radii (positive is down). */
    private fun drawBrows(canvas: Canvas, hx: Float, hy: Float, hr: Float, mood: Mood) {
        val sets: Array<FloatArray> = when (mood) {
            Mood.SMUG -> arrayOf(floatArrayOf(0.02f, 0.02f, 0f), floatArrayOf(-0.08f, -0.04f, -0.1f))
            Mood.BORED -> arrayOf(floatArrayOf(0.06f, 0.06f, 0f), floatArrayOf(0.06f, 0.06f, 0f))
            Mood.DISAPPOINTED -> arrayOf(floatArrayOf(-0.09f, 0.05f, 0f), floatArrayOf(-0.09f, 0.05f, 0f))
            Mood.HORRIFIED -> arrayOf(floatArrayOf(-0.13f, -0.07f, -0.1f), floatArrayOf(-0.13f, -0.07f, -0.1f))
            Mood.ANGRY -> arrayOf(floatArrayOf(0.12f, -0.08f, 0f), floatArrayOf(0.12f, -0.08f, 0f))
            Mood.PROUD -> arrayOf(floatArrayOf(-0.04f, -0.04f, -0.08f), floatArrayOf(-0.04f, -0.04f, -0.08f))
            Mood.NONE -> return
        }
        val base = hy - hr * 0.42f
        for ((i, ex) in floatArrayOf(hx - hr * 0.30f, hx + hr * 0.22f).withIndex()) {
            val (inner, outer, arch) = sets[i].let { Triple(it[0], it[1], it[2]) }
            val side = if (i == 0) -1f else 1f
            val ix = ex - side * hr * 0.16f
            val ox = ex + side * hr * 0.16f
            path.reset()
            path.moveTo(ix, base + inner * hr)
            path.quadTo(ex, base + (inner + outer) / 2f * hr + arch * hr, ox, base + outer * hr)
            canvas.drawPath(path, inkStrokePaint)
        }
    }

    private fun drawOpenMouth(canvas: Canvas, mx: Float, my: Float, hr: Float, pose: BlobPose) {
        val open = (0.25f + 0.55f * pose.chatter + 0.8f * pose.yawn).coerceIn(0f, 1f)
        rect.set(mx - hr * 0.12f, my - hr * 0.06f * open, mx + hr * 0.12f, my + hr * 0.16f * open)
        canvas.drawOval(rect, mouthPaint)
    }

    private fun drawMoodMouth(canvas: Canvas, mx: Float, my: Float, hr: Float, mood: Mood) {
        path.reset()
        when (mood) {
            Mood.SMUG -> {
                path.moveTo(mx - hr * 0.12f, my)
                path.quadTo(mx, my + hr * 0.08f, mx + hr * 0.14f, my - hr * 0.05f)
                canvas.drawPath(path, inkStrokePaint)
            }
            Mood.BORED -> canvas.drawLine(mx - hr * 0.1f, my + hr * 0.02f, mx + hr * 0.1f, my + hr * 0.02f, inkStrokePaint)
            Mood.DISAPPOINTED, Mood.ANGRY -> {
                path.moveTo(mx - hr * 0.12f, my + hr * 0.07f)
                path.quadTo(mx, my - hr * 0.05f, mx + hr * 0.12f, my + hr * 0.07f)
                canvas.drawPath(path, inkStrokePaint)
            }
            Mood.HORRIFIED -> {
                rect.set(mx - hr * 0.08f, my - hr * 0.06f, mx + hr * 0.08f, my + hr * 0.16f)
                canvas.drawOval(rect, mouthPaint)
                rect.set(mx - hr * 0.05f, my + hr * 0.06f, mx + hr * 0.05f, my + hr * 0.14f)
                canvas.drawOval(rect, tonguePaint)
            }
            Mood.PROUD -> {
                // A big open grin.
                path.moveTo(mx - hr * 0.15f, my - hr * 0.02f)
                path.quadTo(mx, my + hr * 0.24f, mx + hr * 0.15f, my - hr * 0.02f)
                path.close()
                canvas.drawPath(path, mouthPaint)
                rect.set(mx - hr * 0.06f, my + hr * 0.07f, mx + hr * 0.06f, my + hr * 0.13f)
                canvas.drawOval(rect, tonguePaint)
            }
            Mood.NONE -> {
                path.moveTo(mx - hr * 0.11f, my - hr * 0.02f)
                path.quadTo(mx, my + hr * 0.09f, mx + hr * 0.11f, my - hr * 0.02f)
                canvas.drawPath(path, inkStrokePaint)
            }
        }
    }

    /**
     * The trunk: a thick curve from the front of the face. It leaves the face heading down, then bends towards
     * [BlobPose.trunkAngle]: 0 hangs down, 90 points forward, 180 points up (trumpeting). A small hook at the
     * tip curls back.
     */
    private fun drawTrunk(canvas: Canvas, bx: Float, by: Float, u: Float, pose: BlobPose) {
        val angle = pose.trunkAngle.coerceIn(-40f, 200f)
        val length = u * 0.27f
        val a1 = Math.toRadians((angle / 3f).toDouble())
        val a3 = Math.toRadians(angle.toDouble())
        val p1x = bx + length * 0.40f * sin(a1).toFloat()
        val p1y = by + length * 0.40f * cos(a1).toFloat()
        val tipX = bx + length * sin(a3).toFloat() * 0.95f
        val tipY = by + length * 0.35f * cos(a1).toFloat() + length * 0.75f * cos(a3).toFloat()
        val a2 = Math.toRadians((angle * 0.8f).toDouble())
        val p2x = tipX - length * 0.35f * sin(a2).toFloat()
        val p2y = tipY - length * 0.35f * cos(a2).toFloat()
        trunkPath.reset()
        trunkPath.moveTo(bx, by)
        trunkPath.cubicTo(p1x, p1y, p2x, p2y, tipX, tipY)
        val curl = pose.trunkCurl.coerceIn(0f, 1f)
        if (curl > 0.01f) {
            val dirX = tipX - p2x
            val dirY = tipY - p2y
            val d = sqrt(dirX * dirX + dirY * dirY).coerceAtLeast(1f)
            val ux = dirX / d
            val uy = dirY / d
            val hook = length * 0.16f * curl
            trunkPath.quadTo(tipX + ux * hook, tipY + uy * hook, tipX + ux * hook * 0.6f + uy * hook, tipY + uy * hook * 0.6f - ux * hook)
        }
        canvas.drawPath(trunkPath, trunkOutlinePaint)
        canvas.drawPath(trunkPath, trunkPaint)
        for (k in 1..3) {
            val t = 0.2f + 0.15f * k
            val x = cubic(bx, p1x, p2x, tipX, t)
            val y = cubic(by, p1y, p2y, tipY, t)
            val tx = cubicD(bx, p1x, p2x, tipX, t)
            val ty = cubicD(by, p1y, p2y, tipY, t)
            val tl = sqrt(tx * tx + ty * ty).coerceAtLeast(1f)
            val half = trunkPaint.strokeWidth * 0.35f
            canvas.drawLine(x - ty / tl * half, y + tx / tl * half, x + ty / tl * half, y - tx / tl * half, wrinklePaint)
        }
    }

    private fun drawBow(canvas: Canvas, x: Float, y: Float, hr: Float) {
        val s = hr * 0.26f
        canvas.save()
        canvas.rotate(-18f, x, y)
        for (side in floatArrayOf(-1f, 1f)) {
            path.reset()
            path.moveTo(x, y)
            path.lineTo(x + side * s * 1.5f, y - s * 0.9f)
            path.quadTo(x + side * s * 2.0f, y, x + side * s * 1.5f, y + s * 0.9f)
            path.close()
            canvas.drawPath(path, bowPaint)
            canvas.drawPath(path, rimPaint)
        }
        canvas.drawCircle(x, y, s * 0.45f, bowKnotPaint)
        canvas.drawCircle(x, y, s * 0.45f, rimPaint)
        canvas.restore()
    }

    private fun drawSweatDrop(canvas: Canvas, x: Float, top: Float, hr: Float) {
        val size = hr * 0.18f
        path.reset()
        path.moveTo(x, top)
        path.quadTo(x + size, top + size * 1.4f, x, top + size * 1.9f)
        path.quadTo(x - size, top + size * 1.4f, x, top)
        path.close()
        canvas.drawPath(path, sweatPaint)
    }

    /** Two little puffs rising from her head when she is cross. */
    private fun drawSteam(canvas: Canvas, x: Float, y: Float, hr: Float, pose: BlobPose) {
        steamPaint.alpha = (200 * pose.anger * alpha / 255f).roundToInt()
        for (k in 0 until 2) {
            val p = (pose.phase + k * 0.5f) % 1f
            val px = x + (if (k == 0) -hr * 0.5f else hr * 0.4f)
            val py = y - p * hr * 0.9f
            val r = hr * (0.12f + 0.12f * p)
            canvas.drawCircle(px, py, r, steamPaint)
            canvas.drawCircle(px + r * 0.8f, py + r * 0.2f, r * 0.75f, steamPaint)
        }
    }

    /** Sparkles around her when she is proud of you. */
    private fun drawSparkles(canvas: Canvas, bx: Float, by: Float, rx: Float, ry: Float, pose: BlobPose) {
        for (k in 0 until 3) {
            val p = (pose.phase + k / 3f) % 1f
            val a = (k * 2.1f + 0.6f)
            val sx = bx + cos(a) * rx * 1.35f
            val sy = by - ry * 0.4f + sin(a) * ry * 1.6f
            val s = rx * 0.10f * (0.4f + 0.6f * sin(p * PI.toFloat()))
            path.reset()
            path.moveTo(sx, sy - s * 2f)
            path.lineTo(sx + s * 0.5f, sy - s * 0.5f)
            path.lineTo(sx + s * 2f, sy)
            path.lineTo(sx + s * 0.5f, sy + s * 0.5f)
            path.lineTo(sx, sy + s * 2f)
            path.lineTo(sx - s * 0.5f, sy + s * 0.5f)
            path.lineTo(sx - s * 2f, sy)
            path.lineTo(sx - s * 0.5f, sy - s * 0.5f)
            path.close()
            canvas.drawPath(path, sparklePaint)
        }
    }

    /** A little "z" floating up while she naps. */
    private fun drawZs(canvas: Canvas, x: Float, y: Float, hr: Float, phase: Float) {
        for (k in 0 until 2) {
            val p = (phase + k * 0.5f) % 1f
            val s = hr * (0.12f + 0.08f * k)
            val zx = x + p * hr * 0.4f + k * hr * 0.25f
            val zy = y - p * hr * 0.8f - k * hr * 0.2f
            path.reset()
            path.moveTo(zx, zy)
            path.lineTo(zx + s, zy)
            path.lineTo(zx, zy + s)
            path.lineTo(zx + s, zy + s)
            canvas.drawPath(path, zPaint)
        }
    }

    private fun cubic(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val v = 1f - t
        return v * v * v * a + 3f * v * v * t * b + 3f * v * t * t * c + t * t * t * d
    }

    private fun cubicD(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val v = 1f - t
        return 3f * v * v * (b - a) + 6f * v * t * (c - b) + 3f * t * t * (d - c)
    }

    companion object {
        /** The elephant's box fills this share of the floating window, leaving room for a hop and her ear. */
        const val BODY_FILL = 0.86f

        private const val CHEEK_ALPHA = 150
        private const val HIGHLIGHT_ALPHA = 0x88

        val COLOR_SKIN_LIGHT = 0xFFFFD0E4.toInt()
        val COLOR_SKIN_MID = 0xFFF8B2D1.toInt()
        val COLOR_SKIN_DARK = 0xFFEE8DB9.toInt()
        val COLOR_SKIN_FAR = 0xFFE07FAA.toInt()
        val COLOR_INNER_EAR = 0xFFFF9EC4.toInt()
        val COLOR_RIM = 0xFF9C3D6E.toInt()
        val COLOR_NAIL = 0xFFFFF4F8.toInt()
        val COLOR_SCLERA = 0xFFFFFFFF.toInt()
        val COLOR_INK = 0xFF3A1530.toInt()
        val COLOR_CHEEK = 0x96FF6F9F.toInt()
        val COLOR_BOW = 0xFFFF5C8A.toInt()
        val COLOR_BOW_KNOT = 0xFFFF7FA3.toInt()
        val COLOR_ANGER = 0xFFFF3B3B.toInt()
        val COLOR_STEAM = 0xFFC9C9D3.toInt()
        val COLOR_SPARKLE = 0xFFFFC93C.toInt()
        val COLOR_SWEAT = 0xFF7EC8F0.toInt()
        val COLOR_TONGUE = 0xFFFF7A8A.toInt()

        /** How far the upper lids come down for a face, 0 open to 1 shut. */
        fun moodLid(mood: Mood): Float = when (mood) {
            Mood.SMUG -> 0.42f
            Mood.BORED -> 0.58f
            Mood.DISAPPOINTED -> 0.26f
            Mood.ANGRY -> 0.30f
            else -> 0f
        }
    }
}
