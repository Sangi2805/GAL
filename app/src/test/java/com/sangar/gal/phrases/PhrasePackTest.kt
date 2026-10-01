package com.sangar.gal.phrases

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PhrasePackTest {

    @Test
    fun parseNullReturnsSpicy() {
        assertEquals(PhrasePack.SPICY, PhrasePack.parse(null))
        assertEquals(PhrasePack.SPICY, PhrasePack.parse("unknown_pack"))
        assertEquals(PhrasePack.CRY, PhrasePack.parse("cry"))
    }
}

class RoastGreetingTest {

    @Test
    fun addsGreetingOnlyOnFirstNagThisSession() {
        val morning = LocalDateTime.of(2024, 1, 1, 9, 0)
        val roast = "This is a roast."
        
        val first = RoastGreeting.compose(roast, 0, morning)
        assertTrue(first.endsWith(roast))
        assertTrue(first.length > roast.length)
        assertTrue(first.contains("loser") || first.contains("waster") || first.contains("champion"))

        val second = RoastGreeting.compose(roast, 1, morning)
        assertEquals(roast, second)
    }

    @Test
    fun picksTheRightSlotForTheHour() {
        val roast = "Roast."
        val tests = listOf(
            LocalDateTime.of(2024, 1, 1, 5, 0) to RoastGreeting.MORNING,
            LocalDateTime.of(2024, 1, 1, 11, 59) to RoastGreeting.MORNING,
            LocalDateTime.of(2024, 1, 1, 12, 0) to RoastGreeting.AFTERNOON,
            LocalDateTime.of(2024, 1, 1, 16, 59) to RoastGreeting.AFTERNOON,
            LocalDateTime.of(2024, 1, 1, 17, 0) to RoastGreeting.EVENING,
            LocalDateTime.of(2024, 1, 1, 21, 59) to RoastGreeting.EVENING,
            LocalDateTime.of(2024, 1, 1, 22, 0) to RoastGreeting.LATE_NIGHT,
            LocalDateTime.of(2024, 1, 1, 4, 59) to RoastGreeting.LATE_NIGHT,
        )
        
        for ((time, expectedOptions) in tests) {
            var found = false
            repeat(20) {
                val composed = RoastGreeting.compose(roast, 0, time)
                if (expectedOptions.any { composed.startsWith(it) }) {
                    found = true
                }
            }
            assertTrue("Expected one of $expectedOptions for hour ${time.hour}", found)
        }
    }
}
