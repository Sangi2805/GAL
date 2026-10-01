package com.sangar.gal.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NagLogTest {

    @Test
    fun releaseBuildsNeverLogTheAppYouAreInOrTheLineYouWereShown() {
        assertEquals("hidden", NagLog.app("com.example.socialapp", verbose = false))
        assertEquals("none", NagLog.app(null, verbose = false))
        assertEquals("hidden", NagLog.phrase(1421, verbose = false))
    }

    @Test
    fun debugBuildsKeepTheDetail() {
        assertEquals("com.example.socialapp", NagLog.app("com.example.socialapp", verbose = true))
        assertEquals("none", NagLog.app(null, verbose = true))
        assertEquals("1421", NagLog.phrase(1421, verbose = true))
    }

    @Test
    fun unitTestsRunAgainstTheDebugBuild() {
        assertEquals(true, NagLog.verbose)
    }
}
