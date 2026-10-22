package com.sangar.gal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sangar.gal.Permissions
import com.sangar.gal.container
import com.sangar.gal.data.Settings
import com.sangar.gal.sidekick.SidekickOverlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GAL's one foreground service. It hosts whichever of the two features are switched on:
 *
 * - Screen Time Roasts ([ScreenTimeTracker]), foreground type `specialUse`. Measuring screen time and drawing an
 *   optional card is not one of Android's named FGS types, which is exactly what specialUse is for; the manifest
 *   property explains the use. It needs no runtime permission and may start from the background (boot, update,
 *   a sticky restart), so this part is sticky: if Android kills the process it is restarted and picks the
 *   session back up.
 *
 * - Floating Sidekick ([SidekickOverlay]): the pink elephant that wanders the screen edges and wears the roast
 *   cards' faces. It needs only "Display over other apps", so it shares the specialUse type and is sticky too.
 *   (It used to be Voice Sidekick with the microphone type; voice was dropped because speech recognition did
 *   not work reliably on real phones.)
 *
 * onStartCommand returns START_STICKY while anything runs. If Sidekick should be up but cannot be shown,
 * GAL posts "Tap to bring Sidekick back" ([GalNotifications.postBringBack]).
 */
class GalService : LifecycleService() {

    private var tracker: ScreenTimeTracker? = null
    private var sidekick: SidekickOverlay? = null
    private var roastStatus: String = ScreenTimeTracker.STATUS_WAITING
    private var inForeground = false

    /** What the last reconcile wanted, so onDestroy knows whether Sidekick was meant to be up. */
    private var voiceWanted = false

    /** When a hidden elephant comes back (wall clock), or 0. Shown in the ongoing notification. */
    private var hiddenUntil = 0L
    private var unhideJob: Job? = null

    /**
     * The unhide timer runs on uptime, which stops while the phone sleeps. So when the screen comes back on we
     * also check whether a hidden elephant is due back.
     */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (hiddenUntil in 1..System.currentTimeMillis()) {
                lifecycleScope.launch { readSettings()?.let { reconcile(it, fromForeground = false) } }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // EXPORTED for the same reason as in ScreenTimeTracker: USER_PRESENT comes from SystemUI. Both are
        // protected broadcasts, so no other app can send them.
        ContextCompat.registerReceiver(this, wakeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Satisfy the startForeground rule first, before any disk access, so a slow cold start can never miss it.
        if (!enterForeground(roasts = tracker != null, voice = sidekick != null)) return START_NOT_STICKY
        val action = intent?.action
        lifecycleScope.launch {
            // The notification's buttons. Written before reconciling so the settings and the service agree.
            runCatching {
                val repo = container.settings
                when (action) {
                    ACTION_TURN_OFF_VOICE -> repo.setVoiceEnabled(false)
                    ACTION_HIDE_HOUR -> repo.setElephantHiddenUntil(System.currentTimeMillis() + 60 * 60_000L)
                    ACTION_SHOW_ELEPHANT -> repo.setElephantHiddenUntil(0L)
                }
            }.onFailure { NagLog.e(C, "could not apply $action", it) }
            val settings = readSettings()
            if (settings != null) {
                reconcile(settings, fromForeground = action == ACTION_START_VOICE)
            } else {
                // Reading the switches failed (a one-off DataStore error). Do not treat that as "everything
                // off": keep whatever is already running and try reading again shortly.
                retrySettings(attempt = 1)
            }
        }
        return START_STICKY
    }

    /**
     * Brings the running parts in line with the switches. Both parts use the specialUse type and need no runtime
     * permission beyond "Display over other apps", so either may start from the background (boot, a sticky
     * restart). [fromForeground] is kept for the callers' sake; it no longer changes anything.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun reconcile(settings: Settings, fromForeground: Boolean): Int {
        voiceWanted = settings.voiceActive
        val now = System.currentTimeMillis()
        // Dropped on the X: switched on, but hidden until a time. She comes back by herself at that time.
        val hidden = settings.elephantHidden(now)
        hiddenUntil = if (hidden) settings.elephantHiddenUntil else 0L
        scheduleUnhide(if (hidden) settings.elephantHiddenUntil - now else null)
        val runRoasts = settings.roastsActive
        var runVoice = voiceWanted && !hidden && Permissions.canDrawOverlays(this)
        // A hidden elephant keeps the service up (with "Show her now" in the notification) so she can come
        // back on time, even when roasts are off.
        val waitForUnhide = voiceWanted && hidden

        if (!enterForeground(runRoasts, runVoice)) {
            NagLog.e(C, "could not enter the foreground at all; stopping")
            stopEverything()
            return START_NOT_STICKY
        }

        if (runRoasts) {
            if (tracker == null) tracker = ScreenTimeTracker(this) { status -> roastStatus = status; refreshNotification() }
            tracker?.start()
        } else {
            tracker?.stop()
            tracker = null
        }

        if (runVoice) {
            if (sidekick == null) {
                val overlay = SidekickOverlay(this, lifecycleScope)
                if (overlay.show()) {
                    sidekick = overlay
                } else {
                    overlay.release()
                    runVoice = false
                }
            }
        } else {
            sidekick?.release()
            sidekick = null
        }

        if (voiceWanted && !hidden && !runVoice) GalNotifications.postBringBack(this) else GalNotifications.cancelBringBack(this)

        if (!runRoasts && !runVoice && !waitForUnhide) {
            NagLog.i(C, "nothing to run; stopping")
            stopEverything()
            return START_NOT_STICKY
        }
        refreshNotification()
        NagLog.i(C, "running roasts=$runRoasts sidekick=$runVoice")
        return START_STICKY
    }

    /** Wakes the service when a hidden elephant's time is up, so she walks back on by herself. */
    private fun scheduleUnhide(delayMillis: Long?) {
        unhideJob?.cancel()
        unhideJob = null
        if (delayMillis == null) return
        unhideJob = lifecycleScope.launch {
            delay(delayMillis.coerceAtLeast(0L) + 500L)
            readSettings()?.let { reconcile(it, fromForeground = false) }
        }
    }

    private suspend fun readSettings(): Settings? =
        runCatching { container.settings.current() }
            .onFailure { NagLog.e(C, "could not read settings", it) }
            .getOrNull()

    /** Re-reads the switches a few times, a couple of seconds apart, before giving up. */
    private fun retrySettings(attempt: Int) {
        lifecycleScope.launch {
            delay(SETTINGS_RETRY_MILLIS)
            val settings = readSettings()
            when {
                settings != null -> reconcile(settings, fromForeground = false)
                attempt < SETTINGS_RETRIES -> retrySettings(attempt + 1)
                // Still unreadable and nothing running: stop cleanly rather than sit in the foreground empty.
                tracker == null && sidekick == null -> stopEverything()
            }
        }
    }

    /**
     * Enters the foreground once; later changes only update the notification. Both features use the same
     * specialUse type (and Android before 14 has no types), so the type never needs to change.
     */
    private fun enterForeground(roasts: Boolean, voice: Boolean): Boolean {
        if (inForeground) return true
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(
                this,
                GalNotifications.NOTIFICATION_ID,
                GalNotifications.build(this, if (roasts) roastStatus else null, voice, hiddenUntil),
                type,
            )
            inForeground = true
            NagLog.i(C, "in the foreground")
            true
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException or a missing FGS permission.
            NagLog.e(C, "startForeground refused", e)
            false
        }
    }

    private fun refreshNotification() {
        GalNotifications.update(this, if (tracker != null) roastStatus else null, sidekick != null, hiddenUntil)
    }

    private fun stopEverything() {
        tracker?.stop()
        tracker = null
        sidekick?.release()
        sidekick = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sidekick?.onConfigurationChanged()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(wakeReceiver) }
        val sidekickWasUp = sidekick != null
        tracker?.stop()
        tracker = null
        sidekick?.release()
        sidekick = null
        // Stopped by the system (not by a switch) while Sidekick was up: offer the way back.
        if (sidekickWasUp && voiceWanted) GalNotifications.postBringBack(this)
        NagLog.i(C, "service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val C = "Service"
        private const val SETTINGS_RETRY_MILLIS = 2_000L
        private const val SETTINGS_RETRIES = 3

        /** From the app's screens or the bring-back notification. (The name is from the voice days; kept so old intents still work.) */
        const val ACTION_START_VOICE = "com.sangar.gal.action.START_VOICE"

        /** From boot, updates and anything else in the background. Starts the same parts as ACTION_START_VOICE. */
        const val ACTION_SYNC = "com.sangar.gal.action.SYNC"

        /** Turns her off completely. Old notifications may still send it. */
        const val ACTION_TURN_OFF_VOICE = "com.sangar.gal.action.TURN_OFF_VOICE"

        /** The ongoing notification's "Hide for 1 hour" button. */
        const val ACTION_HIDE_HOUR = "com.sangar.gal.action.HIDE_HOUR"

        /** The ongoing notification's "Show her now" button, while she is hidden. */
        const val ACTION_SHOW_ELEPHANT = "com.sangar.gal.action.SHOW_ELEPHANT"
    }
}
