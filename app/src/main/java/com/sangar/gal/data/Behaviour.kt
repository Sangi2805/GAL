package com.sangar.gal.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One thing GAL noticed, written as a single short line. [strong] is the part the screen prints in bold, e.g.
 * "YouTube peaks on " + "Sunday mornings".
 */
data class Insight(val kind: Kind, val before: String, val strong: String, val after: String = "") {
    val text: String get() = before + strong + after

    enum class Kind { PEAK_SLOT, APP_TREND, BUSIEST_HOUR, LATE_NIGHT, HEAVIEST_DAY, TOP_SHARE }
}

/**
 * The behaviour engine: reads the last four weeks of "which app, which hour, how long" and says what stands
 * out, in plain words. Everything happens on the phone. Pure, so it is unit tested.
 *
 * It only speaks when the data backs it up: every insight has a minimum amount of use and a minimum amount of
 * history behind it, so a new install shows a few honest lines instead of confident nonsense.
 */
object Behaviour {

    const val WINDOW_DAYS = 28L

    private const val MINUTE = 60_000L

    enum class DayPart(val hours: IntRange?, val plural: String, val phrase: String) {
        MORNING(5..11, "mornings", "in the morning"),
        AFTERNOON(12..16, "afternoons", "in the afternoon"),
        EVENING(17..21, "evenings", "in the evening"),
        NIGHT(null, "nights", "at night"),
        ;

        companion object {
            fun of(hour: Int): DayPart = entries.firstOrNull { it.hours != null && hour in it.hours } ?: NIGHT
        }
    }

    fun insights(rows: List<HourUse>, today: LocalDate, label: (String) -> String, limit: Int = 3): List<Insight> {
        val first = today.minusDays(WINDOW_DAYS - 1)
        val window = rows.filter { it.date in first..today && it.millis > 0 }
        if (window.isEmpty()) return emptyList()
        val earliest = window.minOf { it.date }
        val dates = generateSequence(earliest) { it.plusDays(1) }.takeWhile { it <= today }.toList()
        val byApp = window.groupBy { it.packageName }.mapValues { (_, r) -> r.sumOf { it.millis } }
        val ranked = byApp.entries.sortedByDescending { it.value }.map { it.key }

        return listOfNotNull(
            peakSlot(window, dates, ranked.first(), byApp.getValue(ranked.first()), label),
            appTrend(window, earliest, today, ranked.take(5), label),
            busiestHour(window),
            lateNights(window, today),
            heaviestDay(window, dates),
            topShare(window, today, label),
        ).take(limit)
    }

    /** "YouTube peaks on Sunday mornings", or with under a week of history, "You use YouTube most at night". */
    private fun peakSlot(window: List<HourUse>, dates: List<LocalDate>, app: String, total: Long, label: (String) -> String): Insight? {
        if (total < 30 * MINUTE) return null
        val mine = window.filter { it.packageName == app }
        if (dates.size >= 7) {
            val perWeekday = dates.groupingBy { it.dayOfWeek }.eachCount()
            val slots = mine.groupBy { it.date.dayOfWeek to DayPart.of(it.hour) }
                .mapValues { (slot, r) -> r.sumOf { it.millis }.toDouble() / perWeekday.getValue(slot.first) }
            val best = slots.maxByOrNull { it.value } ?: return null
            val mean = slots.values.sum() / (7 * DayPart.entries.size)
            if (best.value >= 10 * MINUTE && best.value >= 2 * mean) {
                return Insight(Insight.Kind.PEAK_SLOT, "${label(app)} peaks on ", "${dayName(best.key.first)} ${best.key.second.plural}")
            }
        }
        val parts = mine.groupBy { DayPart.of(it.hour) }.mapValues { (_, r) -> r.sumOf { it.millis } }
        val best = parts.maxByOrNull { it.value } ?: return null
        if (best.value < 10 * MINUTE || best.value < 0.4 * total) return null
        return Insight(Insight.Kind.PEAK_SLOT, "You use ${label(app)} most ", best.key.phrase)
    }

    /** "Instagram is down 20% this week": the last seven full days against the seven before. */
    private fun appTrend(window: List<HourUse>, earliest: LocalDate, today: LocalDate, apps: List<String>, label: (String) -> String): Insight? {
        if (earliest > today.minusDays(14)) return null
        val thisWeek = today.minusDays(7)..today.minusDays(1)
        val lastWeek = today.minusDays(14)..today.minusDays(8)
        val best = apps.mapNotNull { app ->
            val now = window.filter { it.packageName == app && it.date in thisWeek }.sumOf { it.millis }
            val before = window.filter { it.packageName == app && it.date in lastWeek }.sumOf { it.millis }
            if (before < 30 * MINUTE) null else app to (now - before).toDouble() / before
        }.maxByOrNull { abs(it.second) } ?: return null
        val (app, change) = best
        if (abs(change) < 0.15) return null
        val percent = (abs(change) * 100).roundToInt()
        return when {
            change >= 1.0 -> Insight(Insight.Kind.APP_TREND, "${label(app)} has ", "more than doubled", " this week")
            change > 0 -> Insight(Insight.Kind.APP_TREND, "${label(app)} is ", "up $percent%", " this week")
            else -> Insight(Insight.Kind.APP_TREND, "${label(app)} is ", "down $percent%", " this week")
        }
    }

    /** "Most screen time is 10 to 11 pm". */
    private fun busiestHour(window: List<HourUse>): Insight? {
        val total = window.sumOf { it.millis }
        if (total < 60 * MINUTE) return null
        val byHour = window.groupBy { it.hour }.mapValues { (_, r) -> r.sumOf { it.millis } }
        val best = byHour.maxByOrNull { it.value } ?: return null
        // An hour only stands out if it holds clearly more than an even share of the day.
        if (best.value < total * 1.5 / 24) return null
        return Insight(Insight.Kind.BUSIEST_HOUR, "Most screen time is ", hourRange(best.key))
    }

    /** "On your phone past midnight 3 of the last 7 nights". */
    private fun lateNights(window: List<HourUse>, today: LocalDate): Insight? {
        val week = today.minusDays(6)..today
        val nights = window.filter { it.date in week && it.hour in 0..4 }
            .groupBy { it.date }
            .count { (_, r) -> r.sumOf { it.millis } >= 10 * MINUTE }
        if (nights < 2) return null
        return Insight(Insight.Kind.LATE_NIGHT, "Up past midnight on ", "$nights of the last 7", " nights")
    }

    /** "Saturdays are your heaviest day". Needs two weeks, so every weekday has been seen twice. */
    private fun heaviestDay(window: List<HourUse>, dates: List<LocalDate>): Insight? {
        if (dates.size < 14) return null
        val perWeekday = dates.groupingBy { it.dayOfWeek }.eachCount()
        val averages = window.groupBy { it.date.dayOfWeek }
            .mapValues { (day, r) -> r.sumOf { it.millis }.toDouble() / perWeekday.getValue(day) }
        val best = averages.maxByOrNull { it.value } ?: return null
        val mean = averages.values.sum() / 7
        if (best.value < 1.25 * mean) return null
        return Insight(Insight.Kind.HEAVIEST_DAY, "", "${dayName(best.key)}s", " are your heaviest day")
    }

    /** "YouTube is 45% of your screen time", over the last seven days. */
    private fun topShare(window: List<HourUse>, today: LocalDate, label: (String) -> String): Insight? {
        val week = window.filter { it.date in today.minusDays(6)..today }
        val total = week.sumOf { it.millis }
        if (total < 60 * MINUTE) return null
        val top = week.groupBy { it.packageName }.mapValues { (_, r) -> r.sumOf { it.millis } }.maxByOrNull { it.value } ?: return null
        val share = top.value.toDouble() / total
        if (share < 0.3) return null
        return Insight(Insight.Kind.TOP_SHARE, "${label(top.key)} is ", "${(share * 100).roundToInt()}%", " of your screen time")
    }

    private fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /** 22 -> "10 to 11 pm", 11 -> "11 am to 12 pm", 23 -> "11 pm to 12 am". */
    fun hourRange(hour: Int): String {
        fun clock(h: Int) = when (val twelve = h % 12) {
            0 -> 12
            else -> twelve
        }
        fun half(h: Int) = if (h % 24 < 12) "am" else "pm"
        val next = (hour + 1) % 24
        return if (half(hour) == half(next)) {
            "${clock(hour)} to ${clock(next)} ${half(next)}"
        } else {
            "${clock(hour)} ${half(hour)} to ${clock(next)} ${half(next)}"
        }
    }
}
