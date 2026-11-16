package com.sangar.gal.guard

import com.sangar.gal.phrases.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GuardRulesTest {

    private val min = 60_000L

    @Test
    fun noPauseIsOwedBeforeTheLimit() {
        assertEquals(0, GuardRules.blocksDue(0, 30))
        assertEquals(0, GuardRules.blocksDue(30 * min - 1, 30))
    }

    @Test
    fun thePauseComesAtTheLimitAndThenEveryFiveMinutes() {
        assertEquals(1, GuardRules.blocksDue(30 * min, 30))
        assertEquals(1, GuardRules.blocksDue(35 * min - 1, 30))
        assertEquals(2, GuardRules.blocksDue(35 * min, 30))
        assertEquals(3, GuardRules.blocksDue(41 * min, 30))
    }

    @Test
    fun aPauseOnlyHappensInsideAChosenApp() {
        assertFalse(GuardRules.shouldBlock(inChosenApp = false, usedMillis = 40 * min, limitMinutes = 30, blocksShown = 0))
        assertTrue(GuardRules.shouldBlock(inChosenApp = true, usedMillis = 40 * min, limitMinutes = 30, blocksShown = 0))
    }

    @Test
    fun aShownPauseIsNotShownAgainUntilTheNextOneIsDue() {
        assertFalse(GuardRules.shouldBlock(true, 31 * min, 30, blocksShown = 1))
        assertTrue(GuardRules.shouldBlock(true, 35 * min, 30, blocksShown = 1))
    }

    @Test
    fun severalOwedPausesCollapseIntoOne() {
        // Say the service was away and three are owed: after one pause nothing more is due right now.
        val shown = GuardRules.shownAfterBlock(usedMillis = 41 * min, limitMinutes = 30, blocksShown = 0)
        assertEquals(3, shown)
        assertFalse(GuardRules.shouldBlock(true, 41 * min, 30, shown))
    }

    @Test
    fun theReminderShowsOncePerAppPerDayWhileThereIsTimeLeft() {
        val chosen = setOf("yt", "ig")
        assertTrue(GuardRules.shouldRemind("yt", chosen, emptySet(), 0, 30))
        assertFalse(GuardRules.shouldRemind("yt", chosen, setOf("yt"), 0, 30))
        assertTrue(GuardRules.shouldRemind("ig", chosen, setOf("yt"), 0, 30))
        assertFalse(GuardRules.shouldRemind("maps", chosen, emptySet(), 0, 30))
        assertFalse(GuardRules.shouldRemind(null, chosen, emptySet(), 0, 30))
        // Time is up: the pause speaks, not the reminder.
        assertFalse(GuardRules.shouldRemind("yt", chosen, emptySet(), 30 * min, 30))
    }

    @Test
    fun theLimitStaysOnTheSliderSteps() {
        assertEquals(5, GuardRules.clampLimit(0))
        assertEquals(30, GuardRules.clampLimit(32))
        assertEquals(120, GuardRules.clampLimit(500))
    }

    @Test
    fun minutesLeftNeverGoesBelowZero() {
        assertEquals(8, GuardRules.minutesLeft(22 * min, 30))
        assertEquals(0, GuardRules.minutesLeft(45 * min, 30))
    }

    @Test
    fun lateAtNightSheGoesToBedAndByDaySheNeverRepeatsTheLastScene() {
        assertEquals(BlockScene.BED, BlockScenes.pick(23, null))
        assertEquals(BlockScene.BED, BlockScenes.pick(3, BlockScene.BED))
        val random = Random(7)
        var previous: BlockScene? = null
        repeat(50) {
            val scene = BlockScenes.pick(14, previous, random)
            assertNotEquals(BlockScene.BED, scene)
            assertNotEquals(previous, scene)
            previous = scene
        }
    }

    @Test
    fun everySceneHasShortLinesInEveryTone() {
        for (scene in BlockScene.entries) for (tone in Tone.entries) {
            val lines = BlockLines.all(scene, tone)
            assertTrue("$scene $tone needs variety", lines.size >= 4)
            assertEquals("$scene $tone has a repeated line", lines.size, lines.toSet().size)
            lines.forEach { assertTrue("too long for the pause screen: $it", it.length <= 52) }
        }
    }
}
