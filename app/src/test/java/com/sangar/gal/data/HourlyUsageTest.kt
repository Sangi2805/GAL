package com.sangar.gal.data

import com.sangar.gal.data.AppEvent.Kind.PAUSED
import com.sangar.gal.data.AppEvent.Kind.RESUMED
import com.sangar.gal.data.AppEvent.Kind.SCREEN_OFF
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class HourlyUsageTest {

    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 11, 15)

    private fun at(hour: Int, minute: Int, date: LocalDate = day) =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private val from = at(0, 0)
    private val to = at(0, 0, day.plusDays(1))

    @Test
    fun aSessionInsideOneHourIsOneRow() {
        val rows = HourlyUsage.rows(listOf(AppEvent(at(9, 10), "yt", RESUMED), AppEvent(at(9, 25), "yt", PAUSED)), from, to, zone, false)
        assertEquals(listOf(HourUse(day, 9, "yt", 15 * 60_000L)), rows)
    }

    @Test
    fun aSessionAcrossTheHourIsSplitAtTheHourMark() {
        val rows = HourlyUsage.rows(listOf(AppEvent(at(9, 50), "yt", RESUMED), AppEvent(at(10, 20), "yt", PAUSED)), from, to, zone, false)
        assertEquals(listOf(HourUse(day, 9, "yt", 10 * 60_000L), HourUse(day, 10, "yt", 20 * 60_000L)), rows)
    }

    @Test
    fun theScreenGoingOffEndsWhateverWasInFront() {
        val rows = HourlyUsage.rows(listOf(AppEvent(at(9, 0), "yt", RESUMED), AppEvent(at(9, 5), null, SCREEN_OFF)), from, to, zone, false)
        assertEquals(5 * 60_000L, rows.single().millis)
    }

    @Test
    fun anAppLeftOpenCountsToNowTodayButOnlyToTheLastEventOnAPastDay() {
        val events = listOf(AppEvent(at(9, 0), "yt", RESUMED), AppEvent(at(9, 30), "maps", RESUMED), AppEvent(at(9, 40), "maps", PAUSED))
        val today = HourlyUsage.rows(events, from, at(9, 50), zone, openRunsToEnd = true)
        assertEquals(50 * 60_000L, today.first { it.packageName == "yt" }.millis)
        val past = HourlyUsage.rows(events, from, to, zone, openRunsToEnd = false)
        assertEquals(40 * 60_000L, past.first { it.packageName == "yt" }.millis)
    }

    @Test
    fun useBeforeTheWindowIsClippedAndExcludedAppsAreLeftOut() {
        val events = listOf(
            AppEvent(from - 10 * 60_000L, "yt", RESUMED),
            AppEvent(at(0, 20), "yt", PAUSED),
            AppEvent(at(1, 0), "launcher", RESUMED),
            AppEvent(at(1, 30), "launcher", PAUSED),
        )
        val rows = HourlyUsage.rows(events, from, to, zone, false, exclude = setOf("launcher"))
        assertEquals(listOf(HourUse(day, 0, "yt", 20 * 60_000L)), rows)
    }

    @Test
    fun blinksShorterThanFiveSecondsAreDropped() {
        val rows = HourlyUsage.rows(listOf(AppEvent(at(9, 0), "yt", RESUMED), AppEvent(at(9, 0) + 2_000, "yt", PAUSED)), from, to, zone, false)
        assertEquals(emptyList<HourUse>(), rows)
    }

    @Test
    fun theDayTotalOnlyCountsTheChosenApps() {
        val rows = listOf(HourUse(day, 9, "yt", 600_000), HourUse(day, 10, "ig", 300_000), HourUse(day, 10, "maps", 900_000), HourUse(day.minusDays(1), 9, "yt", 999_000))
        assertEquals(900_000L, HourlyUsage.total(rows, setOf("yt", "ig"), day))
    }
}
