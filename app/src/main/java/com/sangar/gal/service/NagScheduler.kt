package com.sangar.gal.service

import com.sangar.gal.data.SessionCardCap
import com.sangar.gal.data.Threshold

/**
 * Decides when a nag is due. Pure logic, times passed in.
 *
 * - The first nag is due once the session's active time reaches the threshold.
 * - After a card is shown, the next one is due 10 minutes of session time later, for as long as the
 *   session lasts. A short screen-off that does not end the session does not restart the cycle,
 *   otherwise every quick glance at the lock screen would earn an instant nag on return.
 * - A session shows at most [SessionCardCap] cards. The cap is latched when the session starts and does
 *   not move again until the next one: a threshold changed mid-session recomputes the cap for later
 *   sessions only, so lowering it cannot suddenly unlock extra cards in the sitting you are in.
 * - Snooze silences everything for 10 minutes of real time.
 * - A new session starts from scratch.
 */
class NagScheduler(
    private val snoozeMillis: Long = SNOOZE_MILLIS,
) {
    private var sessionKey: Long? = null
    private var nextDueActiveMillis = 0L
    private var snoozeUntilElapsed = 0L
    private var repeatMillis = REPEAT_MILLIS_MAX

    var nagsThisSession = 0
        private set

    /** The cap this session is running under, latched when it started. */
    var cap = SessionCardCap.auto(Threshold.DEFAULT_MINUTES)
        private set

    /** The full comparison, so callers can log and display why a card is or is not due. */
    data class Check(
        val due: Boolean,
        val activeMillis: Long,
        val thresholdMillis: Long,
        val dueAtActiveMillis: Long,
        val snoozeRemainingMillis: Long,
        val nagsThisSession: Int,
        val cap: Int,
    ) {
        val millisUntilDue: Long get() = (dueAtActiveMillis - activeMillis).coerceAtLeast(0)

        /** This session has had everything it is going to get. */
        val capReached: Boolean get() = nagsThisSession >= cap

        /** The card this check allows would be the session's last one, so it gets the sign-off line. */
        val lastOfSession: Boolean get() = nagsThisSession == cap - 1
    }

    fun check(sessionKey: Long, activeMillis: Long, thresholdMillis: Long, cap: Int, nowElapsed: Long): Check {
        syncSession(sessionKey, cap, thresholdMillis)
        val dueAt = maxOf(thresholdMillis, nextDueActiveMillis)
        val snoozeRemaining = (snoozeUntilElapsed - nowElapsed).coerceAtLeast(0)
        val capReached = nagsThisSession >= this.cap
        return Check(
            due = !capReached && snoozeRemaining == 0L && activeMillis >= dueAt,
            activeMillis = activeMillis,
            thresholdMillis = thresholdMillis,
            dueAtActiveMillis = dueAt,
            snoozeRemainingMillis = snoozeRemaining,
            nagsThisSession = nagsThisSession,
            cap = this.cap,
        )
    }

    fun isDue(sessionKey: Long, activeMillis: Long, thresholdMillis: Long, cap: Int, nowElapsed: Long): Boolean =
        check(sessionKey, activeMillis, thresholdMillis, cap, nowElapsed).due

    fun onShown(sessionKey: Long, activeMillis: Long, cap: Int, thresholdMillis: Long) {
        syncSession(sessionKey, cap, thresholdMillis)
        nagsThisSession++
        nextDueActiveMillis = activeMillis + repeatMillis
    }

    /** The cap is read once per session, so a later change waits for the next one. */
    private fun syncSession(key: Long, cap: Int, thresholdMillis: Long) {
        if (key != sessionKey) {
            sessionKey = key
            nextDueActiveMillis = 0L
            nagsThisSession = 0
            this.cap = SessionCardCap.clamp(cap)
            this.repeatMillis = minOf(thresholdMillis, REPEAT_MILLIS_MAX)
        }
    }

    fun onSnoozed(nowElapsed: Long) {
        snoozeUntilElapsed = nowElapsed + snoozeMillis
    }

    companion object {
        const val REPEAT_MILLIS_MAX = 10 * 60_000L
        const val SNOOZE_MILLIS = 10 * 60_000L
    }
}
