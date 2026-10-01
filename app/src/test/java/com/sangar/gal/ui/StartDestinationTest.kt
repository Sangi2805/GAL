package com.sangar.gal.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sangar.gal.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The landing page belongs to the first launch after install and is never shown again unless app data is
 * cleared. Both paths are covered: the decision itself, and the flag really surviving in DataStore.
 */
@RunWith(RobolectricTestRunner::class)
class StartDestinationTest {

    /** The decision, without DataStore in the way. */
    @Test
    fun theLandingPageWinsUntilItHasBeenSeenAndNeverAfter() {
        assertEquals(Routes.LANDING, Routes.startDestination(hasSeenLandingPage = false, onboardingComplete = false))
        assertEquals(Routes.LANDING, Routes.startDestination(hasSeenLandingPage = false, onboardingComplete = true))
        assertEquals(Routes.SETUP, Routes.startDestination(hasSeenLandingPage = true, onboardingComplete = false))
        assertEquals(Routes.HOME, Routes.startDestination(hasSeenLandingPage = true, onboardingComplete = true))
    }

    /**
     * One method on purpose: DataStore is a file that outlives a test method, so the two launches have to
     * be played out in order against the same wiped store, the way a real install does it.
     */
    @Test
    fun firstLaunchShowsItAndTheSecondDoesNot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "datastore").deleteRecursively()
        val repository = SettingsRepository(context)

        // First launch after install.
        val onInstall = repository.current()
        assertFalse("a fresh install has not seen the landing page", onInstall.hasSeenLandingPage)
        assertEquals(
            Routes.LANDING,
            Routes.startDestination(onInstall.hasSeenLandingPage, onInstall.onboardingComplete),
        )

        // The user leaves the landing page. That, and nothing earlier, is what writes the flag: a crash on
        // the screen itself must not skip it permanently.
        repository.setHasSeenLandingPage()

        // Second launch: a new repository over the same store, as a fresh process would build.
        val onRelaunch = SettingsRepository(context).current()
        assertTrue("the flag survived", onRelaunch.hasSeenLandingPage)
        val destination = Routes.startDestination(onRelaunch.hasSeenLandingPage, onRelaunch.onboardingComplete)
        assertNotEquals("never the landing page again", Routes.LANDING, destination)
        assertEquals("setup is still outstanding, so it opens there", Routes.SETUP, destination)

        // And once the permissions are done, straight to home.
        repository.setOnboardingComplete()
        val settled = SettingsRepository(context).current()
        assertEquals(Routes.HOME, Routes.startDestination(settled.hasSeenLandingPage, settled.onboardingComplete))
    }
}
