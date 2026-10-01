package com.sangar.gal.phrases

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class PhraseLengthMixTest {

    private val phrases = PhraseTestData.phrases
    private val established = UsageSignals(historyDays = 30)

    @Test
    fun lengthComesFromTheWordCount() {
        assertEquals(PhraseLength.SHORT, PhraseLength.of("No job?"))
        assertEquals(PhraseLength.SHORT, PhraseLength.of("Impressive. Not the good kind."))
        assertEquals(PhraseLength.MEDIUM, PhraseLength.of("One two three four five six"))
        assertEquals(PhraseLength.MEDIUM, PhraseLength.of("1 2 3 4 5 6 7 8 9 10 11 12"))
        assertEquals(PhraseLength.LONG, PhraseLength.of("1 2 3 4 5 6 7 8 9 10 11 12 13"))
        assertEquals(PhraseLength.SHORT, PhraseLength.of("  Still?   Really?  "))
    }

    @Test
    fun shortLinesNeverSkipTheirTags() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(11))
        val sunday2am = LocalDateTime.of(2026, 9, 20, 2, 0)
        for (tier in Tags.TIERS) for (nags in listOf(0, 3)) for (minutes in listOf(20L, 60L, 150L)) {
            val tags = NagContext.tags(minutes, nags, sunday2am, established)
            repeat(300) {
                val phrase = engine.pick(tags, tier).phrase
                assertTrue("'${phrase.text}' ${phrase.tags} shown for $tags", phrase.tags.any { it in tags })
                assertFalse("'${phrase.text}' is work_hours only", Tags.WORK_HOURS in phrase.tags)
            }
        }
    }

    @Test
    fun noJobShowsUpOnAWeekdayAfternoonButNeverOnASundayNight() = runTest {
        val noJob = phrases.single { it.text == "No job?" }
        assertEquals(setOf(Tags.WORK_HOURS), noJob.tags)
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(5))
        val sunday = NagContext.tags(20, 0, LocalDateTime.of(2026, 9, 20, 2, 0), established)
        assertTrue((1..3000).none { engine.pick(sunday, noJob.tier).phrase.id == noJob.id })
        val wednesday = NagContext.tags(20, 0, LocalDateTime.of(2026, 9, 16, 14, 0), established)
        assertTrue((1..3000).any { engine.pick(wednesday, noJob.tier).phrase.id == noJob.id })
    }

    @Test
    fun everydayCardsLeanShortAndBigMomentsGetTheLongOnes() = runTest {
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), Random(21))
        val noon = LocalDateTime.of(2026, 9, 16, 12, 30)
        val everyday = mix(engine, 2000) { i -> NagContext.tags(if (i % 2 == 0) 30L else 70L, i % 3, noon, established) to 2 }
        val marathon = mix(engine, 2000) { i -> NagContext.tags(150L, i % 3, noon, established) to 3 }
        // 2000 draws of one moment in a row use up its short lines faster than real life does (the recent
        // window keeps them from repeating), so compare the two moments rather than expect exact weights.
        val detail = "everyday $everyday, marathon $marathon"
        assertTrue(detail, everyday.getValue(PhraseLength.SHORT) > marathon.getValue(PhraseLength.SHORT) + 0.10)
        assertTrue(detail, everyday.getValue(PhraseLength.LONG) < 0.10)
        assertTrue(detail, marathon.getValue(PhraseLength.LONG) > 3 * everyday.getValue(PhraseLength.LONG))
    }

    /**
     * A month of realistic nagging on one engine and one ring buffer: a 30 minute threshold, a card every
     * 10 minutes, sessions of every length at all hours, and an occasional record day. Lands on the rough
     * target of 60 percent short, 30 medium, 10 long.
     */
    @Test
    fun overallMixIsRoughlySixtyThirtyTen() = runTest {
        val random = Random(2026)
        val engine = PhraseEngine(phrases, InMemoryRecentIdStore(), random)
        val counts = HashMap<PhraseLength, Int>()
        var bigMoments = 0
        var total = 0
        val start = LocalDateTime.of(2026, 9, 1, 7, 0)
        repeat(30 * 8) { session ->
            val startedAt = start.plusMinutes(session * 180L + random.nextLong(0, 120))
            // Mostly 30 to 90 minutes, sometimes a two to three hour marathon.
            val length = if (random.nextDouble() < 0.2) random.nextLong(120, 200) else random.nextLong(30, 90)
            val signals = established.copy(newRecord = random.nextDouble() < 0.05)
            var minute = 30L
            var nags = 0
            while (minute <= length) {
                val tags = NagContext.tags(minute, nags, startedAt.plusMinutes(minute), signals)
                val pick = engine.pick(tags, NagContext.tier(minute, signals))
                counts.merge(pick.phrase.length, 1, Int::plus)
                if (LengthMix.isBigMoment(tags)) bigMoments++
                total++
                nags++
                minute += 10
            }
        }
        val share = PhraseLength.entries.associateWith { (counts[it] ?: 0).toDouble() / total }
        val summary = "short/medium/long ${share.values.map { "%.3f".format(it) }} over $total cards, " +
            "big moments ${"%.3f".format(bigMoments.toDouble() / total)}"
        println(summary)
        assertEquals(summary, 0.60, share.getValue(PhraseLength.SHORT), 0.06)
        assertEquals(summary, 0.30, share.getValue(PhraseLength.MEDIUM), 0.06)
        assertEquals(summary, 0.10, share.getValue(PhraseLength.LONG), 0.05)
    }

    private suspend fun mix(engine: PhraseEngine, draws: Int, moment: (Int) -> Pair<Set<String>, Int>): Map<PhraseLength, Double> {
        val counts = HashMap<PhraseLength, Int>()
        repeat(draws) { i ->
            val (tags, tier) = moment(i)
            counts.merge(engine.pick(tags, tier).phrase.length, 1, Int::plus)
        }
        return PhraseLength.entries.associateWith { (counts[it] ?: 0).toDouble() / draws }
    }
}
