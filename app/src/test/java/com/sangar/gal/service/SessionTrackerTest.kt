package com.sangar.gal.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrackerTest {

    private val wall0 = 1_700_000_000_000L
    private fun sec(s: Long) = s * 1000

    @Test
    fun screenOnWhileLockedDoesNotStartTheClock() {
        val t = SessionTracker()
        assertNull(t.onScreenOn(sec(0), wall0, keyguardLocked = true))
        assertFalse(t.isOpen)
        assertEquals(0L, t.activeMillis(sec(120)))
    }

    @Test
    fun clockStartsAtUserPresentAndCountsUp() {
        val t = SessionTracker()
        t.onScreenOn(sec(0), wall0, keyguardLocked = true)
        t.onUserPresent(sec(10), wall0 + sec(10))
        assertTrue(t.isCounting)
        assertEquals(sec(90), t.activeMillis(sec(100)))
        assertEquals(1, t.unlockCount)
    }

    @Test
    fun shortScreenOffContinuesTheSessionWithoutCountingTheGap() {
        val t = SessionTracker()
        t.onUserPresent(sec(0), wall0)
        t.onScreenOff(sec(300))
        assertNull(t.onScreenOn(sec(330), wall0, keyguardLocked = true))
        assertNull(t.onUserPresent(sec(340), wall0))
        assertEquals(sec(300) + sec(60), t.activeMillis(sec(400)))
        assertEquals(2, t.unlockCount)
    }

    @Test
    fun screenOffForMoreThanSixtySecondsResetsTheSession() {
        val t = SessionTracker()
        t.onUserPresent(sec(0), wall0)
        t.onScreenOff(sec(600))
        val finished = t.onUserPresent(sec(661), wall0 + sec(661))
        assertNotNull(finished)
        assertEquals(sec(600), finished!!.screenOnMillis)
        assertEquals(wall0, finished.startEpochMillis)
        assertEquals(wall0 + sec(600), finished.endEpochMillis)
        assertEquals(0L, t.activeMillis(sec(661)))
        assertEquals(sec(10), t.activeMillis(sec(671)))
    }

    @Test
    fun exactlySixtySecondsOffStillContinues() {
        val t = SessionTracker()
        t.onUserPresent(sec(0), wall0)
        t.onScreenOff(sec(100))
        assertNull(t.onUserPresent(sec(160), wall0))
        assertEquals(sec(100), t.activeMillis(sec(160)))
    }

    @Test
    fun lockScreenPeeksOnlyCountScreenOffTimeTowardsTheReset() {
        val t = SessionTracker()
        t.onUserPresent(sec(0), wall0)
        t.onScreenOff(sec(100))
        t.onScreenOn(sec(130), wall0, keyguardLocked = true) // 30 s off
        t.onScreenOff(sec(200)) // on the lock screen for 70 s, not counted either way
        assertNull(t.onUserPresent(sec(225), wall0)) // 25 s more off, 55 s total
        assertEquals(sec(100), t.activeMillis(sec(225)))
    }

    @Test
    fun staleCheckClosesAPausedSession() {
        val t = SessionTracker()
        t.onUserPresent(sec(0), wall0)
        t.onScreenOff(sec(50))
        assertEquals(sec(60) + 1, t.millisUntilStale(sec(50)))
        assertNull(t.closeIfStale(sec(110)))
        val closed = t.closeIfStale(sec(111))
        assertNotNull(closed)
        assertFalse(t.isOpen)
    }

    @Test
    fun wallClockChangesDoNotAffectDuration() {
        val t = SessionTracker()
        t.onUserPresent(sec(0), wall0)
        t.onScreenOff(sec(20))
        // The user moved the clock back an hour before unlocking again.
        t.onUserPresent(sec(30), wall0 - 3_600_000L)
        t.onScreenOff(sec(50))
        val record = t.finish(sec(50))!!
        assertEquals(sec(40), record.screenOnMillis)
        assertEquals(wall0 + sec(50), record.endEpochMillis)
    }

    @Test
    fun screenOnWithoutSecureLockCountsAsUnlock() {
        val t = SessionTracker()
        t.onScreenOn(sec(0), wall0, keyguardLocked = false)
        assertTrue(t.isCounting)
        // The USER_PRESENT that may follow must not double count.
        t.onUserPresent(sec(1), wall0)
        assertEquals(1, t.unlockCount)
    }

    @Test
    fun snapshotReflectsTheOpenSession() {
        val t = SessionTracker()
        assertNull(t.snapshot(sec(0)))
        t.onUserPresent(sec(0), wall0)
        val snap = t.snapshot(sec(45))!!
        assertEquals(sec(45), snap.screenOnMillis)
        assertEquals(wall0 + sec(45), snap.endEpochMillis)
        assertTrue(t.isOpen)
    }

    @Test
    fun aRestartInsideTheResetWindowPicksTheSessionBackUp() {
        val record = SessionRecord(wall0, wall0 + sec(600), sec(540), unlockCount = 3)
        val t = SessionTracker()
        t.restore(elapsed = sec(1000), record = record, gapMillis = sec(20))

        assertTrue(t.isOpen)
        assertFalse(t.isCounting)
        assertEquals(sec(540), t.activeMillis(sec(1000)))
        assertEquals(3, t.unlockCount)

        // The screen comes back: same session continues, and it is not counted as a new unlock.
        assertNull(t.onUserPresent(sec(1005), wall0 + sec(1005), countAsUnlock = false))
        assertEquals(3, t.unlockCount)
        assertEquals(sec(540) + sec(55), t.activeMillis(sec(1060)))

        val snapshot = t.snapshot(sec(1060))!!
        assertEquals(wall0, snapshot.startEpochMillis)
        assertEquals(sec(540) + sec(55), snapshot.screenOnMillis)
    }

    @Test
    fun aRestoredSessionStillGoesStaleOnTime() {
        val record = SessionRecord(wall0, wall0 + sec(600), sec(540), unlockCount = 1)
        val t = SessionTracker()
        // The service was gone for 50 of the 60 seconds that end a session.
        t.restore(elapsed = sec(1000), record = record, gapMillis = sec(50))
        assertNull(t.closeIfStale(sec(1005)))
        // 50 of the 60 seconds are gone, and the window ends one millisecond after the last.
        assertEquals(10_001L, t.millisUntilStale(sec(1000)))

        val closed = t.closeIfStale(sec(1011))!!
        assertEquals(sec(540), closed.screenOnMillis)
        assertFalse(t.isOpen)
    }

    @Test
    fun aRestoredSessionsCheckpointStaysOnWallClockTime() {
        val record = SessionRecord(wall0, wall0 + sec(600), sec(540), unlockCount = 2)
        val t = SessionTracker()
        t.restore(elapsed = sec(1000), record = record, gapMillis = sec(30))
        t.onUserPresent(sec(1000), wall0 + sec(630), countAsUnlock = false)
        val snapshot = t.snapshot(sec(1100))!!
        // 600 s of session, then a 30 s gap, then 100 s more: the stored end must match that span.
        assertEquals(wall0 + sec(730), snapshot.endEpochMillis)
        assertEquals(sec(640), snapshot.screenOnMillis)
    }
}
