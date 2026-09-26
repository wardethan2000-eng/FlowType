package com.ethanward.flowtype.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/**
 * 16 kHz mono 16-bit capture from the VOICE_RECOGNITION source (PLAN §4.2).
 * Audio stays in memory; nothing is written unless a bench screen asks.
 */
class AudioCapture(
    private val onLevel: ((Float) -> Unit)? = null,
    /** Each 30 ms frame as it arrives, on the mic thread (the array is the caller's to keep). */
    private val onFrame: ((ShortArray) -> Unit)? = null,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val chunks = ArrayList<ShortArray>()

    /** Caller must hold RECORD_AUDIO. Returns false if the mic can't open. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        check(!running) { "already recording" }
        val min = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, Wav.RATE), // bytes: at least 0.5 s
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            return false
        }
        synchronized(chunks) { chunks.clear() }
        record = r
        running = true
        thread = Thread({ loop(r) }, "flowtype-mic").also { it.start() }
        return true
    }

    private fun loop(r: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(Wav.RATE * 30 / 1000) // 30 ms
        while (running) {
            val n = r.read(frame, 0, frame.size)
            if (n < 0) break
            if (n == 0) continue
            val copy = frame.copyOf(n)
            synchronized(chunks) { chunks.add(copy) }
            onLevel?.invoke(SignalStats.rms(frame, n))
            onFrame?.invoke(copy)
        }
    }

    /** Stops and returns everything recorded since start(). */
    fun stop(): ShortArray {
        running = false
        thread?.join(1000)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        return synchronized(chunks) { Wav.concat(chunks).also { chunks.clear() } }
    }

    val isRecording: Boolean get() = running
}
