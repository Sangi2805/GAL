package com.sangar.gal.data

/**
 * The "daily total" setting: a card once today's screen time passes it. 30 minutes to 12 hours in
 * 15 minute steps, default 3 hours. Free of Android so it can be unit tested.
 */
object DailyLimit {
    const val MIN_MINUTES = 30
    const val MAX_MINUTES = 12 * 60
    const val DEFAULT_MINUTES = 3 * 60
    const val STEP_MINUTES = 15

    val stops: List<Int> = (MIN_MINUTES..MAX_MINUTES step STEP_MINUTES).toList()

    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /** Slider position for a stored value; values between stops sit on the nearest one. */
    fun indexOf(minutes: Int): Int = ((clamp(minutes) - MIN_MINUTES + STEP_MINUTES / 2) / STEP_MINUTES).coerceIn(0, stops.lastIndex)

    fun minutesAt(index: Int): Int = stops[index.coerceIn(0, stops.lastIndex)]
}
