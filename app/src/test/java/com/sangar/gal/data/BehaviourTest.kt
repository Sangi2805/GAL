package com.sangar.gal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class BehaviourTest {

    /** A Monday, so the four weeks before it are easy to reason about. */
    private val today = LocalDate.of(2026, 11, 16)
    private val min = 60_000L
    private val names = mapOf("yt" to "YouTube", "ig" to "Instagram", "wa" to "WhatsApp")
    private val label: (String) -> String = { names[it] ?: it }

    private fun find(rows: List<HourUse>, kind: Insight.Kind) =
        Behaviour.insights(rows, today, label, limit = 10).firstOrNull { it.kind == kind }

    /** [minutes] of [app] at [hour] on each of the last [days] days. */
    private fun daily(app: String, hour: Int, minutes: Int, days: Int, skipToday: Boolean = true) =
        (if (skipToday) 1..days else 0 until days).map { HourUse(today.minusDays(it.toLong()), hour, app, minutes * min) }

    @Test
    fun nothingToSayWithoutData() {
        assertEquals(emptyList<Insight>(), Behaviour.insights(emptyList(), today, label))
    }

    @Test
    fun aWeekdaySlotThatStandsOutIsNamed() {
        val rows = daily("yt", 20, 10, 27) +
            (1..27).map { today.minusDays(it.toLong()) }.filter { it.dayOfWeek == DayOfWeek.SUNDAY }.map { HourUse(it, 9, "yt", 90 * min) }
        assertEquals("YouTube peaks on Sunday mornings", find(rows, Insight.Kind.PEAK_SLOT)?.text)
    }

    @Test
    fun withUnderAWeekOfHistoryOnlyTheTimeOfDayIsNamed() {
        val rows = daily("yt", 23, 40, 3)
        assertEquals("You use YouTube most at night", find(rows, Insight.Kind.PEAK_SLOT)?.text)
    }

    @Test
    fun evenUseHasNoPeak() {
        val rows = (1..27).flatMap { d -> listOf(8, 14, 19, 23).map { h -> HourUse(today.minusDays(d.toLong()), h, "yt", 20 * min) } }
        assertNull(find(rows, Insight.Kind.PEAK_SLOT))
    }

    @Test
    fun aDropAgainstLastWeekIsReported() {
        val lastWeek = (8..14).map { HourUse(today.minusDays(it.toLong()), 18, "ig", 50 * min) }
        val thisWeek = (1..7).map { HourUse(today.minusDays(it.toLong()), 18, "ig", 40 * min) }
        assertEquals("Instagram is down 20% this week", find(lastWeek + thisWeek, Insight.Kind.APP_TREND)?.text)
    }

    @Test
    fun aRiseIsReportedTooAndSmallChangesAreNot() {
        val lastWeek = (8..14).map { HourUse(today.minusDays(it.toLong()), 18, "ig", 40 * min) }
        val up = (1..7).map { HourUse(today.minusDays(it.toLong()), 18, "ig", 60 * min) }
        assertEquals("Instagram is up 50% this week", find(lastWeek + up, Insight.Kind.APP_TREND)?.text)
        val same = (1..7).map { HourUse(today.minusDays(it.toLong()), 18, "ig", 42 * min) }
        assertNull(find(lastWeek + same, Insight.Kind.APP_TREND))
    }

    @Test
    fun noTrendWithoutTwoFullWeeks() {
        assertNull(find(daily("ig", 18, 60, 9), Insight.Kind.APP_TREND))
    }

    @Test
    fun theBusiestHourIsNamedInPlainClockWords() {
        val rows = daily("yt", 22, 50, 5) + daily("wa", 9, 5, 5)
        assertEquals("Most screen time is 10 to 11 pm", find(rows, Insight.Kind.BUSIEST_HOUR)?.text)
    }

    @Test
    fun hourRangesReadNaturally() {
        assertEquals("10 to 11 pm", Behaviour.hourRange(22))
        assertEquals("11 am to 12 pm", Behaviour.hourRange(11))
        assertEquals("11 pm to 12 am", Behaviour.hourRange(23))
        assertEquals("12 to 1 am", Behaviour.hourRange(0))
        assertEquals("12 to 1 pm", Behaviour.hourRange(12))
    }

    @Test
    fun lateNightsAreCountedOverTheLastWeek() {
        val rows = listOf(1L, 3L, 4L).map { HourUse(today.minusDays(it), 1, "yt", 25 * min) } + daily("wa", 12, 30, 6)
        assertEquals("Up past midnight on 3 of the last 7 nights", find(rows, Insight.Kind.LATE_NIGHT)?.text)
        assertNull(find(daily("wa", 12, 30, 6) + HourUse(today.minusDays(2), 1, "yt", 25 * min), Insight.Kind.LATE_NIGHT))
    }

    @Test
    fun theHeaviestWeekdayNeedsTwoWeeksAndAClearLead() {
        val base = daily("yt", 18, 60, 20)
        val saturdays = (1..20).map { today.minusDays(it.toLong()) }.filter { it.dayOfWeek == DayOfWeek.SATURDAY }.map { HourUse(it, 15, "yt", 180 * min) }
        assertEquals("Saturdays are your heaviest day", find(base + saturdays, Insight.Kind.HEAVIEST_DAY)?.text)
        assertNull(find(base, Insight.Kind.HEAVIEST_DAY))
        assertNull(find(daily("yt", 18, 60, 6), Insight.Kind.HEAVIEST_DAY))
    }

    @Test
    fun theTopAppsShareOfTheWeekIsGiven() {
        val rows = daily("yt", 18, 45, 6) + daily("wa", 12, 55, 6)
        val insight = find(rows, Insight.Kind.TOP_SHARE)
        assertNotNull(insight)
        assertEquals("WhatsApp is 55% of your screen time", insight?.text)
    }

    @Test
    fun onlyTheMostUsefulFewAreShownAndTheBoldPartIsSeparate() {
        val rows = daily("yt", 22, 60, 27) + daily("wa", 12, 20, 27)
        val shown = Behaviour.insights(rows, today, label)
        assertTrue(shown.size <= 3)
        shown.forEach { assertTrue(it.strong.isNotBlank()) }
    }

    @Test
    fun rowsOlderThanFourWeeksAreIgnored() {
        val old = listOf(HourUse(today.minusDays(40), 22, "yt", 300 * min))
        assertEquals(emptyList<Insight>(), Behaviour.insights(old, today, label))
    }
}
