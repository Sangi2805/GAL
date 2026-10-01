package com.sangar.gal.sidekick.scene

import com.sangar.gal.sidekick.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When opening an app earns a roast and a trunk smack, and when it is a plain open. */
class AppHabitsTest {

    private val rules = RoastRules()
    private val now = 1_700_000_000_000L
    private val today = 19_700L
    private val pkg = "com.example.social"

    private val light = AppUsage(opensToday = 3, minutesToday = 10, averageMinutes7d = 20)
    private val heavy = AppUsage(opensToday = 9, minutesToday = 30, averageMinutes7d = 30)

    private fun decide(
        usage: AppUsage? = heavy,
        enabled: Boolean = true,
        social: Boolean = true,
        history: RoastHistory = RoastHistory(),
        inCall: Boolean = false,
        nowWall: Long = now,
        quiet: Boolean = false,
    ) = AppHabits.decide(pkg, enabled, social, usage, rules, history, nowWall, today, inCall, quiet)

    @Test
    fun heavyUseOfASocialAppIsARoast() {
        val d = decide()
        assertTrue(d.reason, d.roast)
        assertEquals(1, d.tier)
    }

    @Test
    fun everyGateCanTurnARoastIntoAPlainOpen() {
        assertFalse(decide(enabled = false).roast)
        assertFalse(decide(social = false).roast)
        assertFalse("no usage access means no numbers", decide(usage = null).roast)
        assertFalse("never during a call", decide(inCall = true).roast)
        assertFalse("never for an app on the stay quiet list", decide(quiet = true).roast)
        assertFalse(decide(usage = light).roast)
    }

    @Test
    fun anyOneLimitMakesItHeavy() {
        assertTrue(AppHabits.isHeavy(light.copy(opensToday = 8), rules))
        assertFalse(AppHabits.isHeavy(light.copy(opensToday = 7), rules))
        // Minutes and the average must be over the limit, not equal to it.
        assertFalse(AppHabits.isHeavy(light.copy(minutesToday = 45), rules))
        assertTrue(AppHabits.isHeavy(light.copy(minutesToday = 46), rules))
        assertFalse(AppHabits.isHeavy(light.copy(averageMinutes7d = 60), rules))
        assertTrue(AppHabits.isHeavy(light.copy(averageMinutes7d = 61), rules))
    }

    @Test
    fun tierFollowsTheWorstNumber() {
        assertEquals(1, AppHabits.tier(AppUsage(8, 0, 0), rules)) // 1.0x opens
        assertEquals(1, AppHabits.tier(AppUsage(11, 0, 0), rules)) // 1.375x
        assertEquals(2, AppHabits.tier(AppUsage(12, 0, 0), rules)) // 1.5x
        assertEquals(2, AppHabits.tier(AppUsage(0, 100, 0), rules)) // 2.2x minutes
        assertEquals(3, AppHabits.tier(AppUsage(0, 0, 150), rules)) // 2.5x average
        assertEquals(3, AppHabits.tier(AppUsage(3, 10, 400), rules))
        // Zero limits from a broken setting never divide by zero.
        assertEquals(3, AppHabits.tier(AppUsage(5, 5, 5), RoastRules(0, 0, 0)))
    }

    @Test
    fun moodEscalatesWithTheTier() {
        assertEquals(Mood.SMUG, AppHabits.moodFor(1))
        assertEquals(Mood.DISAPPOINTED, AppHabits.moodFor(2))
        assertEquals(Mood.HORRIFIED, AppHabits.moodFor(3))
    }

    @Test
    fun cooldownKeepsOneAppFromBeingRoastedTwiceInTwoHours() {
        val justRoasted = RoastHistory().after(pkg, now, today)
        assertFalse(decide(history = justRoasted, nowWall = now + 119 * 60_000L).roast)
        assertTrue(decide(history = justRoasted, nowWall = now + 120 * 60_000L).roast)
        // Another app is not held back by this one's cooldown.
        val other = RoastHistory().after("com.example.other", now, today)
        assertTrue(decide(history = other, nowWall = now + 60_000L).roast)
        // A clock that went backwards does not block roasts forever.
        assertTrue(decide(history = justRoasted, nowWall = now - 60_000L).roast)
    }

    @Test
    fun atMostFiveRoastsADay() {
        var history = RoastHistory()
        repeat(5) { i -> history = history.after("com.example.app$i", now, today) }
        assertEquals(5, history.roastsToday(today))
        assertFalse(decide(history = history).roast)
        // A new day starts the count again.
        assertEquals(0, history.roastsToday(today + 1))
        assertTrue(AppHabits.decide(pkg, true, true, heavy, rules, history, now, today + 1, false).roast)
        assertEquals(1, history.after(pkg, now, today + 1).roastsOnDay)
    }

    @Test
    fun countsOpensFromAnotherAppOrAfterTheScreenWasOff() {
        val s = AppHabits.SCREEN_OFF
        val day = listOf(
            pkg, // 1: first thing in the morning
            pkg, // same app, another activity: not an open
            "launcher",
            pkg, // 2
            s,
            pkg, // 3: back after the screen was off
            "other",
            "other",
            pkg, // 4
        )
        assertEquals(4, AppHabits.countOpens(day, pkg))
        assertEquals(0, AppHabits.countOpens(emptyList(), pkg))
        assertEquals(0, AppHabits.countOpens(listOf("other", s, "other"), pkg))
    }
}
