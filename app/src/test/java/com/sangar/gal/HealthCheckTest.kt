package com.sangar.gal

import com.sangar.gal.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCheckTest {

    private val allGranted = PermissionStatus(usageAccess = true, overlay = true, notifications = true, batteryUnrestricted = true)
    private val on = Settings(roastsEnabled = true, voiceEnabled = true, enabled = true, onboardingComplete = true)

    @Test
    fun activeWhenEverythingRuns() {
        assertEquals(Health.ACTIVE, HealthCheck.health(on, allGranted, serviceRunning = true, elephantOnScreen = true))
        assertTrue(HealthCheck.items(on, allGranted, true, true).all { it.ok })
    }

    @Test
    fun aMissingPermissionComesFirst() {
        val noOverlay = allGranted.copy(overlay = false)
        assertEquals(Health.NEEDS_PERMISSION, HealthCheck.health(on, noOverlay, serviceRunning = false, elephantOnScreen = false))
        assertFalse(HealthCheck.items(on, noOverlay, true, true).first { it.fix == "Display over other apps" }.ok)
    }

    @Test
    fun aKilledServiceIsRestricted() {
        assertEquals(Health.RESTRICTED, HealthCheck.health(on, allGranted, serviceRunning = false, elephantOnScreen = true))
        assertEquals(Health.RESTRICTED, HealthCheck.health(on, allGranted, serviceRunning = true, elephantOnScreen = false))
    }

    @Test
    fun hiddenOrCardsOffIsPausedNotBroken() {
        val now = 1_000_000L
        val hidden = on.copy(elephantHiddenUntil = now + 60_000)
        assertEquals(Health.PAUSED, HealthCheck.health(hidden, allGranted, serviceRunning = true, elephantOnScreen = false, nowMillis = now))
        assertEquals(Health.PAUSED, HealthCheck.health(on.copy(enabled = false), allGranted, true, true))
    }
}
