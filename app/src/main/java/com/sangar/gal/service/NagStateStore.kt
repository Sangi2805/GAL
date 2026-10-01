package com.sangar.gal.service

import android.content.Context
import android.content.SharedPreferences

/**
 * What the session card logic keeps across a process restart: how far the current session has got, a running
 * snooze, and when the last card was shown. Without it, a service that Android kills and restarts mid-session
 * would start the session's 12 cards again from one, and a crash loop could put a card on screen after every
 * restart, because the gap guard between cards lived only in memory.
 *
 * Times that must survive a restart are stored as wall-clock times, since elapsedRealtime starts again from
 * zero after a reboot. [NagStatePersistence] converts them back.
 */
data class PersistedNagState(
    /** The session these numbers belong to: its start time on the wall clock. */
    val sessionKey: Long,
    val nagsThisSession: Int,
    val nextDueActiveMillis: Long,
    /** Wall-clock end of a running snooze, or 0. */
    val snoozeUntilWall: Long,
    /** Wall-clock time the last card of any session was shown, or 0. */
    val lastCardWall: Long,
)

interface NagStateStore {
    fun load(): PersistedNagState?
    fun save(state: PersistedNagState)
}

class InMemoryNagStateStore(private var state: PersistedNagState? = null) : NagStateStore {
    override fun load() = state
    override fun save(state: PersistedNagState) {
        this.state = state
    }
}

/**
 * SharedPreferences rather than DataStore: the controller reads this once, synchronously, before its first
 * tick, and writes it with apply() after a card or a snooze, which never blocks the main thread.
 */
class PrefsNagStateStore(context: Context) : NagStateStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): PersistedNagState? {
        if (!prefs.contains(KEY_SESSION)) return null
        return runCatching {
            PersistedNagState(
                sessionKey = prefs.getLong(KEY_SESSION, 0L),
                nagsThisSession = prefs.getInt(KEY_NAGS, 0),
                nextDueActiveMillis = prefs.getLong(KEY_NEXT_DUE, 0L),
                snoozeUntilWall = prefs.getLong(KEY_SNOOZE, 0L),
                lastCardWall = prefs.getLong(KEY_LAST_CARD, 0L),
            )
        }.getOrNull()
    }

    override fun save(state: PersistedNagState) {
        prefs.edit()
            .putLong(KEY_SESSION, state.sessionKey)
            .putInt(KEY_NAGS, state.nagsThisSession)
            .putLong(KEY_NEXT_DUE, state.nextDueActiveMillis)
            .putLong(KEY_SNOOZE, state.snoozeUntilWall)
            .putLong(KEY_LAST_CARD, state.lastCardWall)
            .apply()
    }

    private companion object {
        const val FILE = "nag_state"
        const val KEY_SESSION = "session_key"
        const val KEY_NAGS = "nags_this_session"
        const val KEY_NEXT_DUE = "next_due_active_millis"
        const val KEY_SNOOZE = "snooze_until_wall"
        const val KEY_LAST_CARD = "last_card_wall"
    }
}

/** Pure conversions between stored wall-clock times and this boot's elapsedRealtime. */
object NagStatePersistence {

    /**
     * When the last card was shown, on this boot's elapsed clock, or null if it was long enough ago not to
     * matter. A time in the future (the clock was changed) counts as "just now", which errs towards quiet.
     */
    fun lastCardElapsed(lastCardWall: Long, nowWall: Long, nowElapsed: Long, guardMillis: Long): Long? {
        if (lastCardWall <= 0L) return null
        val ago = (nowWall - lastCardWall).coerceAtLeast(0L)
        if (ago >= guardMillis) return null
        return nowElapsed - ago
    }

    /** A running snooze on this boot's elapsed clock, or null if it has ended. Never longer than [maxMillis]. */
    fun snoozeUntilElapsed(snoozeUntilWall: Long, nowWall: Long, nowElapsed: Long, maxMillis: Long): Long? {
        if (snoozeUntilWall <= nowWall) return null
        return nowElapsed + (snoozeUntilWall - nowWall).coerceAtMost(maxMillis)
    }
}
