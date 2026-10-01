package com.sangar.gal.phrases

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NagContextTest {

    private val wednesdayNoon = LocalDateTime.of(2026, 9, 16, 12, 0)

    @Test
    fun sessionLengthBuckets() {
        assertTrue(Tags.SHORT_SESSION in NagContext.tags(44, 0, wednesdayNoon, UsageSignals()))
        assertTrue(Tags.LONG_SESSION in NagContext.tags(45, 0, wednesdayNoon, UsageSignals()))
        assertTrue(Tags.MARATHON in NagContext.tags(120, 0, wednesdayNoon, UsageSignals()))
    }

    @Test
    fun timeOfDayAndWeekend() {
        val sat7am = LocalDateTime.of(2026, 9, 19, 7, 0)
        val tags = NagContext.tags(10, 0, sat7am, UsageSignals())
        assertTrue(Tags.MORNING in tags && Tags.WEEKEND in tags)
        val lateTags = NagContext.tags(10, 0, LocalDateTime.of(2026, 9, 16, 23, 30), UsageSignals())
        assertTrue(Tags.LATE_NIGHT in lateTags)
        assertTrue(Tags.LATE_NIGHT in NagContext.tags(10, 0, LocalDateTime.of(2026, 9, 16, 3, 0), UsageSignals()))
        val noon = NagContext.tags(10, 0, wednesdayNoon, UsageSignals())
        assertTrue(Tags.MORNING !in noon && Tags.LATE_NIGHT !in noon && Tags.WEEKEND !in noon)
    }

    @Test
    fun nagOrderAndSignals() {
        val tags = NagContext.tags(60, 2, wednesdayNoon, UsageSignals(Trend.UP, newRecord = true, streakGood = true, historyDays = 30))
        assertTrue(Tags.REPEAT_NAG in tags && Tags.TREND_UP in tags && Tags.NEW_RECORD in tags && Tags.STREAK_GOOD in tags)
        assertTrue(Tags.FIRST_NAG in NagContext.tags(60, 0, wednesdayNoon, UsageSignals()))
        assertTrue(Tags.GENERAL !in tags)
    }

    @Test
    fun tierEscalatesWithSessionAndTrend() {
        assertEquals(1, NagContext.tier(30, Trend.FLAT))
        assertEquals(2, NagContext.tier(30, Trend.UP))
        assertEquals(1, NagContext.tier(60, Trend.DOWN))
        assertEquals(2, NagContext.tier(60, Trend.FLAT))
        assertEquals(3, NagContext.tier(60, Trend.UP))
        assertEquals(2, NagContext.tier(150, Trend.DOWN))
        assertEquals(3, NagContext.tier(150, Trend.FLAT))
        assertEquals(3, NagContext.tier(500, Trend.UP))
    }

    @Test
    fun newUserStaysAtTierOneWithNoHistoryTags() {
        val loud = UsageSignals(Trend.UP, newRecord = true, streakGood = true, historyDays = 6)
        assertEquals(1, NagContext.tier(500, loud))
        assertEquals(1, NagContext.tier(150, UsageSignals()))
        val tags = NagContext.tags(500, 3, wednesdayNoon, loud)
        assertTrue(Tags.TREND_UP !in tags && Tags.NEW_RECORD !in tags && Tags.STREAK_GOOD !in tags)
        // Facts about the moment itself still apply.
        assertTrue(Tags.MARATHON in tags && Tags.REPEAT_NAG in tags)
    }

    @Test
    fun aWeekOfHistoryUnlocksEscalation() {
        val week = UsageSignals(Trend.UP, newRecord = true, historyDays = 7)
        assertEquals(3, NagContext.tier(60, week))
        assertEquals(2, NagContext.tier(30, week))
        assertTrue(Tags.TREND_UP in NagContext.tags(60, 0, wednesdayNoon, week))
        assertTrue(Tags.NEW_RECORD in NagContext.tags(60, 0, wednesdayNoon, week))
    }

    @Test
    fun workHoursAreWeekdayNineToFive() {
        fun at(day: Int, hour: Int, minute: Int = 0) =
            NagContext.tags(10, 0, LocalDateTime.of(2026, 9, day, hour, minute), UsageSignals())
        // 14 September 2026 is a Monday, the 18th a Friday, the 19th and 20th the weekend.
        assertTrue(Tags.WORK_HOURS in at(14, 9))
        assertTrue(Tags.WORK_HOURS in at(18, 16, 59))
        assertTrue(Tags.WORK_HOURS !in at(16, 8, 59))
        assertTrue(Tags.WORK_HOURS !in at(16, 17))
        assertTrue(Tags.WORK_HOURS !in at(19, 11))
        assertTrue(Tags.WORK_HOURS !in at(20, 2))
    }
}
