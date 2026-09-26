package com.ethanward.flowtype.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV in and out. Everything inside the app is 16 kHz mono floats. */
object Wav {
    const val RATE = 16_000

    fun encode(samples: ShortArray, rate: Int = RATE): ByteArray {
        val data = samples.size * 2
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(data)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    fun write(file: File, samples: ShortArray) = file.writeBytes(encode(samples))

    fun read(file: File): FloatArray = decode(file.readBytes())

    /**
     * Reads 16-bit PCM, mono or stereo (averaged), at any rate (linearly
     * resampled to 16 kHz). Throws on anything else.
     */
    fun decode(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "not a WAV file"
        }
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = buf.getShort(body).toInt()
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(format == 1 && bits == 16 && channels in 1..2) {
                        "need 16-bit PCM mono/stereo, got format=$format bits=$bits channels=$channels"
                    }
                    val end = minOf(bytes.size, body + size)
                    val frames = (end - body) / (2 * channels)
                    val out = FloatArray(frames)
                    for (i in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) sum += buf.getShort(body + (i * channels + c) * 2)
                        out[i] = sum / channels / 32768f
                    }
                    return if (rate == RATE) out else resample(out, rate, RATE)
                }
            }
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("WAV has no data chunk")
    }

    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val n = (input.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val x = i * step
            val j = x.toInt()
            val f = (x - j).toFloat()
            val a = input[minOf(j, input.size - 1)]
            val b = input[minOf(j + 1, input.size - 1)]
            out[i] = a + (b - a) * f
        }
        return out
    }

    fun toFloats(samples: ShortArray) = FloatArray(samples.size) { samples[it] / 32768f }

    /** For tests and in-app recordings that are built in pieces. */
    fun concat(parts: List<ShortArray>): ShortArray {
        val result = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(result, at)
            at += p.size
        }
        return result
    }
}
