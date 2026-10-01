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
        // Haiku: 100 × $1 + 1,200 × $0.10 + 40 × $5, per million.
        assertEquals(0.00042, Prices.dollars(CleanupConfig.of(Providers.ANTHROPIC, "claude-haiku-4-5"), u), 1e-12)
        // Unknown prices count as nothing, and say so.
        val groq = CleanupConfig.of(Providers.GROQ, "some-open-model")
        assertEquals(0.0, Prices.dollars(groq, u), 0.0)
        assertEquals(false, Prices.known(groq))
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
}
