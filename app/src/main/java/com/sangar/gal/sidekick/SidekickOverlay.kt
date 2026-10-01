package com.sangar.gal.sidekick

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import com.sangar.gal.Permissions
import com.sangar.gal.R
import com.sangar.gal.overlay.CardEvents
import com.sangar.gal.service.NagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Process-wide flag so the Compose screens can show whether Sidekick is on screen. The activity and the
 * service always share one process.
 */
object SidekickStatus {
    var running: Boolean by mutableStateOf(false)
}

/**
 * Voice Sidekick: the floating blob, its voice engine and the app index. This is Pocket Sidekick's service
 * logic, hosted by GalService instead of owning a service of its own. Main thread only.
 *
 * While a roast card is on screen the blob wears the card's face ([CardEvents]), so the roast visibly comes
 * from Sidekick.
 */
class SidekickOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
) : SidekickHost, VoiceEngine.Listener {

    private val windowManager: WindowManager = context.getSystemService()!!
    private val voice = VoiceEngine(context, this)
    private val resolver = AppResolver(context)
    private val main = Handler(Looper.getMainLooper())

    private var view: SidekickView? = null
    private var params: WindowManager.LayoutParams? = null
    private var moodJob: Job? = null

    /** True from tap until the character finishes talking. Blocks re-entry. */
    private var busy = false

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
        SidekickStatus.running = true
        NagLog.i(C, "Sidekick is on screen")
        return true
    }

    fun release() {
        moodJob?.cancel()
        moodJob = null
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

        val match = resolver.resolve(candidates)
        if (match == null) {
            // Maybe the app was installed after the last index build.
            refreshAppsAsync()
            voice.speak(voice.randomConfusedLine()) { finishInteraction() }
            return
        }

        NagLog.d(C, "matched '${match.entry.label}' (${NagLog.app(match.entry.packageName)}) score=${"%.2f".format(match.score)}")

        voice.speak(voice.randomAcknowledgement()) {
            if (!resolver.launch(match.entry)) {
                voice.speak(context.getString(R.string.voice_launch_failed)) { finishInteraction() }
            } else {
                finishInteraction()
            }
        }
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
    }
}
