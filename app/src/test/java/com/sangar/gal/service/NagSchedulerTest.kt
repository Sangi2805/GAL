package com.sangar.gal.service

import com.sangar.gal.data.SessionCardCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NagSchedulerTest {
    private val min = 60_000L
    private val threshold = 30 * min

    /** The cap for the 30 minute threshold these tests use, so they are never the ones running out. */
    private val cap = SessionCardCap.auto(30)

    @Test
    fun firstNagWaitsForTheThreshold() {
        val s = NagScheduler()
        assertFalse(s.isDue(1, 29 * min, threshold, cap, 0))
        assertTrue(s.isDue(1, 30 * min, threshold, cap, 0))
    }

    @Test
    fun repeatsEveryTenMinutesOfSessionTime() {
        val s = NagScheduler()
        s.onShown(1, 30 * min, cap, 30 * min)
        assertFalse(s.isDue(1, 39 * min, threshold, cap, 0))
        assertTrue(s.isDue(1, 40 * min, threshold, cap, 0))
        s.onShown(1, 40 * min, cap, 30 * min)
        assertEquals(2, s.nagsThisSession)
    }

    @Test
    fun newSessionStartsOver() {
        val s = NagScheduler()
        s.onShown(1, 30 * min, cap, 30 * min)
        assertFalse(s.isDue(2, 5 * min, threshold, cap, 0))
        assertEquals(0, s.nagsThisSession)
        assertTrue(s.isDue(2, 30 * min, threshold, cap, 0))
    }

    @Test
    fun snoozeSilencesForTenMinutesOfRealTime() {
        val s = NagScheduler()
        s.onShown(1, 30 * min, cap, 30 * min)
        s.onSnoozed(nowElapsed = 100 * min)
        assertFalse(s.isDue(1, 45 * min, threshold, cap, 105 * min))
        assertTrue(s.isDue(1, 45 * min, threshold, cap, 110 * min))
    }

    @Test
    fun raisingTheThresholdLiveDelaysTheNextNag() {
        val s = NagScheduler()
        s.onShown(1, 10 * min, cap, 10 * min)
        assertTrue(s.isDue(1, 20 * min, 10 * min, cap, 0))
        assertFalse(s.isDue(1, 20 * min, 60 * min, cap, 0))
    }

    @Test
    fun theCapStopsTheSessionAndNoLaterMinuteReopensIt() {
        val s = NagScheduler()
        var minute = 30L
        repeat(3) {
            assertTrue("card at $minute min", s.isDue(1, minute * min, threshold, 3, 0))
            s.onShown(1, minute * min, 3, 30 * min)
            minute += 10
        }
        assertEquals(3, s.nagsThisSession)
        for (later in listOf(60L, 120L, 600L)) {
            val check = s.check(1, later * min, threshold, 3, 0)
            assertFalse("still due at $later min", check.due)
            assertTrue(check.capReached)
        }
    }

    @Test
    fun onlyTheCardThatUsesUpTheCapIsTheLastOfTheSession() {
        val s = NagScheduler()
        val signOffs = mutableListOf<Int>()
        var minute = 30L
        while (!s.check(1, minute * min, threshold, 4, 0).capReached) {
            val check = s.check(1, minute * min, threshold, 4, 0)
            if (check.due) {
                if (check.lastOfSession) signOffs += check.nagsThisSession
                s.onShown(1, minute * min, 4, 30 * min)
            }
            minute += 1
        }
        // One sign-off, and it is the fourth card (index 3), not any of the three before it.
        assertEquals(listOf(3), signOffs)
    }

    @Test
    fun aSessionThatEndsBeforeTheCapNeverReachesItsSignOff() {
        val s = NagScheduler()
        var minute = 30L
        repeat(3) {
            val check = s.check(1, minute * min, threshold, 4, 0)
            assertTrue(check.due)
            assertFalse("card ${check.nagsThisSession} signed off early", check.lastOfSession)
            s.onShown(1, minute * min, 4, 30 * min)
            minute += 10
        }
        // The user locks the phone here. The next session starts from zero and owes nothing.
        val next = s.check(2, 30 * min, threshold, 4, 0)
        assertTrue(next.due)
        assertFalse(next.lastOfSession)
        assertEquals(0, next.nagsThisSession)
    }

    @Test
    fun theCapIsLatchedForTheSessionSoLoweringTheThresholdCannotUnlockExtraCards() {
        val s = NagScheduler()
        // Starts under a 2 hour threshold: 3 cards.
        val longThreshold = 120 * min
        assertEquals(3, s.check(1, 120 * min, longThreshold, SessionCardCap.auto(120), 0).cap)
        var minute = 120L
        repeat(3) {
            s.onShown(1, minute * min, SessionCardCap.auto(120), 120 * min)
            minute += 10
        }
        // Mid-session the user drops the threshold to 5 minutes, whose cap would be 6.
        val after = s.check(1, 200 * min, 5 * min, SessionCardCap.auto(5), 0)
        assertEquals(3, after.cap)
        assertTrue(after.capReached)
        assertFalse(after.due)
        // The new cap arrives with the next session.
        assertEquals(6, s.check(2, 5 * min, 5 * min, SessionCardCap.auto(5), 0).cap)
    }

    @Test
    fun raisingTheThresholdMidSessionDoesNotTakeCardsAway() {
        val s = NagScheduler()
        // A 5 minute threshold: 6 cards.
        assertEquals(6, s.check(1, 5 * min, 5 * min, SessionCardCap.auto(5), 0).cap)
        s.onShown(1, 5 * min, SessionCardCap.auto(5), 5 * min)
        // Raising it to 2 hours would mean 3 cards; this session keeps the 6 it started with.
        val after = s.check(1, 130 * min, 120 * min, SessionCardCap.auto(120), 0)
        assertEquals(6, after.cap)
        assertFalse(after.capReached)
        assertTrue(after.due)
    }
}
