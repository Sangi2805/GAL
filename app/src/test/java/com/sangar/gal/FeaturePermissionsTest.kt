package com.sangar.gal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each switch asks only for its own permissions, and nothing asks for the microphone any more. */
class FeaturePermissionsTest {

    private fun status(
        usage: Boolean = false,
        overlay: Boolean = false,
        notifications: Boolean = false,
        battery: Boolean = false,
    ) = PermissionStatus(usage, overlay, notifications, battery)

    @Test
    fun roastsAskForUsageOverlayAndNotifications() {
        val missing = status().missingFor(Feature.ROASTS)
        assertEquals(listOf(Need.USAGE_ACCESS, Need.OVERLAY, Need.NOTIFICATIONS), missing)
        assertTrue(status(usage = true, overlay = true, notifications = true).ready(Feature.ROASTS))
    }

    @Test
    fun sidekickNeedsOnlyTheOverlayAndNotifications() {
        val missing = status().missingFor(Feature.SIDEKICK)
        assertEquals(listOf(Need.OVERLAY, Need.NOTIFICATIONS), missing)
        assertTrue(status(overlay = true, notifications = true).ready(Feature.SIDEKICK))
    }

    @Test
    fun batteryIsOfferedToRoastsButNeverRequired() {
        assertTrue(Need.BATTERY in Need.forFeature(Feature.ROASTS))
        assertFalse(Need.BATTERY in Need.forFeature(Feature.SIDEKICK))
        assertTrue(status(usage = true, overlay = true, notifications = true, battery = false).ready(Feature.ROASTS))
    }

    @Test
    fun oneFeatureReadyDoesNotMakeTheOtherReady() {
        val sidekickOnly = status(overlay = true, notifications = true)
        assertTrue(sidekickOnly.ready(Feature.SIDEKICK))
        assertFalse(sidekickOnly.ready(Feature.ROASTS))
        assertEquals(listOf("usage access"), sidekickOnly.missing)
    }

    @Test
    fun noFeatureEverAsksForTheMicrophone() {
        assertFalse(Need.entries.any { it.label.contains("Microphone", ignoreCase = true) })
    }
}
