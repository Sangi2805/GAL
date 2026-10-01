package com.sangar.gal.data

import android.util.Log
import com.sangar.gal.data.db.AppDailyUsage
import com.sangar.gal.data.db.AppDatabase
import com.sangar.gal.data.db.DailyUsage
import com.sangar.gal.data.db.ScreenSession
import com.sangar.gal.service.TrackingCoverage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Per-app foreground time for a window. Null when the data is unavailable (no usage access, too old). */
fun interface AppUsageSource {
    fun foregroundMillisByPackage(fromEpochMillis: Long, toEpochMillis: Long): Map<String, Long>?
}

/**
 * Writes daily_usage rows from our own screen_sessions. Never uses UsageStatsManager daily buckets,
 * which differ between OEMs and often double count.
 */
class DailyUsageAggregator(
    private val db: AppDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val appUsage: AppUsageSource? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Start of the interval the service in this process is measuring right now, if it is running. */
    private val openCoverageStart: () -> Long? = { TrackingCoverage.openStartEpochMillis },
) {

    /**
     * Writes every missing day up to and including yesterday: the device may have been off or dozing
     * at midnight for any number of days. Starts after the newest stored row (or at the first recorded
     * session if there are no rows yet), and always rewrites yesterday. Returns how many days were written.
     *
     * Also rewrites every earlier day that a session overlapping yesterday started on. The run just after
     * midnight sees a session that is still open (say 23:30 to 01:00) only up to its last checkpoint, so
     * the day before was written from partial numbers; the next run corrects it from the finished session.
     */
    suspend fun backfill(today: LocalDate): Int {
        val yesterday = today.minusDays(1)
        val latest = db.dailyUsage().latestDate()
        val first = latest?.plusDays(1)
            ?: db.screenSessions().earliestStart()?.let { dateOf(it) }
            ?: return 0
        val spanningIntoYesterday = db.screenSessions().overlapping(startOf(yesterday), startOf(today))
            .minOfOrNull { dateOf(it.startEpochMillis) }
        val earliest = listOfNotNull(first, yesterday, spanningIntoYesterday).min()
        val start = maxOf(earliest, today.minusDays(RETENTION_DAYS))
        if (start > yesterday) return 0

        var written = 0
        var day = start
        while (day <= yesterday) {
            db.dailyUsage().upsert(summarise(day))
            writeAppUsage(day)
            written++
            day = day.plusDays(1)
        }
        Log.i(TAG, "backfilled $written day(s) from $start to $yesterday")
        return written
    }

    /** Keeps [RETENTION_DAYS] days of everything. */
    suspend fun prune(today: LocalDate) {
        val cutoffDate = today.minusDays(RETENTION_DAYS)
        val cutoffMillis = startOf(cutoffDate)
        db.dailyUsage().deleteBefore(cutoffDate)
        db.appDailyUsage().deleteBefore(cutoffDate)
        db.screenSessions().deleteEndingBefore(cutoffMillis)
        db.nagEvents().deleteBefore(cutoffMillis)
        db.trackingIntervals().deleteEndingBefore(cutoffMillis)
    }

    suspend fun summarise(day: LocalDate): DailyUsage {
        val from = startOf(day)
        val to = startOf(day.plusDays(1))
        val sessions = db.screenSessions().overlapping(from, to)
        return DailyUsage(
            date = day,
            totalScreenMinutes = (sessions.sumOf { screenOnWithin(it, from, to) } / 60_000.0).let { Math.round(it).toInt() },
            unlockCount = sessions.filter { it.startEpochMillis in from until to }.sumOf { it.unlockCount },
            nagCount = db.nagEvents().countBetween(from, to),
            tracked = coveredMillis(from, to) >= MIN_TRACKED_MILLIS,
        )
    }

    /**
     * How much of [from, to) the service was measuring. Stored intervals end at the last moment the service
     * wrote one down, which can be well before now if the phone sat untouched; the interval the service in this
     * process has open right now counts up to now, so the run just after midnight sees yesterday in full.
     */
    suspend fun coveredMillis(from: Long, to: Long): Long {
        val nowMillis = now()
        val open = openCoverageStart()
        val spans = db.trackingIntervals().overlapping(from, to)
            .map { if (it.startEpochMillis == open) it.startEpochMillis to nowMillis else it.startEpochMillis to it.endEpochMillis }
            .toMutableList()
        if (open != null && spans.none { it.first == open } && open < to && nowMillis > from) spans += open to nowMillis
        var covered = 0L
        var reach = from
        for ((start, end) in spans.sortedBy { it.first }) {
            val clippedStart = maxOf(start, reach)
            val clippedEnd = minOf(end, to)
            if (clippedEnd > clippedStart) {
                covered += clippedEnd - clippedStart
                reach = clippedEnd
            }
        }
        return covered
    }

    private suspend fun writeAppUsage(day: LocalDate) {
        val source = appUsage ?: return
        val totals = runCatching { source.foregroundMillisByPackage(startOf(day), startOf(day.plusDays(1))) }.getOrNull() ?: return
        val rows = totals.filterValues { it >= 1_000 }.map { (pkg, millis) -> AppDailyUsage(day, pkg, millis) }
        if (rows.isNotEmpty()) db.appDailyUsage().upsertAll(rows)
    }

    private fun startOf(day: LocalDate) = day.atStartOfDay(zone()).toInstant().toEpochMilli()

    private fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    companion object {
        private const val TAG = "GAL.Daily"
        const val RETENTION_DAYS = 400L

        /** A day counts as tracked when the service measured at least half of it. */
        const val MIN_TRACKED_MILLIS = 12 * 60 * 60_000L

        /**
         * The part of a session's screen-on time that falls inside [from, to). Screen-on time is spread
         * evenly across the session's span, which is exact for continuous use; only the short
         * screen-off gaps inside a session (each under a minute) can land on the wrong side of midnight.
         *
         * Computed as a difference of running totals, so adjacent windows add up to exactly
         * [ScreenSession.screenOnMillis]: a session split across midnight is neither lost nor counted twice.
         */
        fun screenOnWithin(session: ScreenSession, from: Long, to: Long): Long {
            if (to <= from) return 0
            return screenOnBefore(session, to) - screenOnBefore(session, from)
        }

        /** Screen-on time of [session] before the instant [t]. */
        private fun screenOnBefore(session: ScreenSession, t: Long): Long {
            val start = session.startEpochMillis
            val span = session.endEpochMillis - start
            if (span <= 0) return if (t > start) session.screenOnMillis else 0
            val elapsed = (t - start).coerceIn(0, span)
            // screenOnMillis <= span, so the product fits in a Long for any span under about 35 days.
            return session.screenOnMillis * elapsed / span
        }
    }
}
