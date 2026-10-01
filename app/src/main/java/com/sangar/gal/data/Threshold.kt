package com.sangar.gal.data

import kotlin.math.abs

/**
 * The rules for the "nag me after" setting, kept free of Android so they can be unit tested.
 *
 * Range is 1 minute to 8 hours. The slider snaps to 1 minute, then every 5 minutes up to an hour, then every
 * 15 minutes up to 8 hours. Exact entry accepts any whole minute in range. 0 is not a threshold: it means
 * "off", which is the master switch's job.
 */
object Threshold {
    const val OFF = 0
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 8 * 60
    const val DEFAULT_MINUTES = 30

    /** Every value the slider can land on, ascending. */
    val stops: List<Int> = buildList {
        add(MIN_MINUTES)
        addAll(5..55 step 5)
        addAll(60..MAX_MINUTES step 15)
    }

    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /** Slider position for a stored value. Exact values between stops sit on the nearest stop. */
    fun indexOf(minutes: Int): Int {
        val target = clamp(minutes)
        return stops.indices.minBy { abs(stops[it] - target) }
    }

    fun minutesAt(index: Int): Int = stops[index.coerceIn(0, stops.lastIndex)]

    sealed interface Input {
        /** 0 was entered: switch nagging off, keep the stored threshold. */
        data object Off : Input
        data class Minutes(val minutes: Int) : Input
        data class Invalid(val message: String) : Input
    }

    fun parse(text: String): Input {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Input.Invalid("Enter a number of minutes.")
        val value = trimmed.toIntOrNull()
            ?: return Input.Invalid("Whole minutes only, from $MIN_MINUTES to $MAX_MINUTES, or 0 to switch off.")
        return when {
            value == OFF -> Input.Off
            value < 0 -> Input.Invalid("That is in the past. Try $MIN_MINUTES to $MAX_MINUTES.")
            value > MAX_MINUTES -> Input.Invalid("Eight hours ($MAX_MINUTES minutes) is the most.")
            else -> Input.Minutes(value)
        }
    }

    fun describe(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$minutes min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }
}
