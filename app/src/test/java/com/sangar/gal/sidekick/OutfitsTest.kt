package com.sangar.gal.sidekick

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class OutfitsTest {
    @Test
    fun seasonsAndThePartyHat() {
        val plant = com.sangar.gal.data.Plant()
        assertEquals(Outfit.SCARF, Outfits.pick(LocalDate.of(2026, 12, 20), plant))
        assertEquals(Outfit.SCARF, Outfits.pick(LocalDate.of(2027, 2, 1), plant))
        assertEquals(Outfit.SUNGLASSES, Outfits.pick(LocalDate.of(2027, 7, 4), plant))
        assertEquals(Outfit.NONE, Outfits.pick(LocalDate.of(2026, 11, 12), plant))
        val day = LocalDate.of(2026, 12, 20)
        assertEquals(Outfit.PARTY_HAT, Outfits.pick(day, plant.copy(bloomedOn = day)))
        assertEquals(Outfit.SCARF, Outfits.pick(day.plusDays(1), plant.copy(bloomedOn = day)))
    }
}
