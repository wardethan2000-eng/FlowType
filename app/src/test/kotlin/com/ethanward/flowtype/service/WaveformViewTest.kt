package com.ethanward.flowtype.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformViewTest {
    @Test
    fun silenceIsFlatAndLoudSpeechFillsTheBar() {
        assertEquals(0f, WaveformView.levelOf(0f), 0f)
        assertEquals(0f, WaveformView.levelOf(0.001f), 0f) // -60 dB
        assertEquals(1f, WaveformView.levelOf(0.5f), 0f)
    }

    @Test
    fun normalSpeechLandsInTheMiddle() {
        val speech = WaveformView.levelOf(0.03f) // about -30 dB
        assertTrue(speech in 0.5f..0.75f)
    }
}
