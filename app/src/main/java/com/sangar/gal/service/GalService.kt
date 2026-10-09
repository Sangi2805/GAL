package com.sangar.gal.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sangar.gal.Permissions
import com.sangar.gal.container
import com.sangar.gal.data.Settings
import com.sangar.gal.sidekick.SidekickOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    private var foregroundTypes: Int? = null

    /** What the last reconcile wanted, so onDestroy knows whether Sidekick was meant to be up. */
    private var voiceWanted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action == ACTION_TURN_OFF_VOICE) {
            // The notification's button. Written before reconciling so the switch and the service agree.
            runBlocking { container.settings.setVoiceEnabled(false) }
        }
        val settings = readSettings() ?: run {
            // Reading the switches failed (a one-off DataStore error). Do not treat that as "everything off":
            // keep whatever is already running, satisfy the foreground rule, and try reading again shortly.
            if (!enterForeground(roasts = tracker != null, voice = sidekick != null)) return START_NOT_STICKY
            retrySettings(attempt = 1)
            return START_STICKY
        }
        return reconcile(settings, fromForeground = action == ACTION_START_VOICE)
    }

    /**
     * Brings the running parts in line with the switches. Both parts use the specialUse type and need no runtime
     * permission beyond "Display over other apps", so either may start from the background (boot, a sticky
     * restart). [fromForeground] is kept for the callers' sake; it no longer changes anything.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun reconcile(settings: Settings, fromForeground: Boolean): Int {
        voiceWanted = settings.voiceActive
        val runRoasts = settings.roastsActive
        var runVoice = voiceWanted && Permissions.canDrawOverlays(this)

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

        if (voiceWanted && !runVoice) GalNotifications.postBringBack(this) else GalNotifications.cancelBringBack(this)

        if (!runRoasts && !runVoice) {
            NagLog.i(C, "nothing to run; stopping")
            stopEverything()
            return START_NOT_STICKY
        }
        // Drop a type we no longer need (for example Sidekick switched off while roasts keep running).
        enterForeground(runRoasts, runVoice)
        refreshNotification()
        NagLog.i(C, "running roasts=$runRoasts sidekick=$runVoice")
        return START_STICKY
    }

    private fun readSettings(): Settings? =
        runCatching { runBlocking { container.settings.current() } }
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

    /** Calls startForeground only when the type set changes. */
    private fun enterForeground(roasts: Boolean, voice: Boolean): Boolean {
        var types = 0
        if (roasts && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (voice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        // Started with startForegroundService but about to stop: Android still wants one startForeground call.
        if (types == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (types == foregroundTypes) return true
        return try {
            ServiceCompat.startForeground(
                this,
                GalNotifications.NOTIFICATION_ID,
                GalNotifications.build(this, if (roasts) roastStatus else null, voice),
                types,
            )
            foregroundTypes = types
            NagLog.i(C, "foreground types=0x${Integer.toHexString(types)}")
            true
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException or a missing FGS permission.
            NagLog.e(C, "startForeground refused for types=0x${Integer.toHexString(types)}", e)
            false
        }
    }

    private fun refreshNotification() {
        GalNotifications.update(this, if (tracker != null) roastStatus else null, sidekick != null)
    }

    private fun stopEverything() {
        tracker?.stop()
        tracker = null
        sidekick?.release()
        sidekick = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundTypes = null
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sidekick?.onConfigurationChanged()
    }

    override fun onDestroy() {
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

        /** The ongoing notification's "Hide the elephant" button. */
        const val ACTION_TURN_OFF_VOICE = "com.sangar.gal.action.TURN_OFF_VOICE"
    }
}
