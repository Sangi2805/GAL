package com.sangar.gal.sidekick

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionsTest {

    @Test
    fun feedsAreSocialMessagingIsNot() {
        assertEquals(AppKind.SOCIAL, Reactions.classify("com.instagram.android", null))
        assertEquals(AppKind.SOCIAL, Reactions.classify("com.google.android.youtube", null))
        assertEquals(AppKind.SOCIAL, Reactions.classify("com.zhiliaoapp.musically", null))
        assertEquals(AppKind.NEUTRAL, Reactions.classify("com.whatsapp", ApplicationInfo.CATEGORY_SOCIAL))
        assertEquals(AppKind.NEUTRAL, Reactions.classify("org.telegram.messenger", ApplicationInfo.CATEGORY_SOCIAL))
    }

    @Test
    fun usefulAppsMakeHerProud() {
        assertEquals(AppKind.PRODUCTIVE, Reactions.classify("com.google.android.apps.docs.editors.docs", null))
        assertEquals(AppKind.PRODUCTIVE, Reactions.classify("com.duolingo", null))
        assertEquals(AppKind.PRODUCTIVE, Reactions.classify("com.amazon.kindle", null))
    }

    @Test
    fun unknownAppsFallBackToTheStoreCategory() {
        assertEquals(AppKind.SOCIAL, Reactions.classify("com.example.feed", ApplicationInfo.CATEGORY_SOCIAL))
        assertEquals(AppKind.PRODUCTIVE, Reactions.classify("com.example.notes", ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertEquals(AppKind.NEUTRAL, Reactions.classify("com.example.game", ApplicationInfo.CATEGORY_GAME))
        assertEquals(AppKind.NEUTRAL, Reactions.classify(null, null))
    }

    @Test
    fun angerGrowsWithTheSession() {
        val min = 60_000L
        assertEquals(0f, Reactions.anger(10 * min, 20, sessionOpen = true), 0.001f)
        assertEquals(0f, Reactions.anger(30 * min, 20, sessionOpen = false), 0.001f)
        assertEquals(0f, Reactions.anger(30 * min, 0, sessionOpen = true), 0.001f)
        // At the threshold she has the angry face; at twice the threshold she is fully furious.
        assertTrue(Reactions.anger(20 * min, 20, sessionOpen = true) >= Reactions.ANGRY_FACE_AT - 0.001f)
        assertTrue(Reactions.anger(19 * min, 20, sessionOpen = true) < Reactions.ANGRY_FACE_AT)
        assertEquals(1f, Reactions.anger(40 * min, 20, sessionOpen = true), 0.001f)
        assertEquals(1f, Reactions.anger(90 * min, 20, sessionOpen = true), 0.001f)
    }
}
