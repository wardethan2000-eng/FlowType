package com.ethanward.flowtype.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test
    fun roundTripsMono16k() {
        val pcm = shortArrayOf(0, 16384, -16384, 32767, -32768)
        val back = Wav.decode(Wav.encode(pcm))
        assertEquals(pcm.size, back.size)
        assertEquals(0.5f, back[1], 1e-6f)
        assertEquals(-1f, back[4], 1e-6f)
    }

    @Test
    fun averagesStereoAndResamples48k() {
        val frames = 4800 // 0.1 s at 48 kHz
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { data.putShort(16384).putShort(0) }
        val bytes = header(channels = 2, rate = 48_000, dataBytes = frames * 4) + data.array()
        val out = Wav.decode(bytes)
        assertEquals(1600, out.size) // 0.1 s at 16 kHz
        assertEquals(0.25f, out[800], 1e-4f)
    }

    @Test
    fun skipsUnknownChunks() {
        val pcm = Wav.encode(shortArrayOf(1000, 2000))
        // Insert a LIST chunk between fmt and data.
        val list = "LIST".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array() + byteArrayOf(1, 2, 3, 0)
        val bytes = pcm.copyOfRange(0, 36) + list + pcm.copyOfRange(36, pcm.size)
        assertEquals(2, Wav.decode(bytes).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonWav() {
        Wav.decode(ByteArray(64))
    }

    @Test
    fun concatKeepsOrder() {
        val joined = Wav.concat(listOf(shortArrayOf(1, 2), shortArrayOf(), shortArrayOf(3)))
        assertTrue(joined.contentEquals(shortArrayOf(1, 2, 3)))
    }

    private fun header(channels: Int, rate: Int, dataBytes: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }
}
