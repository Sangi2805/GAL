package com.sangar.gal.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sidekick wears the face of the card on screen, and only that card's dismissal takes it off. */
class CardEventsTest {

    @Test
    fun anOlderCardTimingOutDoesNotClearTheNewerCardsFace() {
        val testCards = Any()
        val realCards = Any()
        CardEvents.shown(testCards, Mascot.HORRIFIED)
        CardEvents.shown(realCards, Mascot.SMUG)

        CardEvents.gone(testCards)
        assertEquals(Mascot.SMUG, CardEvents.face.value)

        CardEvents.gone(realCards)
        assertNull(CardEvents.face.value)
    }
}
