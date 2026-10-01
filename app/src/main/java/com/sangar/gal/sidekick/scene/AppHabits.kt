package com.sangar.gal.sidekick.scene

import com.sangar.gal.sidekick.Mood

/** How much one app has been used, as far as Android's usage stats can tell. */
data class AppUsage(
    /** Times it was opened today: coming to it from another app or from the lock screen. */
    val opensToday: Int,
    /** Minutes in front today. */
    val minutesToday: Long,
    /** Average minutes a day over the last seven days. */
    val averageMinutes7d: Long,
)

/** When opening a social app earns a roast instead of a plain open. Changeable in Settings. */
data class RoastRules(
    val minOpensToday: Int = 8,
    val minMinutesToday: Int = 45,
    val minAverageMinutes: Int = 60,
    /** At most one roast per app in this many minutes. */
    val cooldownMinutes: Int = 120,
    /** At most this many roasts a day across all apps. */
    val maxPerDay: Int = 5,
)

/** What has already been roasted, so the cooldown and the daily limit can be applied. */
data class RoastHistory(
    /** Wall-clock time of the last roast, per package. */
    val lastRoastWall: Map<String, Long> = emptyMap(),
    /** Roasts given on [day]. */
    val roastsOnDay: Int = 0,
    /** Local day the count belongs to, as epoch days. */
    val day: Long = 0L,
) {
    fun roastsToday(today: Long): Int = if (day == today) roastsOnDay else 0

    fun after(packageName: String, nowWall: Long, today: Long): RoastHistory = RoastHistory(
        lastRoastWall = lastRoastWall + (packageName to nowWall),
        roastsOnDay = roastsToday(today) + 1,
        day = today,
    )
}

/**
 * Decides whether opening an app is a plain open or a roast. Plain Kotlin, unit tested.
 *
 * A roast needs all of: the roasts feature on, a social app that is not on the "Stay quiet" list, heavy use
 * (opened [RoastRules.minOpensToday] times today, or more than [RoastRules.minMinutesToday] minutes today, or a
 * seven day average over [RoastRules.minAverageMinutes]), the app not roasted within the cooldown, today's roast
 * limit not reached, and the phone not in a call.
 */
object AppHabits {

    data class Decision(
        val roast: Boolean,
        /** 1 mild to 3 brutal, by how far past the limits the usage is. Only meaningful when [roast]. */
        val tier: Int,
        /** Why, for the log. */
        val reason: String,
    )

    fun decide(
        packageName: String,
        enabled: Boolean,
        social: Boolean,
        usage: AppUsage?,
        rules: RoastRules,
        history: RoastHistory,
        nowWall: Long,
        today: Long,
        inCall: Boolean,
        /** On the "Stay quiet in these apps" list. */
        quiet: Boolean = false,
    ): Decision {
        fun plain(reason: String) = Decision(false, 0, reason)
        if (!enabled) return plain("app roasts are off")
        if (quiet) return plain("on the stay quiet list")
        if (!social) return plain("not a social app")
        if (usage == null) return plain("no usage access")
        if (inCall) return plain("in a call")
        if (!isHeavy(usage, rules)) return plain("not heavy use (${describe(usage)})")
        val last = history.lastRoastWall[packageName]
        if (last != null && nowWall - last in 0 until rules.cooldownMinutes * 60_000L) {
            return plain("roasted ${(nowWall - last) / 60_000} min ago, cooldown ${rules.cooldownMinutes} min")
        }
        if (history.roastsToday(today) >= rules.maxPerDay) return plain("already ${rules.maxPerDay} roasts today")
        return Decision(true, tier(usage, rules), "heavy use (${describe(usage)})")
    }

    fun isHeavy(usage: AppUsage, rules: RoastRules): Boolean =
        usage.opensToday >= rules.minOpensToday ||
            usage.minutesToday > rules.minMinutesToday ||
            usage.averageMinutes7d > rules.minAverageMinutes

    /** How far past the nearest limit the worst number is: under 1.5x mild, under 2.5x pointed, beyond brutal. */
    fun tier(usage: AppUsage, rules: RoastRules): Int {
        val ratio = maxOf(
            usage.opensToday.toFloat() / rules.minOpensToday.coerceAtLeast(1),
            usage.minutesToday.toFloat() / rules.minMinutesToday.coerceAtLeast(1),
            usage.averageMinutes7d.toFloat() / rules.minAverageMinutes.coerceAtLeast(1),
        )
        return when {
            ratio < 1.5f -> 1
            ratio < 2.5f -> 2
            else -> 3
        }
    }

    /** The face for the roast, same escalation as the cards. */
    fun moodFor(tier: Int): Mood = when (tier) {
        1 -> Mood.SMUG
        2 -> Mood.DISAPPOINTED
        else -> Mood.HORRIFIED
    }

    private fun describe(u: AppUsage) = "${u.opensToday} opens, ${u.minutesToday} min today, ${u.averageMinutes7d} min/day avg"

    /**
     * Counts opens of [target] in a day's foreground changes, oldest first: a change to the target from
     * anything else is one open. [SCREEN_OFF] in the sequence means the screen went off, so the next time the
     * target comes up counts as a new open even if nothing else was opened in between.
     */
    fun countOpens(foregroundChanges: List<String>, target: String): Int {
        var opens = 0
        var current: String? = null
        for (pkg in foregroundChanges) {
            if (pkg == SCREEN_OFF) {
                current = null
                continue
            }
            if (pkg == target && current != target) opens++
            current = pkg
        }
        return opens
    }

    const val SCREEN_OFF = "\u0000screen_off"
}
