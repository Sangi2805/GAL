package com.sangar.gal.sidekick.scene

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import com.sangar.gal.service.NagLog
import com.sangar.gal.sidekick.stage.MovementTuning
import com.sangar.gal.sidekick.stage.Scene
import com.sangar.gal.sidekick.stage.StageEvent
import com.sangar.gal.sidekick.stage.StageOverlay
import com.sangar.gal.sidekick.stage.StageRenderer
import com.sangar.gal.sidekick.stage.StageView
import com.sangar.gal.sidekick.stage.World

/**
 * Plays one scene at a time on the stage window and turns its events into real effects: haptics, sounds and,
 * at the right moment, opening the app. Process-wide and main-thread only, so Voice Sidekick and the debug
 * tools share one stage.
 */
object SceneDirector {

    /** The floating Sidekick, when it is on screen. The stage blob takes its place for the scene. */
    interface FloatingBlob {
        /** Centre of the floating blob on screen, into [out] (x, y). False if it is not laid out. */
        fun centerOnScreen(out: IntArray): Boolean

        fun setHiddenForScene(hidden: Boolean)
    }

    /** Sounds for stage events. Optional; see [SceneSounds]. */
    fun interface Sounds {
        fun play(event: StageEvent)
    }

    class Callbacks(
        /** Open the app. Called while the stage is still visible, at most once. */
        val onLaunch: () -> Unit = {},
        /** The stage is gone. [skipped] is true when the user tapped through it. */
        val onDone: (skipped: Boolean) -> Unit = {},
    )

    var floatingBlob: FloatingBlob? = null

    var sounds: Sounds? = null

    /** Haptics on landings and hits. */
    var hapticsEnabled = true

    private var overlay: StageOverlay? = null
    private var playing: Playing? = null
    private val main = Handler(Looper.getMainLooper())

    val isPlaying: Boolean get() = playing != null

    private class Playing(
        val world: World,
        val view: StageView,
        val scene: Scene,
        val callbacks: Callbacks,
        var launched: Boolean = false,
    )

    /**
     * Shows the stage and plays the scene [makeScene] builds once the stage knows where the floating blob is.
     * Returns false if the stage could not be shown (no overlay permission); the caller should then open the
     * app directly. A scene already playing is skipped first.
     */
    fun play(
        context: Context,
        icon: Bitmap?,
        playground: Boolean = false,
        makeScene: (Entry) -> Scene,
        callbacks: Callbacks,
    ): Boolean {
        playing?.let { finish(it, skipped = true) }
        val app = context.applicationContext
        val density = app.resources.displayMetrics.density
        val tuning = MovementTuning().scaled(density)
        val world = World(tuning)
        val renderer = StageRenderer(app, tuning.bodySize, icon, playground)
        lateinit var current: Playing
        val listener = object : StageView.Listener {
            override fun onStageReady() {
                val scene = makeScene(entryFor(current.view))
                val p = Playing(world, current.view, scene, callbacks)
                current = p
                playing = p
                world.start(scene)
                floatingBlob?.setHiddenForScene(true)
                current.view.startLoop()
                NagLog.i(C, "scene ${scene.name} started")
            }

            override fun onStageEvents(events: List<StageEvent>) {
                for (event in events) handle(current, event)
            }
        }
        val view = StageView(app, world, renderer, listener, playground)
        // Placeholder until the stage is ready; replaced in onStageReady with the real scene.
        current = Playing(world, view, PendingScene, callbacks)
        val stage = overlay ?: StageOverlay(app).also { overlay = it }
        if (!stage.show(view)) return false
        playing = current
        // A stage that never gets going (no layout, a stuck frame clock) must not sit over the phone eating
        // touches. Whatever happens, it is gone a little after the longest scene could last.
        val limitMillis = ((if (playground) PLAYGROUND_LIMIT_SECONDS else SCENE_LIMIT_SECONDS) * 1000).toLong()
        main.postDelayed({
            val p = playing ?: return@postDelayed
            if (p.world === world) {
                NagLog.w(C, "stage watchdog: scene ${p.scene.name} still up after ${limitMillis / 1000}s, removing it")
                if (p.scene.opensApp && !p.launched) {
                    p.launched = true
                    runCatching { p.callbacks.onLaunch() }
                }
                finish(p, skipped = true)
            }
        }, limitMillis)
        return true
    }

    /**
     * The phone's "Remove animations" setting (animator duration scale 0). Scenes are skipped then: the app just
     * opens, after the line is said.
     */
    fun animationsOff(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    /** Ends whatever is playing at once, opening its app if it had not yet. */
    fun skip() {
        val p = playing ?: return
        p.world.skip()
    }

    /** Removes the stage without opening anything, e.g. when Sidekick is switched off. */
    fun cancel() {
        val p = playing ?: return
        p.launched = true
        finish(p, skipped = true)
    }

    private fun handle(p: Playing, event: StageEvent) {
        if (playing !== p) return
        sounds?.play(event)
        when (event) {
            is StageEvent.Landed -> if (event.impact > 0.6f) haptic(p, HapticFeedbackConstants.CLOCK_TICK)
            StageEvent.HeadBump, is StageEvent.HammerHit -> haptic(p, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            StageEvent.CrateBroken -> haptic(p, HapticFeedbackConstants.LONG_PRESS)
            StageEvent.LaunchApp -> if (!p.launched) {
                p.launched = true
                NagLog.i(C, "scene ${p.scene.name} opens the app${if (p.scene.skipped) " (skipped)" else ""}")
                runCatching { p.callbacks.onLaunch() }.onFailure { NagLog.e(C, "launch threw", it) }
            }
            StageEvent.SceneOver -> finish(p, skipped = p.scene.skipped)
            else -> Unit
        }
    }

    private fun haptic(p: Playing, constant: Int) {
        if (hapticsEnabled) p.view.performHapticFeedback(constant)
    }

    private fun finish(p: Playing, skipped: Boolean) {
        if (playing !== p) return
        playing = null
        overlay?.dismiss()
        floatingBlob?.setHiddenForScene(false)
        NagLog.i(C, "scene ${p.scene.name} over${if (skipped) " (skipped)" else ""}")
        runCatching { p.callbacks.onDone(skipped) }.onFailure { NagLog.e(C, "onDone threw", it) }
    }

    /** Where the floating blob sits, in stage coordinates. */
    private fun entryFor(view: StageView): Entry {
        val blob = floatingBlob ?: return Entry.OFFSCREEN_RIGHT
        val center = IntArray(2)
        if (!blob.centerOnScreen(center)) return Entry.OFFSCREEN_RIGHT
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        return Entry((center[0] - origin[0]).toFloat(), (center[1] - origin[1]).toFloat(), fromFloatingBlob = true)
    }

    /** Stands in until the stage knows its size. Never updated. */
    private object PendingScene : Scene() {
        override val name = "Pending"
        override fun start(world: World) = Unit
        override fun onUpdate(dt: Float, world: World) = Unit
    }

    private const val C = "Stage"

    /** Longest any scene may keep the stage up, including fades. Scenes end themselves well before. */
    private const val SCENE_LIMIT_SECONDS = 6f
    private const val PLAYGROUND_LIMIT_SECONDS = 125f
}
