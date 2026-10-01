package com.sangar.gal.data

/** Ranges and defaults for the three "heavy use" numbers behind app roasts. Free of Android, unit tested. */
object AppRoastLimits {
    const val DEFAULT_OPENS = 8
    const val DEFAULT_MINUTES = 45
    const val DEFAULT_AVERAGE = 60

    val OPENS = 2..50
    val MINUTES = 5..240
    val AVERAGE = 10..300

    /** Steps for the plus and minus buttons in Settings. */
    const val OPENS_STEP = 1
    const val MINUTES_STEP = 5
    const val AVERAGE_STEP = 10

    fun clampOpens(value: Int): Int = value.coerceIn(OPENS)
    fun clampMinutes(value: Int): Int = value.coerceIn(MINUTES)
    fun clampAverage(value: Int): Int = value.coerceIn(AVERAGE)
}
