package com.sangar.gal.data

import com.sangar.gal.data.db.DailyUsage
import com.sangar.gal.data.db.ScreenSession
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class InsightsTest {

    private val zone = ZoneId.of("America/St_Johns")
    private val today = LocalDate.of(2026, 10, 30)

    private fun day(back: Long, minutes: Int) = DailyUsage(today.minusDays(back), minutes, 40, 2, tracked = true)

    private fun session(back: Long, hour: Int, minutes: Long, unlocks: Int = 1): ScreenSession {
        val start = today.minusDays(back).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return ScreenSession(start, start + minutes * 60_000L, minutes * 60_000L, unlocks)
    }

    @Test
    fun comparesWeeksAndNamesTheBestDay() {
        val days = (1L..7L).map { day(it, 120) } + (8L..14L).map { day(it, 200) } + day(3, 45)
        val lines = Insights.weekly(emptyList(), days.distinctBy { it.date }, today, zone, Locale.ENGLISH)
        assertTrue(lines.toString(), lines.any { it.contains("less than the week before") })
        assertTrue(lines.toString(), lines.any { it.startsWith("Your best day was") })
    }

    @Test
    fun noticesLateNightsAndLongStretches() {
        val sessions = (1L..6L).map { session(it, 22, 10) } + session(2, 14, 95)
        val lines = Insights.weekly(sessions, emptyList(), today, zone, Locale.ENGLISH)
        assertTrue(lines.toString(), lines.any { it.contains("after 9 p.m.") })
        assertTrue(lines.toString(), lines.any { it.contains("1 h 35 min in one go") })
    }

    @Test
    fun saysNothingWithoutEnoughData() {
        assertTrue(Insights.weekly(emptyList(), listOf(day(1, 60)), today, zone, Locale.ENGLISH).isEmpty())
    }
}
