package com.ethanward.flowtype.audio

import android.content.Context
import android.media.AudioAttributes
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

    fun pause() {
        val playing = audio.isMusicActive
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        holdingFocus = granted
        if (!granted && playing) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            sentPause = true
        }
        Trace.event("other_audio_pause", "playing" to playing, "focus" to granted, "mediaKey" to sentPause)
    }

    fun resume() {
        if (holdingFocus) audio.abandonAudioFocusRequest(request)
        if (sentPause) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        holdingFocus = false
        sentPause = false
    }

    private fun mediaKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }
}
