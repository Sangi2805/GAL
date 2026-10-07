package com.sangar.gal.sidekick.stage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.sangar.gal.sidekick.BlobPainter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a [World] onto the stage: a soft shade along the bottom so the action reads over any app, the crate
 * with the real app icon, particles, the elephant and her trunk, the speech bubble and the hit words. Everything is
 * allocated up front; a frame allocates nothing except a new text layout when the bubble's line changes.
 */
class StageRenderer(
    context: Context,
    /** Body size in pixels, from the scaled tuning. */
    private val body: Float,
    /** The app's launcher icon, square. Null draws a plain crate. */
    private val icon: Bitmap?,
    /** Playground: draws the touch zones and the tuning numbers. */
    private val playground: Boolean,
) {
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = density * context.resources.configuration.fontScale

    private val painter = BlobPainter()

    private val shadePaint = Paint()
    private var shadeTop = -1f

    private val woodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WOOD }
    private val woodDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WOOD_DARK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val plankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WOOD_DARK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CRACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TANGERINE }
    private val chuteStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CREAM }
    private val stringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        style = Paint.Style.STROKE
    }
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DUST }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WOOD }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STAR }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val bubbleEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val bubbleText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val wordFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CREAM
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val wordStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val debugText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.MONOSPACE
    }

    private val rect = RectF()
    private val path = Path()
    private val iconSrc = Rect()
    private var bubbleLayout: StaticLayout? = null
    private var bubbleLayoutText: String? = null

    init {
        woodDarkPaint.strokeWidth = body * 0.045f
        plankPaint.strokeWidth = body * 0.025f
        crackPaint.strokeWidth = body * 0.035f
        stringPaint.strokeWidth = body * 0.012f
        bubbleEdgePaint.strokeWidth = 2f * density
        bubbleText.textSize = 17f * scaledDensity
        wordFill.textSize = 30f * scaledDensity
        wordStroke.textSize = 30f * scaledDensity
        wordStroke.strokeWidth = 5f * density
        debugText.textSize = 12f * scaledDensity
        icon?.let { iconSrc.set(0, 0, it.width, it.height) }
    }

    fun draw(canvas: Canvas, world: World) {
        if (!world.ready) return
        val fade = world.fade
        drawShade(canvas, world, fade)
        for (crate in world.crates) drawCrate(canvas, world, crate, fade)
        drawParticles(canvas, world, fade)
        drawBlob(canvas, world, fade)
        drawWord(canvas, world, fade)
        drawBubble(canvas, world, fade)
        if (playground) drawPlayground(canvas, world)
    }

    // ---- Ground shade -----------------------------------------------------

    private fun drawShade(canvas: Canvas, world: World, fade: Float) {
        val top = world.groundY - body * 2.2f
        if (top != shadeTop) {
            shadeTop = top
            shadePaint.shader = LinearGradient(0f, top, 0f, world.height, 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP)
        }
        shadePaint.alpha = (255 * fade).roundToInt()
        canvas.drawRect(0f, top, world.width, world.height, shadePaint)
    }

    // ---- Crate ------------------------------------------------------------

    private fun drawCrate(canvas: Canvas, world: World, crate: AppCrate, fade: Float) {
        val alpha = (255 * fade).roundToInt()
        if (crate.broken) {
            drawChuteAway(canvas, crate, alpha)
            drawIconPop(canvas, crate, alpha)
            return
        }
        if (crate.state == AppCrate.State.WAITING) return
        val shakeX = if (crate.shake > 0f) sin(world.time * 90f) * body * 0.05f * crate.shake else 0f
        val cx = crate.x + crate.sway + shakeX
        val bottom = crate.y + crate.bumpOffset
        val w = crate.width
        val h = crate.height
        if (crate.parachute) drawChute(canvas, cx, bottom - h, w, crate.chuteOpen, alpha)

        rect.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        val corner = w * 0.1f
        woodPaint.alpha = alpha
        woodDarkPaint.alpha = alpha
        plankPaint.alpha = alpha
        canvas.drawRoundRect(rect, corner, corner, woodPaint)
        // Planks and a frame, so it reads as a crate and not a box.
        for (i in 1..2) {
            val py = rect.top + h * i / 3f
            canvas.drawLine(rect.left + w * 0.06f, py, rect.right - w * 0.06f, py, plankPaint)
        }
        canvas.drawRoundRect(rect, corner, corner, woodDarkPaint)

        // White plate with the app's own icon.
        val plate = w * 0.62f
        rect.set(cx - plate / 2f, bottom - h / 2f - plate / 2f, cx + plate / 2f, bottom - h / 2f + plate / 2f)
        platePaint.alpha = alpha
        canvas.drawRoundRect(rect, plate * 0.22f, plate * 0.22f, platePaint)
        icon?.let {
            val inset = plate * 0.08f
            rect.inset(inset, inset)
            iconPaint.alpha = alpha
            canvas.drawBitmap(it, iconSrc, rect, iconPaint)
        }
        if (crate.cracks > 0) drawCracks(canvas, cx, bottom, w, h, crate.cracks, alpha)
    }

    private fun drawChute(canvas: Canvas, cx: Float, crateTop: Float, w: Float, open: Float, alpha: Int) {
        val span = w * (0.6f + 1.0f * open)
        val canopyH = w * 0.55f * open.coerceAtLeast(0.3f)
        val canopyBottom = crateTop - w * 0.7f
        stringPaint.alpha = alpha
        canvas.drawLine(cx - w * 0.42f, crateTop, cx - span / 2f, canopyBottom, stringPaint)
        canvas.drawLine(cx + w * 0.42f, crateTop, cx + span / 2f, canopyBottom, stringPaint)
        canvas.drawLine(cx, crateTop, cx, canopyBottom, stringPaint)
        rect.set(cx - span / 2f, canopyBottom - canopyH, cx + span / 2f, canopyBottom + canopyH)
        chutePaint.alpha = alpha
        canvas.drawArc(rect, 180f, 180f, true, chutePaint)
        // Two cream gores.
        chuteStripePaint.alpha = alpha
        canvas.drawArc(rect, 220f, 22f, true, chuteStripePaint)
        canvas.drawArc(rect, 298f, 22f, true, chuteStripePaint)
    }

    private fun drawChuteAway(canvas: Canvas, crate: AppCrate, alpha: Int) {
        val t = crate.chuteAway
        if (t < 0f || t > 0.7f) return
        val fadeOut = (1f - t / 0.7f)
        val cx = crate.x + body * 1.4f * t
        val top = crate.y - crate.height - body * 2.2f * t
        canvas.save()
        canvas.rotate(35f * t, cx, top)
        drawChute(canvas, cx, top, crate.width, 1f, (alpha * fadeOut).roundToInt())
        canvas.restore()
    }

    private fun drawIconPop(canvas: Canvas, crate: AppCrate, alpha: Int) {
        val t = crate.brokenFor
        val p = (t / 0.3f).coerceIn(0f, 1f)
        val ease = 1f - (1f - p) * (1f - p)
        val size = crate.width * 0.62f * (1f + 0.85f * ease)
        val cx = crate.x
        val cy = crate.y - crate.height / 2f - body * 1.3f * ease
        // A burst ring behind the icon.
        starPaint.alpha = (alpha * (1f - p) * 0.8f).roundToInt()
        canvas.drawCircle(cx, cy, size * (0.6f + 0.5f * p), starPaint)
        rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        platePaint.alpha = alpha
        canvas.drawRoundRect(rect, size * 0.22f, size * 0.22f, platePaint)
        icon?.let {
            val inset = size * 0.08f
            rect.inset(inset, inset)
            iconPaint.alpha = alpha
            canvas.drawBitmap(it, iconSrc, rect, iconPaint)
        }
    }

    private fun drawCracks(canvas: Canvas, cx: Float, bottom: Float, w: Float, h: Float, cracks: Int, alpha: Int) {
        crackPaint.alpha = alpha
        val top = bottom - h
        path.reset()
        // One jagged crack per hit, each starting from a different edge.
        if (cracks >= 1) {
            path.moveTo(cx - w * 0.5f, top + h * 0.25f)
            path.lineTo(cx - w * 0.3f, top + h * 0.33f)
            path.lineTo(cx - w * 0.36f, top + h * 0.45f)
            path.lineTo(cx - w * 0.18f, top + h * 0.5f)
        }
        if (cracks >= 2) {
            path.moveTo(cx + w * 0.5f, top + h * 0.7f)
            path.lineTo(cx + w * 0.28f, top + h * 0.62f)
            path.lineTo(cx + w * 0.34f, top + h * 0.5f)
            path.lineTo(cx + w * 0.16f, top + h * 0.42f)
        }
        if (cracks >= 3) {
            path.moveTo(cx - w * 0.05f, top)
            path.lineTo(cx + w * 0.06f, top + h * 0.18f)
            path.lineTo(cx - w * 0.04f, top + h * 0.3f)
        }
        canvas.drawPath(path, crackPaint)
    }

    // ---- Particles --------------------------------------------------------

    private fun drawParticles(canvas: Canvas, world: World, fade: Float) {
        val p = world.particles
        for (i in 0 until p.capacity) {
            val kind = p.kind[i] ?: continue
            val a = (255 * fade * p.fade(i)).roundToInt()
            val x = p.x[i]
            val y = p.y[i]
            val s = p.size[i]
            when (kind) {
                ParticleKind.DUST -> {
                    dustPaint.alpha = (a * 0.7f).roundToInt()
                    canvas.drawCircle(x, y, s * (1f + 0.8f * (1f - p.fade(i))), dustPaint)
                }
                ParticleKind.CHIP -> {
                    chipPaint.alpha = a
                    canvas.save()
                    canvas.rotate(p.angle[i], x, y)
                    canvas.drawRect(x - s, y - s * 0.45f, x + s, y + s * 0.45f, chipPaint)
                    canvas.restore()
                }
                ParticleKind.STAR -> {
                    starPaint.alpha = a
                    drawStar(canvas, x, y, s, p.angle[i])
                }
            }
        }
    }

    private fun drawStar(canvas: Canvas, x: Float, y: Float, r: Float, angle: Float) {
        path.reset()
        for (k in 0 until 8) {
            val a = (angle / 180f * PI.toFloat()) + k * PI.toFloat() / 4f
            val rr = if (k % 2 == 0) r else r * 0.38f
            val px = x + cos(a) * rr
            val py = y + sin(a) * rr
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, starPaint)
    }

    // ---- The elephant -----------------------------------------------------

    private fun drawBlob(canvas: Canvas, world: World, fade: Float) {
        val a = world.actor
        val bw = body * a.scaleX
        val bh = body * a.scaleY
        val cx = a.x
        val cy = a.y - a.hopLift - bh / 2f
        a.pose.facing = a.facing
        a.pose.trunkAngle = when {
            a.trunkUp -> a.trunkAngle
            // Rising in a jump: trunk thrown up, so it is the trunk that boops the crate from below.
            !a.onGround && a.vy < 0f -> 150f
            !a.onGround -> 60f
            // Running: the trunk swings with the hops.
            else -> BlobActor.TRUNK_REST + 12f * kotlin.math.sin(world.time * 9f)
        }
        a.pose.earFlap = if (a.onGround) 0f else 1f
        // On the ground and moving: legs stride. The phase follows the clock, a stride every 0.6 s.
        a.pose.walking = a.onGround && kotlin.math.abs(a.vx) > 1f
        a.pose.walkPhase = (world.time / 0.6f) % 1f
        painter.alpha = (255 * fade * a.alpha).roundToInt()
        painter.draw(canvas, cx, cy, bw, bh, body, a.pose)
    }

    // ---- Words ------------------------------------------------------------

    private fun drawWord(canvas: Canvas, world: World, fade: Float) {
        val f = world.floatText
        val text = f.text ?: return
        val scale = f.scale
        if (scale <= 0f) return
        val alpha = (255 * fade * f.alpha).roundToInt()
        wordFill.alpha = alpha
        wordStroke.alpha = alpha
        canvas.save()
        canvas.translate(f.x, f.y)
        canvas.rotate(f.tilt)
        canvas.scale(scale, scale)
        canvas.drawText(text, 0f, 0f, wordStroke)
        canvas.drawText(text, 0f, 0f, wordFill)
        canvas.restore()
    }

    private fun drawBubble(canvas: Canvas, world: World, fade: Float) {
        val b = world.bubble
        val text = b.text ?: return
        val scale = b.scale
        if (scale <= 0f) return
        val layout = layoutFor(text, world)
        val pad = 12f * density
        val w = layoutWidth(layout) + pad * 2f
        val h = layout.height + pad * 2f
        val a = world.actor
        val tail = 12f * density
        val margin = 12f * density
        val blobTop = a.y - a.hopLift - body * a.scaleY
        val cx = a.x.coerceIn(world.left + margin + w / 2f, world.right - margin - w / 2f)
        val bottom = blobTop - tail - 6f * density
        val alpha = (255 * fade).roundToInt()
        bubblePaint.alpha = alpha
        bubbleEdgePaint.alpha = alpha
        bubbleText.alpha = alpha

        canvas.save()
        canvas.scale(scale, scale, a.x, bottom + tail)
        rect.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        val r = 16f * density
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        // Tail towards the blob.
        val tipX = a.x.coerceIn(rect.left + r, rect.right - r)
        path.moveTo(tipX - tail * 0.8f, bottom - 1f)
        path.lineTo(tipX, bottom + tail)
        path.lineTo(tipX + tail * 0.8f, bottom - 1f)
        path.close()
        canvas.drawPath(path, bubblePaint)
        canvas.drawPath(path, bubbleEdgePaint)
        canvas.drawRect(tipX - tail * 0.7f, bottom - bubbleEdgePaint.strokeWidth * 1.5f, tipX + tail * 0.7f, bottom, bubblePaint)
        canvas.translate(rect.left + pad, rect.top + pad)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layoutFor(text: String, world: World): StaticLayout {
        val cached = bubbleLayout
        if (cached != null && bubbleLayoutText == text) return cached
        val maxWidth = min(world.width * 0.7f, 300f * density).roundToInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, bubbleText, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(4)
            .setLineSpacing(0f, 1.05f)
            .build()
        bubbleLayout = layout
        bubbleLayoutText = text
        return layout
    }

    private fun layoutWidth(layout: StaticLayout): Float {
        var w = 0f
        for (i in 0 until layout.lineCount) w = maxOf(w, layout.getLineWidth(i))
        return w.coerceAtMost(layout.width.toFloat())
    }

    // ---- Playground -------------------------------------------------------

    private fun drawPlayground(canvas: Canvas, world: World) {
        val third = world.width / 3f
        val stripBottom = world.top + CLOSE_STRIP_DP * density
        canvas.drawRect(0f, world.top, world.width, stripBottom, debugPaint)
        canvas.drawText("Tap here to close the playground", 16f * density, stripBottom - 18f * density, debugText)
        canvas.drawRect(0f, stripBottom, third, world.height, debugPaint)
        canvas.drawRect(third * 2f, stripBottom, world.width, world.height, debugPaint)
        val t = world.tuning
        val a = world.actor
        val lines = arrayOf(
            "hold left/right third: run   middle: jump",
            "speed %.0f/%.0f  vy %.0f".format(a.vx / density, t.maxRunSpeed / density, a.vy / density),
            "jump %.0fdp  g %.0f  fall x%.1f".format(t.jumpHeight / density, t.gravity / density, t.fallGravityMultiplier),
        )
        var y = stripBottom + 28f * density
        for (line in lines) {
            canvas.drawText(line, 16f * density, y, debugText)
            y += 18f * density
        }
    }

    companion object {
        /** Height of the playground's close strip. */
        const val CLOSE_STRIP_DP = 72f

        val WOOD = 0xFFC8873E.toInt()
        val WOOD_DARK = 0xFF7A4B1F.toInt()
        val CRACK = 0xFF3B2410.toInt()
        val TANGERINE = 0xFFF28C28.toInt()
        val CREAM = 0xFFFBF3E4.toInt()
        val INK = 0xFF0E2B1B.toInt()
        val DUST = 0xFFD9D2C5.toInt()
        val STAR = 0xFFFFD166.toInt()
    }
}
