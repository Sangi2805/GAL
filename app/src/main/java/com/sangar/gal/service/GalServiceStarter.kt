package com.sangar.gal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sangar.gal.container
import com.sangar.gal.data.DailyUsageWork
import com.sangar.gal.data.Settings
import com.sangar.gal.sidekick.SidekickStatus
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object GalServiceStarter {
    private const val C = "Starter"

    /**
     * From GAL's own screens: starts or updates [GalService] so it matches the switches, and stops it when both
     * are off.
     */
    fun syncFromForeground(context: Context, settings: Settings) {
        SidekickWatchdog.update(context, settings.voiceActive)
        if (!settings.roastsActive && !settings.voiceActive) {
            context.stopService(Intent(context, GalService::class.java))
            GalNotifications.cancelBringBack(context)
            return
        }
        start(context, GalService.ACTION_START_VOICE)
    }

    /**
     * From the background (boot, app update). Both parts may start from here. If Android refuses the start,
     * a wanted Sidekick gets "Tap to bring Sidekick back".
     */
    fun syncFromBackground(context: Context, settings: Settings) {
        SidekickWatchdog.update(context, settings.voiceActive)
        if (!settings.roastsActive && !settings.voiceActive) return
        if (!start(context, GalService.ACTION_SYNC) && settings.voiceActive) GalNotifications.postBringBack(context)
    }

    /** Returns false if Android refused the start, which can happen when called from the background. */
    private fun start(context: Context, action: String): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, GalService::class.java).setAction(action))
        NagLog.d(C, "foreground service start requested: $action")
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException extends IllegalStateException.
        NagLog.w(C, "foreground service start refused", e)
        false
    } catch (e: SecurityException) {
        NagLog.w(C, "foreground service start refused", e)
        false
    }
}

/** Restarts measuring after a reboot or an app update, and offers Sidekick back, once setup is done. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                DailyUsageWork.scheduleNightly(context)
                // Start even if a permission was revoked: the service degrades and reports what is missing.
                GalServiceStarter.syncFromBackground(context, context.container.settings.current())
                NagLog.i("Boot", "${intent.action}: synced")
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * A safety net for phones that kill background apps and never restart them: every half hour while Floating
 * Sidekick is switched on, posts "Tap to bring Sidekick back" if Sidekick is gone.
 */
class SidekickWatchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = applicationContext.container.settings.current()
        if (!settings.voiceActive) {
            update(applicationContext, false)
        } else if (!SidekickStatus.running) {
            NagLog.i("Watchdog", "Sidekick is wanted but not running")
            GalNotifications.postBringBack(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "sidekick-watchdog"

        fun update(context: Context, voiceOn: Boolean) {
            runCatching {
                val work = WorkManager.getInstance(context)
                if (voiceOn) {
                    val request = PeriodicWorkRequestBuilder<SidekickWatchdog>(30, TimeUnit.MINUTES)
                        .setInitialDelay(30, TimeUnit.MINUTES)
                        .build()
                    work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
                } else {
                    work.cancelUniqueWork(NAME)
                }
            }.onFailure { NagLog.e("Watchdog", "could not update the Sidekick watchdog", it) }
        }
    }
}
