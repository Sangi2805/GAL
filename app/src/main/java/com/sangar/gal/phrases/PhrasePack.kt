package com.sangar.gal.phrases

import java.time.LocalDateTime
import kotlin.random.Random

/** The card style chosen on the home screen's tabs. Every phrase in phrases.json belongs to exactly one. */
enum class PhrasePack(val key: String, val title: String) {
    /** The original lines: tiers, moment tags and the length mix. */
    SPICY("spicy", "Spicy"),

    /** One-line roasts, brutal on purpose. Opted into on the home screen, so no tiers and no first-week hold. */
    CRY("cry", "You may cry"),
    ;

    companion object {
        /** Missing or unknown means spicy, so older phrase files and settings keep working. */
        fun parse(value: String?): PhrasePack = entries.firstOrNull { it.key == value } ?: SPICY
    }
}

/**
 * The opener on the first "You may cry" card of a session, picked for the time of day:
 * morning 5:00 to 11:59, afternoon 12:00 to 16:59, evening 17:00 to 21:59, late night otherwise.
 */
object RoastGreeting {

    val MORNING = listOf(
        "Good morning, loser!",
        "Good morning, time waster!",
        "Rise and scroll, loser!",
        "Morning, professional time waster!",
        "Good morning, champion of nothing!",
    )
    val AFTERNOON = listOf(
        "Good afternoon, loser!",
        "Good afternoon, time waster!",
        "Afternoon, champion of nothing!",
        "Good afternoon, professional scroller!",
        "Afternoon, loser. Still at it?",
    )
    val EVENING = listOf(
        "Good evening, loser!",
        "Good evening, time waster!",
        "Evening, professional scroller!",
        "Good evening, champion of nothing!",
        "Evening, loser. Productive day?",
    )
    val LATE_NIGHT = listOf(
        "Still up, loser?",
        "Burning the midnight battery, loser?",
        "Up past bedtime, loser?",
        "Hello, night shift time waster!",
        "Nothing good happens this late, loser.",
    )

    fun optionsFor(now: LocalDateTime): List<String> = when (now.hour) {
        in 5..11 -> MORNING
        in 12..16 -> AFTERNOON
        in 17..21 -> EVENING
        else -> LATE_NIGHT
    }

    fun pick(now: LocalDateTime, random: Random = Random.Default): String = optionsFor(now).random(random)

    /** The first card of a session opens with the greeting; later cards are the roast alone. */
    fun compose(roast: String, nagsThisSession: Int, now: LocalDateTime, random: Random = Random.Default): String =
        if (nagsThisSession == 0) "${pick(now, random)} $roast" else roast
}
