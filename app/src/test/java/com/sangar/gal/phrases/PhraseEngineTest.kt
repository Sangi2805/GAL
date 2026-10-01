package com.sangar.gal.phrases

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhraseEngineTest {

    private val phrases = PhraseTestData.phrases

    /**
     * Acceptance: for every tag and tier, 500 draws in a row never repeat an id inside that
     * combination's effective window. One shared store across all combinations, as on a real phone.
     */
    @Test
    fun noRepeatsInsideTheEffectiveWindowForEveryTagAndTier() = runTest {
        val store = InMemoryRecentIdStore()
        val engine = PhraseEngine(phrases, store, Random(42))
        for (tag in Tags.ALL) {
            for (tier in Tags.TIERS) {
                val drawn = ArrayList<Int>(500)
                var window = -1
                repeat(500) {
                    val pick = engine.pick(setOf(tag), tier)
                    assertEquals("$tag/t$tier fell through to ${pick.source}", PoolSource.MATCHED, pick.source)
                    window = pick.effectiveWindow
                    drawn += pick.phrase.id
                }
                val pool = engine.matchedPool(setOf(tag), tier).size
                assertEquals(minOf(150, pool / 2), window)
                for (i in drawn.indices) {
                    val recent = drawn.subList(maxOf(0, i - window), i)
                    assertFalse(
                        "$tag/t$tier: id ${drawn[i]} repeated at draw $i within window $window (pool $pool)",
                        drawn[i] in recent,
                    )
                }
            }
        }
    }

    /** Acceptance: every tag and tier has its own non-empty pool, so nothing falls through to general. */
    @Test
    fun everyTagAndTierHasANonEmptyMatchedPool() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore())
        for (tag in Tags.ALL) {
            for (tier in Tags.TIERS) {
                val pool = engine.matchedPool(setOf(tag), tier)
                assertTrue("$tag/t$tier is empty", pool.isNotEmpty())
                assertEquals(PoolSource.MATCHED, engine.pick(setOf(tag), tier).source)
            }
        }
    }

    @Test
    fun windowIsSizedToThePoolNotFixed() {
        val engine = PhraseEngine(emptyList(), InMemoryRecentIdStore())
        assertEquals(0, engine.effectiveWindow(1))
        assertEquals(5, engine.effectiveWindow(11))
        assertEquals(149, engine.effectiveWindow(298))
        assertEquals(150, engine.effectiveWindow(1000))
    }

    @Test
    fun smallPoolStillRotatesInsteadOfStarving() = runTest {
        val tiny = (1..4).map { Phrase(it, "p$it", 1, setOf(Tags.GENERAL, Tags.WEEKEND)) } +
            (5..400).map { Phrase(it, "g$it", 1, setOf(Tags.GENERAL)) }
        val engine = PhraseEngine(tiny, InMemoryRecentIdStore(), Random(7))
        val drawn = (1..40).map { engine.pick(setOf(Tags.WEEKEND), 1) }
        assertTrue(drawn.all { it.source == PoolSource.MATCHED && it.phrase.id in 1..4 })
        // Window is 2 for a pool of 4: never the same id as either of the previous two.
        drawn.map { it.phrase.id }.windowed(3).forEach { (a, b, c) -> assertTrue(c != a && c != b) }
    }

    @Test
    fun fallsBackToGeneralAtSameTierThenAnyTier() = runTest {
        val catalog = listOf(
            Phrase(1, "night t1", 1, setOf(Tags.GENERAL, Tags.LATE_NIGHT)),
            Phrase(2, "plain t1", 1, setOf(Tags.GENERAL)),
            Phrase(3, "plain t3", 3, setOf(Tags.GENERAL)),
        )
        val engine = PhraseEngine(catalog, InMemoryRecentIdStore())
        assertEquals(PoolSource.MATCHED, engine.pick(setOf(Tags.LATE_NIGHT), 1).source)
        val sameTier = engine.pick(setOf(Tags.MARATHON), 1)
        assertEquals(PoolSource.GENERAL_SAME_TIER, sameTier.source)
        assertEquals(1, sameTier.phrase.tier)
        val anyTier = engine.pick(setOf(Tags.MARATHON), 2)
        assertEquals(PoolSource.GENERAL_ANY_TIER, anyTier.source)
    }

    @Test
    fun ringBufferSurvivesARestart() = runTest {
        val catalog = (1..20).map { Phrase(it, "p$it", 2, setOf(Tags.GENERAL, Tags.MORNING)) }
        val store = InMemoryRecentIdStore()
        val before = PhraseEngine(catalog, store, Random(1))
        val firstIds = (1..9).map { before.pick(setOf(Tags.MORNING), 2).phrase.id }

        // A fresh engine, as after a process restart, reads the persisted buffer.
        val after = PhraseEngine(catalog, store, Random(99))
        val next = after.pick(setOf(Tags.MORNING), 2).phrase.id
        assertFalse("id $next was shown just before the restart", next in firstIds.takeLast(10))
        assertTrue(store.load().size <= PhraseEngine.MAX_WINDOW)
    }

    @Test
    fun ringBufferIsCappedAt150() = runTest {
        val store = InMemoryRecentIdStore()
        val engine = PhraseEngine(phrases, store, Random(3))
        repeat(400) { engine.pick(setOf(Tags.GENERAL), 2) }
        assertEquals(150, store.load().size)
    }

    @Test
    fun pickRoastReturnsOnlyCryLines() = runTest {
        val store = InMemoryRecentIdStore()
        val engine = PhraseEngine(phrases, store, Random(1))
        repeat(100) {
            val pick = engine.pickRoast(setOf(Tags.GENERAL), 1)
            assertEquals(PhrasePack.CRY, pick.phrase.pack)
        }
    }

    @Test
    fun pickNeverReturnsACryLine() = runTest {
        val store = InMemoryRecentIdStore()
        val engine = PhraseEngine(phrases, store, Random(2))
        for (tag in Tags.ALL) {
            for (tier in Tags.TIERS) {
                repeat(10) {
                    val pick = engine.pick(setOf(tag), tier)
                    assertEquals(PhrasePack.SPICY, pick.phrase.pack)
                }
            }
        }
    }
}
