package com.sangar.gal.phrases

import com.sangar.gal.overlay.Mascot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClosingRoastTest {

    private val phrases = PhraseTestData.phrases
    private val giveUpTags = setOf(Tags.GIVE_UP)
    
    @Test
    fun giveUpUnreachableFromCryPack() = runTest {
        val store = InMemoryRecentIdStore()
        val engine = PhraseEngine(phrases, store, Random(1))
        
        val testTags = Tags.ALL.map { setOf(it) } + listOf(
            setOf(Tags.OWL_MODE),
            setOf("unknown_tag"),
            Tags.ALL.toSet(),
            emptySet()
        )
        
        for (tags in testTags) {
            for (tier in 0..4) {
                val roast = engine.pickRoast(tags, tier).phrase
                assertEquals(PhrasePack.CRY, roast.pack)
                assertTrue(Tags.GIVE_UP !in roast.tags)
                
                val closing = engine.pickClosingRoast(tags, tier).phrase
                assertEquals(PhrasePack.CRY, closing.pack)
                assertTrue(Tags.GIVE_UP !in closing.tags)
            }
        }
    }
    
    @Test
    fun mascotRuleForClosingRoastVsGiveUp() {
        // A closing cry card is just a tier 3 roast, so it gets HORRIFIED
        val closingRoast = Mascot.forNag(tier = 3, nagsThisSession = 5, tags = setOf(Tags.GENERAL))
        assertEquals(Mascot.HORRIFIED, closingRoast)
        
        // A spicy give_up line has Tags.GIVE_UP, so it gets BORED
        val giveUp = Mascot.forNag(tier = 3, nagsThisSession = 5, tags = setOf(Tags.GIVE_UP))
        assertEquals(Mascot.BORED, giveUp)
    }

    @Test
    fun closingCardIsTier3AndLongAgainstRealCatalog() = runTest {
        val store = InMemoryRecentIdStore()
        val engine = PhraseEngine(phrases, store, Random(2))
        
        val distinctIds = mutableSetOf<Int>()
        repeat(200) {
            val pick = engine.pickClosingRoast(setOf(Tags.GENERAL), 1).phrase
            assertEquals(3, pick.tier)
            assertEquals(PhraseLength.LONG, pick.length)
            distinctIds.add(pick.id)
        }
        
        assertTrue("Expected many distinct ids, got ${distinctIds.size}", distinctIds.size > 50)
    }

    @Test
    fun fallbackStepsAreExercised() = runTest {
        // Synthetic catalog with specific lengths
        val synthetic = listOf(
            Phrase(1, "long1", 3, setOf(Tags.GENERAL), declaredLength = "long", declaredPack = "cry"),
            Phrase(2, "long2", 3, setOf(Tags.GENERAL), declaredLength = "long", declaredPack = "cry"),
            Phrase(3, "med1", 3, setOf(Tags.GENERAL), declaredLength = "medium", declaredPack = "cry"),
            Phrase(4, "short1", 3, setOf(Tags.GENERAL), declaredLength = "short", declaredPack = "cry")
        )
        
        val logs = mutableListOf<String>()
        val store = InMemoryRecentIdStore()
        val engine = PhraseEngine(synthetic, store, Random(3), log = { logs.add(it) })
        logs.clear() // Clear the init log about empty usable (spicy) phrases
        
        // 1. First two draws will consume tier 3 long.
        engine.pickClosingRoast(setOf(Tags.GENERAL), 1)
        engine.pickClosingRoast(setOf(Tags.GENERAL), 1)
        assertTrue(logs.toString(), logs.isEmpty()) // No fallback yet
        
        // 2. Third draw: long is exhausted (since pool size is 2, effectiveWindow is 1, but we exclude full buffer MAX_WINDOW). 
        // Wait, actually drawUnseen excludes the FULL buffer (150). So both 1 and 2 are in the buffer!
        val pick3 = engine.pickClosingRoast(setOf(Tags.GENERAL), 1)
        assertEquals(3, pick3.phrase.id) // Medium
        assertTrue(logs.any { it.contains("tier 3 long exhausted") })
        logs.clear()
        
        // 3. Fourth draw: med1 is in buffer. Now both long and medium are gone. Any tier 3 (which includes short) will be picked.
        val pick4 = engine.pickClosingRoast(setOf(Tags.GENERAL), 1)
        assertEquals(4, pick4.phrase.id) // Short
        assertTrue(logs.any { it.contains("tier 3 medium exhausted") })
        logs.clear()
        
        // 4. Fifth draw: all items (1, 2, 3, 4) are in the buffer. Nothing unseen left.
        val pick5 = engine.pickClosingRoast(setOf(Tags.GENERAL), 1)
        assertTrue(logs.any { it.contains("any tier 3 exhausted") })
        assertTrue(logs.any { it.contains("falling back to ordinary roast selector") })
        logs.clear()
        
        // 5. No roasts at all case.
        val spicyOnly = listOf(
            Phrase(10, "spicy1", 1, setOf(Tags.GENERAL), declaredPack = "spicy"),
            Phrase(11, "giveup", 1, setOf(Tags.GIVE_UP), declaredPack = "spicy")
        )
        val engineSpicy = PhraseEngine(spicyOnly, InMemoryRecentIdStore(), Random(4), log = { logs.add(it) })
        
        val pickSpicy = engineSpicy.pickClosingRoast(setOf(Tags.GIVE_UP), 1)
        assertTrue(logs.any { it.contains("no roast lines loaded; using a spicy line instead") })
        assertNotEquals(11, pickSpicy.phrase.id) // Must never be a give_up one
        assertEquals(10, pickSpicy.phrase.id)
    }

    @Test
    fun ordinaryRoastDrawLeansShort() = runTest {
        val store = InMemoryRecentIdStore()
        val engine = PhraseEngine(phrases, store, Random(5))
        
        var shorts = 0
        var longs = 0
        repeat(500) {
            val length = engine.pickRoast(setOf(Tags.GENERAL), 1).phrase.length
            if (length == PhraseLength.SHORT) shorts++
            if (length == PhraseLength.LONG) longs++
        }
        
        // Everyday roasts lean short
        assertTrue("Expected mostly short, got $shorts short and $longs long", shorts > longs * 2)
    }
}
