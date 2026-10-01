package com.sangar.gal.data

import com.sangar.gal.data.db.DailyUsage
import com.sangar.gal.phrases.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrendCalculatorTest {

    private val today = LocalDate.of(2026, 9, 15)

    /** Days before today, oldest first: minutes[0] is (size) days ago, the last is yesterday. */
    private fun history(vararg minutes: Int) = minutes.mapIndexed { i, m ->
        DailyUsage(today.minusDays((minutes.size - i).toLong()), m, 0, 0)
    }

    private fun DailyUsage.untracked() = copy(tracked = false)

    @Test
    fun emptyHistoryIsFlatWithNoAverages() {
        val snap = TrendCalculator.compute(emptyList(), today)
        assertNull(snap.average7)
        assertNull(snap.average30)
        assertEquals(Trend.FLAT, snap.trend)
        assertEquals(0, snap.historyDays)
    }

    @Test
    fun fewerThanSevenDaysIsAlwaysFlat() {
        val snap = TrendCalculator.compute(history(10, 10, 10, 10, 10, 300), today)
        assertEquals(6, snap.historyDays)
        assertEquals(Trend.FLAT, snap.trend)
    }

    @Test
    fun averagesUseTheLastSevenAndThirtyCompleteDays() {
        // 23 days at 100, then 7 days at 200. Today's partial row must be ignored.
        val rows = history(*IntArray(23) { 100 }, *IntArray(7) { 200 }) + DailyUsage(today, 999, 0, 0)
        val snap = TrendCalculator.compute(rows, today)
        assertEquals(200.0, snap.average7!!, 0.001)
        assertEquals((23 * 100 + 7 * 200) / 30.0, snap.average30!!, 0.001)
        assertEquals(Trend.UP, snap.trend)
    }

    @Test
    fun withinTenPercentIsFlat() {
        assertEquals(Trend.FLAT, TrendCalculator.trend(110.0, 100.0, 30))
        assertEquals(Trend.FLAT, TrendCalculator.trend(90.0, 100.0, 30))
        assertEquals(Trend.UP, TrendCalculator.trend(110.5, 100.0, 30))
        assertEquals(Trend.DOWN, TrendCalculator.trend(89.5, 100.0, 30))
    }

    @Test
    fun zeroMonthlyAverage() {
        assertEquals(Trend.FLAT, TrendCalculator.trend(0.0, 0.0, 30))
        assertEquals(Trend.UP, TrendCalculator.trend(5.0, 0.0, 30))
    }

    @Test
    fun recordAndStreakSignals() {
        val rows = history(*IntArray(20) { 120 }, 80, 70, 60)
        val snap = TrendCalculator.compute(rows, today)
        val quiet = TrendCalculator.signals(snap, rows, today, todayMinutes = 30)
        assertTrue(quiet.streakGood)
        assertFalse(quiet.newRecord)
        val record = TrendCalculator.signals(snap, rows, today, todayMinutes = 121)
        assertTrue(record.newRecord)
    }

    @Test
    fun signalsNeedAWeekOfHistory() {
        val rows = history(10, 10, 10)
        val snap = TrendCalculator.compute(rows, today)
        val signals = TrendCalculator.signals(snap, rows, today, todayMinutes = 500)
        assertFalse(signals.newRecord)
        assertFalse(signals.streakGood)
    }

    @Test
    fun installDayDoesNotCountTowardsTheWeek() {
        // Seven rows, but the oldest is the install evening.
        val rows = history(15, 100, 100, 100, 100, 100, 300)
        val installDay = rows.first().date
        val snap = TrendCalculator.compute(rows, today, trackingStart = installDay)
        assertEquals(6, snap.historyDays)
        assertEquals(Trend.FLAT, snap.trend)
        val signals = TrendCalculator.signals(snap, rows, today, todayMinutes = 999, trackingStart = installDay)
        assertEquals(6, signals.historyDays)
        assertFalse(signals.established)
        assertFalse(signals.newRecord)

        val week = history(15, 100, 100, 100, 100, 100, 100, 300)
        val weekSnap = TrendCalculator.compute(week, today, trackingStart = week.first().date)
        assertEquals(7, weekSnap.historyDays)
        assertTrue(TrendCalculator.signals(weekSnap, week, today, todayMinutes = 999, trackingStart = week.first().date).established)
    }

    @Test
    fun untrackedDaysAreLeftOutRatherThanCountedAsZero() {
        val rows = history(100, 100, 100, 100, 100, 100, 100, 100)
        val full = TrendCalculator.compute(rows, today)
        assertEquals(8, full.historyDays)
        assertEquals(100.0, full.average30!!, 0.001)

        // Three days the timer was not running, with the partial minutes they did catch.
        val patchy = rows.mapIndexed { i, row -> if (i < 3) row.copy(totalScreenMinutes = 12).untracked() else row }
        val snap = TrendCalculator.compute(patchy, today)
        assertEquals(5, snap.historyDays)
        assertEquals("only the tracked days count", 100.0, snap.average30!!, 0.001)
        assertEquals(Trend.FLAT, snap.trend)
    }

    @Test
    fun untrackedDaysCannotSetRecordsOrBreakStreaks() {
        val rows = history(*IntArray(20) { 120 }, 80, 70, 60).map { it } +
            listOf(DailyUsage(today.minusDays(4), 400, 0, 0, tracked = false))
        val snap = TrendCalculator.compute(rows, today)
        val signals = TrendCalculator.signals(snap, rows, today, todayMinutes = 130)
        assertTrue("400 minutes on an untracked day is not the record to beat", signals.newRecord)
        assertTrue(signals.streakGood)
    }
}
