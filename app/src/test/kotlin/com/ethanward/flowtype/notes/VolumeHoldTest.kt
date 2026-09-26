package com.ethanward.flowtype.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeHoldTest {
    @Test
    fun aTapIsATap() {
        val v = VolumeHold(600)
        v.onDown(1000)
        assertEquals(true, v.onUp())
    }

    @Test
    fun quickTapsInARowAreAllTaps() {
        // The bug this replaces: every second quick tap was taken as a double press.
        val v = VolumeHold(600)
        repeat(5) { i ->
            v.onDown(1000L + i * 200)
            assertEquals(true, v.onUp())
        }
    }

    @Test
    fun aHoldStartsANoteOnceAndIsNotATap() {
        val v = VolumeHold(600)
        v.onDown(1000)
        assertEquals(false, v.onTimer(1300)) // too early
        assertEquals(true, v.onTimer(1600))
        assertEquals(false, v.onTimer(1700)) // once only
        assertEquals(false, v.onUp())
    }

    @Test
    fun aStaleTimerAfterReleaseDoesNothing() {
        val v = VolumeHold(600)
        v.onDown(1000)
        v.onUp()
        assertEquals(false, v.onTimer(1700))
    }
}
