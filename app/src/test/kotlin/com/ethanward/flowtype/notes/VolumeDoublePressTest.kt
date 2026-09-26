package com.ethanward.flowtype.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeDoublePressTest {
    @Test
    fun twoQuickPressesAreADoublePress() {
        val d = VolumeDoublePress(400)
        assertEquals(false, d.onDown(1000))
        assertEquals(true, d.onDown(1300))
    }

    @Test
    fun slowPressesAreJustVolume() {
        val d = VolumeDoublePress(400)
        assertEquals(false, d.onDown(1000))
        assertEquals(false, d.onDown(1500))
        assertEquals(false, d.onDown(2100))
    }

    @Test
    fun aThirdQuickPressStartsCountingAfresh() {
        // Press, press (note starts), press: the third isn't a second double press.
        val d = VolumeDoublePress(400)
        d.onDown(1000)
        assertEquals(true, d.onDown(1200))
        assertEquals(false, d.onDown(1400))
        assertEquals(true, d.onDown(1600))
    }
}
