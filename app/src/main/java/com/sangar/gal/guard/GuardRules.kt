package com.sangar.gal.guard

/**
 * The whole rulebook of the redesigned GAL, kept pure so it is unit tested.
 *
 * You pick a few apps and one daily time for all of them together. Opening one of them for the first time in a
 * day shows a short reminder. Once the time is used up, a full-screen pause appears for [BLOCK_SECONDS], and
 * again after every [REPEAT_MINUTES] more minutes in those apps.
 */
object GuardRules {

    const val MIN_LIMIT_MINUTES = 5
    const val MAX_LIMIT_MINUTES = 120
    const val LIMIT_STEP_MINUTES = 5
    const val DEFAULT_LIMIT_MINUTES = 30

    /** How long the full-screen pause stays. Fixed for now; it will become dynamic later. */
    const val BLOCK_SECONDS = 10

    /** After the first pause, another one each time this much more is spent in the chosen apps. */
    const val REPEAT_MINUTES = 5

    /** How long the "this app is on your list" reminder stays if it is not tapped away. */
    const val REMINDER_MILLIS = 3_000L

    fun clampLimit(minutes: Int): Int {
        val stepped = (minutes.coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES) / LIMIT_STEP_MINUTES) * LIMIT_STEP_MINUTES
        return stepped.coerceAtLeast(MIN_LIMIT_MINUTES)
    }

    /** How many pauses should have happened by the time [usedMillis] has been spent today. */
    fun blocksDue(usedMillis: Long, limitMinutes: Int, repeatMinutes: Int = REPEAT_MINUTES): Int {
        val limit = limitMinutes * 60_000L
        if (limitMinutes <= 0 || usedMillis < limit) return 0
        val repeat = (repeatMinutes * 60_000L).coerceAtLeast(60_000L)
        return 1 + ((usedMillis - limit) / repeat).toInt()
    }

    /** A pause is due when more are owed than were shown. Only ever while a chosen app is in front. */
    fun shouldBlock(inChosenApp: Boolean, usedMillis: Long, limitMinutes: Int, blocksShown: Int): Boolean =
        inChosenApp && blocksDue(usedMillis, limitMinutes) > blocksShown

    /**
     * After a pause, everything owed so far counts as shown: if the service was away for a while, or the limit
     * was lowered, you get one pause, never several in a row.
     */
    fun shownAfterBlock(usedMillis: Long, limitMinutes: Int, blocksShown: Int): Int =
        maxOf(blocksShown + 1, blocksDue(usedMillis, limitMinutes))

    /**
     * The reminder: the first time each chosen app is opened in a day, as long as there is time left. Once the
     * time is up the pause speaks for itself.
     */
    fun shouldRemind(
        opened: String?,
        chosen: Set<String>,
        remindedToday: Set<String>,
        usedMillis: Long,
        limitMinutes: Int,
    ): Boolean = opened != null && opened in chosen && opened !in remindedToday && usedMillis < limitMinutes * 60_000L

    /** Whole minutes left today, never negative. */
    fun minutesLeft(usedMillis: Long, limitMinutes: Int): Int =
        ((limitMinutes * 60_000L - usedMillis).coerceAtLeast(0L) / 60_000L).toInt()
}
