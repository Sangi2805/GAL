package com.sangar.gal.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sangar.gal.data.db.AppDatabase
import com.sangar.gal.data.db.DailyUsage
import com.sangar.gal.data.db.NagEvent
import com.sangar.gal.data.db.ScreenSession
import com.sangar.gal.data.db.TrackingInterval
import com.sangar.gal.phrases.Trend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/** Seeded in-memory databases: acceptance for daily totals, backfill, averages and trend state. */
@RunWith(RobolectricTestRunner::class)
class DailyUsageAggregatorTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 9, 15)
    private lateinit var db: AppDatabase
    private lateinit var aggregator: DailyUsageAggregator

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        aggregator = DailyUsageAggregator(db, zone = { zone })
        // The service was measuring throughout, unless a test says otherwise.
        runBlocking { cover(today.minusDays(500), today.plusDays(1)) }
    }

    private suspend fun cover(from: LocalDate, to: LocalDate) =
        db.trackingIntervals().upsert(TrackingInterval(millis(from, 0), millis(to, 0)))

    private suspend fun stopCovering() {
        db.trackingIntervals().deleteEndingBefore(Long.MAX_VALUE)
    }

    @After
    fun tearDown() = db.close()

    private fun millis(day: LocalDate, hour: Int, minute: Int = 0) =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** A session starting at [hour] on [day], with [onMinutes] of screen time spread over [spanMinutes]. */
    private suspend fun session(day: LocalDate, hour: Int, onMinutes: Int, spanMinutes: Int = onMinutes, unlocks: Int = 1) {
        val start = millis(day, hour)
        db.screenSessions().upsert(ScreenSession(start, start + spanMinutes * 60_000L, onMinutes * 60_000L, unlocks))
    }

    private suspend fun rows() = db.dailyUsage().between(today.minusDays(500), today)

    private suspend fun trend() = TrendCalculator.compute(rows(), today)

    @Test
    fun emptyDatabase() = runBlocking {
        assertEquals(0, aggregator.backfill(today))
        assertTrue(rows().isEmpty())
        val snap = trend()
        assertNull(snap.average7)
        assertNull(snap.average30)
        assertEquals(Trend.FLAT, snap.trend)
    }

    @Test
    fun threeDaysOfHistory() = runBlocking {
        session(today.minusDays(3), 9, onMinutes = 40)
        session(today.minusDays(3), 20, onMinutes = 20, unlocks = 3)
        session(today.minusDays(2), 13, onMinutes = 200)
        session(today.minusDays(1), 18, onMinutes = 5)
        session(today, 8, onMinutes = 50) // today is never written

        assertEquals(3, aggregator.backfill(today))
        val written = rows()
        assertEquals(listOf(today.minusDays(3), today.minusDays(2), today.minusDays(1)), written.map { it.date })
        assertEquals(listOf(60, 200, 5), written.map { it.totalScreenMinutes })
        assertEquals(4, written.first().unlockCount)

        val snap = trend()
        assertEquals(265 / 3.0, snap.average7!!, 0.001)
        assertEquals(3, snap.historyDays)
        // Wildly uneven days, but three days is not a trend.
        assertEquals(Trend.FLAT, snap.trend)
    }

    @Test
    fun twoWeekGapIsBackfilled() = runBlocking {
        // Rows exist up to 16 days ago. Then the worker did not run for 15 days while sessions kept coming.
        for (d in 30 downTo 16) db.dailyUsage().upsert(DailyUsage(today.minusDays(d.toLong()), 100, 5, 1))
        for (d in 15 downTo 1) session(today.minusDays(d.toLong()), 10, onMinutes = 150)

        assertEquals(15, aggregator.backfill(today))
        val all = rows()
        assertEquals(30, all.size)
        assertEquals((1L..30L).map { today.minusDays(it) }.sorted(), all.map { it.date })
        assertTrue(all.filter { it.date >= today.minusDays(15) }.all { it.totalScreenMinutes == 150 })

        val snap = trend()
        assertEquals(150.0, snap.average7!!, 0.001)
        assertEquals(125.0, snap.average30!!, 0.001)
        assertEquals(Trend.UP, snap.trend)
    }

    @Test
    fun downTrendFromSeededRows() = runBlocking {
        for (d in 30 downTo 8) db.dailyUsage().upsert(DailyUsage(today.minusDays(d.toLong()), 240, 0, 0))
        for (d in 7 downTo 1) session(today.minusDays(d.toLong()), 12, onMinutes = 60)
        aggregator.backfill(today)
        val snap = trend()
        assertEquals(60.0, snap.average7!!, 0.001)
        assertEquals((23 * 240 + 7 * 60) / 30.0, snap.average30!!, 0.001)
        assertEquals(Trend.DOWN, snap.trend)
    }

    @Test
    fun backfillWithNoGapOnlyRewritesYesterday() = runBlocking {
        session(today.minusDays(2), 10, onMinutes = 30)
        session(today.minusDays(1), 10, onMinutes = 30)
        assertEquals(2, aggregator.backfill(today))
        // A checkpoint for yesterday's last session landed after the first run.
        session(today.minusDays(1), 23, onMinutes = 15)
        assertEquals(1, aggregator.backfill(today))
        assertEquals(listOf(30, 45), rows().map { it.totalScreenMinutes })
    }

    @Test
    fun sessionAcrossMidnightIsSplit() = runBlocking {
        val day = today.minusDays(2)
        val start = millis(day, 23, 30)
        db.screenSessions().upsert(ScreenSession(start, start + 60 * 60_000L, 60 * 60_000L, 1))
        aggregator.backfill(today)
        val byDate = rows().associateBy { it.date }
        assertEquals(30, byDate.getValue(day).totalScreenMinutes)
        assertEquals(30, byDate.getValue(day.plusDays(1)).totalScreenMinutes)
        assertEquals(1, byDate.getValue(day).unlockCount)
        assertEquals(0, byDate.getValue(day.plusDays(1)).unlockCount)
    }

    @Test
    fun sessionFrom2330To0100SplitsAcrossBothDays() = runBlocking {
        val day = today.minusDays(2)
        val start = millis(day, 23, 30)
        val end = millis(day.plusDays(1), 1, 0)
        db.screenSessions().upsert(ScreenSession(start, end, 90 * 60_000L, 1))
        aggregator.backfill(today)
        val byDate = rows().associateBy { it.date }
        assertEquals(30, byDate.getValue(day).totalScreenMinutes)
        assertEquals(60, byDate.getValue(day.plusDays(1)).totalScreenMinutes)
        // The unlock that started it belongs to the first day only.
        assertEquals(1, byDate.getValue(day).unlockCount)
        assertEquals(0, byDate.getValue(day.plusDays(1)).unlockCount)
    }

    @Test
    fun midnightSplitNeverLosesOrDoublesAMillisecond() {
        val midnight = millis(today, 0)
        // Deliberately awkward numbers: 23:29:42.317 to 01:00:05.911 with gaps, so every share has a remainder.
        val start = midnight - (30 * 60_000L + 17_683L)
        val end = midnight + (60 * 60_000L + 5_911L)
        for (onMillis in listOf(end - start, 5_431_117L, 4_999_999L, 1L)) {
            val session = ScreenSession(start, end, onMillis, 1)
            val before = DailyUsageAggregator.screenOnWithin(session, millis(today.minusDays(1), 0), midnight)
            val after = DailyUsageAggregator.screenOnWithin(session, midnight, millis(today.plusDays(1), 0))
            assertEquals("on=$onMillis", onMillis, before + after)
        }
    }

    @Test
    fun dayWrittenWhileASessionWasStillOpenIsCorrectedTheNextNight() = runBlocking {
        val day = today.minusDays(2)
        val start = millis(day, 23, 30)
        // The run just after midnight sees the 00:05 checkpoint of a session that is still going.
        db.screenSessions().upsert(ScreenSession(start, millis(day.plusDays(1), 0, 5), 35 * 60_000L, 1))
        aggregator.backfill(day.plusDays(1))
        assertEquals(30, rows().single().totalScreenMinutes)

        // It ends at 01:00 with 80 minutes on screen (some short screen-off gaps along the way).
        db.screenSessions().upsert(ScreenSession(start, millis(day.plusDays(1), 1, 0), 80 * 60_000L, 1))
        assertEquals(2, aggregator.backfill(today))
        val written = rows()
        assertEquals(listOf(day, day.plusDays(1)), written.map { it.date })
        assertEquals(listOf(27, 53), written.map { it.totalScreenMinutes })
        assertEquals(80, written.sumOf { it.totalScreenMinutes })
    }

    @Test
    fun freshInstallAveragesOnlyTrackedDays() = runBlocking {
        // Installed four days ago at 21:00; nothing exists before that.
        val installDay = today.minusDays(4)
        session(installDay, 21, onMinutes = 20)
        for (d in 3 downTo 1) session(today.minusDays(d.toLong()), 10, onMinutes = 120)
        aggregator.backfill(today)
        assertEquals(4, rows().size)

        val snap = TrendCalculator.compute(rows(), today, trackingStart = installDay)
        // Not (20 + 3 * 120) / 30 with the missing days as zeros, and not dragged down by the partial install day.
        assertEquals(120.0, snap.average7!!, 0.001)
        assertEquals(120.0, snap.average30!!, 0.001)
        assertEquals(3, snap.historyDays)
        assertEquals(Trend.FLAT, snap.trend)
    }

    @Test
    fun nagsAreCountedPerDay() = runBlocking {
        val day = today.minusDays(1)
        session(day, 10, onMinutes = 90)
        repeat(3) { db.nagEvents().insert(NagEvent(timestampEpochMillis = millis(day, 11, it), phraseId = 1, tier = 2, sessionMinutes = 60, foregroundPackage = null)) }
        db.nagEvents().insert(NagEvent(timestampEpochMillis = millis(today, 1), phraseId = 2, tier = 1, sessionMinutes = 30, foregroundPackage = "x"))
        aggregator.backfill(today)
        assertEquals(3, rows().single().nagCount)
    }

    @Test
    fun pruneKeeps400Days() = runBlocking {
        db.dailyUsage().upsert(DailyUsage(today.minusDays(401), 10, 0, 0))
        db.dailyUsage().upsert(DailyUsage(today.minusDays(400), 10, 0, 0))
        session(today.minusDays(402), 10, onMinutes = 10)
        session(today.minusDays(5), 10, onMinutes = 10)
        aggregator.prune(today)
        assertEquals(listOf(today.minusDays(400)), rows().map { it.date })
        assertEquals(1, db.screenSessions().overlapping(0, Long.MAX_VALUE).size)
    }

    @Test
    fun backfillNeverReachesFurtherBackThanRetention() = runBlocking {
        session(today.minusDays(450), 10, onMinutes = 10)
        assertEquals(400, aggregator.backfill(today))
    }

    @Test
    fun aDayTheServiceWasNotRunningIsUntrackedNotZero() = runBlocking {
        stopCovering()
        val yesterday = today.minusDays(1)
        // The phone was off, or the service was killed: nothing measured, so no row may claim zero minutes.
        aggregator.backfill(today)
        session(yesterday, 10, onMinutes = 30)
        aggregator.backfill(today)
        val row = rows().single { it.date == yesterday }
        assertEquals(30, row.totalScreenMinutes)
        assertFalse("no coverage, so not a tracked day", row.tracked)

        val snap = TrendCalculator.compute(rows(), today)
        assertEquals(0, snap.historyDays)
        assertNull("untracked days must not become averages", snap.average7)
    }

    @Test
    fun halfADayOfCoverageIsEnoughAndLessIsNot() = runBlocking {
        stopCovering()
        val day = today.minusDays(1)
        session(day, 9, onMinutes = 60)
        // 11 h 59 min: just short of half the day.
        db.trackingIntervals().upsert(TrackingInterval(millis(day, 0), millis(day, 11, 59)))
        aggregator.backfill(today)
        assertFalse(rows().single { it.date == day }.tracked)

        db.trackingIntervals().upsert(TrackingInterval(millis(day, 0), millis(day, 12)))
        aggregator.backfill(today)
        assertTrue(rows().single { it.date == day }.tracked)
        assertEquals(1, TrendCalculator.compute(rows(), today).historyDays)
    }

    @Test
    fun overlappingIntervalsAreNotCountedTwice() = runBlocking {
        stopCovering()
        val day = today.minusDays(1)
        db.trackingIntervals().upsert(TrackingInterval(millis(day, 0), millis(day, 8)))
        db.trackingIntervals().upsert(TrackingInterval(millis(day, 4), millis(day, 10)))
        session(day, 9, onMinutes = 30)
        val covered: Long = aggregator.coveredMillis(millis(day, 0), millis(day.plusDays(1), 0))
        assertEquals(10 * 60 * 60_000L, covered)
        aggregator.backfill(today)
        assertFalse("10 h of real coverage is still under half a day", rows().single { it.date == day }.tracked)
    }

    @Test
    fun theStillOpenIntervalCountsUpToNow() = runBlocking {
        stopCovering()
        val day = today.minusDays(1)
        val serviceStarted = millis(day, 6)
        // The service started at 06:00 yesterday and is still alive now, five minutes past midnight.
        db.trackingIntervals().upsert(TrackingInterval(serviceStarted, millis(day, 20)))
        val live = DailyUsageAggregator(
            db,
            zone = { zone },
            now = { millis(today, 0, 5) },
            openCoverageStart = { serviceStarted },
        )
        session(day, 10, onMinutes = 45)
        live.backfill(today)
        assertTrue("18 h of coverage, counted to now", rows().single { it.date == day }.tracked)
    }
}
