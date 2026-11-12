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
import com.sangar.gal.service.GalServiceStarter
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

    /** True while she is in the middle of a scene or reaction (football, "Nooo", cheering). */
    @Volatile
    var busy: Boolean = false
}

/**
 * Lets the app ask the elephant to show off her "Nooo" once, right after setup, so a new user sees what she
 * does without waiting for a real social app. Same process as the service, so a plain flow is enough.
 */
object SidekickDemo {
    internal val requests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    fun sayNo() {
        requests.tryEmit("no")
    }

    fun football() {
        requests.tryEmit("football")
    }
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
    private var paceGen = 0
    private var demoJob: Job? = null

    /** From settings: how honest she is (Calm never gets cross) and whether she steps back for videos. */
    private var tone = com.sangar.gal.phrases.Tone.DEFAULT
    private var stepBackEnabled = true

    /** A video, map or game is in front: she fades and keeps still. */
    private var inStepBackApp = false
    private var steppedBack = false

    /** The system's "Remove animations" setting: she stays put and only changes her face. */
    private val reducedMotion: Boolean
        get() = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)

    private val bubble = SpeechBubble(context, windowManager)
    private var football: Football? = null
    private var pendingRoast: Speech? = null
    private var lastActivityAt = 0L
    private val plane = PaperPlane(context, windowManager)

    /** Social apps opened today, so every third one gets the broom. */
    private var socialDay = java.time.LocalDate.MIN
    private var socialOpens = 0

    /** "How long this time?": the app, the promise and when it runs out. */
    private var askHowLong = true
    private var reactingTo: String? = null
    private var intentionApp: String? = null
    private var intentionMinutes = 0
    private var intentionUntil = 0L
    private val askedAt = HashMap<String, Long>()
    private var plant = com.sangar.gal.data.Plant()
    private var liveliness = com.sangar.gal.data.Liveliness.NORMAL

    /** Scenes played today and when the last one was, for the limits in [com.sangar.gal.data.Liveliness]. */
    private var scenesDay = java.time.LocalDate.MIN
    private var scenesToday = 0
    private var lastSceneAt = 0L
    private val dismissTarget = DismissTarget(context, windowManager)
    private val hidePanel = HidePanel(context, windowManager)

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
                        paceGen++
                        endReaction()
                        stopWalking()
                        v.napping = false
                        v.mood = mood
                    } else {
                        applyAnger()
                    }
                    updateStepBack()
                }
            }
        }
        settingsJob = scope.launch {
            context.container.settings.settings.collect { latest ->
                wanders = latest.sidekickWanders
                tone = latest.tone
                liveliness = latest.liveliness
                askHowLong = latest.askHowLong
                plant = latest.plant
                view?.outfit = Outfits.pick(java.time.LocalDate.now(), plant)
                stepBackEnabled = latest.stepBack
                updateStepBack()
                applyAnger()
                if (!wanders) {
                    stopWalking()
                    view?.napping = false
                }
            }
        }
        demoJob = scope.launch {
            SidekickDemo.requests.collect {
                val v = view ?: return@collect
                if (cardMood != Mood.NONE || reacting || v.isHeld || hidePanel.isShowing) return@collect
                lastReactionAt = System.currentTimeMillis()
                if (it == "football") startFootball() else sayNo()
            }
        }
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                // Nothing to react to with the screen off or while she is hidden behind the hide card: check
                // back less often, and skip the usage-stats query entirely.
                if (power?.isInteractive == false || view?.visibility != android.view.View.VISIBLE) {
                    delay(POLL_MS * 3)
                    continue
                }
                val pkg = withContext(Dispatchers.Default) { ForegroundApp.current(context) }
                checkIntention(pkg)
                onForegroundApp(pkg)
                applyAnger()
            }
        }
        GalVoice.host = ::say
        GalVoice.hush = {
            pendingRoast = null
            if (bubble.isRoast) bubble.dismiss()
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
        demoJob?.cancel()
        demoJob = null
        reactionAnimator?.cancel()
        reactionAnimator = null
        GalVoice.host = null
        GalVoice.hush = null
        pendingRoast = null
        SidekickStatus.busy = false
        football?.cancel()
        football = null
        plane.cancel()
        bubble.dismiss()
        dismissTarget.hide()
        hidePanel.dismiss()
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
        cachedBounds = null
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
            v.napping || hidePanel.isShowing || v.visibility != android.view.View.VISIBLE || steppedBack || reducedMotion ||
            power?.isInteractive == false
        if (!busy) {
            val roll = Random.nextFloat()
            when {
                // Something that fits the hour: skipping in the morning, a book in the evening, bed at night.
                roll < 0.30f && startDailyActivity() -> Unit
                // Once you have been on the phone a while, she may get her ball out instead.
                v.mood != Mood.NONE && sceneAllowed() && roll < 0.35f -> startFootball()
                roll < 0.45f -> paceFromEdge()
                roll < 0.57f -> crossToOtherSide()
                roll < 0.72f -> v.hop()
                roll < 0.90f -> {
                    v.lookAround()
                    if (Random.nextFloat() < 0.4f) v.emote("?")
                }
                // Too cross to sleep.
                v.anger < 0.3f -> nap()
                else -> v.hop()
            }
        }
        scheduleWander(Random.nextLong(REST_MIN_MS, REST_MAX_MS))
    }

    /**
     * A short stroll: a few steps in from the side she is on, a look around, and back. She only ever walks left
     * and right, the way she faces, because a side-on elephant moving up or down the screen looks like she is
     * floating rather than walking.
     */
    private fun paceFromEdge() {
        val v = view ?: return
        val p = params ?: return
        val bounds = overlayBounds()
        val right = maxOf(bounds.left, bounds.right - v.width)
        val onLeft = p.x + v.width / 2 < bounds.centerX()
        val inward = if (onLeft) 1 else -1
        val distance = (Random.nextFloat() * (PACE_MAX_DP - PACE_MIN_DP) + PACE_MIN_DP) * density
        val homeX = p.x
        val outX = (p.x + inward * distance).roundToInt().coerceIn(bounds.left, right)
        // Anything that interrupts her (a drag, a tap, a reaction, a card) bumps the generation, so the delayed
        // "look around and walk back" steps of an old stroll quietly give up instead of fighting the new move.
        val gen = ++paceGen
        walkTo(outX, p.y, STROLL_DP_PER_S, facing = inward) {
            main.postDelayed({
                val me = view ?: return@postDelayed
                if (gen != paceGen || me.isHeld || cardMood != Mood.NONE || reacting || hidePanel.isShowing) return@postDelayed
                me.lookAround()
                main.postDelayed({
                    val again = view ?: return@postDelayed
                    if (gen != paceGen || again.isHeld || cardMood != Mood.NONE || reacting || hidePanel.isShowing) {
                        return@postDelayed
                    }
                    walkTo(homeX, params?.y ?: return@postDelayed, STROLL_DP_PER_S, facing = -inward)
                }, 900L)
            }, 400L)
        }
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
        v.walkDistance = 0f
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
                now.walkDistance = distance * t
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
        val category = if (pkg == context.packageName) {
            null
        } else {
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).category }.getOrNull()
        }
        inStepBackApp = pkg != context.packageName && Reactions.stepsBack(pkg, category)
        updateStepBack()
        if (pkg == context.packageName) return
        val kind = Reactions.classify(pkg, category)
        if (kind == AppKind.NEUTRAL) return
        val v = view ?: return
        val now = System.currentTimeMillis()
        if (cardMood != Mood.NONE || reacting || v.isHeld || hidePanel.isShowing ||
            v.visibility != android.view.View.VISIBLE || now - lastReactionAt < REACT_COOLDOWN_MS
        ) {
            return
        }
        lastReactionAt = now
        NagLog.d(C, "reacting to $pkg as $kind")
        reactingTo = pkg
        if (kind == AppKind.SOCIAL) {
            val today = java.time.LocalDate.now()
            if (today != socialDay) {
                socialDay = today
                socialOpens = 0
            }
            socialOpens++
            // Every third visit of the day she comes with the broom; otherwise the usual "Nooo".
            if (socialOpens % 3 == 0 && !reducedMotion) sweep() else sayNo()
        } else {
            cheer()
        }
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
        if (reducedMotion) {
            // "Remove animations" is on: no running across the screen, just the face, then back to normal.
            main.postDelayed({ view?.mood = Mood.DISAPPOINTED }, 600L)
            main.postDelayed({ if (reacting) endReaction() }, NO_MS)
            return
        }
        val homeX = p.x
        val homeY = p.y
        val bounds = overlayBounds()
        val midX = bounds.centerX() - v.width / 2
        // Straight across to the middle at the height she is at: walking, never floating.
        walkTo(midX, homeY, HURRY_DP_PER_S, facing = if (midX > p.x) 1 else -1) {
            val me = view ?: return@walkTo
            me.mood = Mood.DISAPPOINTED
            me.walkFacing = 0
            if (!askHowLongNow()) remark(ActivityLines.sayNo(tone))
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
        v.emote("♥")
        // Half the time the reward is a paper plane.
        if (Random.nextBoolean() && !reducedMotion) {
            remark(ActivityLines.plane(tone))
            main.postDelayed({ if (reacting) plane.fly(elephantRect(), overlayBounds()) }, 500L)
        } else {
            remark(ActivityLines.cheer(tone))
        }
        main.postDelayed({ if (reacting) view?.hop() }, 700L)
        main.postDelayed({ if (reacting) view?.hop() }, 1_400L)
    }

    // ---- Speaking ---------------------------------------------------------------

    /**
     * GAL says something in a bubble. A roast (it has buttons) stops whatever she was doing and she wears its
     * face until it goes; a quick remark just pops up. False when she cannot be seen, so the caller can fall
     * back to the card.
     */
    private fun say(speech: Speech): Boolean {
        val v = view ?: return false
        if (v.visibility != android.view.View.VISIBLE || hidePanel.isShowing || !v.isAttachedToWindow) return false
        if (speech.isRoast && football != null) {
            // Mid-game: let her finish (a game lasts a few seconds), then roast. Counted as shown.
            pendingRoast = speech
            return true
        }
        if (speech.isRoast) {
            paceGen++
            endReaction()
            stopWalking()
            main.removeCallbacks(wakeRunnable)
            v.napping = false
        } else if (bubble.isRoast) {
            // Never talk over a roast.
            return false
        }
        val shown = bubble.show(speech, elephantRect(), overlayBounds()) {
            if (speech.isRoast) com.sangar.gal.overlay.CardEvents.gone(this)
        }
        if (shown && speech.isRoast) com.sangar.gal.overlay.CardEvents.shown(this, speech.face)
        return shown
    }

    private fun remark(text: String) {
        say(Speech(text))
    }

    // ---- Her day: small activities by the hour ----------------------------------

    /**
     * Picks what fits the time of day. These are short and stay where she stands, so they are allowed more
     * often than football: one every [ACTIVITY_GAP_MS]. Returns false when nothing fits right now.
     */
    private fun startDailyActivity(): Boolean {
        if (System.currentTimeMillis() - lastActivityAt < ACTIVITY_GAP_MS) return false
        when (java.time.LocalTime.now().hour) {
            in 5..10 -> skipRope()
            in 11..19 -> stretchUp()
            in 20..22 -> readBook()
            23, in 0..4 -> goToBed()
            else -> return false
        }
        lastActivityAt = System.currentTimeMillis()
        return true
    }

    /** Morning: eight turns of the rope, a small jump on each, then her line. */
    private fun skipRope() {
        val v = view ?: return
        startReaction()
        v.napping = false
        v.mood = Mood.NONE
        v.prop = Prop.ROPE
        v.trunkBoost = 70f
        reactionAnimator = ValueAnimator.ofFloat(0f, SKIPS.toFloat()).apply {
            duration = SKIPS * 560L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val turn = (it.animatedValue as Float) % 1f
                v.propPhase = turn
                // The rope passes under her feet half way through a turn: that is when she is in the air.
                v.jump = (kotlin.math.cos((turn - 0.5f) * 2f * PI.toFloat())).coerceAtLeast(0f) * 0.55f
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    reactionAnimator = null
                    if (cancelled) return
                    v.prop = Prop.NONE
                    v.jump = 0f
                    v.trunkBoost = 0f
                    v.emote("♪")
                    remark(ActivityLines.skipping(tone))
                    main.postDelayed({ if (reacting && reactionAnimator == null && football == null) endReaction() }, 2_500L)
                }
            })
            start()
        }
    }

    /** Daytime: a long stretch up on her toes, trunk to the sky, a wobble, and her line. */
    private fun stretchUp() {
        val v = view ?: return
        startReaction()
        v.napping = false
        v.mood = Mood.NONE
        var said = false
        reactionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = STRETCH_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // Up over the first fifth, hold, down over the last fifth.
                val up = when {
                    t < 0.2f -> t / 0.2f
                    t > 0.8f -> (1f - t) / 0.2f
                    else -> 1f
                }
                v.stretch = up
                v.trunkBoost = 150f * up
                v.headTilt = if (t in 0.3f..0.7f) 5f * sin((t - 0.3f) / 0.4f * 2f * PI.toFloat() * 2f) else 0f
                if (!said && t > 0.35f) {
                    said = true
                    remark(ActivityLines.stretching(tone))
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    reactionAnimator = null
                    if (!cancelled) endReaction()
                }
            })
            start()
        }
    }

    /** The third social app of the day: she sweeps her way across the screen with a broom, and says so. */
    private fun sweep() {
        val v = view ?: return
        val p = params ?: return
        stopWalking()
        startReaction()
        v.napping = false
        v.anger = 0f
        v.mood = Mood.DISAPPOINTED
        v.prop = Prop.BROOM
        v.trunkBoost = 55f
        remark(ActivityLines.sweeping(tone))
        reactionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { v.propPhase = it.animatedValue as Float }
            start()
        }
        val bounds = overlayBounds()
        val right = maxOf(bounds.left, bounds.right - v.width)
        val onLeft = p.x + v.width / 2 < bounds.centerX()
        walkTo(if (onLeft) right else bounds.left, p.y, STROLL_DP_PER_S * 1.6f, facing = if (onLeft) 1 else -1) {
            endReaction()
        }
    }

    /** Put down after being carried: a shake of the head and a look. */
    private fun dropped() {
        val v = view ?: return
        if (reacting || cardMood != Mood.NONE || hidePanel.isShowing) return
        startReaction()
        v.mood = Mood.DISAPPOINTED
        if (Random.nextFloat() < 0.35f) remark(ActivityLines.dropped(tone))
        reactionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            addUpdateListener { v.headTilt = 8f * sin((it.animatedValue as Float) * 2f * PI.toFloat() * 3f) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    reactionAnimator = null
                    if (cancelled) return
                    main.postDelayed({ if (reacting && reactionAnimator == null && football == null) endReaction() }, 900L)
                }
            })
            start()
        }
    }

    // ---- "How long this time?" ---------------------------------------------------

    /**
     * Asks how long you mean to stay in the social app you just opened, with three answers to tap. At most once
     * per app every [ASK_GAP_MS]. Returns false when she should just say "Nooo" as before.
     */
    private fun askHowLongNow(): Boolean {
        val app = reactingTo ?: return false
        val now = System.currentTimeMillis()
        if (!askHowLong || intentionApp != null || now - (askedAt[app] ?: 0L) < ASK_GAP_MS) return false
        askedAt[app] = now
        return say(
            Speech(
                text = ActivityLines.howLong(tone),
                stayMillis = 9_000L,
                choices = listOf(5, 10, 20).map { minutes ->
                    "$minutes min" to {
                        intentionApp = app
                        intentionMinutes = minutes
                        intentionUntil = System.currentTimeMillis() + minutes * 60_000L
                        main.postDelayed({ remark(ActivityLines.counting(tone, minutes)) }, 250L)
                        Unit
                    }
                },
            ),
        )
    }

    /** Holds you to what you said: a call when the time is up, a nod if you left before it. */
    private fun checkIntention(foreground: String?) {
        val app = intentionApp ?: return
        if (foreground == null) return
        val now = System.currentTimeMillis()
        if (foreground != app) {
            // Gone from the app. Our own screen and a moment on the launcher count as leaving too.
            intentionApp = null
            if (now < intentionUntil && !reacting && cardMood == Mood.NONE) {
                remark(ActivityLines.leftEarly(tone))
                view?.emote("♥")
            }
            return
        }
        if (now < intentionUntil) return
        intentionApp = null
        say(
            Speech(
                text = ActivityLines.timeUp(tone, intentionMinutes),
                label = "GAL · $intentionMinutes min, as agreed",
                face = com.sangar.gal.overlay.Mascot.DISAPPOINTED,
                onOk = {},
                stayMillis = Speech.ROAST_MILLIS,
            ),
        )
    }

    /** Evening: she reads for a while, eyes down, nodding along, and says her line a few seconds in. */
    private fun readBook() {
        val v = view ?: return
        startReaction()
        v.napping = false
        v.mood = Mood.NONE
        v.prop = Prop.BOOK
        v.trunkBoost = 50f
        v.lookAt(0.5f, 0.9f)
        var said = false
        reactionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = READ_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                v.headTilt = 2.5f * sin(t * 2f * PI.toFloat() * 3f)
                if (!said && t > 0.25f) {
                    said = true
                    remark(ActivityLines.reading(tone))
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    reactionAnimator = null
                    if (!cancelled) endReaction()
                }
            })
            start()
        }
    }

    /** Late night: a yawn, her line, then a nightcap and a long sleep. */
    private fun goToBed() {
        val v = view ?: return
        startReaction()
        v.napping = false
        v.mood = Mood.NONE
        v.yawnNow()
        remark(ActivityLines.bedtime(tone))
        main.postDelayed({
            if (!reacting || football != null || reactionAnimator != null) return@postDelayed
            endReaction()
            val me = view ?: return@postDelayed
            if (me.mood == Mood.ANGRY || cardMood != Mood.NONE) return@postDelayed
            me.napping = true
            me.nightcap = true
            main.removeCallbacks(wakeRunnable)
            main.postDelayed(wakeRunnable, BED_NAP_MS)
        }, 3_000L)
    }

    // ---- Football ---------------------------------------------------------------

    /** The daily and hourly limits on scenes, from the "How lively is she?" setting. */
    private fun sceneAllowed(): Boolean {
        val today = java.time.LocalDate.now()
        if (today != scenesDay) {
            scenesDay = today
            scenesToday = 0
        }
        return scenesToday < liveliness.scenesPerDay &&
            System.currentTimeMillis() - lastSceneAt >= liveliness.minGapMinutes * 60_000L
    }

    /** A kickabout along the screen at the height she is standing. See [Football]. */
    private fun startFootball() {
        val v = view ?: return
        if (reducedMotion || steppedBack || football != null) return
        stopWalking()
        startReaction()
        v.napping = false
        v.mood = Mood.NONE
        v.anger = 0f
        val game = Football(context, windowManager, object : Football.Host {
            override fun elephant(): Rect = elephantRect()
            override fun bounds(): Rect = overlayBounds()
            override fun walk(x: Int, facing: Int, then: () -> Unit) {
                val y = params?.y ?: return
                walkTo(x, y, HURRY_DP_PER_S, facing, then)
            }

            override fun kickPose(facing: Int) {
                val me = view ?: return
                me.walkFacing = facing
                me.hop()
                reactionAnimator?.cancel()
                reactionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 380L
                    addUpdateListener { me.trunkBoost = 95f * sin((it.animatedValue as Float) * PI.toFloat()) }
                    start()
                }
            }

            override fun watch(x: Float, y: Float) {
                val me = view ?: return
                val e = elephantRect()
                val reach = overlayBounds().width() * 0.35f
                me.lookAt((x - e.centerX()) / reach, ((y - e.centerY()) / reach).coerceIn(-1f, 1f))
            }

            override fun finished(scored: Boolean) {
                football = null
                val me = view
                if (!scored || me == null) {
                    endReaction()
                    return
                }
                // Lap of honour: proud, two hops and her line.
                me.mood = Mood.PROUD
                me.proud = 1f
                me.trunkBoost = 110f
                me.hop()
                me.emote("♪")
                remark(ActivityLines.football(tone))
                main.postDelayed({ if (reacting) view?.hop() }, 600L)
                main.postDelayed({ if (reacting && football == null) endReaction() }, 3_200L)
            }
        })
        if (!game.start()) {
            endReaction()
            return
        }
        football = game
        scenesToday++
        lastSceneAt = System.currentTimeMillis()
        NagLog.i(C, "football started ($scenesToday today)")
    }

    private fun startReaction() {
        paceGen++
        reactionAnimator?.cancel()
        reactionAnimator = null
        reacting = true
        SidekickStatus.busy = true
        // A reaction is meant to be seen: full strength, even over a video.
        updateStepBack()
    }

    private fun endReaction() {
        if (!reacting) return
        reactionAnimator?.cancel()
        reactionAnimator = null
        reacting = false
        SidekickStatus.busy = false
        football?.cancel()
        football = null
        view?.let { v ->
            v.trunkBoost = 0f
            v.headTilt = 0f
            v.proud = 0f
            v.prop = Prop.NONE
            v.jump = 0f
            v.stretch = 0f
            v.lookAt(0f, 0f)
            v.walkFacing = 0
            if (cardMood == Mood.NONE) v.mood = Mood.NONE
        }
        applyAnger()
        updateStepBack()
        // A roast that came due during a game was held back so it would not cut the game off: say it now.
        pendingRoast?.let { held ->
            pendingRoast = null
            main.post { if (!say(held)) held.onOk?.invoke() }
        }
    }

    /** The longer this phone session, the crosser she gets. A card or a reaction wins over it. */
    private fun applyAnger() {
        val v = view ?: return
        if (reacting || cardMood != Mood.NONE) return
        val s = LiveTracker.state.value
        val level = Reactions.anger(s.activeMillisNow(), s.thresholdMinutes, s.sessionOpen)
        val feeling = Reactions.feeling(level, tone)
        v.anger = feeling.tint
        v.mood = feeling.mood
        if (feeling.mood == Mood.ANGRY) v.napping = false
    }

    /**
     * Low presence: while a video, map or game is in front she fades to a ghost of herself and stops wandering,
     * so she never covers what you are watching or following. A reaction brings her back to full strength.
     */
    private fun updateStepBack() {
        val v = view ?: return
        val back = stepBackEnabled && inStepBackApp && !reacting && cardMood == Mood.NONE
        if (back == steppedBack) return
        steppedBack = back
        if (back) {
            stopWalking()
            v.napping = false
        }
        v.animate().alpha(if (back) STEP_BACK_ALPHA else 1f).setDuration(350L).start()
    }

    // ---- Drag to the X to hide her -----------------------------------------------

    override fun onDragStarted() {
        view?.emote("!")
        paceGen++
        endReaction()
        stopWalking()
        main.removeCallbacks(wakeRunnable)
        view?.napping = false
        dismissTarget.show(overlayBounds())
    }

    override fun onDragMoved(rawX: Float, rawY: Float) {
        dismissTarget.track(rawX, rawY)
    }

    override fun onDragEnded(rawX: Float, rawY: Float, cancelled: Boolean): Boolean {
        val over = !cancelled && dismissTarget.track(rawX, rawY)
        dismissTarget.hide()
        if (!over) {
            // Let her settle at the edge first, then she tells you what she thinks of being carried.
            main.postDelayed({ dropped() }, 350L)
            return false
        }
        val v = view ?: return false
        v.visibility = android.view.View.INVISIBLE
        hidePanel.show { choice ->
            if (choice == null) {
                view?.let { me ->
                    me.visibility = android.view.View.VISIBLE
                    me.snapToNearestEdge()
                    me.hop()
                }
            } else {
                hide(choice)
            }
        }
        return true
    }

    /** Saves the choice; the service then takes her off screen (and brings her back when the time is up). */
    private fun hide(choice: HideFor) {
        NagLog.i(C, "hidden by the user: $choice")
        scope.launch {
            val repo = context.container.settings
            val until = choice.until(System.currentTimeMillis())
            if (until == null) repo.setVoiceEnabled(false) else repo.setElephantHiddenUntil(until)
            GalServiceStarter.syncFromForeground(context, repo.current())
        }
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
        if (bubble.isShowing) bubble.follow(elephantRect(), overlayBounds())
    }

    private fun elephantRect(): Rect {
        val p = params
        val v = view
        if (p == null || v == null) return Rect()
        val size = if (v.width > 0) v.width else p.width
        return Rect(p.x, p.y, p.x + size, p.y + size)
    }

    /**
     * Cached: the view asks for this on every frame and every touch move, and working it out is a call into the
     * window manager. It only changes with the configuration (rotation, display size), which clears it.
     */
    private var cachedBounds: Rect? = null

    override fun overlayBounds(): Rect = cachedBounds ?: run {
        val size = usableScreenSize()
        Rect(0, 0, size.x, size.y).also { cachedBounds = it }
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
        paceGen++
        endReaction()
        stopWalking()
        main.removeCallbacks(wakeRunnable)
        v.napping = false
        v.hop()
        if (Random.nextFloat() < 0.4f) v.emote("♥")
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
        const val PACE_MIN_DP = 70f
        const val PACE_MAX_DP = 150f
        const val STROLL_DP_PER_S = 70f
        const val CROSS_DP_PER_S = 120f
        const val HURRY_DP_PER_S = 260f

        const val POLL_MS = 1_500L
        const val ACTIVITY_GAP_MS = 30 * 60_000L
        const val SKIPS = 8
        const val READ_MS = 9_000L
        const val STRETCH_MS = 5_000L
        const val ASK_GAP_MS = 30 * 60_000L
        const val BED_NAP_MS = 5 * 60_000L
        const val STEP_BACK_ALPHA = 0.3f
        const val REACT_COOLDOWN_MS = 20_000L
        const val NO_MS = 3_200L
        const val NO_WAGS = 5f
        const val PROUD_MS = 4_500L
    }
}
