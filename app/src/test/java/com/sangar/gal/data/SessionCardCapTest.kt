package com.sangar.gal.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCardCapTest {

    @Test
    fun everySessionGetsTwelveCards() {
        assertEquals(12, SessionCardCap.PER_SESSION)
    }

    @Test
    fun theGapBetweenCardsIsTheThresholdItself() {
        assertEquals(60_000L, SessionCardCap.gapMillis(1))
        assertEquals(10 * 60_000L, SessionCardCap.gapMillis(10))
        assertEquals(30 * 60_000L, SessionCardCap.gapMillis(30))
        assertEquals(8 * 60 * 60_000L, SessionCardCap.gapMillis(8 * 60))
    }

    @Test
    fun theGapFollowsTheThresholdRange() {
        assertEquals(SessionCardCap.gapMillis(Threshold.MIN_MINUTES), SessionCardCap.gapMillis(0))
        assertEquals(SessionCardCap.gapMillis(Threshold.MAX_MINUTES), SessionCardCap.gapMillis(10_000))
    }

    @Test
    fun theLastCardFallsAfterTwelveThresholds() {
        assertEquals(120L, SessionCardCap.lastCardAtMinutes(10))
        assertEquals(360L, SessionCardCap.lastCardAtMinutes(30))
        assertEquals(12L, SessionCardCap.lastCardAtMinutes(1))
    }

    @Test
    fun describesTheDealInPlainWords() {
        assertEquals("12 cards, one every 10 min", SessionCardCap.describe(10))
        assertEquals("12 cards, one every 1 h 30 min", SessionCardCap.describe(90))
    }
}
