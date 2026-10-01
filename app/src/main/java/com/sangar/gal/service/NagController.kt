package com.sangar.gal.service

import android.content.Context
import android.os.SystemClock
import com.sangar.gal.Permissions
import com.sangar.gal.data.SessionCardCap
import com.sangar.gal.data.Settings
import com.sangar.gal.data.SettingsRepository
import com.sangar.gal.overlay.Mascot
import com.sangar.gal.overlay.NagCard
import com.sangar.gal.overlay.OverlayController
import com.sangar.gal.overlay.QuietRules
import com.sangar.gal.overlay.ShowResult
import com.sangar.gal.phrases.ChosenPhrase
import com.sangar.gal.phrases.NagRequest
import com.sangar.gal.phrases.PhraseSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Called after a card is actually on screen, so it can be logged. */
fun interface NagListener {
    fun onNagShown(phrase: ChosenPhrase, sessionMinutes: Long, foregroundPackage: String?)
}

/**
 * Runs on every tracker tick. Checks health, asks both triggers whether a card is due, applies the quiet
 * rules, then shows the card. Never throws: anything missing turns into a [problem] the UI can show.
 * Every threshold comparison and every show or suppress decision is logged under the NAG tag.
 *
 * Two independent triggers share the snooze, the exclusion list and the quiet rules:
 * - session: one continuous session reaches the "nag me after" threshold, then every 10 minutes, up to
 *   the session's card cap ([NagScheduler]), whose last card is a give_up sign-off;
 * - daily: today's total reaches the daily limit, then every extra hour, three times a day ([DailyNagRules]).
 * When both are due the daily card wins and also counts as the session's card. Any card holds the other
 * trigger off for [MIN_GAP_MILLIS], so the two never arrive back to back.
 */
class NagController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val overlay: OverlayController,
    private val phrases: PhraseSource,
    private val listener: NagListener,
    private val onProblemChanged: (String?) -> Unit,
) {
    private val scheduler = NagScheduler()
    private var evaluating = false
    private var lastCardElapsed: Long? = null

    var problem: String? = null
        private set(value) {
            if (field != value) {
                field = value
                NagLog.i(C, "problem changed: ${value ?: "none"}")
                DiagnosticsState.update { it.copy(nagProblem = value) }
                onProblemChanged(value)
            }
        }

    fun onTick(sessionKey: Long, activeMillis: Long, settings: Settings, settingsLoaded: Boolean, stillCounting: () -> Boolean) {
        val now = SystemClock.elapsedRealtime()
        val thresholdMillis = settings.thresholdMinutes * 60_000L
        // The scheduler latches this when the session starts, so a mid-session change waits for the next one.
        val check = scheduler.check(sessionKey, activeMillis, thresholdMillis, settings.sessionCardCap, now)
        DiagnosticsState.update {
            it.copy(
                nextDueActiveMillis = check.dueAtActiveMillis,
                snoozeUntilElapsed = now + check.snoozeRemainingMillis,
                sessionCardCap = check.cap,
                sessionCardsShown = check.nagsThisSession,
                sessionCardCapSetting = SessionCardCap.describe(settings.thresholdMinutes, settings.sessionCardCapOverride),
            )
        }
        NagLog.d(
            C,
            "compare active=${activeMillis / 1000}s threshold=${thresholdMillis / 1000}s dueAt=${check.dueAtActiveMillis / 1000}s " +
                "untilDue=${check.millisUntilDue / 1000}s snooze=${check.snoozeRemainingMillis / 1000}s " +
                "nagsThisSession=${check.nagsThisSession}/${check.cap} -> ${if (check.due) "DUE" else "not due"}",
        )

        problem = healthProblem(settings)
        val sinceLastCard = lastCardElapsed?.let { now - it }
        val skip = when {
            !settingsLoaded -> "settings not loaded yet"
            !settings.enabled -> "disabled in settings"
            problem != null -> "paused: $problem"
            // One snooze silences both triggers.
            check.snoozeRemainingMillis > 0 -> "snoozed for ${check.snoozeRemainingMillis / 1000}s more"
            sinceLastCard != null && sinceLastCard < MIN_GAP_MILLIS -> "last card was ${sinceLastCard / 1000}s ago"
            evaluating -> "previous evaluation still running"
            else -> null
        }
        if (skip != null) {
            suppress(skip)
            return
        }

        evaluating = true
        scope.launch {
            try {
                if (!check.due) {
                    if (check.capReached) {
                        suppress("this session has had all ${check.cap} of its cards")
                        return@launch
                    }
                    suppress("not due, ${check.millisUntilDue / 1000}s of session time to go")
                    return@launch
                }

                val verdict = withContext(Dispatchers.Default) { QuietRules.evaluate(context, settings.exclusions) }
                DiagnosticsState.update {
                    it.copy(lastForegroundPackage = verdict.observed.foregroundPackage, lastForegroundExcluded = verdict.observed.foregroundExcluded)
                }
                NagLog.d(C, "quiet rules observed: ${verdict.observed}")
                if (verdict is QuietRules.Verdict.Quiet) {
                    suppress(verdict.reason)
                    return@launch
                }
                val foreground = verdict.observed.foregroundPackage
                if (!stillCounting()) {
                    suppress("session stopped counting before the card could be shown")
                    return@launch
                }

                val sessionMinutes = activeMillis / 60_000L
                val nagIndex = check.nagsThisSession
                val lastOfSession = check.lastOfSession
                val phrase = phrases.next(
                    NagRequest(
                        sessionMinutes = sessionMinutes,
                        nagsThisSession = nagIndex,
                        foregroundPackage = foreground,
                        pack = settings.phrasePack,
                        lastOfSession = lastOfSession,
                    ),
                )

                // Picking the phrase reads the database, and the user may have switched apps since the tick.
                // Look again on the main thread, right before addView, so an excluded app never gets a card.
                val final = QuietRules.evaluate(context, settings.exclusions)
                DiagnosticsState.update {
                    it.copy(lastForegroundPackage = final.observed.foregroundPackage, lastForegroundExcluded = final.observed.foregroundExcluded)
                }
                if (final is QuietRules.Verdict.Quiet) {
                    suppress("${final.reason} (re-checked just before drawing)")
                    return@launch
                }
                if (!stillCounting()) {
                    suppress("session stopped counting before the card could be shown")
                    return@launch
                }
                if (final.observed.foregroundPackage != foreground) {
                    NagLog.d(C, "foreground changed while picking the phrase: $foreground -> ${final.observed.foregroundPackage}")
                }
                val mascot = Mascot.forNag(
                    phrase.tier,
                    nagIndex,
                    phrase.tags,
                )
                val label = "Sidekick · ${describeMinutes(sessionMinutes)} on screen"
                NagLog.i(
                    C,
                    "showing session card${if (lastOfSession) " (give_up sign-off, ${check.cap} of ${check.cap})" else ""}: " +
                        "session=${sessionMinutes}min tier=${phrase.tier} " +
                        "phrase=${NagLog.phrase(phrase.id)} mascot=${mascot.description} textLength=${phrase.text.length} " +
                        "fg=${NagLog.app(final.observed.foregroundPackage)}",
                )
                val result = overlay.show(
                    NagCard(
                        text = phrase.text,
                        mascot = mascot.drawable,
                        mascotDescription = mascot.description,
                        label = label,
                        face = mascot,
                    ),
                    onFine = { NagLog.i(C, "user tapped Fine") },
                    onSnooze = {
                        NagLog.i(C, "user tapped Snooze, silent for ${NagScheduler.SNOOZE_MILLIS / 60_000} min")
                        scheduler.onSnoozed(SystemClock.elapsedRealtime())
                    },
                )
                when (result) {
                    ShowResult.SHOWN -> {
                        lastCardElapsed = SystemClock.elapsedRealtime()
                        scheduler.onShown(sessionKey, activeMillis, check.cap, thresholdMillis)
                        DiagnosticsState.decision("SHOWN phrase ${NagLog.phrase(phrase.id)} at ${sessionMinutes} min")
                        NagLog.i(C, "decision SHOWN")
                        listener.onNagShown(phrase, sessionMinutes, final.observed.foregroundPackage)
                    }
                    ShowResult.NO_PERMISSION -> {
                        suppress("overlay permission missing at show time")
                        problem = PROBLEM_OVERLAY_PERMISSION
                    }
                    ShowResult.FAILED -> {
                        suppress("WindowManager refused the window")
                        // Stop trying until the user has looked at it; see HomeScreen.
                        settingsRepository.setOverlayFailed(true)
                        problem = PROBLEM_OVERLAY_FAILED
                    }
                }
            } catch (e: Exception) {
                NagLog.e(C, "nag attempt threw", e)
                DiagnosticsState.decision("ERROR ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                evaluating = false
            }
        }
    }

    fun onScreenOff() = overlay.dismiss("screen off")

    fun release() = overlay.dismiss("service stopped")

    private fun suppress(reason: String) {
        NagLog.i(C, "decision SUPPRESSED: $reason")
        DiagnosticsState.decision("SUPPRESSED: $reason")
    }

    private fun healthProblem(settings: Settings): String? = when {
        !settings.enabled -> null
        settings.overlayFailed -> PROBLEM_OVERLAY_FAILED
        !Permissions.canDrawOverlays(context) -> PROBLEM_OVERLAY_PERMISSION
        !Permissions.hasUsageAccess(context) -> PROBLEM_USAGE_ACCESS
        else -> null
    }

    private fun describeMinutes(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    companion object {
        private const val C = "Nag"

        /** Minimum real time between any two cards, whichever trigger they come from. */
        const val MIN_GAP_MILLIS = 1 * 60_000L
        const val PROBLEM_OVERLAY_PERMISSION = "\"Display over other apps\" is off, so nagging is paused."
        const val PROBLEM_USAGE_ACCESS = "Usage access is off, so nagging is paused."
        const val PROBLEM_OVERLAY_FAILED = "The card could not be drawn, so nagging is paused."
    }
}
