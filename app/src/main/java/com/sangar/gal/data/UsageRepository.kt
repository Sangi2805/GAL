package com.sangar.gal.data

import com.sangar.gal.data.db.AppDailyUsage
import com.sangar.gal.data.db.AppDatabase
import com.sangar.gal.data.db.DailyUsage
import com.sangar.gal.data.db.NagEvent
import com.sangar.gal.data.db.ScreenSession
import com.sangar.gal.phrases.UsageSignals
import com.sangar.gal.phrases.UsageSignalsProvider
import com.sangar.gal.service.LiveSession
import com.sangar.gal.service.LiveTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Read side of the history, plus the few writes the service needs. */
class UsageRepository(
    private val db: AppDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = { System.currentTimeMillis() },
) : UsageSignalsProvider {

    fun today(): LocalDate = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()

    private fun startOf(day: LocalDate) = day.atStartOfDay(zone()).toInstant().toEpochMilli()

    suspend fun recordNag(event: NagEvent) = db.nagEvents().insert(event)

    /**
     * Today's screen-on time: stored sessions for today, with the open session's checkpoint row swapped
     * for the live figure so it is neither missing nor counted twice.
     */
    fun todayScreenMillis(sessions: List<ScreenSession>, live: LiveSession, day: LocalDate = today()): Long {
        val from = startOf(day)
        val to = startOf(day.plusDays(1))
        val stored = sessions
            .filterNot { live.sessionOpen && it.startEpochMillis == live.sessionStartEpochMillis }
            .sumOf { DailyUsageAggregator.screenOnWithin(it, from, to) }
        if (!live.sessionOpen) return stored
        val nowMillis = now()
        val active = live.activeMillisNow()
        val liveShare = if (live.sessionStartEpochMillis >= from) {
            active
        } else {
            val span = (nowMillis - live.sessionStartEpochMillis).coerceAtLeast(1)
            (active.toDouble() * (nowMillis - from).coerceAtLeast(0) / span).toLong()
        }
        return stored + liveShare
    }

    /** Today's screen-on time so far, including the open session, for the daily total trigger. */
    suspend fun todayTotalMillis(): Long = withContext(Dispatchers.IO) {
        val day = today()
        val sessions = db.screenSessions().overlapping(startOf(day), startOf(day.plusDays(1)))
        todayScreenMillis(sessions, LiveTracker.state.value, day)
    }

    /** Emits today's sessions and the live session together; the UI adds its own clock tick. */
    fun observeToday(day: LocalDate = today()): Flow<Pair<List<ScreenSession>, LiveSession>> =
        combine(db.screenSessions().observeOverlapping(startOf(day), startOf(day.plusDays(1))), LiveTracker.state) { s, l -> s to l }

    fun observeDays(from: LocalDate, to: LocalDate): Flow<List<DailyUsage>> = db.dailyUsage().observeBetween(from, to)

    fun observeNagCount(day: LocalDate = today()): Flow<Int> =
        db.nagEvents().observeCountBetween(startOf(day), startOf(day.plusDays(1)))

    suspend fun trend(day: LocalDate = today()): TrendSnapshot = withContext(Dispatchers.IO) {
        TrendCalculator.compute(db.dailyUsage().between(day.minusDays(30), day.minusDays(1)), day, trackingStart())
    }

    /** Local date of the first recorded session: the install day, which only holds a partial day. */
    suspend fun trackingStart(): LocalDate? = withContext(Dispatchers.IO) {
        db.screenSessions().earliestStart()?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }
    }

    suspend fun topApps(day: LocalDate, limit: Int = 5): List<AppDailyUsage> = db.appDailyUsage().topForDate(day, limit)

    override suspend fun signals(): UsageSignals = withContext(Dispatchers.IO) {
        val day = today()
        val rows = db.dailyUsage().between(day.minusDays(DailyUsageAggregator.RETENTION_DAYS), day.minusDays(1))
        val start = trackingStart()
        val snapshot = TrendCalculator.compute(rows, day, start)
        val todaySessions = db.screenSessions().overlapping(startOf(day), startOf(day.plusDays(1)))
        val todayMinutes = (todayScreenMillis(todaySessions, LiveTracker.state.value, day) / 60_000L).toInt()
        TrendCalculator.signals(snapshot, rows, day, todayMinutes, start)
    }

    suspend fun wipeAll() = withContext(Dispatchers.IO) { db.clearAllTables() }
}
