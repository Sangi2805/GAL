package com.sangar.gal.sidekick

import com.sangar.gal.data.Plant
import java.time.LocalDate

/** What GAL wears today. Pure, so the rules are unit tested. */
object Outfits {
    /**
     * A party hat on the day her plant flowers; otherwise a scarf in December, January and February and
     * sunglasses in June, July and August (northern seasons); nothing the rest of the year.
     */
    fun pick(today: LocalDate, plant: Plant): Outfit = when {
        plant.bloomedOn == today -> Outfit.PARTY_HAT
        today.monthValue in setOf(12, 1, 2) -> Outfit.SCARF
        today.monthValue in 6..8 -> Outfit.SUNGLASSES
        else -> Outfit.NONE
    }
}
