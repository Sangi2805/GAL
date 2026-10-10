package com.sangar.gal.sidekick

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** The choices offered after the elephant is dropped on the X. Pure, so the times are unit tested. */
enum class HideFor(val label: String) {
    ONE_HOUR("For 1 hour"),
    UNTIL_TOMORROW("Until tomorrow"),
    TURN_OFF("Turn her off"),
    ;

    /** Wall-clock millis she comes back at, or null for [TURN_OFF] (she stays off until switched back on). */
    fun until(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? = when (this) {
        ONE_HOUR -> nowMillis + 60 * 60_000L
        UNTIL_TOMORROW -> {
            // Tomorrow morning. Hidden in the small hours, "tomorrow" still means the morning coming up.
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
            val day = if (now.toLocalTime() < BACK_AT) now.toLocalDate() else now.toLocalDate().plusDays(1)
            day.atTime(BACK_AT).atZone(zone).toInstant().toEpochMilli()
        }
        TURN_OFF -> null
    }

    companion object {
        val BACK_AT: LocalTime = LocalTime.of(7, 0)
    }
}
