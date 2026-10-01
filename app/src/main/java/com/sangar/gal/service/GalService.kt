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
 * - Voice Sidekick ([SidekickOverlay]), foreground type `microphone`. Android 14 refuses that type unless
 *   RECORD_AUDIO is held, and Android 11+ only lets it open the mic if the service was started while GAL was
 *   in the foreground or from a notification tap. So this part is deliberately NOT sticky: a restart by the
 *   system would come from the background and could not use the mic. When Sidekick should be on but is not,
 *   GAL posts "Tap to bring Sidekick back" instead ([GalNotifications.postBringBack]).
 *
 * The two types are combined in one startForeground call when both run. onStartCommand returns START_STICKY
 * while roasts run (the restart brings back roasts only, and posts the bring-back notification for Sidekick)
 * and START_NOT_STICKY when only Sidekick runs.
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
        val settings = runCatching { runBlocking { container.settings.current() } }.getOrDefault(Settings())
        return reconcile(settings, micAllowed = action == ACTION_START_VOICE)
    }

    /**
     * Brings the running parts in line with the switches. [micAllowed] is true only for starts that may open the
     * microphone: from the app's own screens or from the bring-back notification. Anything else (boot, a sticky
     * restart, a plain sync) keeps a Sidekick that is already up but will not start one.
     */
    private fun reconcile(settings: Settings, micAllowed: Boolean): Int {
        voiceWanted = settings.voiceActive
        val runRoasts = settings.roastsActive
        val voiceReady = Permissions.canDrawOverlays(this) && Permissions.hasMicrophone(this)
        var runVoice = voiceWanted && voiceReady && (micAllowed || sidekick != null)

        if (!enterForeground(runRoasts, runVoice)) {
            // Most likely the microphone type was refused from the background. Keep roasts going without it.
            if (runVoice && enterForeground(runRoasts, false)) {
                runVoice = false
            } else {
                NagLog.e(C, "could not enter the foreground at all; stopping")
                stopEverything()
                return START_NOT_STICKY
            }
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
        return if (runRoasts) START_STICKY else START_NOT_STICKY
    }

    /** Calls startForeground only when the type set changes, since each call re-checks the mic rules. */
    private fun enterForeground(roasts: Boolean, voice: Boolean): Boolean {
        var types = 0
        if (roasts && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (voice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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
            // ForegroundServiceStartNotAllowedException, a SecurityException for the microphone type, or a
            // missing FGS permission.
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

        /** From the app's screens or the bring-back notification: the microphone may be opened. */
        const val ACTION_START_VOICE = "com.sangar.gal.action.START_VOICE"

        /** From boot, updates and anything else in the background: roasts yes, a new Sidekick no. */
        const val ACTION_SYNC = "com.sangar.gal.action.SYNC"

        /** The ongoing notification's "Turn off Sidekick" button. */
        const val ACTION_TURN_OFF_VOICE = "com.sangar.gal.action.TURN_OFF_VOICE"
    }
}
