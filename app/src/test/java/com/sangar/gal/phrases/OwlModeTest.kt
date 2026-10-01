package com.sangar.gal.phrases

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwlModeTest {

    private val phrases = PhraseTestData.phrases

    @Test
    fun appliesOnlyAboveAnHour() {
        assertFalse(OwlMode.applies(1))
        assertFalse(OwlMode.applies(60))
        assertTrue(OwlMode.applies(61))
        assertTrue(OwlMode.applies(480))
    }

    @Test
    fun tierRisesWithTheChosenThreshold() {
        assertEquals(1, OwlMode.tierFor(61))
        assertEquals(1, OwlMode.tierFor(180))
        assertEquals(2, OwlMode.tierFor(181))
        assertEquals(2, OwlMode.tierFor(300))
        assertEquals(3, OwlMode.tierFor(301))
        assertEquals(3, OwlMode.tierFor(480))
    }

    @Test
    fun everyLongThresholdGetsAnOwlLineFromItsTier() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore())
        for (minutes in 61..480 step 7) {
            val pick = engine.pickOwl(minutes)
            assertTrue(pick.phrase.text.isNotBlank())
            assertEquals(setOf(Tags.OWL_MODE), pick.phrase.tags)
            assertEquals("threshold $minutes", OwlMode.tierFor(minutes), pick.phrase.tier)
            assertEquals(PoolSource.MATCHED, pick.source)
        }
    }

    @Test
    fun owlLinesDoNotRepeatBackToBack() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore())
        val ids = (1..60).map { engine.pickOwl(90).phrase.id }
        ids.windowed(11).forEach { window -> assertTrue("repeat inside $window", window.last() !in window.dropLast(1)) }
    }

    @Test
    fun owlLinesNeverReachANagCardEvenThroughFallbacks() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore())
        for (tag in Tags.KNOWN) for (tier in 0..4) repeat(20) {
            val pick = engine.pick(setOf(tag), tier)
            assertFalse("owl line ${pick.phrase.id} picked for a nag ($tag/t$tier)", Tags.OWL_MODE in pick.phrase.tags)
        }
        // A catalog with nothing but owl lines must fall through to the default nag line, not to an owl line.
        val owlOnly = PhraseEngine(phrases.filter { Tags.OWL_MODE in it.tags }, InMemoryRecentIdStore())
        assertEquals(PoolSource.HARDCODED_DEFAULT, owlOnly.pick(setOf(Tags.GENERAL), 1).source)
    }

    @Test
    fun noOwlLinesFallsBackToTheHardcodedOwlLine() = runTest {
        val engine = PhraseEngine(emptyList(), InMemoryRecentIdStore())
        val pick = engine.pickOwl(400)
        assertEquals(OwlMode.DEFAULT_TEXT, pick.phrase.text)
        assertEquals(PoolSource.HARDCODED_DEFAULT, pick.source)
    }
}
