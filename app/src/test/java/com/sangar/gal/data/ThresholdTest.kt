package com.sangar.gal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdTest {

    @Test
    fun stopsRunFromOneMinuteToEightHours() {
        assertEquals(1, Threshold.stops.first())
        assertEquals(480, Threshold.stops.last())
        assertEquals(Threshold.stops.sorted(), Threshold.stops)
        assertEquals(Threshold.stops.size, Threshold.stops.toSet().size)
    }

    @Test
    fun fiveMinuteStepsBelowAnHourFifteenAbove() {
        val below = Threshold.stops.filter { it < 60 }
        assertEquals(listOf(1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55), below)
        val above = Threshold.stops.filter { it >= 60 }
        assertEquals(60, above.first())
        above.zipWithNext().forEach { (a, b) -> assertEquals(15, b - a) }
    }

    @Test
    fun indexAndMinutesRoundTripOnStops() {
        Threshold.stops.forEachIndexed { index, minutes ->
            assertEquals(index, Threshold.indexOf(minutes))
            assertEquals(minutes, Threshold.minutesAt(index))
        }
    }

    @Test
    fun exactValuesSitOnTheNearestStopAndAreClamped() {
        assertEquals(30, Threshold.minutesAt(Threshold.indexOf(31)))
        assertEquals(75, Threshold.minutesAt(Threshold.indexOf(70)))
        assertEquals(1, Threshold.minutesAt(Threshold.indexOf(-5)))
        assertEquals(480, Threshold.minutesAt(Threshold.indexOf(10_000)))
        assertEquals(1, Threshold.clamp(0))
        assertEquals(480, Threshold.clamp(481))
    }

    @Test
    fun zeroMeansOff() {
        assertEquals(Threshold.Input.Off, Threshold.parse("0"))
        assertEquals(Threshold.Input.Off, Threshold.parse(" 0 "))
    }

    @Test
    fun exactEntryAcceptsAnyWholeMinuteInRange() {
        assertEquals(Threshold.Input.Minutes(1), Threshold.parse("1"))
        assertEquals(Threshold.Input.Minutes(37), Threshold.parse("37"))
        assertEquals(Threshold.Input.Minutes(480), Threshold.parse("480"))
    }

    @Test
    fun invalidEntriesExplainThemselves() {
        listOf("", "  ", "abc", "1.5", "481", "9999", "-3").forEach { input ->
            val result = Threshold.parse(input)
            assertTrue("'$input' should be invalid, got $result", result is Threshold.Input.Invalid)
            assertTrue((result as Threshold.Input.Invalid).message.isNotBlank())
        }
    }

    @Test
    fun describesHoursAndMinutes() {
        assertEquals("1 min", Threshold.describe(1))
        assertEquals("1 h", Threshold.describe(60))
        assertEquals("2 h 15 min", Threshold.describe(135))
        assertEquals("8 h", Threshold.describe(480))
    }
}
