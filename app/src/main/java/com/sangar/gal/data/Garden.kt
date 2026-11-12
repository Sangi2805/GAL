package com.sangar.gal.data

import com.sangar.gal.data.db.DailyUsage
import java.time.LocalDate

/**
 * GAL's plant. It lives on your screen time: every day at or under your daily goal it grows a step, and after
 * [Garden.BLOOM_AT] good days it flowers, joins your garden (your score) and a new seed is planted. A day over
 * the goal makes it wilt and lose a step; [Garden.DIES_AT] bad days in a row and it dies, and you start again
 * from a seed. The flowers you already grew are yours to keep.
 */
data class Plant(
    /** Good days this plant has had, 0 until it blooms at [Garden.BLOOM_AT]. */
    val growth: Int = 0,
    /** Days over the goal in a row. */
    val wilt: Int = 0,
    /** Plants grown all the way to a flower: the garden score. */
    val flowers: Int = 0,
    /** Plants that died. */
    val lost: Int = 0,
    /** The last day already counted. */
    val lastDay: LocalDate? = null,
    /** The day the newest flower was there to see (the morning after its seventh good day). */
    val bloomedOn: LocalDate? = null,
)

enum class PlantStage { SEED, SPROUT, LEAVES, BUD }

object Garden {
    const val BLOOM_AT = 7
    const val DIES_AT = 3
    const val DEFAULT_GOAL_MINUTES = 180
    const val MIN_GOAL_MINUTES = 30
    const val MAX_GOAL_MINUTES = 600
    const val GOAL_STEP_MINUTES = 30

    fun clampGoal(minutes: Int): Int = minutes.coerceIn(MIN_GOAL_MINUTES, MAX_GOAL_MINUTES)

    /** One finished day: [underGoal] is whether screen time stayed at or under the goal. */
    fun step(plant: Plant, underGoal: Boolean): Plant =
        if (underGoal) {
            val grown = plant.growth + 1
            if (grown >= BLOOM_AT) plant.copy(growth = 0, wilt = 0, flowers = plant.flowers + 1)
            else plant.copy(growth = grown, wilt = 0)
        } else {
            val wilt = plant.wilt + 1
            if (wilt >= DIES_AT) plant.copy(growth = 0, wilt = 0, lost = plant.lost + 1)
            else plant.copy(growth = (plant.growth - 1).coerceAtLeast(0), wilt = wilt)
        }

    /**
     * Counts every finished, fully tracked day after [Plant.lastDay] up to yesterday. The very first time there
     * is no history to judge, so the plant simply starts today as a seed. Days the timer was not running are
     * skipped: they neither help nor hurt.
     */
    fun catchUp(plant: Plant, days: List<DailyUsage>, goalMinutes: Int, today: LocalDate): Plant {
        val yesterday = today.minusDays(1)
        val last = plant.lastDay ?: return plant.copy(lastDay = yesterday)
        if (!last.isBefore(yesterday)) return plant
        var p = plant
        days.filter { it.tracked && it.date.isAfter(last) && it.date.isBefore(today) }
            .sortedBy { it.date }
            .forEach {
                val before = p.flowers
                p = step(p, it.totalScreenMinutes <= goalMinutes)
                if (p.flowers > before) p = p.copy(bloomedOn = it.date.plusDays(1))
            }
        return p.copy(lastDay = yesterday)
    }

    fun stage(plant: Plant): PlantStage = when {
        plant.growth <= 0 -> PlantStage.SEED
        plant.growth <= 2 -> PlantStage.SPROUT
        plant.growth <= 4 -> PlantStage.LEAVES
        else -> PlantStage.BUD
    }

    /** One line about how the plant is doing, for the home screen. */
    fun status(plant: Plant, todayMinutes: Long, goalMinutes: Int): String = when {
        todayMinutes > goalMinutes && plant.wilt + 1 >= DIES_AT -> "Over your goal again. One more day like this and it dies."
        todayMinutes > goalMinutes -> "Over your goal today. It will wilt tonight."
        plant.wilt > 0 -> "It is wilting. A day under your goal brings it back."
        plant.growth == 0 && plant.flowers == 0 && plant.lost == 0 -> "A fresh seed. Stay under your goal and it grows."
        plant.growth == 0 -> "A new seed is in the pot."
        else -> "${BLOOM_AT - plant.growth} more good day${if (BLOOM_AT - plant.growth == 1) "" else "s"} and it flowers."
    }
}
