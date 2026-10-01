package com.sangar.gal.data

/**
 * How many cards one session may show before the app stops for that session.
 *
 * The cap follows the threshold, because the repeat interval does not: cards come every
 * [com.sangar.gal.service.NagScheduler.REPEAT_MILLIS] whatever the threshold, so a 5 minute threshold
 * would otherwise nag far more per sitting than a 2 hour one. A short threshold is someone asking to be
 * interrupted often, so it earns more cards; a long one is someone who only wants to hear about real
 * marathons, so a few is plenty.
 *
 * The user can override [AUTO] with an exact number in Settings.
 */
object SessionCardCap {
    /** Stored value meaning "follow the threshold", i.e. [auto]. */
    const val AUTO = 0
    const val MIN = 1
    const val MAX = 12

    const val SHORT_THRESHOLD_MINUTES = 15
    const val LONG_THRESHOLD_MINUTES = 45

    /** The cap the threshold implies: under 15 min 6 cards, 15 to 45 min 4, above 45 min 3. */
    fun auto(thresholdMinutes: Int): Int = when {
        thresholdMinutes < SHORT_THRESHOLD_MINUTES -> 6
        thresholdMinutes <= LONG_THRESHOLD_MINUTES -> 4
        else -> 3
    }

    /** What a session actually gets: the manual override if there is one, otherwise [auto]. */
    fun effective(thresholdMinutes: Int, override: Int): Int =
        if (override == AUTO) auto(thresholdMinutes) else clamp(override)

    fun clamp(cap: Int): Int = cap.coerceIn(MIN, MAX)

    /** For the settings and diagnostics screens. */
    fun describe(thresholdMinutes: Int, override: Int): String =
        if (override == AUTO) "${auto(thresholdMinutes)} (auto)" else "${clamp(override)} (set by hand)"
}
