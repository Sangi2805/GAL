package com.sangar.gal.phrases

import com.sangar.gal.overlay.Mascot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * The give_up sign-off: the last card a session's cap allows, and only that card. See NagSchedulerTest for
 * the other half, which is that the last card only exists when the cap is actually reached.
 */
class GiveUpPhraseTest {

    private val phrases = PhraseTestData.phrases
    private val giveUp = phrases.filter { Tags.GIVE_UP in it.tags }

    /** Every moment a session card can be drawn for: length, repeat count, hour, day and history. */
    private val moments: List<Set<String>> = buildList {
        val signals = listOf(
            UsageSignals(),
            UsageSignals(historyDays = 30),
            UsageSignals(Trend.UP, newRecord = true, historyDays = 30),
            UsageSignals(Trend.DOWN, streakGood = true, historyDays = 30),
        )
        // A full week, every hour, so work_hours, late_night, morning and weekend all come up.
        for (day in 14..20) for (hour in 0..23) for (minutes in listOf(1L, 20L, 44L, 45L, 119L, 120L, 400L)) {
            for (nags in 0..3) for (signal in signals) {
                add(NagContext.tags(minutes, nags, LocalDateTime.of(2026, 9, day, hour, 0), signal))
            }
        }
    }

    @Test
    fun noOrdinaryMomentEverAsksForTheGiveUpTag() {
        assertTrue(moments.none { Tags.GIVE_UP in it })

    }

    /**
     * The tag stands alone with no "general", so it is unreachable from an ordinary moment: not through the
     * matched pool, and not through either general fallback. This is what stops it landing on a non-final card.
     */
    @Test
    fun theGiveUpLinesSitOutsideEveryPoolAnOrdinaryCardCanReach() {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore())
        assertEquals(80, giveUp.size)
        giveUp.forEach { assertEquals("${it.id} must carry give_up alone", setOf(Tags.GIVE_UP), it.tags) }
        for (tags in moments.distinct()) for (tier in Tags.TIERS) {
            val pool = engine.matchedPool(tags, tier)
            assertTrue("give_up reachable from $tags/t$tier", pool.none { Tags.GIVE_UP in it.tags })
        }
        // Both fallback steps ask for "general", which no give_up line carries.
        assertTrue(phrases.none { Tags.GIVE_UP in it.tags && Tags.GENERAL in it.tags })
    }

    @Test
    fun aNonFinalCardNeverDrawsOneEvenOverThousandsOfPicks() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(7))
        for (tags in moments.distinct().shuffled(Random(3)).take(120)) {
            for (tier in Tags.TIERS) {
                repeat(20) {
                    val phrase = engine.pick(tags, tier).phrase
                    assertFalse("'${phrase.text}' drawn for $tags", Tags.GIVE_UP in phrase.tags)
                }
            }
        }
    }

    @Test
    fun theFinalCardAlwaysDrawsOne() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(9))
        for (tier in Tags.TIERS) {
            repeat(200) {
                val pick = engine.pick(NagContext.GIVE_UP_TAGS, tier)
                assertEquals("t$tier fell through to ${pick.source}", PoolSource.MATCHED, pick.source)
                assertEquals(setOf(Tags.GIVE_UP), pick.phrase.tags)
                assertEquals(tier, pick.phrase.tier)
                assertTrue(pick.phrase.text.isNotBlank())
            }
        }
    }

    /** Exempt from the short-line preference: the sign-off is allowed to run on a bit. */
    @Test
    fun theSignOffIsNotHeldToTheShortLinePreference() = runTest {
        assertEquals(LengthMix.GIVE_UP, LengthMix.weightsFor(NagContext.GIVE_UP_TAGS))
        assertTrue(LengthMix.GIVE_UP.short < LengthMix.EVERYDAY.short)
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(4))
        val drawn = (1..600).map { engine.pick(NagContext.GIVE_UP_TAGS, 1 + it % 3).phrase.length }
        val short = drawn.count { it == PhraseLength.SHORT }.toDouble() / drawn.size
        assertTrue("short share $short", short < 0.25)
    }

    @Test
    fun theSignOffWearsTheBoredFaceWhateverTheTier() {
        for (tier in Tags.TIERS) for (nags in 0..5) {
            assertEquals(Mascot.BORED, Mascot.forNag(tier, nags, tags = setOf(Tags.GIVE_UP)))
        }
        // Without the sign-off the faces are unchanged.
        assertEquals(Mascot.HORRIFIED, Mascot.forNag(3, 2, tags = emptySet()))
    }
}
