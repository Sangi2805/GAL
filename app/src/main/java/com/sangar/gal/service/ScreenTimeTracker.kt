package com.sangar.gal.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sangar.gal.container
import com.sangar.gal.data.Settings
import com.sangar.gal.data.db.NagEvent
import com.sangar.gal.data.db.ScreenSession
import com.sangar.gal.data.db.TrackingInterval
import com.sangar.gal.overlay.OverlayController
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Screen Time Roasts: GetALife's session clock, moved out of its own service so [GalService] can run it next to
 * Sidekick. The logic is GetALife's TrackerService unchanged; only the foreground plumbing moved out.
 *
 * Does nothing at all while the screen is off except wait for broadcasts; while the phone is unlocked it ticks
 * every 30 seconds.
 *
 * It measures whenever it runs. The home screen's cards switch only decides whether cards are shown, so screen
 * time history has no holes just because cards were off. Each run records a tracking interval, from start to
 * the last moment the tracker was known to be alive, which is how a day is later marked tracked or not.
 *
 * [onStatus] receives the line for the ongoing notification.
 */
class ScreenTimeTracker(
    private val service: LifecycleService,
    private val onStatus: (String) -> Unit,
) {

    private val context: Context = service
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = SessionTracker()
    private var keyguard: KeyguardManager? = null
    private var receiverRegistered = false
    private var ticksSinceCheckpoint = 0
    private var lastNotifiedMinute = -1L
    private var settings = Settings()
    private var settingsLoaded = false
    private var nagController: NagController? = null
    private var coverageStart = 0L
    private var settingsJob: Job? = null

    var running = false
        private set

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            val wall = wallClock()
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    val locked = isKeyguardLocked()
                    DiagnosticsState.update { it.copy(lastScreenOnWall = wall) }
                    NagLog.d(C, "SCREEN_ON keyguardLocked=$locked (clock starts only once unlocked)")
                    persist(tracker.onScreenOn(now, wall, locked))
                }
                Intent.ACTION_USER_PRESENT -> {
                    DiagnosticsState.update { it.copy(lastUserPresentWall = wall) }
                    persist(tracker.onUserPresent(now, wall))
                }
                Intent.ACTION_SCREEN_OFF -> {
                    DiagnosticsState.update { it.copy(lastScreenOffWall = wall) }
                    // A card must never outlive the screen. Debug builds' test cards dismiss themselves;
                    // see TestCard in the debug source set.
                    nagController?.onScreenOff()
                    tracker.onScreenOff(now)
                    checkpoint(now)
                }
                else -> return
            }
            recordCoverage()
            NagLog.d(C, "${intent.action?.substringAfterLast('.')} handled: ${describe(now)}")
            reschedule(now)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (!tracker.isCounting) return
            onTick(now)
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private val staleCheck = Runnable {
        val now = SystemClock.elapsedRealtime()
        tracker.closeIfStale(now)?.let {
            NagLog.i(C, "session closed after more than 60s of screen off: ${it.screenOnMillis / 1000}s on, ${it.unlockCount} unlocks; next unlock starts a new session")
            persist(it)
        }
        publish(now)
    }

    fun start() {
        if (running) return
        running = true
        lastNotifiedMinute = -1L
        settingsLoaded = false
        keyguard = context.getSystemService()
        coverageStart = wallClock()
        TrackingCoverage.openStartEpochMillis = coverageStart
        recordCoverage()
        DiagnosticsState.update {
            ServiceDiagnostics(serviceAlive = true, pid = Process.myPid(), serviceCreatedWall = wallClock())
        }
        nagController = NagController(
            context = context,
            scope = service.lifecycleScope,
            settingsRepository = context.container.settings,
            overlay = OverlayController(context),
            phrases = context.container.phrases,
            listener = { phrase, minutes, foreground ->
                context.container.appScope.launch {
                    runCatching {
                        context.container.usage.recordNag(
                            NagEvent(
                                timestampEpochMillis = System.currentTimeMillis(),
                                phraseId = phrase.id,
                                tier = phrase.tier,
                                sessionMinutes = minutes,
                                foregroundPackage = foreground,
                            ),
                        )
                    }.onFailure { NagLog.e(C, "failed to record nag", it) }
                }
            },
            onProblemChanged = { problem ->
                lastNotifiedMinute = -1L
                problem?.let(onStatus)
                publish(SystemClock.elapsedRealtime())
            },
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // These three are only delivered to receivers registered in code. It must be EXPORTED:
        // USER_PRESENT is sent by SystemUI, not system_server, so a NOT_EXPORTED receiver silently never
        // gets it. All three are protected broadcasts, so no other app can send them to us.
        ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true

        // Pick up the state we were started in, since no broadcast will tell us.
        val now = SystemClock.elapsedRealtime()
        restoreRecentSession(now)
        val interactive = context.getSystemService<PowerManager>()?.isInteractive ?: false
        if (interactive && !isKeyguardLocked()) tracker.onUserPresent(now, wallClock(), countAsUnlock = false)
        NagLog.i(C, "tracker started pid=${Process.myPid()} interactive=$interactive keyguardLocked=${isKeyguardLocked()} ${describe(now)}")
        onStatus(if (tracker.isCounting) "Current session: ${tracker.activeMillis(now) / 60_000L} min." else STATUS_WAITING)
        reschedule(now)

        settingsJob = service.lifecycleScope.launch {
            context.container.settings.settings.collect { latest ->
                if (!settingsLoaded) {
                    NagLog.i(C, "settings loaded: enabled=${latest.enabled} threshold=${latest.thresholdMinutes}min exclusions=${latest.exclusions.size}")
                } else if (latest.thresholdMinutes != settings.thresholdMinutes) {
                    NagLog.i(C, "threshold changed live: ${settings.thresholdMinutes}min -> ${latest.thresholdMinutes}min")
                }
                if (settingsLoaded && latest.enabled != settings.enabled) lastNotifiedMinute = -1L
                settingsLoaded = true
                settings = latest
                DiagnosticsState.update {
                    it.copy(settingsLoaded = true, enabledInService = latest.enabled, thresholdMinutesInUse = latest.thresholdMinutes)
                }
                publish(SystemClock.elapsedRealtime())
                if (!latest.enabled) NagLog.i(C, "cards switched off in settings; still measuring")
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        settingsJob?.cancel()
        settingsJob = null
        handler.removeCallbacksAndMessages(null)
        nagController?.release()
        nagController = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        persist(tracker.finish(SystemClock.elapsedRealtime()))
        recordCoverage()
        coverageStart = 0L
        TrackingCoverage.openStartEpochMillis = null
        LiveTracker.publish(LiveSession(serviceRunning = false))
        DiagnosticsState.update { it.copy(serviceAlive = false, counting = false) }
        NagLog.i(C, "tracker stopped")
    }

    private fun onTick(now: Long) {
        val activeMillis = tracker.activeMillis(now)
        DiagnosticsState.update { it.copy(lastTickWall = wallClock()) }
        NagLog.d(C, "tick ${describe(now)} thresholdInUse=${settings.thresholdMinutes}min")

        if (++ticksSinceCheckpoint >= CHECKPOINT_EVERY_TICKS) checkpoint(now)

        val minute = activeMillis / 60_000L
        if (minute != lastNotifiedMinute && nagController?.problem == null) {
            lastNotifiedMinute = minute
            onStatus(if (settings.enabled) "Current session: $minute min." else "Cards off. Current session: $minute min.")
        }
        nagController?.onTick(tracker.sessionStartElapsed, activeMillis, settings, settingsLoaded) { tracker.isCounting }
        publish(now)
    }

    /** Ticks only while counting; the stale check only while a paused session waits for the screen. */
    private fun reschedule(now: Long) {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(staleCheck)
        if (tracker.isCounting) {
            handler.postDelayed(tick, TICK_MILLIS)
            NagLog.d(C, "counting; next tick in ${TICK_MILLIS / 1000}s")
        } else {
            NagLog.d(C, "not counting; ticks stopped, stale check in ${tracker.millisUntilStale(now)?.div(1000) ?: "n/a"}s")
            tracker.millisUntilStale(now)?.let { handler.postDelayed(staleCheck, it.coerceAtLeast(0)) }
            if (lastNotifiedMinute != -1L) {
                lastNotifiedMinute = -1L
                onStatus("Screen off. Enjoy the real world.")
            }
        }
        publish(now)
    }

    /**
     * A kill and restart inside the reset window continues the session that was running: without this, an
     * OEM battery manager could reset the clock all day and the threshold would never be reached.
     */
    private fun restoreRecentSession(now: Long) {
        val last = runCatching { runBlocking { context.container.database.screenSessions().latest() } }
            .onFailure { NagLog.e(C, "could not read the last session", it) }
            .getOrNull() ?: return
        val gap = wallClock() - last.endEpochMillis
        if (gap < 0 || gap >= SessionTracker.DEFAULT_RESET_AFTER_OFF_MILLIS) {
            NagLog.i(C, "last session ended ${gap / 1000}s ago, too long to pick up; starting fresh")
            return
        }
        tracker.restore(
            now,
            SessionRecord(last.startEpochMillis, last.endEpochMillis, last.screenOnMillis, last.unlockCount),
            gap,
        )
        NagLog.i(C, "restored the session that was running ${gap / 1000}s ago: ${last.screenOnMillis / 1000}s on, ${last.unlockCount} unlocks")
    }

    private fun checkpoint(now: Long) {
        ticksSinceCheckpoint = 0
        persist(tracker.snapshot(now))
        recordCoverage()
    }

    /** Extends this run's tracking interval to now. */
    private fun recordCoverage() {
        if (coverageStart == 0L) return
        val interval = TrackingInterval(coverageStart, wallClock())
        val dao = context.container.database.trackingIntervals()
        context.container.appScope.launch {
            runCatching { dao.upsert(interval) }.onFailure { NagLog.e(C, "failed to record tracking interval", it) }
        }
    }

    private fun persist(record: SessionRecord?) {
        record ?: return
        val dao = context.container.database.screenSessions()
        context.container.appScope.launch {
            try {
                dao.upsert(
                    ScreenSession(
                        startEpochMillis = record.startEpochMillis,
                        endEpochMillis = record.endEpochMillis,
                        screenOnMillis = record.screenOnMillis,
                        unlockCount = record.unlockCount,
                    ),
                )
            } catch (e: Exception) {
                NagLog.e(C, "failed to write session", e)
            }
        }
    }

    private fun publish(now: Long) {
        if (!running) return
        DiagnosticsState.update {
            it.copy(
                sessionOpen = tracker.isOpen,
                counting = tracker.isCounting,
                activeMillis = tracker.activeMillis(now),
                measuredAtElapsed = now,
            )
        }
        LiveTracker.publish(
            LiveSession(
                serviceRunning = true,
                sessionOpen = tracker.isOpen,
                counting = tracker.isCounting,
                sessionStartEpochMillis = tracker.sessionStartWall,
                activeMillis = tracker.activeMillis(now),
                measuredAtElapsed = now,
                unlockCount = tracker.unlockCount,
                thresholdMinutes = settings.thresholdMinutes,
                nagProblem = nagController?.problem,
            ),
        )
    }

    private fun isKeyguardLocked() = keyguard?.isKeyguardLocked ?: false

    /** Wall clock is only used to stamp when a session started, never to measure a duration. */
    private fun wallClock() = System.currentTimeMillis()

    private fun describe(now: Long) =
        "session=${tracker.activeMillis(now) / 1000}s open=${tracker.isOpen} counting=${tracker.isCounting} unlocks=${tracker.unlockCount}"

    companion object {
        private const val C = "Tracker"
        const val TICK_MILLIS = 30_000L
        const val STATUS_WAITING = "Waiting for you to pick up your phone."

        // Every minute: a kill loses at most that much of the open session.
        private const val CHECKPOINT_EVERY_TICKS = 2
    }
}
