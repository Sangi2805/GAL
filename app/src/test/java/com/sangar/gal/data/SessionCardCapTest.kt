package com.sangar.gal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCardCapTest {

    /** The brief's boundaries, including both sides of the 45 minute edge. */
    @Test
    fun theThresholdDecidesHowManyCardsASessionGets() {
        assertEquals(6, SessionCardCap.auto(5))
        assertEquals(6, SessionCardCap.auto(10))
        assertEquals(4, SessionCardCap.auto(15))
        assertEquals(4, SessionCardCap.auto(30))
        assertEquals(4, SessionCardCap.auto(45))
        assertEquals(3, SessionCardCap.auto(46))
        assertEquals(3, SessionCardCap.auto(120))
    }

    @Test
    fun everyThresholdInRangeGetsACapAndTheCapOnlyFalls() {
        var previous = Int.MAX_VALUE
        for (minutes in Threshold.MIN_MINUTES..Threshold.MAX_MINUTES) {
            val cap = SessionCardCap.auto(minutes)
            assertEquals("$minutes min is out of range", cap, cap.coerceIn(SessionCardCap.MIN, SessionCardCap.MAX))
            assertTrue("$minutes min went back up to $cap from $previous", cap <= previous)
            previous = cap
        }
    }

    @Test
    fun autoFollowsTheThresholdAndAnOverrideDoesNot() {
        assertEquals(6, SessionCardCap.effective(5, SessionCardCap.AUTO))
        assertEquals(3, SessionCardCap.effective(120, SessionCardCap.AUTO))
        assertEquals(2, SessionCardCap.effective(5, 2))
        assertEquals(2, SessionCardCap.effective(120, 2))
    }

    @Test
    fun anOverrideOutOfRangeIsClamped() {
        assertEquals(SessionCardCap.MIN, SessionCardCap.effective(30, -4))
        assertEquals(SessionCardCap.MAX, SessionCardCap.effective(30, 999))
    }

    @Test
    fun describeSaysWhereTheCapCameFrom() {
        assertEquals("4 (auto)", SessionCardCap.describe(30, SessionCardCap.AUTO))
        assertEquals("2 (set by hand)", SessionCardCap.describe(30, 2))
    }
}
