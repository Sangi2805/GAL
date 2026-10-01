package com.sangar.gal.data

import com.sangar.gal.sidekick.scene.RoastRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoastLimitsTest {

    @Test
    fun defaultsMatchTheRulesAndSitInsideTheirRanges() {
        val rules = RoastRules()
        assertEquals(rules.minOpensToday, AppRoastLimits.DEFAULT_OPENS)
        assertEquals(rules.minMinutesToday, AppRoastLimits.DEFAULT_MINUTES)
        assertEquals(rules.minAverageMinutes, AppRoastLimits.DEFAULT_AVERAGE)
        assertTrue(AppRoastLimits.DEFAULT_OPENS in AppRoastLimits.OPENS)
        assertTrue(AppRoastLimits.DEFAULT_MINUTES in AppRoastLimits.MINUTES)
        assertTrue(AppRoastLimits.DEFAULT_AVERAGE in AppRoastLimits.AVERAGE)
        val settings = Settings()
        assertEquals(AppRoastLimits.DEFAULT_OPENS, settings.roastMinOpens)
        assertEquals(AppRoastLimits.DEFAULT_MINUTES, settings.roastMinMinutes)
        assertEquals(AppRoastLimits.DEFAULT_AVERAGE, settings.roastMinAverage)
    }

    @Test
    fun clampsKeepEveryValueInRange() {
        assertEquals(2, AppRoastLimits.clampOpens(0))
        assertEquals(50, AppRoastLimits.clampOpens(500))
        assertEquals(5, AppRoastLimits.clampMinutes(-10))
        assertEquals(240, AppRoastLimits.clampMinutes(1000))
        assertEquals(10, AppRoastLimits.clampAverage(1))
        assertEquals(300, AppRoastLimits.clampAverage(301))
        assertEquals(45, AppRoastLimits.clampMinutes(45))
    }

    @Test
    fun scenesAndRoastsStartOnAndSoundsStartOff() {
        val settings = Settings()
        assertTrue(settings.scenesEnabled)
        assertTrue(settings.appRoastsEnabled)
        assertEquals(false, settings.sceneSounds)
        assertTrue(settings.sceneHaptics)
    }
}
