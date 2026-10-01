package com.sangar.gal.phrases

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Sidekick's lines for smashing open a social app you use a lot. Never on a card, always naming the app. */
class AppRoastPhraseTest {

    private val phrases = PhraseTestData.phrases

    @Test
    fun picksComeFromTheRequestedTier() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(3))
        assertEquals(150, engine.appRoastSize)
        for (tier in 1..3) repeat(30) {
            val pick = engine.pickAppRoast(tier)
            assertEquals(PoolSource.MATCHED, pick.source)
            assertEquals(tier, pick.phrase.tier)
            assertEquals(setOf(Tags.APP_ROAST), pick.phrase.tags)
        }
    }

    @Test
    fun appRoastsDoNotRepeatBackToBack() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(4))
        val ids = (1..40).map { engine.pickAppRoast(2).phrase.id }
        // The window for a pool of 50 is 25, so no line comes back within 25 picks.
        ids.windowed(26).forEach { window -> assertTrue("repeat inside $window", window.last() !in window.dropLast(1)) }
    }

    @Test
    fun appRoastsNeverReachACardEvenThroughFallbacks() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(5))
        for (tag in Tags.KNOWN) for (tier in 0..4) repeat(10) {
            val pick = engine.pick(setOf(tag), tier)
            assertFalse("app roast ${pick.phrase.id} picked for a card ($tag/t$tier)", Tags.APP_ROAST in pick.phrase.tags)
            val roast = engine.pickRoast(setOf(tag), tier)
            assertFalse("app roast ${roast.phrase.id} picked as a You may cry roast", Tags.APP_ROAST in roast.phrase.tags)
        }
        // A catalog with nothing but app roasts must fall through to the default card line.
        val appOnly = PhraseEngine(phrases.filter { Tags.APP_ROAST in it.tags }, InMemoryRecentIdStore())
        assertEquals(PoolSource.HARDCODED_DEFAULT, appOnly.pick(setOf(Tags.GENERAL), 1).source)
        assertEquals(0, appOnly.size)
    }

    @Test
    fun missingTierFallsBackToAnyTierThenToTheHardcodedLine() = runTest {
        val onlyTierOne = PhraseEngine(phrases.filter { Tags.APP_ROAST in it.tags && it.tier == 1 }, InMemoryRecentIdStore())
        val pick = onlyTierOne.pickAppRoast(3)
        assertEquals(PoolSource.GENERAL_ANY_TIER, pick.source)
        assertEquals(1, pick.phrase.tier)

        val none = PhraseEngine(emptyList(), InMemoryRecentIdStore())
        val fallback = none.pickAppRoast(2)
        assertEquals(PoolSource.HARDCODED_DEFAULT, fallback.source)
        assertEquals(PhraseEngine.DEFAULT_APP_ROAST, fallback.phrase.text)
    }

    @Test
    fun fillAppNamePutsTheNameInEveryLine() {
        assertEquals("Are you married to Instagram?", fillAppName("Are you married to {app}?", "Instagram"))
        assertEquals("Reddit again? Reddit, really?", fillAppName("{app} again? {app}, really?", "Reddit"))
        // A blank label still reads as a sentence.
        assertEquals("Are you married to this app?", fillAppName("Are you married to {app}?", "  "))
        // A line that lost its placeholder still names the app.
        assertEquals("TikTok? Bold choice.", fillAppName("Bold choice.", "TikTok"))
        phrases.filter { Tags.APP_ROAST in it.tags }.forEach { p ->
            val filled = fillAppName(p.text, "Instagram")
            assertTrue("${p.id}: $filled", "Instagram" in filled && Tags.APP_PLACEHOLDER !in filled)
        }
    }
}
