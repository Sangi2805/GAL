package com.sangar.gal.phrases

/** How long a phrase reads on the card, by whitespace-separated word count. */
enum class PhraseLength(val key: String) {
    /** Under 6 words: what a friend mutters while walking past. */
    SHORT("short"),

    /** 6 to 12 words. */
    MEDIUM("medium"),

    /** 13 words or more. */
    LONG("long"),
    ;

    companion object {
        const val SHORT_MAX_WORDS = 5
        const val MEDIUM_MAX_WORDS = 12

        fun of(text: String): PhraseLength {
            val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            return when {
                words <= SHORT_MAX_WORDS -> SHORT
                words <= MEDIUM_MAX_WORDS -> MEDIUM
                else -> LONG
            }
        }

        fun parse(value: String?): PhraseLength? = entries.firstOrNull { it.key == value }
    }
}

/**
 * Which length to reach for, by moment. The engine applies these weights only inside the pool that already
 * matched the moment's tags and tier, so a short line never skips its tags: "No job?" is tagged work_hours
 * and nothing else, and cannot turn up at 2am on a Sunday however much short lines are favoured.
 *
 * Everyday cards (the first of a session and the repeats) lean short. Marathon sessions and new records are
 * where the longer lines are saved for. The recent-id window stops short lines repeating, which pulls the
 * real share below these weights; with about a fifth of cards being big moments the overall mix lands on
 * 60 percent short, 30 medium, 10 long. PhraseLengthMixTest simulates a month to check that.
 */
object LengthMix {

    data class Weights(val short: Double, val medium: Double, val long: Double) {
        fun of(length: PhraseLength): Double = when (length) {
            PhraseLength.SHORT -> short
            PhraseLength.MEDIUM -> medium
            PhraseLength.LONG -> long
        }
    }

    val EVERYDAY = Weights(short = 0.69, medium = 0.28, long = 0.03)
    val BIG_MOMENT = Weights(short = 0.30, medium = 0.35, long = 0.35)

    /**
     * The give_up sign-off is exempt from the short-line preference. It is the one card that is allowed
     * to take its time: it arrives once per capped session at most, and the line has to land a walk-off.
     */
    val GIVE_UP = Weights(short = 0.20, medium = 0.45, long = 0.35)

    fun isBigMoment(tags: Set<String>): Boolean = Tags.MARATHON in tags || Tags.NEW_RECORD in tags

    fun weightsFor(tags: Set<String>): Weights = when {
        Tags.GIVE_UP in tags -> GIVE_UP
        isBigMoment(tags) -> BIG_MOMENT
        else -> EVERYDAY
    }
}
