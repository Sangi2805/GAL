package com.sangar.gal.service

/** A session as it goes into the `screen_sessions` table. */
data class SessionRecord(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val screenOnMillis: Long,
    val unlockCount: Int,
)

/**
 * The session clock, with no Android dependencies so it can be unit tested.
 *
 * Every duration is measured on `elapsed` (SystemClock.elapsedRealtime), which is monotonic and immune
 * to clock and timezone changes. `wall` is only used once, to stamp the session start for storage; the
 * stored end is derived as start + elapsed span so a clock change mid-session cannot corrupt it.
 *
 * Rules:
 *  - Counting starts at ACTION_USER_PRESENT. A screen that wakes on a locked phone does not count.
 *  - Only unlocked screen-on time is counted.
 *  - A session survives the screen being off for up to [resetAfterOffMillis] in total; beyond that the
 *    next unlock starts a fresh session.
 */
class SessionTracker(private val resetAfterOffMillis: Long = DEFAULT_RESET_AFTER_OFF_MILLIS) {

    var isOpen = false
        private set

    /** Elapsed time the current session started at. Stable for the life of the session, so it works as a key. */
    var sessionStartElapsed = 0L
        private set

    var sessionStartWall = 0L
        private set

    var unlockCount = 0
        private set

    private var accumulatedOnMillis = 0L
    private var countingSince: Long? = null
    private var lastCountedEndElapsed = 0L
    private var offSince: Long? = null
    private var offAccumulatedMillis = 0L

    val isCounting: Boolean get() = countingSince != null

    fun activeMillis(elapsed: Long): Long =
        if (!isOpen) 0L else accumulatedOnMillis + (countingSince?.let { elapsed - it } ?: 0L)

    /**
     * Picks up a session that was still open when the service was killed, so a restart inside the reset
     * window continues it instead of starting the clock again. [gapMillis] is how long ago the session's last
     * checkpoint was, and counts as screen-off time, so a session that goes stale still closes on schedule.
     */
    fun restore(elapsed: Long, record: SessionRecord, gapMillis: Long) {
        val lastKnownElapsed = elapsed - gapMillis.coerceAtLeast(0)
        isOpen = true
        sessionStartWall = record.startEpochMillis
        // Anchored so a later snapshot's end, derived from elapsed, lands back on wall-clock time.
        sessionStartElapsed = lastKnownElapsed - (record.endEpochMillis - record.startEpochMillis)
        accumulatedOnMillis = record.screenOnMillis
        lastCountedEndElapsed = lastKnownElapsed
        countingSince = null
        offSince = lastKnownElapsed
        offAccumulatedMillis = 0L
        unlockCount = record.unlockCount
    }

    /**
     * The phone was unlocked. Returns the previous session if this unlock closed it.
     * [countAsUnlock] is false when the service starts on an already-unlocked phone.
     */
    fun onUserPresent(elapsed: Long, wall: Long, countAsUnlock: Boolean = true): SessionRecord? {
        endOffStretch(elapsed)
        if (countingSince != null) return null // duplicate signal, already counting

        if (isOpen && offAccumulatedMillis <= resetAfterOffMillis) {
            countingSince = elapsed
            offAccumulatedMillis = 0L
            if (countAsUnlock) unlockCount++
            return null
        }

        val finished = close()
        isOpen = true
        sessionStartElapsed = elapsed
        sessionStartWall = wall
        accumulatedOnMillis = 0L
        lastCountedEndElapsed = elapsed
        countingSince = elapsed
        offAccumulatedMillis = 0L
        unlockCount = if (countAsUnlock) 1 else 0
        return finished
    }

    /**
     * The screen turned on. On a phone with no secure lock screen USER_PRESENT may never arrive,
     * so an unlocked keyguard is treated as an unlock right away.
     */
    fun onScreenOn(elapsed: Long, wall: Long, keyguardLocked: Boolean): SessionRecord? {
        endOffStretch(elapsed)
        return if (keyguardLocked) null else onUserPresent(elapsed, wall)
    }

    fun onScreenOff(elapsed: Long) {
        countingSince?.let {
            accumulatedOnMillis += elapsed - it
            lastCountedEndElapsed = elapsed
            countingSince = null
        }
        if (isOpen && offSince == null) offSince = elapsed
    }

    /** How long until an open, paused session becomes stale, or null if that cannot happen right now. */
    fun millisUntilStale(elapsed: Long): Long? {
        if (!isOpen || isCounting || offSince == null) return null
        return resetAfterOffMillis - offMillis(elapsed) + 1
    }

    /** Closes the session if the screen has been off for longer than the reset window. */
    fun closeIfStale(elapsed: Long): SessionRecord? =
        if (isOpen && !isCounting && offMillis(elapsed) > resetAfterOffMillis) close() else null

    /** The open session as it would be stored right now, for periodic checkpoints. */
    fun snapshot(elapsed: Long): SessionRecord? {
        if (!isOpen) return null
        val endElapsed = if (isCounting) elapsed else lastCountedEndElapsed
        return SessionRecord(
            startEpochMillis = sessionStartWall,
            endEpochMillis = sessionStartWall + (endElapsed - sessionStartElapsed),
            screenOnMillis = activeMillis(elapsed),
            unlockCount = unlockCount,
        ).takeIf { it.screenOnMillis > 0 }
    }

    /** Ends everything, e.g. when the service is destroyed. */
    fun finish(elapsed: Long): SessionRecord? {
        onScreenOff(elapsed)
        return close()
    }

    private fun offMillis(elapsed: Long) = offAccumulatedMillis + (offSince?.let { elapsed - it } ?: 0L)

    private fun endOffStretch(elapsed: Long) {
        offSince?.let {
            offAccumulatedMillis += elapsed - it
            offSince = null
        }
    }

    private fun close(): SessionRecord? {
        if (!isOpen) return null
        val record = SessionRecord(
            startEpochMillis = sessionStartWall,
            endEpochMillis = sessionStartWall + (lastCountedEndElapsed - sessionStartElapsed),
            screenOnMillis = accumulatedOnMillis,
            unlockCount = unlockCount,
        )
        isOpen = false
        countingSince = null
        offSince = null
        offAccumulatedMillis = 0L
        accumulatedOnMillis = 0L
        unlockCount = 0
        return record.takeIf { it.screenOnMillis > 0 }
    }

    companion object {
        const val DEFAULT_RESET_AFTER_OFF_MILLIS = 60_000L
    }
}
