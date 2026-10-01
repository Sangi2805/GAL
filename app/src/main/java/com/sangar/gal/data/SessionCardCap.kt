package com.sangar.gal.data

/**
 * How many cards one session shows, and how far apart they are. Free of Android so it can be unit tested.
 *
 * Every session gets the same deal, whatever the threshold: a card each time another "nag me after" period
 * of continuous use passes, up to [PER_SESSION] cards. With a 10 minute threshold that is a card at 10, 20,
 * 30 minutes and so on. The last of them, card number [PER_SESSION], is always the give-up sign-off, in both
 * phrase packs. After it the app stays quiet until the next session starts.
 */
object SessionCardCap {
    /** Cards per session. The last one is the give-up sign-off. */
    const val PER_SESSION = 12

    /** The gap between two cards of one session is the threshold itself. */
    fun gapMillis(thresholdMinutes: Int): Long = Threshold.clamp(thresholdMinutes) * 60_000L

    /** Session time at which the last card of a session falls due, if nothing snoozes or excludes it. */
    fun lastCardAtMinutes(thresholdMinutes: Int): Long = Threshold.clamp(thresholdMinutes).toLong() * PER_SESSION

    /** For the settings and diagnostics screens, e.g. "12 cards, one every 30 min". */
    fun describe(thresholdMinutes: Int): String =
        "$PER_SESSION cards, one every ${Threshold.describe(Threshold.clamp(thresholdMinutes))}"
}
