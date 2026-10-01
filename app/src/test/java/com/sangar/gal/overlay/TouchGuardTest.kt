package com.sangar.gal.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGuardTest {

    private val shownAt = 10_000L

    private fun TouchGuard.down(at: Long) = shouldSwallow(isDown = true, isEnd = false, downTimeUptime = at)
    private fun TouchGuard.move(downAt: Long) = shouldSwallow(isDown = false, isEnd = false, downTimeUptime = downAt)
    private fun TouchGuard.up(downAt: Long) = shouldSwallow(isDown = false, isEnd = true, downTimeUptime = downAt)

    @Test
    fun tapInsideTheFirst600msIsIgnoredEvenIfItLiftsLater() {
        val guard = TouchGuard().apply { arm(shownAt) }
        val downAt = shownAt + 599
        assertTrue(guard.down(downAt))
        assertTrue(guard.move(downAt))
        assertTrue(guard.up(downAt)) // lifts at 900 ms, still part of the early gesture
    }

    @Test
    fun tapFrom600msOnGoesThrough() {
        val guard = TouchGuard().apply { arm(shownAt) }
        val downAt = shownAt + 600
        assertFalse(guard.down(downAt))
        assertFalse(guard.move(downAt))
        assertFalse(guard.up(downAt))
    }

    @Test
    fun anEarlyGestureDoesNotPoisonTheNextOne() {
        val guard = TouchGuard().apply { arm(shownAt) }
        assertTrue(guard.down(shownAt + 100))
        assertTrue(guard.up(shownAt + 100))
        assertFalse(guard.down(shownAt + 2_000))
        assertFalse(guard.up(shownAt + 2_000))
    }

    @Test
    fun fingerAlreadyDownBeforeTheCardAppearedIsIgnored() {
        val guard = TouchGuard().apply { arm(shownAt) }
        assertTrue(guard.down(shownAt - 50))
    }

    @Test
    fun unarmedGuardIgnoresEverything() {
        assertTrue(TouchGuard().down(Long.MAX_VALUE / 2))
    }

    @Test
    fun reArmingForANewCardStartsTheWindowAgain() {
        val guard = TouchGuard().apply { arm(shownAt) }
        assertFalse(guard.down(shownAt + 5_000))
        guard.arm(shownAt + 5_010)
        assertTrue(guard.down(shownAt + 5_100))
    }
}
