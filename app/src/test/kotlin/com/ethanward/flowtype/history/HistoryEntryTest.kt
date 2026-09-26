package com.ethanward.flowtype.history

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryEntryTest {
    private val day = 86_400_000L
    private val now = 100 * day

    private fun e(at: Long) = HistoryEntry(at, "app", "raw", "typed", "cleaned", "VERIFIED", 900)

    @Test
    fun keepsOnlyTheRetentionWindow() {
        val kept = HistoryEntry.prune(listOf(e(now - 8 * day), e(now - 6 * day), e(now)), days = 7, now = now)
        assertEquals(listOf(now - 6 * day, now), kept.map { it.at })
    }

    @Test
    fun keepsTheNewestFifty() {
        val kept = HistoryEntry.prune((1..60).map { e(now - it * 1000L) }, days = 7, now = now)
        assertEquals(50, kept.size)
        assertEquals(now - 1000L, kept.last().at)
    }

    @Test
    fun offKeepsNothing() = assertEquals(emptyList<HistoryEntry>(), HistoryEntry.prune(listOf(e(now)), 0, now))

    @Test
    fun roundTrips() {
        val x = HistoryEntry(5, "com.whatsapp", "um hi", "Hi", "cleaned", "VERIFIED", 1234)
        assertEquals(x, HistoryEntry.fromJson(JSONObject(x.toJson().toString())))
    }
}
