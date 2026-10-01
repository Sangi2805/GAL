package com.sangar.gal.service

import com.sangar.gal.data.SessionCardCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NagSchedulerTest {
    private val min = 60_000L
    private val threshold = 30 * min

    @Test
    fun firstNagWaitsForTheThreshold() {
        val s = NagScheduler()
        assertFalse(s.isDue(1, 29 * min, threshold, 0))
        assertTrue(s.isDue(1, 30 * min, threshold, 0))
    }

    @Test
    fun repeatsEveryThresholdOfSessionTime() {
        val s = NagScheduler()
        val ten = 10 * min
        assertTrue(s.isDue(1, 10 * min, ten, 0))
        s.onShown(1, 10 * min, ten)
        assertFalse(s.isDue(1, 19 * min, ten, 0))
        assertTrue(s.isDue(1, 20 * min, ten, 0))
        s.onShown(1, 20 * min, ten)
        assertFalse(s.isDue(1, 29 * min, ten, 0))
        assertTrue(s.isDue(1, 30 * min, ten, 0))
        assertEquals(2, s.nagsThisSession)
    }

    @Test
    fun aLongThresholdMeansALongGapToo() {
        val s = NagScheduler()
        s.onShown(1, 30 * min, threshold)
        // The old rule capped the gap at 10 minutes. Now a 30 minute threshold means 30 minutes between cards.
        assertFalse(s.isDue(1, 40 * min, threshold, 0))
        assertFalse(s.isDue(1, 59 * min, threshold, 0))
        assertTrue(s.isDue(1, 60 * min, threshold, 0))
    }

    @Test
    fun newSessionStartsOver() {
        val s = NagScheduler()
        s.onShown(1, 30 * min, threshold)
        assertFalse(s.isDue(2, 5 * min, threshold, 0))
        assertEquals(0, s.nagsThisSession)
        assertTrue(s.isDue(2, 30 * min, threshold, 0))
    }

    @Test
    fun snoozeSilencesForTenMinutesOfRealTime() {
        val s = NagScheduler()
        s.onShown(1, 30 * min, 10 * min)
        s.onSnoozed(nowElapsed = 100 * min)
        assertFalse(s.isDue(1, 45 * min, 10 * min, 105 * min))
        assertTrue(s.isDue(1, 45 * min, 10 * min, 110 * min))
    }

    @Test
    fun raisingTheThresholdLiveDelaysTheNextNag() {
        val s = NagScheduler()
        s.onShown(1, 10 * min, 10 * min)
        assertTrue(s.isDue(1, 20 * min, 10 * min, 0))
        assertFalse(s.isDue(1, 20 * min, 60 * min, 0))
    }

    @Test
    fun twelveCardsThenNothingMoreThatSession() {
        val s = NagScheduler()
        var minute = 0L
        var shown = 0
        while (minute < 600) {
            minute += 1
            val check = s.check(1, minute * min, min, 0)
            if (check.due) {
                s.onShown(1, minute * min, min)
                shown++
            }
        }
        assertEquals(SessionCardCap.PER_SESSION, shown)
        assertEquals(12, s.nagsThisSession)
        val after = s.check(1, 900 * min, min, 0)
        assertFalse(after.due)
        assertTrue(after.capReached)
    }

    @Test
    fun onlyTheTwelfthCardIsTheSignOff() {
        val s = NagScheduler()
        val signOffs = mutableListOf<Int>()
        var minute = 0L
        while (!s.check(1, minute * min, 5 * min, 0).capReached) {
            minute += 1
            val check = s.check(1, minute * min, 5 * min, 0)
            if (check.due) {
                if (check.lastOfSession) signOffs += check.nagsThisSession
                s.onShown(1, minute * min, 5 * min)
            }
        }
        // One sign-off, and it is card twelve (index 11), not any of the eleven before it.
        assertEquals(listOf(11), signOffs)
        // With a 5 minute threshold it lands at 60 minutes of session time.
        assertEquals(60L, minute)
    }

    @Test
    fun aSessionThatEndsBeforeCardTwelveNeverReachesItsSignOff() {
        val s = NagScheduler()
        var minute = 10L
        repeat(5) {
            val check = s.check(1, minute * min, 10 * min, 0)
            assertTrue(check.due)
            assertFalse("card ${check.nagsThisSession} signed off early", check.lastOfSession)
            s.onShown(1, minute * min, 10 * min)
            minute += 10
        }
        // The user locks the phone here. The next session starts from zero and owes nothing.
        val next = s.check(2, 10 * min, 10 * min, 0)
        assertTrue(next.due)
        assertFalse(next.lastOfSession)
        assertEquals(0, next.nagsThisSession)
    }

    @Test
    fun aRestoredSessionCarriesOnCounting() {
        val first = NagScheduler()
        var minute = 0L
        repeat(5) {
            minute += 10
            first.onShown(7, minute * min, 10 * min)
        }
        val saved = first.snapshot()!!
        assertEquals(NagScheduler.Snapshot(7, 5, 60 * min), saved)

        // The service is killed and starts again; the same session is restored.
        val second = NagScheduler()
        second.restore(saved)
        val check = second.check(7, 55 * min, 10 * min, 0)
        assertEquals(5, check.nagsThisSession)
        assertFalse("restored session must keep its gap", check.due)
        assertTrue(second.check(7, 60 * min, 10 * min, 0).due)
    }

    @Test
    fun aSnapshotFromAnOlderSessionIsIgnored() {
        val s = NagScheduler()
        s.restore(NagScheduler.Snapshot(sessionKey = 1, nagsThisSession = 11, nextDueActiveMillis = 500 * min))
        val check = s.check(2, 30 * min, threshold, 0)
        assertEquals(0, check.nagsThisSession)
        assertTrue(check.due)
    }

    @Test
    fun aRestoreCannotGoPastTheCap() {
        val s = NagScheduler()
        s.restore(NagScheduler.Snapshot(sessionKey = 3, nagsThisSession = 99, nextDueActiveMillis = 0))
        val check = s.check(3, 30 * min, threshold, 0)
        assertEquals(SessionCardCap.PER_SESSION, check.nagsThisSession)
        assertFalse(check.due)
    }

    @Test
    fun noSnapshotBeforeTheFirstCheck() {
        assertNull(NagScheduler().snapshot())
    }

    @Test
    fun aRestoredSnoozeIsNeverLongerThanAFreshOne() {
        val s = NagScheduler()
        s.restoreSnooze(untilElapsed = 10_000 * min, nowElapsed = 100 * min)
        val check = s.check(1, 60 * min, threshold, 100 * min)
        assertEquals(NagScheduler.SNOOZE_MILLIS, check.snoozeRemainingMillis)
    }
}
