package com.sangar.gal.phrases

import com.sangar.gal.overlay.Mascot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The "You may cry" pack's sign-off: card 12 of a session gives up on you in the roast pack's own voice. */
class CryGiveUpTest {

    private val phrases = PhraseTestData.phrases
    private val cryGiveUps = phrases.filter { it.pack == PhrasePack.CRY && Tags.GIVE_UP in it.tags }

    @Test
    fun thePackHasEightySignOffsTaggedGiveUpAlone() {
        assertEquals(80, cryGiveUps.size)
        cryGiveUps.forEach {
            assertEquals("${it.id} must carry give_up alone", setOf(Tags.GIVE_UP), it.tags)
            assertEquals(3, it.tier)
        }
    }

    @Test
    fun anOrdinaryRoastCardNeverDrawsASignOff() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(1))
        val testTags = Tags.ALL.map { setOf(it) } + listOf(setOf(Tags.OWL_MODE), setOf("unknown_tag"), Tags.ALL.toSet(), emptySet())
        for (tags in testTags) for (tier in 0..4) repeat(10) {
            val roast = engine.pickRoast(tags, tier).phrase
            assertEquals(PhrasePack.CRY, roast.pack)
            assertTrue("'${roast.text}' is a sign-off", Tags.GIVE_UP !in roast.tags)
        }
    }

    @Test
    fun theLastCardAlwaysDrawsACrySignOff() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(2))
        val distinct = mutableSetOf<Int>()
        repeat(200) {
            val pick = engine.pickCryGiveUp(3)
            assertEquals(PoolSource.MATCHED, pick.source)
            assertEquals(PhrasePack.CRY, pick.phrase.pack)
            assertEquals(setOf(Tags.GIVE_UP), pick.phrase.tags)
            distinct += pick.phrase.id
        }
        assertTrue("expected variety, got ${distinct.size} distinct lines", distinct.size > 40)
    }

    @Test
    fun theSignOffWearsTheBoredFaceNotTheHorrifiedOne() {
        assertEquals(Mascot.BORED, Mascot.forNag(tier = 3, nagsThisSession = 11, tags = setOf(Tags.GIVE_UP)))
        assertEquals(Mascot.HORRIFIED, Mascot.forNag(tier = 3, nagsThisSession = 5, tags = setOf(Tags.GENERAL)))
    }

    @Test
    fun withoutCrySignOffsItFallsBackToASpicyOneNeverAnOrdinaryLine() = runTest {
        val logs = mutableListOf<String>()
        val catalog = listOf(
            Phrase(1, "roast one", 3, setOf(Tags.GENERAL), declaredPack = "cry"),
            Phrase(2, "spicy nag", 1, setOf(Tags.GENERAL), declaredPack = "spicy"),
            Phrase(3, "spicy sign-off", 1, setOf(Tags.GIVE_UP), declaredPack = "spicy"),
        )
        val engine = PhraseEngine(catalog, InMemoryRecentIdStore(), Random(3), log = { logs.add(it) })
        val pick = engine.pickCryGiveUp(1)
        assertEquals(3, pick.phrase.id)
        assertTrue(logs.any { it.contains("no You may cry give_up lines loaded") })
    }

    @Test
    fun ordinaryRoastDrawLeansShort() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(5))
        var shorts = 0
        var longs = 0
        repeat(500) {
            val length = engine.pickRoast(setOf(Tags.GENERAL), 1).phrase.length
            if (length == PhraseLength.SHORT) shorts++
            if (length == PhraseLength.LONG) longs++
        }
        assertTrue("Expected mostly short, got $shorts short and $longs long", shorts > longs * 2)
    }
}
