package com.sangar.gal.sidekick.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which scene a voice command plays, and when Sidekick is free again. */
class SceneChoiceTest {

    @Test
    fun choiceTable() {
        fun choose(found: Boolean, roast: Boolean, scenes: Boolean = true, animationsOff: Boolean = false) =
            SceneChoice.choose(found, roast, scenes, animationsOff)

        assertEquals(SceneKind.NORMAL_OPEN, choose(found = true, roast = false))
        assertEquals(SceneKind.ROAST_OPEN, choose(found = true, roast = true))
        assertEquals(SceneKind.NOT_FOUND, choose(found = false, roast = false))
        // Not found wins over a roast flag that cannot apply.
        assertEquals(SceneKind.NOT_FOUND, choose(found = false, roast = true))
        // Quick open and "Remove animations" mean no scene at all, whatever happened.
        for (found in listOf(true, false)) for (roast in listOf(true, false)) {
            assertNull(choose(found, roast, scenes = false))
            assertNull(choose(found, roast, animationsOff = true))
        }
    }

    @Test
    fun finishesOnceAfterBothSceneAndSpeech() {
        var fired = 0
        val sceneFirst = AfterSceneAndSpeech { fired++ }
        sceneFirst.sceneDone()
        assertEquals(0, fired)
        sceneFirst.speechDone()
        assertEquals(1, fired)
        // Late or repeated callbacks do not finish it twice.
        sceneFirst.sceneDone()
        sceneFirst.speechDone()
        assertEquals(1, fired)

        val speechFirst = AfterSceneAndSpeech { fired++ }
        speechFirst.speechDone()
        assertEquals(1, fired)
        speechFirst.sceneDone()
        assertEquals(2, fired)
    }
}
