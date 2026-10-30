package com.sangar.gal.phrases

/**
 * How honest she is. The user picks this, not a phrase pack: three words people understand, mapped onto the
 * lines we have.
 *
 * - Calm: the Spicy lines, but only the gentlest tier. No escalation, however long the session.
 * - Honest: the Spicy lines as they are, sharper as the session drags on and the week trends up.
 * - Brutal: the "You may cry" one-line roasts, mean on purpose.
 */
enum class Tone(val title: String, val blurb: String) {
    CALM("Calm", "Gentle nudges. She notices, she does not judge."),
    HONEST("Honest", "Dry, sarcastic, sharper the longer you scroll."),
    BRUTAL("Brutal", "One-line roasts, mean on purpose. Opt in only if you laugh at yourself."),
    ;

    val pack: PhrasePack get() = if (this == BRUTAL) PhrasePack.CRY else PhrasePack.SPICY

    /** Calm keeps every card at the first tier. */
    val maxTier: Int get() = if (this == CALM) 1 else 3

    companion object {
        val DEFAULT = HONEST

        fun of(pack: PhrasePack, calm: Boolean): Tone = when {
            pack == PhrasePack.CRY -> BRUTAL
            calm -> CALM
            else -> HONEST
        }
    }
}
