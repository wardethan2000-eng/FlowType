package com.ethanward.flowtype.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietGateTest {
    private val loud = 0.05f
    private val quiet = 0.001f

    @Test
    fun opensAMarginAfterThePlayerStops() {
        val g = QuietGate(startMs = 1_000, marginMs = 600)
        assertFalse(g.frame(1_000, otherPlaying = true, micRms = loud))
        assertFalse(g.frame(1_200, otherPlaying = true, micRms = loud)) // still fading out
        assertFalse(g.frame(1_300, otherPlaying = false, micRms = loud)) // phone stopped; the car still plays
        assertFalse(g.frame(1_800, otherPlaying = false, micRms = loud))
        assertTrue(g.frame(1_900, otherPlaying = false, micRms = loud))
        assertEquals(300, g.stoppedAfterMs)
        assertEquals(900, g.openedAfterMs)
        assertEquals(QuietGate.Why.PLAYER, g.why)
        assertEquals(loud, g.loudestBlankedRms)
    }

    @Test
    fun aBlipOfPlayingRestartsTheWait() {
        val g = QuietGate(startMs = 0, marginMs = 150)
        g.frame(100, otherPlaying = false, micRms = loud)
        g.frame(200, otherPlaying = true, micRms = loud)
        assertFalse(g.frame(300, otherPlaying = false, micRms = loud))
        assertTrue(g.frame(450, otherPlaying = false, micRms = loud))
        assertEquals(300, g.stoppedAfterMs)
    }

    @Test
    fun aQuietMicOpensItWhenThePlayerStillLooksStarted() {
        // Chrome: paused, but its output stays "started" for seconds.
        val g = QuietGate(startMs = 0, marginMs = 600)
        assertFalse(g.frame(0, otherPlaying = true, micRms = loud))
        assertFalse(g.frame(150, otherPlaying = true, micRms = loud)) // still heard
        assertFalse(g.frame(180, otherPlaying = true, micRms = quiet))
        assertFalse(g.frame(390, otherPlaying = true, micRms = quiet))
        assertTrue(g.frame(420, otherPlaying = true, micRms = quiet))
        assertEquals(QuietGate.Why.MIC, g.why)
    }

    @Test
    fun theMicsSilentStartDoesntCount() {
        // The first frames can be zeros before the player has reacted.
        val g = QuietGate(startMs = 0, marginMs = 600)
        assertFalse(g.frame(0, otherPlaying = true, micRms = 0f))
        assertFalse(g.frame(240, otherPlaying = true, micRms = 0f))
        assertFalse(g.frame(270, otherPlaying = true, micRms = loud))
        assertNull(g.why)
    }

    @Test
    fun aPlayerThatIgnoresThePauseOpensAtTheCap() {
        val g = QuietGate(startMs = 0, marginMs = 600, capMs = 1_500)
        assertFalse(g.frame(1_470, otherPlaying = true, micRms = loud))
        assertTrue(g.frame(1_500, otherPlaying = true, micRms = loud))
        assertEquals(-1, g.stoppedAfterMs)
        assertEquals(QuietGate.Why.CAP, g.why)
    }

    @Test
    fun onceOpenItStaysOpen() {
        val g = QuietGate(startMs = 0, marginMs = 0)
        assertTrue(g.frame(30, otherPlaying = false, micRms = loud))
        assertTrue(g.frame(60, otherPlaying = true, micRms = loud))
    }

    @Test
    fun speakersAwayFromThePhoneGetTheLongerWait() {
        assertFalse(OtherAudio.routeOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).far)
        assertFalse(OtherAudio.routeOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).far)
        assertTrue(OtherAudio.routeOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).far)
        assertTrue(OtherAudio.routeOf(AudioDeviceInfo.TYPE_BUS).far)
        assertTrue(OtherAudio.routeOf(null).far)
    }
}
