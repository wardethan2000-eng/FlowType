package com.ethanward.flowtype.insert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsertionRecordTest {
    @Test
    fun roundTripsThroughTheLogLine() {
        val r = InsertionRecord(1_700_000_000_000, "com.google.android.gm", 0x20001, "dictation", 42, Outcome.VERIFIED, 12, 1)
        assertEquals(r, InsertionRecord.parse(r.toLine()))
    }

    @Test
    fun ignoresBrokenLines() {
        assertNull(InsertionRecord.parse("garbage"))
        assertNull(InsertionRecord.parse("1\ta\t0x1\tdictation\t3\tNOPE\t1\t0"))
    }
}
