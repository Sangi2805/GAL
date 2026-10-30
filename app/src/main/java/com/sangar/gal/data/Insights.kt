package com.sangar.gal.data

import com.sangar.gal.data.db.DailyUsage
import com.sangar.gal.data.db.ScreenSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * "She noticed...": a few plain sentences about the last week, worked out on the phone from data that never
 * leaves it. Each one only appears when there is enough data behind it. Pure, so it is unit tested.
 */
object Insights {

    /** A session that started at or after this hour (or before [NIGHT_ENDS]) counts as late night. */
    const val NIGHT_STARTS = 21
    const val NIGHT_ENDS = 4

    fun weekly(
        sessions: List<ScreenSession>,
        days: List<DailyUsage>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<String> {
        val out = mutableListOf<String>()
        val weekStart = today.minusDays(7)
        val lastWeek = days.filter { it.tracked && it.date >= weekStart && it.date < today }
        val weekBefore = days.filter { it.tracked && it.date >= weekStart.minusDays(7) && it.date < weekStart }

        // This week against the week before, when both have enough days to compare.
        if (lastWeek.size >= 4 && weekBefore.size >= 4) {
            val now = lastWeek.map { it.totalScreenMinutes }.average()
            val before = weekBefore.map { it.totalScreenMinutes }.average()
            if (before > 0) {
                val change = ((now - before) / before * 100).toInt()
                when {
                    change <= -10 -> out += "You were on your phone ${-change}% less than the week before. She is quietly proud."
                    change >= 10 -> out += "You were on your phone $change% more than the week before."
                    else -> out += "About the same as the week before, give or take a few minutes a day."
                }
            }
        }

        // The best day of the week.
        if (lastWeek.size >= 3) {
            val best = lastWeek.minByOrNull { it.totalScreenMinutes }!!
            out += "Your best day was ${dayName(best.date.dayOfWeek, locale)}, with ${formatMinutes(best.totalScreenMinutes.toLong())}."
        }

        // Late-night pickups.
        val weekFrom = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val recent = sessions.filter { it.startEpochMillis >= weekFrom }
        val lateNight = recent.filter {
            val hour = Instant.ofEpochMilli(it.startEpochMillis).atZone(zone).hour
            hour >= NIGHT_STARTS || hour < NIGHT_ENDS
        }
        val lateUnlocks = lateNight.sumOf { maxOf(it.unlockCount, 1) }
        if (lateUnlocks >= 5) {
            out += "You picked your phone up $lateUnlocks times after ${NIGHT_STARTS - 12} p.m. this week."
        }

        // The longest single stretch.
        recent.maxByOrNull { it.screenOnMillis }?.takeIf { it.screenOnMillis >= 30 * 60_000L }?.let {
            val day = Instant.ofEpochMilli(it.startEpochMillis).atZone(zone).dayOfWeek
            out += "Your longest stretch was ${formatMinutes(it.screenOnMillis / 60_000L)} in one go, on ${dayName(day, locale)}."
        }
        return out
    }

    private fun dayName(day: DayOfWeek, locale: Locale) = day.getDisplayName(TextStyle.FULL, locale)

    private fun formatMinutes(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
