package com.sangar.gal.phrases

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseCatalogTest {

    private val phrases = PhraseTestData.phrases
    private val nag = phrases.filter { Tags.OWL_MODE !in it.tags && Tags.APP_ROAST !in it.tags && it.pack == PhrasePack.SPICY }
    private val owl = phrases.filter { Tags.OWL_MODE in it.tags }
    private val appRoasts = phrases.filter { Tags.APP_ROAST in it.tags }
    private val roasts = phrases.filter { it.pack == PhrasePack.CRY && Tags.GIVE_UP !in it.tags }
    private val cryGiveUps = phrases.filter { it.pack == PhrasePack.CRY && Tags.GIVE_UP in it.tags }

    @Test
    fun hasCorrectCountsWithUniqueIds() {
        assertEquals(1380, nag.size)
        assertEquals(60, owl.size)
        assertEquals(2677, roasts.size)
        assertEquals(80, nag.count { Tags.GIVE_UP in it.tags })
        assertEquals(80, cryGiveUps.size)
        assertEquals(150, appRoasts.size)
        assertEquals(phrases.size, phrases.map { it.id }.toSet().size)
    }

    /**
     * Catches fill-in-the-blank writing: "I have seen a doorknob do more." and "I have seen a turnip do more."
     * share a pattern, and so do "Please drop swiping." and "Please quit lurking."
     */
    @Test
    fun noMoreThanFifteenPhrasesShareASentencePattern() {
        val crowded = phrases.groupBy { SentencePattern.of(it.text) }.filterValues { it.size > SentencePattern.MAX_PER_PATTERN }
        assertTrue(
            crowded.entries.joinToString("\n") { (pattern, group) ->
                "${group.size} phrases share '$pattern', e.g. ${group.take(3).map { it.text }}"
            },
            crowded.isEmpty(),
        )
    }

    @Test
    fun patternCheckWouldHaveCaughtTheTemplateBatch() {
        val templated = listOf("doorknob", "pillow", "turnip", "sofa", "stone", "table", "rug", "cone", "mop", "pebble",
            "mannequin", "fridge", "bucket", "cushion", "rock", "lamp").map { "I have seen a $it do more." } +
            listOf("drop", "halt", "quit", "cease").map { "Please $it swiping." }
        val groups = templated.groupBy { SentencePattern.of(it) }
        assertEquals(16, groups.getValue("i have _ a _ do more .").size)
        assertEquals("please _ _ .", SentencePattern.of("Please quit lurking."))
        // Shape, not vocabulary: these two differ only in words and must share a pattern; the third must not.
        assertEquals(SentencePattern.of("Your thumb deserves a lawyer."), SentencePattern.of("Your couch needs a holiday."))
        assertFalse(SentencePattern.of("Your thumb deserves a lawyer.") == SentencePattern.of("Is this your big plan?"))
    }

    @Test
    fun noDuplicatesAfterNormalising() {
        val normalised = phrases.groupBy { it.text.lowercase().replace(Regex("[\\W_]+"), "") }
        val dupes = normalised.filterValues { it.size > 1 }
        assertTrue("duplicates: ${dupes.values}", dupes.isEmpty())
    }

    @Test
    fun roastsAreFormattedCorrectly() {
        cryGiveUps.forEach { p ->
            assertEquals("cry", p.declaredPack)
            assertEquals(3, p.tier)
            assertTrue(p.text.length <= 110)
            assertEquals(setOf(Tags.GIVE_UP), p.tags)
        }
        roasts.forEach { p ->
            assertEquals("cry", p.declaredPack)
            assertEquals(PhrasePack.CRY, p.pack)
            assertEquals(3, p.tier)
            assertFalse(p.text.contains("\n"))
            assertTrue(p.text.length <= 110)
            assertEquals(setOf(Tags.GENERAL), p.tags)
        }
    }

    @Test
    fun everyNagPhraseCarriesGeneralAndOnlyKnownTags() {
        nag.forEach { p ->
            if (Tags.WORK_HOURS in p.tags) {
                // Only true on a weekday at work: no other tag and no general, so nothing else can reach it.
                assertEquals("${p.id} must carry work_hours alone", setOf(Tags.WORK_HOURS), p.tags)
            } else if (Tags.GIVE_UP in p.tags) {
                // Only the last card of a session may draw one, so it carries nothing else and no general.
                assertEquals("${p.id} must carry give_up alone", setOf(Tags.GIVE_UP), p.tags)
            } else {
                assertTrue("${p.id} lacks general", Tags.GENERAL in p.tags)
            }
            assertTrue("${p.id} has unknown tags ${p.tags - Tags.ALL.toSet()}", Tags.ALL.containsAll(p.tags))
            assertTrue("${p.id} has tier ${p.tier}", p.tier in Tags.TIERS)
            assertTrue("${p.id} is blank", p.text.isNotBlank())
        }
    }

    @Test
    fun everyPhraseDeclaresTheLengthItsWordCountGives() {
        phrases.forEach { p ->
            assertEquals("${p.id} '${p.text}'", PhraseLength.of(p.text), PhraseLength.parse(p.declaredLength))
        }
        val short = nag.filter { it.length == PhraseLength.SHORT }
        assertTrue("only ${short.size} short nag phrases", short.size >= 300)
        // Short lines exist for every tag at every tier, so favouring them never has to break a tag.
        // give_up is the exception: the sign-off is exempt from the short preference (see LengthMix).
        for (tag in Tags.ALL - Tags.GIVE_UP) for (tier in Tags.TIERS) {
            assertTrue("no short $tag/t$tier", short.any { it.tier == tier && tag in it.tags })
        }
    }

    @Test
    fun owlLinesCarryOnlyOwlModeTwentyPerTier() {
        owl.forEach { p ->
            assertEquals("${p.id} must carry owl_mode alone", setOf(Tags.OWL_MODE), p.tags)
            assertTrue(p.text.isNotBlank())
        }
        Tags.TIERS.forEach { tier -> assertEquals("owl lines at tier $tier", 20, owl.count { it.tier == tier }) }
    }

    @Test
    fun appRoastLinesCarryOnlyAppRoastFiftyPerTierAndNameTheApp() {
        appRoasts.forEach { p ->
            assertEquals("${p.id} must carry app_roast alone", setOf(Tags.APP_ROAST), p.tags)
            assertEquals("${p.id} is not in the spicy pack", PhrasePack.SPICY, p.pack)
            assertTrue("${p.id} has no {app}: ${p.text}", Tags.APP_PLACEHOLDER in p.text)
            // Filled with a ten letter name it still fits the speech bubble.
            assertTrue("${p.id} too long: ${p.text}", fillAppName(p.text, "Tenletters").length <= 90)
        }
        Tags.TIERS.forEach { tier -> assertEquals("app roasts at tier $tier", 50, appRoasts.count { it.tier == tier }) }
        // Only app roasts have a placeholder, so no card can ever show a raw {app}.
        val stray = phrases.filter { Tags.APP_ROAST !in it.tags && ('{' in it.text || '}' in it.text) }
        assertTrue("braces outside app roasts: ${stray.map { it.id }}", stray.isEmpty())
    }

    @Test
    fun toneStaysAwayFromHealthBodiesAndMentalState() {
        val banned = Regex(
            "\\b(fat|weight|obes\\w*|diet\\w*|health\\w*|sick\\w*|depress\\w*|anxi\\w*|mental\\w*|therap\\w*|" +
                "addict\\w*|stupid|idiot|loser|pathetic|ugly|worthless|posture|insomnia)\\b",
            RegexOption.IGNORE_CASE,
        )
        val cryAllowed = Regex("\\b(loser|pathetic)\\b", RegexOption.IGNORE_CASE)
        val hits = phrases.filter { p ->
            val text = if (p.pack == PhrasePack.CRY) p.text.replace(cryAllowed, "") else p.text
            banned.containsMatchIn(text)
        }
        assertTrue("banned topics in: ${hits.map { it.id to it.text }}", hits.isEmpty())
    }
}
