package com.sangar.gal.phrases

import org.junit.Assert.assertEquals
import org.junit.Test

class ToneTest {

    @Test
    fun eachToneMapsToItsLines() {
        assertEquals(PhrasePack.SPICY, Tone.CALM.pack)
        assertEquals(1, Tone.CALM.maxTier)
        assertEquals(PhrasePack.SPICY, Tone.HONEST.pack)
        assertEquals(3, Tone.HONEST.maxTier)
        assertEquals(PhrasePack.CRY, Tone.BRUTAL.pack)
    }

    @Test
    fun storedSettingsReadBackAsTheSameTone() {
        Tone.entries.forEach { tone ->
            assertEquals(tone, Tone.of(tone.pack, calm = tone == Tone.CALM))
        }
        // Brutal wins over a stale calm flag.
        assertEquals(Tone.BRUTAL, Tone.of(PhrasePack.CRY, calm = true))
    }
}
