package com.sangar.gal.sidekick

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import com.sangar.gal.Permissions
import com.sangar.gal.container
import com.sangar.gal.overlay.CardEvents
import com.sangar.gal.service.ForegroundApp
import com.sangar.gal.service.LiveTracker
import com.sangar.gal.service.NagLog
import com.sangar.gal.sidekick.scene.SceneDirector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Process-wide flag so the Compose screens can show whether Sidekick is on screen. The activity and the
 * service always share one process.
 */
object SidekickStatus {
    var running: Boolean by mutableStateOf(false)
}

/**
 * Floating Sidekick: the pink elephant in a small window over every app, hosted by GalService. Drag her
 * anywhere and she snaps to an edge; tap her and she hops. While a roast card is on screen she wears the card's
 * face ([CardEvents]), so the roast visibly comes from her.
 *
 * She does not just stand there: every few seconds she picks something to do: stroll along the edge, now and
 * then walk across to the other side, hop, look around, or take a nap. She keeps still while a finger is on
 * her, while a card is up and while the screen is off. Main thread only.
 *
 * She also reacts to what you do ([Reactions]): open a social app and she marches into the middle of the
 * screen and wags her trunk "nooo" at you; open something useful and she puffs up, sparkles and hops; stay on
 * the phone too long and she gets redder and crosser until steam comes out.
 *
 * (She used to listen for "open X" with the microphone. Voice was dropped because speech recognition did not
 * work reliably on real phones; the app-opening scenes remain in sidekick/scene for the debug tools.)
 */
class SidekickOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
) : SidekickHost {

    private val windowManager: WindowManager = context.getSystemService()!!
    private val power: PowerManager? = context.getSystemService()
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var view: SidekickView? = null
    private var params: WindowManager.LayoutParams? = null
    private var moodJob: Job? = null
    private var settingsJob: Job? = null
    private var walkAnimator: ValueAnimator? = null
    private var wanders = true
    private var released = false
    private var pollJob: Job? = null

    /** The face a roast card asked for; it wins over everything else. */
    private var cardMood = Mood.NONE

    /** True while she is busy with a "nooo" or a proud moment. */
    private var reacting = false
    private var reactionAnimator: ValueAnimator? = null
    private var lastPackage: String? = null
    private var lastReactionAt = 0L

    private val wanderRunnable = Runnable { wanderStep() }
    private val wakeRunnable = Runnable { view?.napping = false }

    /** Returns false when the window could not be added, typically because the overlay permission is gone. */
    fun show(): Boolean {
        if (view != null) return true
        if (!Permissions.canDrawOverlays(context)) {
            NagLog.w(C, "not showing Sidekick: canDrawOverlays is false")
            return false
        }

        val size = (SIZE_DP * density).roundToInt()
        val bounds = overlayBounds()
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps the keyboard and back button with the app underneath; it also implies
            // NOT_TOUCH_MODAL, so taps that miss the character fall through to whatever is below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = maxOf(bounds.left, bounds.right - size)
            y = bounds.top + ((bounds.height() - size) * 0.35f).roundToInt()
            title = "GAL Sidekick"
        }

        val sidekick = SidekickView(context).apply { host = this@SidekickOverlay }
        try {
            windowManager.addView(sidekick, layoutParams)
        } catch (e: RuntimeException) {
            // BadTokenException, or the permission vanished between the check and the call.
            NagLog.e(C, "could not add the Sidekick window", e)
            return false
        }
        view = sidekick
        params = layoutParams

        moodJob = scope.launch {
            CardEvents.face.collect { face ->
                val mood = face?.mood ?: Mood.NONE
                NagLog.d(C, "wearing face $mood")
                cardMood = mood
                view?.let { v ->
                    // A card wakes her up and stops her in her tracks.
                    if (mood != Mood.NONE) {
                        endReaction()
                        stopWalking()
                        v.napping = false
                        v.mood = mood
                    } else {
                        applyAnger()
                    }
                }
            }
        }
        settingsJob = scope.launch {
            context.container.settings.settings.collect { latest ->
                wanders = latest.sidekickWanders
                if (!wanders) {
                    stopWalking()
                    view?.napping = false
                }
            }
        }
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (power?.isInteractive == false) continue
                val pkg = withContext(Dispatchers.Default) { ForegroundApp.current(context) }
                onForegroundApp(pkg)
                applyAnger()
            }
        }
        SceneDirector.floatingBlob = floatingBlob
        SidekickStatus.running = true
        scheduleWander(FIRST_WANDER_MS)
        NagLog.i(C, "Sidekick is on screen")
        return true
    }

    /** Lets the stage elephant take the floating one's place for a debug scene. */
    private val floatingBlob = object : SceneDirector.FloatingBlob {
        override fun centerOnScreen(out: IntArray): Boolean {
            val v = view ?: return false
            if (v.width == 0 || !v.isAttachedToWindow) return false
            v.getLocationOnScreen(out)
            out[0] += v.width / 2
            out[1] += v.height / 2
            return true
        }

        override fun setHiddenForScene(hidden: Boolean) {
            view?.visibility = if (hidden) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
    }

    fun release() {
        released = true
        if (SceneDirector.floatingBlob === floatingBlob) {
            SceneDirector.cancel()
            SceneDirector.floatingBlob = null
        }
        moodJob?.cancel()
        moodJob = null
        settingsJob?.cancel()
        settingsJob = null
        pollJob?.cancel()
        pollJob = null
        reactionAnimator?.cancel()
        reactionAnimator = null
        walkAnimator?.cancel()
        walkAnimator = null
        main.removeCallbacksAndMessages(null)
        view?.let { v -> runCatching { windowManager.removeView(v) } }
        view = null
        params = null
        SidekickStatus.running = false
    }

    /** Rotation can leave the character parked off-screen. */
    fun onConfigurationChanged() {
        val p = params ?: return
        val v = view ?: return
        stopWalking()
        val bounds = overlayBounds()
        moveOverlay(
            p.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - v.width)),
            p.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - v.height)),
        )
    }

    // ---- Wandering -----------------------------------------------------------

    private fun scheduleWander(delayMs: Long) {
        main.removeCallbacks(wanderRunnable)
        if (!released) main.postDelayed(wanderRunnable, delayMs)
    }

    /** Picks the next little thing to do, then schedules the one after. */
    private fun wanderStep() {
        val v = view ?: return
        val busy = !wanders || v.isHeld || cardMood != Mood.NONE || reacting || walkAnimator != null ||
            v.napping || power?.isInteractive == false
        if (!busy) {
            val roll = Random.nextFloat()
            when {
                roll < 0.45f -> strollAlongEdge()
                roll < 0.57f -> crossToOtherSide()
                roll < 0.72f -> v.hop()
                roll < 0.90f -> v.lookAround()
                // Too cross to sleep.
                v.anger < 0.3f -> nap()
                else -> v.hop()
            }
        }
        scheduleWander(Random.nextLong(REST_MIN_MS, REST_MAX_MS))
    }

    /** Walks up or down the side she is on, to a new spot. */
    private fun strollAlongEdge() {
        val v = view ?: return
        val p = params ?: return
        val bounds = overlayBounds()
        val top = bounds.top
        val bottom = maxOf(top, bounds.bottom - v.height)
        if (bottom - top < v.height) return
        val minStep = (MIN_STROLL_DP * density).roundToInt()
        var target = Random.nextInt(top, bottom + 1)
        if (abs(target - p.y) < minStep) {
            target = if (p.y - top > bottom - p.y) p.y - minStep else p.y + minStep
        }
        walkTo(p.x, target.coerceIn(top, bottom), STROLL_DP_PER_S, facing = 0)
    }

    /** Now and then: walks straight across the screen to the other edge. */
    private fun crossToOtherSide() {
        val v = view ?: return
        val p = params ?: return
        val bounds = overlayBounds()
        val right = maxOf(bounds.left, bounds.right - v.width)
        val onLeft = p.x + v.width / 2 < bounds.centerX()
        val target = if (onLeft) right else bounds.left
        walkTo(target, p.y, CROSS_DP_PER_S, facing = if (onLeft) 1 else -1)
    }

    private fun nap() {
        val v = view ?: return
        v.napping = true
        main.removeCallbacks(wakeRunnable)
        main.postDelayed(wakeRunnable, Random.nextLong(NAP_MIN_MS, NAP_MAX_MS))
    }

    private fun walkTo(x: Int, y: Int, dpPerSecond: Float, facing: Int, then: (() -> Unit)? = null) {
        val v = view ?: return
        val p = params ?: return
        val fromX = p.x
        val fromY = p.y
        val distance = hypot((x - fromX).toFloat(), (y - fromY).toFloat())
        if (distance < 2f) {
            then?.invoke()
            return
        }
        val ms = (distance / (dpPerSecond * density) * 1000f).toLong().coerceIn(600L, 6000L)
        walkAnimator?.cancel()
        v.walkFacing = facing
        v.walking = true
        walkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                // Stop at once if she is grabbed or a card needs her face.
                val now = view
                if (now == null || now.isHeld || cardMood != Mood.NONE) {
                    cancel()
                    return@addUpdateListener
                }
                val t = it.animatedValue as Float
                moveOverlay((fromX + (x - fromX) * t).roundToInt(), (fromY + (y - fromY) * t).roundToInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    arrived()
                    // A walk that was part of a reaction and got interrupted (grabbed, tapped) ends the reaction.
                    if (!cancelled) then?.invoke() else if (then != null) endReaction()
                }
            })
            start()
        }
    }

    /** onAnimationEnd also runs after a cancel, so this is the one place a walk ends. */
    private fun arrived() {
        walkAnimator = null
        view?.walking = false
        view?.walkFacing = 0
    }

    private fun stopWalking() {
        val walk = walkAnimator ?: return
        walk.cancel()
        arrived()
    }

    // ---- Reactions -----------------------------------------------------------

    private fun onForegroundApp(pkg: String?) {
        if (pkg == null || pkg == lastPackage) return
        lastPackage = pkg
        if (pkg == context.packageName) return
        val category = runCatching { context.packageManager.getApplicationInfo(pkg, 0).category }.getOrNull()
        val kind = Reactions.classify(pkg, category)
        if (kind == AppKind.NEUTRAL) return
        val v = view ?: return
        val now = System.currentTimeMillis()
        if (cardMood != Mood.NONE || reacting || v.isHeld || now - lastReactionAt < REACT_COOLDOWN_MS) return
        lastReactionAt = now
        NagLog.d(C, "reacting to $pkg as $kind")
        if (kind == AppKind.SOCIAL) sayNo() else cheer()
    }

    /**
     * "Nooo": she hurries to the middle of the screen, in front of the app, looking horrified, then shakes her
     * head and wags her trunk at you to shoo you off it, and walks back to where she was.
     */
    private fun sayNo() {
        val v = view ?: return
        val p = params ?: return
        stopWalking()
        startReaction()
        v.napping = false
        v.anger = 0f
        v.mood = Mood.HORRIFIED
        val homeX = p.x
        val homeY = p.y
        val bounds = overlayBounds()
        val midX = bounds.centerX() - v.width / 2
        val midY = bounds.top + ((bounds.height() - v.height) * 0.55f).roundToInt()
        walkTo(midX, midY, HURRY_DP_PER_S, facing = if (midX > p.x) 1 else -1) {
            val me = view ?: return@walkTo
            me.mood = Mood.DISAPPOINTED
            me.walkFacing = 0
            reactionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = NO_MS
                addUpdateListener {
                    val t = it.animatedValue as Float
                    val wag = sin(t * 2f * PI.toFloat() * NO_WAGS)
                    me.trunkBoost = 60f + 50f * wag
                    me.headTilt = -9f * wag
                    // Turn to face one way then the other, like pacing in front of you.
                    me.walkFacing = if ((t * 4f).toInt() % 2 == 0) 1 else -1
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        reactionAnimator = null
                        if (cancelled) return
                        me.hop()
                        walkTo(homeX, homeY, STROLL_DP_PER_S * 1.5f, facing = 0) { endReaction() }
                    }
                })
                start()
            }
        }
    }

    /** Something useful: she puffs up, grins, raises her trunk like a trumpet and hops for joy. */
    private fun cheer() {
        val v = view ?: return
        stopWalking()
        startReaction()
        v.napping = false
        v.anger = 0f
        v.mood = Mood.PROUD
        reactionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PROUD_MS
            addUpdateListener {
                val t = it.animatedValue as Float
                // Swell in fast, hold, settle back at the end.
                val swell = when {
                    t < 0.1f -> t / 0.1f
                    t > 0.85f -> (1f - t) / 0.15f
                    else -> 1f
                }
                v.proud = swell
                v.trunkBoost = 120f * swell
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    reactionAnimator = null
                    endReaction()
                }
            })
            start()
        }
        v.hop()
        main.postDelayed({ if (reacting) view?.hop() }, 700L)
        main.postDelayed({ if (reacting) view?.hop() }, 1_400L)
    }

    private fun startReaction() {
        reactionAnimator?.cancel()
        reactionAnimator = null
        reacting = true
    }

    private fun endReaction() {
        if (!reacting) return
        reactionAnimator?.cancel()
        reactionAnimator = null
        reacting = false
        view?.let { v ->
            v.trunkBoost = 0f
            v.headTilt = 0f
            v.proud = 0f
            v.walkFacing = 0
            if (cardMood == Mood.NONE) v.mood = Mood.NONE
        }
        applyAnger()
    }

    /** The longer this phone session, the crosser she gets. A card or a reaction wins over it. */
    private fun applyAnger() {
        val v = view ?: return
        if (reacting || cardMood != Mood.NONE) return
        val s = LiveTracker.state.value
        val level = Reactions.anger(s.activeMillisNow(), s.thresholdMinutes, s.sessionOpen)
        v.anger = level
        v.mood = if (level >= Reactions.ANGRY_FACE_AT) Mood.ANGRY else Mood.NONE
        if (level >= Reactions.ANGRY_FACE_AT) v.napping = false
    }

    // ---- SidekickHost ------------------------------------------------------

    override fun overlayX(): Int = params?.x ?: 0

    override fun overlayY(): Int = params?.y ?: 0

    override fun moveOverlay(x: Int, y: Int) {
        val v = view ?: return
        val p = params ?: return
        if (p.x == x && p.y == y) return
        p.x = x
        p.y = y
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    override fun overlayBounds(): Rect {
        val size = usableScreenSize()
        return Rect(0, 0, size.x, size.y)
    }

    private fun usableScreenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            Point(
                metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom,
            )
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val point = Point()
            @Suppress("DEPRECATION")
            display.getSize(point)
            point
        }
    }

    /** A tap wakes her up and makes her hop. She has no voice any more, so that is the whole reply. */
    override fun onSidekickTapped() {
        val v = view ?: return
        endReaction()
        stopWalking()
        main.removeCallbacks(wakeRunnable)
        v.napping = false
        v.hop()
        scheduleWander(Random.nextLong(REST_MIN_MS, REST_MAX_MS))
    }

    private companion object {
        const val C = "Sidekick"
        const val SIZE_DP = 140f

        const val FIRST_WANDER_MS = 4_000L
        const val REST_MIN_MS = 5_000L
        const val REST_MAX_MS = 12_000L
        const val NAP_MIN_MS = 15_000L
        const val NAP_MAX_MS = 40_000L

        /** A stroll covers at least this much, so it reads as going somewhere. */
        const val MIN_STROLL_DP = 120f
        const val STROLL_DP_PER_S = 70f
        const val CROSS_DP_PER_S = 120f
        const val HURRY_DP_PER_S = 260f

        const val POLL_MS = 1_500L
        const val REACT_COOLDOWN_MS = 20_000L
        const val NO_MS = 3_200L
        const val NO_WAGS = 5f
        const val PROUD_MS = 4_500L
    }
}
