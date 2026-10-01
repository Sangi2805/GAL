package com.sangar.gal.phrases

import java.time.DayOfWeek
import java.time.LocalDateTime

enum class Trend { UP, DOWN, FLAT }

/** History-derived facts, computed by the data layer. Defaults are what an empty history means. */
data class UsageSignals(
    val trend: Trend = Trend.FLAT,
    val newRecord: Boolean = false,
    val streakGood: Boolean = false,
    /** Complete, fully tracked days behind these signals. */
    val historyDays: Int = 0,
) {
    /** Enough real history for trend tags and tier escalation to mean anything. */
    val established: Boolean get() = historyDays >= MIN_HISTORY_DAYS

    companion object {
        const val MIN_HISTORY_DAYS = 7
    }
}

fun interface UsageSignalsProvider {
    suspend fun signals(): UsageSignals
}

/** Turns the moment into phrase tags and an escalation tier. Pure, so it is easy to test. */
object NagContext {

    const val LONG_SESSION_MINUTES = 45L
    const val MARATHON_MINUTES = 120L
    const val WORK_START_HOUR = 9
    const val WORK_END_HOUR = 17

    /** Specific tags only. `general` is the fallback, not a filter. */
    fun tags(
        sessionMinutes: Long,
        nagsThisSession: Int,
        now: LocalDateTime,
        signals: UsageSignals,
    ): Set<String> = buildSet {
        add(
            when {
                sessionMinutes < LONG_SESSION_MINUTES -> Tags.SHORT_SESSION
                sessionMinutes < MARATHON_MINUTES -> Tags.LONG_SESSION
                else -> Tags.MARATHON
            },
        )
        val hour = now.hour
        if (hour in 5..10) add(Tags.MORNING)
        if (hour >= 22 || hour < 5) add(Tags.LATE_NIGHT)
        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        if (weekend) add(Tags.WEEKEND)
        if (!weekend && hour in WORK_START_HOUR until WORK_END_HOUR) add(Tags.WORK_HOURS)
        add(if (nagsThisSession == 0) Tags.FIRST_NAG else Tags.REPEAT_NAG)
        // History-derived tags wait for a week of real data, whatever the provider sent.
        if (signals.established) {
            when (signals.trend) {
                Trend.UP -> add(Tags.TREND_UP)
                Trend.DOWN -> add(Tags.TREND_DOWN)
                Trend.FLAT -> Unit
            }
            if (signals.newRecord) add(Tags.NEW_RECORD)
            if (signals.streakGood) add(Tags.STREAK_GOOD)
        }
    }

    /**
     * The only tag the session's sign-off card may use. The line is about the app giving up, not about
     * the sitting, so no moment tag comes along that could pull an ordinary nag into the pool.
     */
    val GIVE_UP_TAGS: Set<String> = setOf(Tags.GIVE_UP)



    /**
     * The tier for a real nag. Until there are [UsageSignals.MIN_HISTORY_DAYS] days of real history the
     * user is new and stays at tier 1, however long the session: no escalation of any kind yet.
     */
    fun tier(sessionMinutes: Long, signals: UsageSignals): Int =
        if (signals.established) tier(sessionMinutes, signals.trend) else 1

    /**
     * For an established user: session length sets the base tier (under 45 min, under 2 h, beyond), and
     * the 7 day trend moves it one step: rising usage makes it harsher, falling usage softer.
     */
    fun tier(sessionMinutes: Long, trend: Trend): Int {
        val base = when {
            sessionMinutes < LONG_SESSION_MINUTES -> 1
            sessionMinutes < MARATHON_MINUTES -> 2
            else -> 3
        }
        val adjust = when (trend) {
            Trend.UP -> 1
            Trend.DOWN -> -1
            Trend.FLAT -> 0
        }
        return (base + adjust).coerceIn(1, 3)
    }
}
