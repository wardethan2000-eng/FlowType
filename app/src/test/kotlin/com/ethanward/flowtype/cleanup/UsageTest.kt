package com.ethanward.flowtype.cleanup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UsageTest {
    @Test
    fun pricesATypicalCleanup() {
        // 1,300 prompt tokens, 1,200 of them cached, 40 out, on Luna's default tier:
        // 100 × $0.10 + 1,200 × $0.01 + 40 × $0.50, per million.
        val u = Usage(1300, 1200, 40)
        assertEquals(0.000042, Prices.dollars(CleanupConfig.LUNA, u), 1e-12)
        assertEquals(0.000084, Prices.dollars(CleanupConfig.LUNA_FAST, u), 1e-12)
        assertEquals(0.000056, Prices.dollars(CleanupConfig.NANO, u), 1e-12)
    }

    @Test
    fun formatsSmallAmountsReadably() {
        assertEquals("\$0", Prices.format(0.0))
        assertEquals("under \$0.0001", Prices.format(0.00004))
        assertEquals("\$0.0041", Prices.format(0.0041))
        assertEquals("\$0.034", Prices.format(0.0341))
        assertEquals("\$1.25", Prices.format(1.25))
    }

    @Test
    fun todayWeekAndMonth() {
        fun day(calls: Int, dollars: Double) = JSONObject().put("calls", calls).put("dollars", dollars)
        val days = JSONObject()
            .put("2026-09-26", day(10, 0.001))
            .put("2026-09-21", day(5, 0.002)) // inside the 7 days
            .put("2026-09-19", day(1, 0.004)) // 8 days back: month only
            .put("2026-08-31", day(1, 0.5)) // last month
        val s = UsageStore.summarize(days, LocalDate.parse("2026-09-26"))
        assertEquals(10, s.today.calls)
        assertEquals(0.003, s.week.dollars, 1e-12)
        assertEquals(16, s.month.calls)
        assertEquals(0.007, s.month.dollars, 1e-12)
    }

    @Test
    fun readsUsageFromAnAnswer() {
        val r = JSONObject("""{"usage":{"input_tokens":120,"input_tokens_details":{"cached_tokens":0},"output_tokens":6}}""")
        assertEquals(Usage(120, 0, 6), NoteTitler.usageOf(r))
    }
}
