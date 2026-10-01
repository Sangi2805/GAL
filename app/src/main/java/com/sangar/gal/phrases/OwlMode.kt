package com.sangar.gal.phrases

/**
 * owl_mode lines react to choosing a long threshold. They are shown once, in the app, when the setting is
 * confirmed, and are never used for an overlay card.
 */
object OwlMode {
    const val ABOVE_MINUTES = 60

    fun applies(thresholdMinutes: Int): Boolean = thresholdMinutes > ABOVE_MINUTES

    /** The longer the chosen threshold, the less polite the owl: over 1 h, over 3 h, over 5 h. */
    fun tierFor(thresholdMinutes: Int): Int = when {
        thresholdMinutes > 5 * 60 -> 3
        thresholdMinutes > 3 * 60 -> 2
        else -> 1
    }

    const val DEFAULT_TEXT = "What are you, an owl? Going to stare for hours?"
}
