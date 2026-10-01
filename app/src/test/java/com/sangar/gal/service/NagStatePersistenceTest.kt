package com.sangar.gal.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NagStatePersistenceTest {
    private val sec = 1_000L
    private val guard = NagController.MIN_GAP_MILLIS

    @Test
    fun aCardShownJustBeforeTheRestartStillHoldsTheGap() {
        // Card at wall 1000 s, the service restarts 20 s later at elapsed 5000 s.
        val last = NagStatePersistence.lastCardElapsed(1_000 * sec, 1_020 * sec, 5_000 * sec, guard)
        assertEquals(4_980 * sec, last)
    }

    @Test
    fun anOldCardIsForgotten() {
        assertNull(NagStatePersistence.lastCardElapsed(1_000 * sec, 1_000 * sec + guard, 5_000 * sec, guard))
        assertNull(NagStatePersistence.lastCardElapsed(0L, 1_000 * sec, 5_000 * sec, guard))
    }

    @Test
    fun aCardFromTheFutureCountsAsJustNow() {
        // The clock was set back after the card. Err towards quiet: treat it as shown this instant.
        assertEquals(5_000 * sec, NagStatePersistence.lastCardElapsed(2_000 * sec, 1_000 * sec, 5_000 * sec, guard))
    }

    @Test
    fun aRunningSnoozeComesBackOnTheNewClock() {
        val until = NagStatePersistence.snoozeUntilElapsed(1_300 * sec, 1_000 * sec, 50 * sec, NagScheduler.SNOOZE_MILLIS)
        assertEquals(350 * sec, until)
    }

    @Test
    fun anEndedSnoozeIsDropped() {
        assertNull(NagStatePersistence.snoozeUntilElapsed(900 * sec, 1_000 * sec, 50 * sec, NagScheduler.SNOOZE_MILLIS))
        assertNull(NagStatePersistence.snoozeUntilElapsed(0L, 1_000 * sec, 50 * sec, NagScheduler.SNOOZE_MILLIS))
    }

    @Test
    fun aSnoozeIsNeverRestoredLongerThanItCouldHaveBeen() {
        val until = NagStatePersistence.snoozeUntilElapsed(1_000_000 * sec, 1_000 * sec, 50 * sec, NagScheduler.SNOOZE_MILLIS)
        assertEquals(50 * sec + NagScheduler.SNOOZE_MILLIS, until)
    }

    @Test
    fun theInMemoryStoreRoundTrips() {
        val store = InMemoryNagStateStore()
        assertNull(store.load())
        val state = PersistedNagState(sessionKey = 42, nagsThisSession = 3, nextDueActiveMillis = 1_800 * sec, snoozeUntilWall = 0, lastCardWall = 99)
        store.save(state)
        assertEquals(state, store.load())
    }
}
