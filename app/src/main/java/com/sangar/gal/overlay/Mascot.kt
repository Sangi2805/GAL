package com.sangar.gal.overlay

import androidx.annotation.DrawableRes
import com.sangar.gal.R
import com.sangar.gal.sidekick.Mood

/**
 * GetALife's four roast faces in escalation order, now worn by Sidekick. The card drawables come from
 * tools/mascot/generate_elephant.py; [mood] is the same face on the live elephant when Voice Sidekick is on.
 */
enum class Mascot(@param:DrawableRes val drawable: Int, val description: String, val mood: Mood) {
    SMUG(R.drawable.mascot_smug, "smug", Mood.SMUG),
    BORED(R.drawable.mascot_bored, "bored", Mood.BORED),
    DISAPPOINTED(R.drawable.mascot_disappointed, "disappointed", Mood.DISAPPOINTED),
    HORRIFIED(R.drawable.mascot_horrified, "horrified", Mood.HORRIFIED);

    companion object {
        /**
         * Tier 1 is smug on the first card and bored once it has to repeat itself, tier 2 is
         * disappointed, tier 3 is horrified. The give_up sign-off is always bored, whatever the tier:
         * the joke there is indifference, not outrage.
         */
        fun forNag(tier: Int, nagsThisSession: Int, tags: Set<String>): Mascot = when {
            com.sangar.gal.phrases.Tags.GIVE_UP in tags -> BORED
            tier >= 3 -> HORRIFIED
            tier == 2 -> DISAPPOINTED
            nagsThisSession > 0 -> BORED
            else -> SMUG
        }
    }
}
