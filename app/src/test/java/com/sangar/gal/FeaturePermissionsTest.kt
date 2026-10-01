package com.sangar.gal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each switch asks only for its own permissions. */
class FeaturePermissionsTest {

    private fun status(
        usage: Boolean = false,
        overlay: Boolean = false,
        notifications: Boolean = false,
        battery: Boolean = false,
        mic: Boolean = false,
    ) = PermissionStatus(usage, overlay, notifications, battery, mic)

    @Test
    fun roastsNeverAskForTheMicrophone() {
        val missing = status().missingFor(Feature.ROASTS)
        assertEquals(listOf(Need.USAGE_ACCESS, Need.OVERLAY, Need.NOTIFICATIONS), missing)
        assertTrue(status(usage = true, overlay = true, notifications = true).ready(Feature.ROASTS))
    }

    @Test
    fun voiceNeverAsksForUsageAccessOrBattery() {
        val missing = status().missingFor(Feature.VOICE)
        assertEquals(listOf(Need.OVERLAY, Need.MICROPHONE, Need.NOTIFICATIONS), missing)
        assertTrue(status(overlay = true, notifications = true, mic = true).ready(Feature.VOICE))
    }

    @Test
    fun batteryIsOfferedToRoastsButNeverRequired() {
        assertTrue(Need.BATTERY in Need.forFeature(Feature.ROASTS))
        assertFalse(Need.BATTERY in Need.forFeature(Feature.VOICE))
        assertTrue(status(usage = true, overlay = true, notifications = true, battery = false).ready(Feature.ROASTS))
    }

    @Test
    fun oneFeatureReadyDoesNotMakeTheOtherReady() {
        val voiceOnly = status(overlay = true, notifications = true, mic = true)
        assertTrue(voiceOnly.ready(Feature.VOICE))
        assertFalse(voiceOnly.ready(Feature.ROASTS))
        assertEquals(listOf("usage access"), voiceOnly.missing)
    }
}
