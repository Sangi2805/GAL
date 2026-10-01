package com.sangar.gal.service

import com.sangar.gal.data.SessionCardCap

/**
 * Decides when a session card is due. Pure logic, times passed in.
 *
 * - The first card is due once the session's active time reaches the threshold.
 * - After a card is shown, the next one is due one more threshold of session time later: with a 10 minute
 *   threshold the cards come at 10, 20, 30 minutes and so on. The gap is read when the card is shown, so a
 *   threshold changed mid-session applies from the next gap. A short screen-off that does not end the session
 *   does not restart the cycle, otherwise every quick glance at the lock screen would earn an instant card.
 * - A session shows at most [SessionCardCap.PER_SESSION] cards. The last one is the give-up sign-off, then
 *   nothing more until the next session.
 * - Snooze silences everything for 10 minutes of real time.
 * - A new session starts from scratch.
 *
 * The progress of the current session can be saved with [snapshot] and handed back with [restore], so a
 * service that is killed and restarted mid-session carries on counting instead of starting again at card one.
 */
class NagScheduler(
    private val snoozeMillis: Long = SNOOZE_MILLIS,
) {
    /** What has to survive a process restart for one session. */
    data class Snapshot(
        val sessionKey: Long,
        val nagsThisSession: Int,
        val nextDueActiveMillis: Long,
    )

    private var sessionKey: Long? = null
    private var nextDueActiveMillis = 0L
    private var snoozeUntilElapsed = 0L
    private var pendingRestore: Snapshot? = null

    var nagsThisSession = 0
        private set

    val cap: Int get() = SessionCardCap.PER_SESSION

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

    fun check(sessionKey: Long, activeMillis: Long, thresholdMillis: Long, nowElapsed: Long): Check {
        syncSession(sessionKey)
        val dueAt = maxOf(thresholdMillis, nextDueActiveMillis)
        val snoozeRemaining = (snoozeUntilElapsed - nowElapsed).coerceAtLeast(0)
        val capReached = nagsThisSession >= cap
        return Check(
            due = !capReached && snoozeRemaining == 0L && activeMillis >= dueAt,
            activeMillis = activeMillis,
            thresholdMillis = thresholdMillis,
            dueAtActiveMillis = dueAt,
            snoozeRemainingMillis = snoozeRemaining,
            nagsThisSession = nagsThisSession,
            cap = cap,
        )
    }

    fun isDue(sessionKey: Long, activeMillis: Long, thresholdMillis: Long, nowElapsed: Long): Boolean =
        check(sessionKey, activeMillis, thresholdMillis, nowElapsed).due

    fun onShown(sessionKey: Long, activeMillis: Long, thresholdMillis: Long) {
        syncSession(sessionKey)
        nagsThisSession++
        nextDueActiveMillis = activeMillis + thresholdMillis
    }

    fun onSnoozed(nowElapsed: Long) {
        snoozeUntilElapsed = nowElapsed + snoozeMillis
    }

    /** Re-applies a snooze that was running before a restart, already converted to elapsed time. */
    fun restoreSnooze(untilElapsed: Long, nowElapsed: Long) {
        // Never longer than a fresh snooze: a stored value from before a reboot must not mute the app for days.
        snoozeUntilElapsed = untilElapsed.coerceIn(nowElapsed, nowElapsed + snoozeMillis)
    }

    /** The current session's progress, or null before the first check. */
    fun snapshot(): Snapshot? = sessionKey?.let { Snapshot(it, nagsThisSession, nextDueActiveMillis) }

    /**
     * Hands back saved progress. It is applied when (and only when) the matching session is next checked, so
     * a snapshot from an older session is simply ignored.
     */
    fun restore(snapshot: Snapshot) {
        if (snapshot.sessionKey == sessionKey) {
            apply(snapshot)
        } else {
            pendingRestore = snapshot
        }
    }

    private fun syncSession(key: Long) {
        if (key == sessionKey) return
        sessionKey = key
        val saved = pendingRestore
        pendingRestore = null
        if (saved != null && saved.sessionKey == key) {
            apply(saved)
        } else {
            nextDueActiveMillis = 0L
            nagsThisSession = 0
        }
    }

    private fun apply(snapshot: Snapshot) {
        nagsThisSession = snapshot.nagsThisSession.coerceIn(0, cap)
        nextDueActiveMillis = snapshot.nextDueActiveMillis.coerceAtLeast(0L)
    }

    companion object {
        const val SNOOZE_MILLIS = 10 * 60_000L
    }
}
