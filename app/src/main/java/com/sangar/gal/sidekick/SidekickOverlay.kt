package com.sangar.gal.sidekick

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import com.sangar.gal.BuildConfig
import com.sangar.gal.Permissions
import com.sangar.gal.R
import com.sangar.gal.container
import com.sangar.gal.data.Settings
import com.sangar.gal.overlay.CardEvents
import com.sangar.gal.overlay.QuietRules
import com.sangar.gal.service.NagLog
import com.sangar.gal.sidekick.scene.AfterSceneAndSpeech
import com.sangar.gal.sidekick.scene.AppHabits
import com.sangar.gal.sidekick.scene.AppHabitsReader
import com.sangar.gal.sidekick.scene.NormalOpenScene
import com.sangar.gal.sidekick.scene.NotFoundScene
import com.sangar.gal.sidekick.scene.RoastOpenScene
import com.sangar.gal.sidekick.scene.RoastRules
import com.sangar.gal.sidekick.scene.SceneChoice
import com.sangar.gal.sidekick.scene.SceneDirector
import com.sangar.gal.sidekick.scene.SceneKind
import com.sangar.gal.sidekick.scene.SceneSounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Process-wide flag so the Compose screens can show whether Sidekick is on screen. The activity and the
 * service always share one process.
 */
object SidekickStatus {
    var running: Boolean by mutableStateOf(false)

    /** Debug builds only: feeds a command to the Sidekick on screen as if it heard it. Null while none is up. */
    var debugHear: ((spoken: String, forceRoast: Boolean) -> Unit)? = null
}

/**
 * Voice Sidekick: the floating blob, its voice engine and the app index. This is Pocket Sidekick's service
 * logic, hosted by GalService instead of owning a service of its own. Main thread only.
 *
 * While a roast card is on screen the blob wears the card's face ([CardEvents]), so the roast visibly comes
 * from Sidekick.
 *
 * "Open X" plays a scene on the stage ([SceneDirector]): a plain open, or for a social app used a lot a roast
 * and a hammer ([AppHabits]). The line is spoken while the scene plays, and the app opens from inside the
 * scene while the stage is still on screen. Quick open, the phone's "Remove animations" setting or a stage
 * that cannot be shown all fall back to the old way: say it, then open.
 */
class SidekickOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
) : SidekickHost, VoiceEngine.Listener {

    private val windowManager: WindowManager = context.getSystemService()!!
    private val voice = VoiceEngine(context, this)
    private val resolver = AppResolver(context)
    private val habits = AppHabitsReader(context)
    private val main = Handler(Looper.getMainLooper())

    private var view: SidekickView? = null
    private var params: WindowManager.LayoutParams? = null
    private var moodJob: Job? = null
    private var settingsJob: Job? = null
    private var openJob: Job? = null
    private var sounds: SceneSounds? = null

    /** The latest settings, kept current while Sidekick is on screen. */
    private var settings = Settings()

    /** Debug builds: the next command roasts whatever it opens, without the usage rules or the history. */
    private var forceRoastOnce = false
    private val debugHear: (String, Boolean) -> Unit = { spoken, forceRoast -> hearForDebug(spoken, forceRoast) }

    /** True from tap until the character finishes talking. Blocks re-entry. */
    private var busy = false

    /** Set once by [release]. Late callbacks check it before speaking or opening anything. */
    private var released = false

    /** Keeps the index fresh when the user installs or removes something. */
    private val packageWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshAppsAsync()
    }

    /** Returns false when the window could not be added, typically because the overlay permission is gone. */
    fun show(): Boolean {
        if (view != null) return true
        if (!Permissions.canDrawOverlays(context)) {
            NagLog.w(C, "not showing Sidekick: canDrawOverlays is false")
            return false
        }

        val size = (SIZE_DP * context.resources.displayMetrics.density).roundToInt()
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

        ContextCompat.registerReceiver(
            context,
            packageWatcher,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshAppsAsync()
        moodJob = scope.launch {
            CardEvents.face.collect { face ->
                val mood = face?.mood ?: Mood.NONE
                NagLog.d(C, "wearing face $mood")
                view?.mood = mood
            }
        }
        settingsJob = scope.launch {
            context.container.settings.settings.collect { latest ->
                settings = latest
                applySceneSettings(latest)
            }
        }
        SceneDirector.floatingBlob = floatingBlob
        if (BuildConfig.DEBUG) SidekickStatus.debugHear = debugHear
        SidekickStatus.running = true
        NagLog.i(C, "Sidekick is on screen")
        return true
    }

    /** Lets the stage blob take the floating blob's place for a scene, so it reads as the same character. */
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
        if (SidekickStatus.debugHear === debugHear) SidekickStatus.debugHear = null
        if (SceneDirector.floatingBlob === floatingBlob) {
            SceneDirector.cancel()
            SceneDirector.floatingBlob = null
        }
        moodJob?.cancel()
        moodJob = null
        settingsJob?.cancel()
        settingsJob = null
        openJob?.cancel()
        openJob = null
        if (SceneDirector.sounds === sounds) SceneDirector.sounds = null
        sounds?.release()
        sounds = null
        if (view != null) runCatching { context.unregisterReceiver(packageWatcher) }
        main.removeCallbacksAndMessages(null)
        voice.release()
        view?.let { v -> runCatching { windowManager.removeView(v) } }
        view = null
        params = null
        busy = false
        SidekickStatus.running = false
    }

    /** Rotation can leave the character parked off-screen. */
    fun onConfigurationChanged() {
        val p = params ?: return
        val v = view ?: return
        val bounds = overlayBounds()
        moveOverlay(
            p.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - v.width)),
            p.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - v.height)),
        )
    }

    // ---- SidekickHost -----------------------------------------------------

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

    override fun onSidekickTapped() {
        if (busy) return
        // Tapping Sidekick mid-roast drops the roast face; it is listening now.
        view?.mood = Mood.NONE

        if (!hasMicPermission()) {
            busy = true
            voice.speak(context.getString(R.string.voice_need_mic)) { finishInteraction() }
            return
        }

        busy = true
        view?.state = SidekickState.LISTENING
        voice.startListening()
    }

    // ---- VoiceEngine.Listener ---------------------------------------------

    override fun onListeningStarted() {
        view?.state = SidekickState.LISTENING
    }

    override fun onHeard(candidates: List<String>) {
        view?.state = SidekickState.THINKING
        // What was said is private: debug builds only, like every other NAG line that names an app.
        NagLog.d(C, "heard: $candidates")
        val forceRoast = forceRoastOnce
        forceRoastOnce = false

        val match = resolver.resolve(candidates)
        if (match == null) {
            // Maybe the app was installed after the last index build.
            refreshAppsAsync()
            notFound(voice.randomConfusedLine())
            return
        }

        val entry = match.entry
        NagLog.d(C, "matched '${entry.label}' (${NagLog.app(entry.packageName)}) score=${"%.2f".format(match.score)}")

        // Usage numbers and the icon come off the main thread; a busy day's events take a moment to read.
        val now = settings
        openJob = scope.launch {
            val plan = try {
                withContext(Dispatchers.Default) { planOpen(entry, now, forceRoast) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class name only: a message could name the app, and this line reaches release logs.
                NagLog.e(C, "planning the open failed (${e.javaClass.simpleName}); opening it plainly")
                OpenPlan(roast = false, tier = 0, roastLine = null, icon = null, counts = false)
            }
            if (released) return@launch
            open(entry, plan)
        }
    }

    /**
     * What opening an app will look like, worked out off the main thread. [counts] is false for a roast forced
     * from adb, so testing does not use up the day's roasts.
     */
    private class OpenPlan(val roast: Boolean, val tier: Int, val roastLine: String?, val icon: Bitmap?, val counts: Boolean)

    private suspend fun planOpen(entry: AppResolver.AppEntry, s: Settings, forceRoast: Boolean): OpenPlan {
        val pkg = entry.packageName
        val enabled = s.roastsActive && s.appRoastsEnabled
        val social = enabled && !forceRoast && habits.isSocial(pkg, s.extraSocialApps)
        val usage = if (social) habits.usage(pkg) else null
        val decision = if (forceRoast) AppHabits.Decision(roast = true, tier = 2, reason = "forced from adb") else AppHabits.decide(
            packageName = pkg,
            enabled = enabled,
            social = social,
            usage = usage,
            rules = RoastRules(minOpensToday = s.roastMinOpens, minMinutesToday = s.roastMinMinutes, minAverageMinutes = s.roastMinAverage),
            history = habits.history(),
            nowWall = System.currentTimeMillis(),
            today = habits.today(),
            inCall = QuietRules.isInCall(context),
            quiet = pkg in s.exclusions,
        )
        NagLog.d(C, "open ${NagLog.app(pkg)}: ${if (decision.roast) "roast, tier ${decision.tier}" else "plain"} (${decision.reason})")
        val line = if (decision.roast) context.container.phrases.appRoast(entry.label, decision.tier) else null
        val icon = if (s.scenesEnabled) appIcon(pkg) else null
        return OpenPlan(decision.roast, decision.tier, line, icon, counts = decision.roast && !forceRoast)
    }

    private fun open(entry: AppResolver.AppEntry, plan: OpenPlan) {
        val line = plan.roastLine ?: voice.randomAcknowledgement()
        if (plan.counts) habits.recordRoast(entry.packageName)
        val kind = SceneChoice.choose(found = true, roast = plan.roast, scenesEnabled = settings.scenesEnabled, animationsOff = SceneDirector.animationsOff(context))
        if (kind != null && playScene(kind, entry, plan.icon, line, AppHabits.moodFor(plan.tier))) return
        // Quick open, no animations, or no stage: say it, then open.
        voice.speak(line) { finishAfterLaunch(launched = resolver.launch(entry)) }
    }

    private fun notFound(line: String) {
        val kind = SceneChoice.choose(found = false, roast = false, scenesEnabled = settings.scenesEnabled, animationsOff = SceneDirector.animationsOff(context))
        if (kind != null && playScene(kind, null, null, line, Mood.DISAPPOINTED)) return
        voice.speak(line) { finishInteraction() }
    }

    /**
     * Plays [kind] with [line] in its bubble and in Sidekick's voice at the same time. [entry] opens from inside
     * the scene, while the stage is still on screen, which is what lets Android 15 start it from the background.
     * A tap on the stage skips the scene, opens the app at once and cuts the line short. Returns false if the
     * stage could not be shown; nothing has been said or opened then.
     */
    private fun playScene(kind: SceneKind, entry: AppResolver.AppEntry?, icon: Bitmap?, line: String, mood: Mood): Boolean {
        var launched = entry == null
        val after = AfterSceneAndSpeech { finishAfterLaunch(launched) }
        val started = SceneDirector.play(
            context,
            icon = icon,
            makeScene = { at ->
                when (kind) {
                    SceneKind.ROAST_OPEN -> RoastOpenScene(at, line, mood)
                    SceneKind.NORMAL_OPEN -> NormalOpenScene(at)
                    SceneKind.NOT_FOUND -> NotFoundScene(at, line)
                }
            },
            callbacks = SceneDirector.Callbacks(
                onLaunch = { if (entry != null) launched = resolver.launch(entry) },
                onDone = { skipped ->
                    if (skipped) voice.stopSpeaking()
                    after.sceneDone()
                },
            ),
        )
        if (!started) {
            NagLog.w(C, "no stage for ${kind.name}; saying it instead")
            return false
        }
        voice.speak(line) { after.speechDone() }
        return true
    }

    private fun finishAfterLaunch(launched: Boolean) {
        if (launched || released) {
            finishInteraction()
        } else {
            voice.speak(context.getString(R.string.voice_launch_failed)) { finishInteraction() }
        }
    }

    /** Sounds and haptics follow Settings, live. */
    private fun applySceneSettings(s: Settings) {
        SceneDirector.hapticsEnabled = s.sceneHaptics
        if (s.sceneSounds && sounds == null) {
            sounds = SceneSounds(context).also { SceneDirector.sounds = it }
        } else if (!s.sceneSounds && sounds != null) {
            if (SceneDirector.sounds === sounds) SceneDirector.sounds = null
            sounds?.release()
            sounds = null
        }
    }

    private fun appIcon(packageName: String): Bitmap? = runCatching {
        val px = (ICON_DP * context.resources.displayMetrics.density).roundToInt()
        context.packageManager.getApplicationIcon(packageName).toBitmap(px, px)
    }.getOrNull()

    /** Debug builds only, from adb: runs [spoken] as if Sidekick heard it. [forceRoast] skips the usage rules. */
    private fun hearForDebug(spoken: String, forceRoast: Boolean) {
        if (busy || released) {
            NagLog.w(C, "debug command ignored: Sidekick is busy")
            return
        }
        busy = true
        view?.mood = Mood.NONE
        forceRoastOnce = forceRoast
        onHeard(listOf(spoken))
    }

    override fun onListenFailed(reason: String) {
        NagLog.i(C, "listen failed: $reason")
        voice.speak(reason) { finishInteraction() }
    }

    override fun onSpeakStarted() {
        view?.state = SidekickState.SPEAKING
    }

    override fun onSpeakFinished() {
        // Per-utterance callbacks drive the state machine; nothing to do here.
    }

    private fun finishInteraction() {
        busy = false
        view?.state = SidekickState.IDLE
        // If a card is still up, go back to wearing its face.
        view?.mood = CardEvents.face.value?.mood ?: Mood.NONE
    }

    // ---- Helpers ----------------------------------------------------------

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshAppsAsync() {
        thread(name = "sidekick-app-index") {
            runCatching { resolver.refresh() }
                .onFailure { NagLog.w(C, "app index refresh failed", it) }
        }
    }

    fun toast(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val C = "Sidekick"
        const val SIZE_DP = 116f

        /** Icon size on the crate. */
        const val ICON_DP = 96f
    }
}
