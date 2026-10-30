package com.sangar.gal.sidekick

import android.content.pm.ApplicationInfo
import com.sangar.gal.phrases.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelingTest {

    private fun level(ratio: Float) = Reactions.anger((ratio * 20 * 60_000L).toLong(), 20, sessionOpen = true)

    @Test
    fun concernedFirstThenCrossThenSteaming() {
        assertEquals(Mood.NONE, Reactions.feeling(level(0.4f), Tone.HONEST).mood)
        val worried = Reactions.feeling(level(0.8f), Tone.HONEST)
        assertEquals(Mood.DISAPPOINTED, worried.mood)
        assertEquals(0f, worried.tint, 0.001f)
        val cross = Reactions.feeling(level(1.1f), Tone.HONEST)
        assertEquals(Mood.ANGRY, cross.mood)
        assertTrue("no steam just past the threshold", cross.tint < 0.3f)
        assertTrue("steam well past it", Reactions.feeling(level(1.6f), Tone.HONEST).tint > 0.3f)
        assertEquals(1f, Reactions.feeling(level(2.5f), Tone.HONEST).tint, 0.001f)
    }

    @Test
    fun calmNeverGetsAngry() {
        val f = Reactions.feeling(level(3f), Tone.CALM)
        assertEquals(Mood.DISAPPOINTED, f.mood)
        assertTrue(f.tint <= 0.25f)
    }

    @Test
    fun stepsBackForVideoMapsAndGames() {
        assertTrue(Reactions.stepsBack("com.netflix.mediaclient", null))
        assertTrue(Reactions.stepsBack("com.google.android.apps.maps", null))
        assertTrue(Reactions.stepsBack("com.example.racer", ApplicationInfo.CATEGORY_GAME))
        assertFalse(Reactions.stepsBack("com.example.notes", ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertFalse(Reactions.stepsBack(null, null))
    }
}
