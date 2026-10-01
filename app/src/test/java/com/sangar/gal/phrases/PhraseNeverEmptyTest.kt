package com.sangar.gal.phrases

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selector must never hand the overlay an empty line, whatever the catalog or request looks like. */
class PhraseNeverEmptyTest {

    /** Combinations tier escalation never produces on its own, so they are the thinnest pools. */
    private val rareCombinations = listOf(
        Tags.SHORT_SESSION to 3,
        Tags.MARATHON to 1,
        Tags.TREND_UP to 1,
        Tags.TREND_DOWN to 3,
        Tags.NEW_RECORD to 1,
        Tags.STREAK_GOOD to 3,
    )

    private suspend fun PhraseEngine.assertNonEmpty(tags: Set<String>, tier: Int): Pick {
        val pick = pick(tags, tier)
        assertTrue("blank text for tags=$tags tier=$tier (source ${pick.source})", pick.phrase.text.isNotBlank())
        return pick
    }

    @Test
    fun everyTagAndTierIncludingTheSixRareOnesReturnsText() = runTest {
        val engine = PhraseEngine(PhraseTestData.phrases, InMemoryRecentIdStore())
        val all = Tags.ALL.flatMap { tag -> Tags.TIERS.map { tier -> tag to tier } }
        assertTrue(all.containsAll(rareCombinations))
        for ((tag, tier) in all) {
            repeat(60) { engine.assertNonEmpty(setOf(tag), tier) }
        }
        for ((tag, tier) in rareCombinations) {
            assertEquals("$tag/t$tier should come from its own pool", PoolSource.MATCHED, engine.pick(setOf(tag), tier).source)
        }
    }

    @Test
    fun emptyCatalogFallsThroughToTheHardcodedDefault() = runTest {
        val engine = PhraseEngine(emptyList(), InMemoryRecentIdStore())
        for (tag in Tags.ALL) for (tier in Tags.TIERS) {
            val pick = engine.assertNonEmpty(setOf(tag), tier)
            assertEquals(PoolSource.HARDCODED_DEFAULT, pick.source)
            assertEquals(PhraseEngine.DEFAULT_TEXT, pick.phrase.text)
            assertEquals(tier, pick.phrase.tier)
        }
    }

    @Test
    fun blankAndInvalidPhrasesAreDroppedNotShown() = runTest {
        val logs = mutableListOf<String>()
        val catalog = listOf(
            Phrase(1, "", 1, setOf(Tags.GENERAL, Tags.MORNING)),
            Phrase(2, "   \n\t", 1, setOf(Tags.GENERAL, Tags.MORNING)),
            Phrase(3, "valid but tier 9", 9, setOf(Tags.GENERAL, Tags.MORNING)),
        )
        val engine = PhraseEngine(catalog, InMemoryRecentIdStore(), log = { logs += it })
        assertEquals(0, engine.size)
        assertTrue(logs.any { "dropped 3" in it })
        repeat(20) { assertEquals(PoolSource.HARDCODED_DEFAULT, engine.assertNonEmpty(setOf(Tags.MORNING), 1).source) }
    }

    @Test
    fun chainGoesSameTierThenAnyTierThenDefaultAndLogsEachStep() = runTest {
        val logs = mutableListOf<String>()
        val catalog = listOf(
            Phrase(1, "morning, tier 1", 1, setOf(Tags.GENERAL, Tags.MORNING)),
            Phrase(2, "plain, tier 2", 2, setOf(Tags.GENERAL)),
        )
        val engine = PhraseEngine(catalog, InMemoryRecentIdStore(), log = { logs += it })

        assertEquals(PoolSource.MATCHED, engine.assertNonEmpty(setOf(Tags.MORNING), 1).source)
        assertEquals(PoolSource.GENERAL_SAME_TIER, engine.assertNonEmpty(setOf(Tags.MARATHON), 2).source)
        assertEquals(PoolSource.GENERAL_ANY_TIER, engine.assertNonEmpty(setOf(Tags.MARATHON), 3).source)
        assertTrue(logs.any { "GENERAL_SAME_TIER" in it })
        assertTrue(logs.any { "GENERAL_ANY_TIER" in it })

        // No general tag anywhere: nothing to fall back on but the default line.
        val noGeneral = PhraseEngine(listOf(Phrase(1, "weekend only", 1, setOf(Tags.WEEKEND))), InMemoryRecentIdStore(), log = { logs += it })
        assertEquals(PoolSource.HARDCODED_DEFAULT, noGeneral.assertNonEmpty(setOf(Tags.MARATHON), 3).source)
        assertTrue(logs.any { "HARDCODED_DEFAULT" in it })
    }

    @Test
    fun oddRequestsStillReturnText() = runTest {
        val engine = PhraseEngine(PhraseTestData.phrases, InMemoryRecentIdStore())
        engine.assertNonEmpty(emptySet(), 2)
        engine.assertNonEmpty(setOf("not_a_tag"), 1)
        engine.assertNonEmpty(setOf(Tags.MORNING), 0)
        engine.assertNonEmpty(setOf(Tags.MORNING), 7)
        engine.assertNonEmpty(Tags.ALL.toSet(), 3)
    }

    @Test
    fun brokenRingBufferStoreDoesNotBreakPicking() = runTest {
        val failing = object : RecentIdStore {
            override suspend fun load(): List<Int> = throw IllegalStateException("disk gone")
            override suspend fun save(ids: List<Int>) = throw IllegalStateException("disk gone")
        }
        val engine = PhraseEngine(PhraseTestData.phrases, failing)
        repeat(10) { engine.assertNonEmpty(setOf(Tags.LATE_NIGHT), 2) }
    }

    @Test
    fun malformedJsonParsesToEmptyAndReportsTheError() {
        var error: Throwable? = null
        assertTrue(PhraseCatalog.parseOrEmpty("{ this is not json", onError = { error = it }).isEmpty())
        assertFalse(error == null)
        error = null
        assertTrue(PhraseCatalog.parseOrEmpty(null, onError = { error = it }).isEmpty())
        assertFalse(error == null)
        // 1380 spicy (80 of them give_up), 60 owl_mode, 2677 roasts and 80 roast give_up lines.
        assertEquals(4197, PhraseCatalog.parseOrEmpty(PhraseTestData.rawJson, onError = { throw it }).size)
    }
}
