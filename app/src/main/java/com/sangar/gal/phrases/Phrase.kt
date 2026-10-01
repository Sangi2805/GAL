package com.sangar.gal.phrases

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Phrase(
    val id: Int,
    val text: String,
    val tier: Int,
    val tags: Set<String>,
    /** "short", "medium" or "long", written by build_phrases.py. Missing or unknown means: work it out from the text. */
    @SerialName("length") val declaredLength: String? = null,
    /** "spicy" or "cry", the home screen tab this line belongs to. Missing means spicy. */
    @SerialName("pack") val declaredPack: String? = null,
) {
    val length: PhraseLength get() = PhraseLength.parse(declaredLength) ?: PhraseLength.of(text)

    val pack: PhrasePack get() = PhrasePack.parse(declaredPack)
}

object Tags {
    const val GENERAL = "general"
    const val SHORT_SESSION = "short_session"
    const val LONG_SESSION = "long_session"
    const val MARATHON = "marathon"
    const val MORNING = "morning"
    const val LATE_NIGHT = "late_night"
    const val WEEKEND = "weekend"
    const val FIRST_NAG = "first_nag"
    const val REPEAT_NAG = "repeat_nag"
    const val TREND_UP = "trend_up"
    const val TREND_DOWN = "trend_down"
    const val NEW_RECORD = "new_record"
    const val STREAK_GOOD = "streak_good"

    /** Monday to Friday, 9:00 to 16:59. Its lines carry no other tag and no general, so nothing else can reach them. */
    const val WORK_HOURS = "work_hours"



    /**
     * The sign-off on the last card a session is allowed: the app announcing it is done nagging until
     * next time. Its lines carry no other tag and no general, so no earlier card and no fallback can
     * reach one.
     */
    const val GIVE_UP = "give_up"

    /** Reaction to choosing a threshold over an hour. Not a nag tag: never used for overlay cards. */
    const val OWL_MODE = "owl_mode"

    /** Tags that describe a nag moment. */
    val ALL = listOf(
        GENERAL, SHORT_SESSION, LONG_SESSION, MARATHON, MORNING, LATE_NIGHT, WEEKEND,
        FIRST_NAG, REPEAT_NAG, TREND_UP, TREND_DOWN, NEW_RECORD, STREAK_GOOD, WORK_HOURS,
        GIVE_UP,
    )

    val KNOWN = ALL + OWL_MODE

    val TIERS = 1..3
}

object PhraseCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<Phrase> = json.decodeFromString<List<Phrase>>(text)

    /** Never throws: a broken file becomes an empty catalog, and [onError] says why. */
    fun parseOrEmpty(text: String?, onError: (Throwable) -> Unit): List<Phrase> =
        try {
            parse(text ?: throw IllegalStateException("phrases.json could not be read"))
        } catch (e: Exception) {
            onError(e)
            emptyList()
        }
}
