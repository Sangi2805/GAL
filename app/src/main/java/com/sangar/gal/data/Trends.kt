package com.sangar.gal.data

import com.sangar.gal.data.db.DailyUsage
import com.sangar.gal.phrases.Trend
import com.sangar.gal.phrases.UsageSignals
import java.time.LocalDate
import kotlin.math.abs

data class TrendSnapshot(
    /** Mean daily minutes over the last 7 complete days that have rows, or null with no rows. */
    val average7: Double?,
    val average30: Double?,
    val trend: Trend,
    /** Complete, fully tracked days with a daily_usage row, up to yesterday. */
    val historyDays: Int,
)

/**
 * Pure trend maths over daily_usage rows.
 *
 * Averages divide by the days that have rows, never by 7 or 30, so days before the app was installed
 * are left out rather than counted as zero, and so are days the service was not measuring (tracked = false). The day tracking started is left out too: it only holds the
 * hours after install and would drag every early average down.
 */
object TrendCalculator {

    const val MIN_HISTORY_DAYS = UsageSignals.MIN_HISTORY_DAYS
    const val FLAT_BAND = 0.10

    /** [trackingStart] is the local date of the first recorded session, or null if unknown. */
    fun compute(rows: List<DailyUsage>, today: LocalDate, trackingStart: LocalDate? = null): TrendSnapshot {
        val complete = completeDays(rows, today, trackingStart)
        val last7 = complete.filter { it.date >= today.minusDays(7) }
        val last30 = complete.filter { it.date >= today.minusDays(30) }
        val average7 = last7.averageMinutes()
        val average30 = last30.averageMinutes()
        return TrendSnapshot(average7, average30, trend(average7, average30, complete.size), complete.size)
    }

    fun trend(average7: Double?, average30: Double?, historyDays: Int): Trend {
        if (historyDays < MIN_HISTORY_DAYS || average7 == null || average30 == null) return Trend.FLAT
        if (average30 == 0.0) return if (average7 > 0.0) Trend.UP else Trend.FLAT
        return when {
            abs(average7 - average30) <= FLAT_BAND * average30 -> Trend.FLAT
            average7 > average30 -> Trend.UP
            else -> Trend.DOWN
        }
    }

    /**
     * new_record: today already beats every stored day. streak_good: the last three complete days were
     * each below the 30 day average. Both need at least a week of history to mean anything.
     */
    fun signals(
        snapshot: TrendSnapshot,
        rows: List<DailyUsage>,
        today: LocalDate,
        todayMinutes: Int,
        trackingStart: LocalDate? = null,
    ): UsageSignals {
        if (snapshot.historyDays < MIN_HISTORY_DAYS) return UsageSignals(trend = Trend.FLAT, historyDays = snapshot.historyDays)
        val complete = completeDays(rows, today, trackingStart)
        val best = complete.maxOfOrNull { it.totalScreenMinutes } ?: 0
        val byDate = complete.associateBy { it.date }
        val lastThree = (1L..3L).map { byDate[today.minusDays(it)] }
        val average30 = snapshot.average30 ?: 0.0
        val streak = average30 > 0.0 && lastThree.all { it != null && it.totalScreenMinutes < average30 }
        return UsageSignals(
            trend = snapshot.trend,
            newRecord = todayMinutes > best,
            streakGood = streak,
            historyDays = snapshot.historyDays,
        )
    }

    /** Past days that were actually measured: not today, not the install day, not an untracked day. */
    private fun completeDays(rows: List<DailyUsage>, today: LocalDate, trackingStart: LocalDate?) =
        rows.filter { it.tracked && it.date < today && (trackingStart == null || it.date > trackingStart) }

    private fun List<DailyUsage>.averageMinutes(): Double? =
        if (isEmpty()) null else sumOf { it.totalScreenMinutes }.toDouble() / size
}
