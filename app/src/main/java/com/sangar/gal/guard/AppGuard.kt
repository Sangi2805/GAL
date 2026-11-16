package com.sangar.gal.guard

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sangar.gal.container
import com.sangar.gal.data.Settings
import com.sangar.gal.service.ForegroundApp
import com.sangar.gal.service.NagLog
import com.sangar.gal.ui.AppInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** What the running guard knows right now, for the home screen's "Limit today" bar. */
data class GuardSnapshot(val day: LocalDate, val usedMillis: Long)

object GuardLive {
    private val _state = MutableStateFlow<GuardSnapshot?>(null)
    val state: StateFlow<GuardSnapshot?> = _state.asStateFlow()

    internal fun publish(snapshot: GuardSnapshot?) {
        _state.value = snapshot
    }
}

/**
 * The redesigned GAL at work. While the phone is unlocked it looks once a second at which app is in front. Time
 * in the apps you chose adds up against the one daily limit; [GuardRules] decides when the reminder and the
 * full-screen pause appear, and [GuardScreens] draws them.
 *
 * It sleeps completely while the screen is off or locked. Today's time so far is read from the phone's own
 * usage events when it wakes, so a restart of the service never resets or loses the count.
 */
class AppGuard(private val service: LifecycleService) {

    private val context: Context = service
    private val handler = Handler(Looper.getMainLooper())
    private val store = GuardStore(context)
    private val screens = GuardScreens(context)
    private var keyguard: KeyguardManager? = null

    private var settings = Settings()
    private var settingsJob: Job? = null
    private var baselineJob: Job? = null
    private var running = false
    private var polling = false
    private var receiverRegistered = false

    private var day: LocalDate = LocalDate.now()
    private var usedMillis = 0L

    /** False until today's time so far has been read; nothing is decided on a guess. */
    private var baselineLoaded = false
    private var lastPollElapsed = 0L
    private var lastPackage: String? = null

    private val poll = object : Runnable {
        override fun run() {
            if (!running || !polling) return
            runCatching { tick() }.onFailure { NagLog.e(C, "tick failed", it) }
            handler.postDelayed(this, POLL_MILLIS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopPolling()
                    // A pause cut short by the screen going off was not served; it comes back next time.
                    screens.dismiss()
                }
                Intent.ACTION_SCREEN_ON -> if (!isLocked()) startPolling()
                Intent.ACTION_USER_PRESENT -> startPolling()
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        keyguard = context.getSystemService()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // EXPORTED for the same reason as in ScreenTimeTracker: USER_PRESENT comes from SystemUI. All three are
        // protected broadcasts, so no other app can send them.
        ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true

        settingsJob = service.lifecycleScope.launch {
            context.container.settings.settings.collect { latest ->
                val appsChanged = latest.monitoredApps != settings.monitoredApps
                settings = latest
                if (appsChanged) {
                    // The count belongs to the old set of apps; decide nothing until it is read again.
                    baselineLoaded = false
                    loadBaseline()
                }
            }
        }
        val interactive = context.getSystemService<PowerManager>()?.isInteractive ?: false
        if (interactive && !isLocked()) startPolling()
        NagLog.i(C, "guard started")
    }

    fun stop() {
        if (!running) return
        running = false
        stopPolling()
        settingsJob?.cancel()
        settingsJob = null
        baselineJob?.cancel()
        baselineJob = null
        screens.dismiss()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        GuardLive.publish(null)
        NagLog.i(C, "guard stopped")
    }

    private fun startPolling() {
        if (!running || polling) return
        polling = true
        lastPollElapsed = SystemClock.elapsedRealtime()
        // Unlocking straight into a chosen app counts as opening it.
        lastPackage = null
        loadBaseline()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MILLIS)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(poll)
    }

    /** Reads today's time in the chosen apps from the phone's usage events. */
    private fun loadBaseline() {
        baselineJob?.cancel()
        baselineJob = service.lifecycleScope.launch {
            val chosen = settings.monitoredApps
            if (chosen.isEmpty()) return@launch
            val used = runCatching { context.container.hourly.usedToday(chosen) }
                .onFailure { NagLog.e(C, "could not read today's usage", it) }
                .getOrNull() ?: return@launch
            rollDay()
            usedMillis = used
            baselineLoaded = true
            publish()
        }
    }

    private fun rollDay() {
        val today = LocalDate.now()
        if (today != day) {
            day = today
            usedMillis = 0L
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastPollElapsed).coerceIn(0L, POLL_MILLIS * 3)
        lastPollElapsed = now
        rollDay()
        if (!settings.guardActive || !baselineLoaded) return

        val foreground = ForegroundApp.current(context)
        val chosen = settings.monitoredApps
        val inChosen = foreground != null && foreground in chosen
        val limit = settings.dailyLimitMinutes

        if (screens.isShowing) {
            // Leaving for the home screen or another app takes the reminder or the pause away. A pause that
            // was left early was not served: it is still owed, and it starts again from the top the next time
            // a chosen app is opened. Time spent under either screen is not counted.
            if (!inChosen) {
                screens.dismiss()
                lastPackage = foreground
            }
            return
        }

        if (inChosen) usedMillis += delta
        val opened = if (foreground != lastPackage) foreground else null
        lastPackage = foreground
        publish()

        val shown = store.blocksShown(day)
        if (GuardRules.shouldBlock(inChosen, usedMillis, limit, shown)) {
            showBlock(foreground ?: return, limit, shown)
        } else if (GuardRules.shouldRemind(opened, chosen, store.reminded(day), usedMillis, limit)) {
            showReminder(opened ?: return, limit)
        }
    }

    private fun showBlock(packageName: String, limit: Int, shown: Int) {
        val scene = BlockScenes.pick(LocalTime.now().hour, store.lastScene())
        val app = AppInfo.label(context, packageName)
        val minutes = (usedMillis / 60_000L).toInt()
        val content = BlockContent(
            label = "TIME'S UP · ${app.uppercase()}",
            line = BlockLines.line(scene, settings.tone),
            detail = if (settings.monitoredApps.size == 1) "$minutes min of $app today" else "$minutes min on your apps today",
            scene = scene,
        )
        val usedAtShow = usedMillis
        val ok = screens.showBlock(content) {
            store.setBlocksShown(day, GuardRules.shownAfterBlock(usedAtShow, limit, shown))
            NagLog.i(C, "pause served")
        }
        if (ok) {
            store.setLastScene(scene)
            NagLog.i(C, "pause shown over $packageName after $minutes min ($scene)")
        }
    }

    private fun showReminder(packageName: String, limit: Int) {
        val app = AppInfo.label(context, packageName)
        val left = GuardRules.minutesLeft(usedMillis, limit)
        val detail = if (left >= limit) "$limit min today. Keep your promise?" else "$left min left today. Keep your promise?"
        if (screens.showReminder(ReminderContent(title = "$app is on your list", detail = detail))) {
            store.addReminded(day, packageName)
            NagLog.i(C, "reminder shown for $packageName")
        }
    }

    private fun publish() {
        GuardLive.publish(GuardSnapshot(day, usedMillis))
    }

    private fun isLocked() = keyguard?.isKeyguardLocked ?: false

    private companion object {
        const val C = "Guard"
        const val POLL_MILLIS = 1_000L
    }
}

/**
 * The little the guard must remember across a restart of the service: how many pauses were served today, which
 * apps already got their reminder today, and the last scene, so it is not repeated. Everything resets itself
 * when the day changes.
 */
internal class GuardStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gal_guard", Context.MODE_PRIVATE)

    private fun sameDay(day: LocalDate) = prefs.getString(KEY_DAY, null) == day.toString()

    private fun startDay(day: LocalDate) {
        if (sameDay(day)) return
        prefs.edit().putString(KEY_DAY, day.toString()).putInt(KEY_BLOCKS, 0).putStringSet(KEY_REMINDED, emptySet()).apply()
    }

    fun blocksShown(day: LocalDate): Int = if (sameDay(day)) prefs.getInt(KEY_BLOCKS, 0) else 0

    fun setBlocksShown(day: LocalDate, count: Int) {
        startDay(day)
        prefs.edit().putInt(KEY_BLOCKS, count).apply()
    }

    fun reminded(day: LocalDate): Set<String> =
        if (sameDay(day)) prefs.getStringSet(KEY_REMINDED, emptySet()).orEmpty() else emptySet()

    fun addReminded(day: LocalDate, packageName: String) {
        startDay(day)
        // A new set: the one SharedPreferences hands back must never be changed in place.
        prefs.edit().putStringSet(KEY_REMINDED, reminded(day) + packageName).apply()
    }

    fun lastScene(): BlockScene? = prefs.getString(KEY_SCENE, null)?.let { name -> BlockScene.entries.firstOrNull { it.name == name } }

    fun setLastScene(scene: BlockScene) = prefs.edit().putString(KEY_SCENE, scene.name).apply()

    private companion object {
        const val KEY_DAY = "day"
        const val KEY_BLOCKS = "blocks_shown"
        const val KEY_REMINDED = "reminded"
        const val KEY_SCENE = "last_scene"
    }
}
