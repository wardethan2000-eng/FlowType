package com.ethanward.flowtype.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalStatsTest {
    @Test
    fun allZerosIsSilent() {
        val s = SignalStats.of(FloatArray(1600))
        assertTrue(s.silent)
        assertEquals(1f, s.zeroFraction, 0f)
    }

    @Test
    fun speechIsNot() {
        val s = SignalStats.of(FloatArray(1600) { if (it % 2 == 0) 0.2f else -0.2f })
        assertFalse(s.silent)
        assertEquals(0.2f, s.peak, 1e-6f)
        assertEquals(0.2f, s.rms, 1e-6f)
    }

    @Test
    fun emptyIsSilent() = assertTrue(SignalStats.of(FloatArray(0)).silent)
}
