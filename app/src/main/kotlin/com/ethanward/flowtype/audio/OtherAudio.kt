package com.ethanward.flowtype.audio

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.ethanward.flowtype.Trace

/**
 * Pauses whatever else is playing (a video, music, a podcast) while Flowtype
 * listens, and lets it resume after.
 *
 * First choice: exclusive transient audio focus, the standard way. Players
 * pause on the loss and resume by themselves when focus is abandoned. If
 * Android refuses focus while something is playing, fall back to a pause media
 * key, and a play key afterwards: only if we sent the pause, so something the
 * user paused themselves is never started.
 */
class OtherAudio(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val uiMode = context.getSystemService(UiModeManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()
    private var holdingFocus = false
    private var sentPause = false

    /** Pauses other audio. True if something was playing. */
    fun pause(): Boolean {
        val playing = audio.isMusicActive
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        holdingFocus = granted
        if (!granted && playing) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            sentPause = true
        }
        Trace.event("other_audio_pause", "playing" to playing, "focus" to granted, "mediaKey" to sentPause)
        return playing
    }

    /**
     * Whether another app's music or video player is still started (fading
     * out, say). Players report their own state, so this goes false the moment
     * one pauses; [AudioManager.isMusicActive] can stay true for seconds after
     * (Chrome keeps its output running: 2026-10-01, never false within 1.5 s).
     */
    fun stillPlaying(): Boolean =
        audio.activePlaybackConfigurations.any { it.audioAttributes.usage in PLAYER_USAGES }

    /** The output's own view: still mixing music, silence included. Traced only, to compare. */
    fun musicActive(): Boolean = audio.isMusicActive

    /** Where music goes now: [Route.FAR] when speakers away from the phone keep playing after it stops. */
    fun route(): Route {
        if (uiMode.currentModeType == Configuration.UI_MODE_TYPE_CAR) return Route.CAR
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val type = runCatching { audio.getAudioDevicesForAttributes(media).firstOrNull()?.type }.getOrNull()
        return routeOf(type)
    }

    fun resume() {
        if (holdingFocus) audio.abandonAudioFocusRequest(request)
        if (sentPause) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        holdingFocus = false
        sentPause = false
    }

    enum class Route(val far: Boolean) { SPEAKER(false), WIRED(false), BLUETOOTH(true), CAR(true), OTHER(true) }

    companion object {
        private val PLAYER_USAGES = setOf(
            AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN,
        )

        fun routeOf(deviceType: Int?): Route = when (deviceType) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> Route.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET -> Route.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> Route.BLUETOOTH
            AudioDeviceInfo.TYPE_BUS -> Route.CAR
            // Unknown (HDMI, a USB DAC, no answer): assume speakers that run on.
            else -> Route.OTHER
        }
    }

    private fun mediaKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }
}
