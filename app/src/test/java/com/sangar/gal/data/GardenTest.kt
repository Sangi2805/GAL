package com.sangar.gal.data

import com.sangar.gal.data.db.DailyUsage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class GardenTest {

    private val today = LocalDate.of(2026, 11, 9)
    private fun day(back: Long, minutes: Int, tracked: Boolean = true) = DailyUsage(today.minusDays(back), minutes, 10, 1, tracked)

    @Test
    fun sevenGoodDaysMakeAFlowerAndANewSeed() {
        var p = Plant()
        repeat(6) { p = Garden.step(p, underGoal = true) }
        assertEquals(6, p.growth)
        assertEquals(PlantStage.BUD, Garden.stage(p))
        p = Garden.step(p, underGoal = true)
        assertEquals(Plant(growth = 0, flowers = 1), p)
    }

    @Test
    fun aBadDayWiltsAndThreeInARowKillIt() {
        var p = Plant(growth = 4, flowers = 2)
        p = Garden.step(p, underGoal = false)
        assertEquals(3, p.growth)
        assertEquals(1, p.wilt)
        p = Garden.step(p, underGoal = true)
        assertEquals(0, p.wilt)
        repeat(3) { p = Garden.step(p, underGoal = false) }
        assertEquals(0, p.growth)
        assertEquals(1, p.lost)
        assertEquals("flowers are kept", 2, p.flowers)
    }

    @Test
    fun firstRunStartsFreshWithoutJudgingOldDays() {
        val p = Garden.catchUp(Plant(), listOf(day(1, 30), day(2, 30)), 180, today)
        assertEquals(0, p.growth)
        assertEquals(today.minusDays(1), p.lastDay)
    }

    @Test
    fun catchUpCountsEachFinishedTrackedDayOnce() {
        val start = Plant(lastDay = today.minusDays(4))
        val days = listOf(day(3, 100), day(2, 400), day(1, 120), day(0, 999), day(4, 50), day(2, 10, tracked = false))
            .distinctBy { it.date }
        val p = Garden.catchUp(start, days, 180, today)
        // good, bad, good: 1 -> 0 (wilt 1) -> 1
        assertEquals(1, p.growth)
        assertEquals(0, p.wilt)
        assertEquals(today.minusDays(1), p.lastDay)
        assertEquals(p, Garden.catchUp(p, days, 180, today))
    }
}
