package com.sangar.gal.sidekick

import com.sangar.gal.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HideForTest {

    private val zone = ZoneId.of("America/St_Johns")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun oneHourIsAnHour() {
        val now = at("2026-10-09T15:20:00")
        assertEquals(now + 3_600_000L, HideFor.ONE_HOUR.until(now, zone))
    }

    @Test
    fun untilTomorrowMeansSevenTheNextMorning() {
        assertEquals(at("2026-10-10T07:00:00"), HideFor.UNTIL_TOMORROW.until(at("2026-10-09T15:20:00"), zone))
        assertEquals(at("2026-10-10T07:00:00"), HideFor.UNTIL_TOMORROW.until(at("2026-10-09T23:59:00"), zone))
        // In the small hours, "tomorrow" is the morning coming up, not a whole day away.
        assertEquals(at("2026-10-10T07:00:00"), HideFor.UNTIL_TOMORROW.until(at("2026-10-10T01:30:00"), zone))
    }

    @Test
    fun turnOffHasNoReturnTime() {
        assertNull(HideFor.TURN_OFF.until(at("2026-10-09T15:20:00"), zone))
    }

    @Test
    fun hiddenOnlyWhileSwitchedOnAndBeforeTheTime() {
        val now = at("2026-10-09T15:20:00")
        val on = Settings(voiceEnabled = true, onboardingComplete = true, elephantHiddenUntil = now + 1_000)
        assertTrue(on.elephantHidden(now))
        assertFalse(on.elephantHidden(now + 2_000))
        assertFalse(on.copy(voiceEnabled = false).elephantHidden(now))
        assertFalse(on.copy(elephantHiddenUntil = 0L).elephantHidden(now))
    }
}
