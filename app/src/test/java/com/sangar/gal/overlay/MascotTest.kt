package com.sangar.gal.overlay

import com.sangar.gal.sidekick.Mood
import org.junit.Assert.assertEquals
import org.junit.Test

class MascotTest {
    @Test
    fun eachTierGetsItsFace() {
        assertEquals(Mascot.SMUG, Mascot.forNag(tier = 1, nagsThisSession = 0, tags = emptySet()))
        assertEquals(Mascot.BORED, Mascot.forNag(tier = 1, nagsThisSession = 2, tags = emptySet()))
        assertEquals(Mascot.DISAPPOINTED, Mascot.forNag(tier = 2, nagsThisSession = 0, tags = emptySet()))
        assertEquals(Mascot.DISAPPOINTED, Mascot.forNag(tier = 2, nagsThisSession = 5, tags = emptySet()))
        assertEquals(Mascot.HORRIFIED, Mascot.forNag(tier = 3, nagsThisSession = 0, tags = emptySet()))
    }

    @Test
    fun facesAreInEscalationOrder() {
        assertEquals(listOf("smug", "bored", "disappointed", "horrified"), Mascot.entries.map { it.description })
    }

    /** The live Sidekick wears the same face as the card it is delivering. */
    @Test
    fun eachFaceHasTheMatchingSidekickMood() {
        assertEquals(
            listOf(Mood.SMUG, Mood.BORED, Mood.DISAPPOINTED, Mood.HORRIFIED),
            Mascot.entries.map { it.mood },
        )
    }

    @Test
    fun giveUpStaysBoredOnTheBlobToo() {
        val face = Mascot.forNag(tier = 3, nagsThisSession = 5, tags = setOf(com.sangar.gal.phrases.Tags.GIVE_UP))
        assertEquals(Mood.BORED, face.mood)
    }
}
