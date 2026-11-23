package com.ethanward.flowtype.cleanup

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Daily totals in usage.json (private storage): calls and dollars per day,
 * kept a year. Holds no text.
 */
class UsageStore(context: Context) : UsageSink {
    private val file = File(context.filesDir, "usage.json")

    /** [tokens]: input and output together, for models with no known price. */
    data class Totals(val calls: Int, val dollars: Double, val tokens: Long = 0)
    data class Summary(val today: Totals, val week: Totals, val month: Totals)

    override fun record(config: CleanupConfig, usage: Usage) = record(config, usage, LocalDate.now())

    @Synchronized
    fun record(config: CleanupConfig, usage: Usage, day: LocalDate) {
        val all = read()
        val key = day.toString()
        val d = all.optJSONObject(key) ?: JSONObject()
        d.put("calls", d.optInt("calls") + 1)
        d.put("dollars", d.optDouble("dollars", 0.0) + Prices.dollars(config, usage))
        d.put("input", d.optLong("input") + usage.input)
        d.put("cached", d.optLong("cached") + usage.cached)
        d.put("output", d.optLong("output") + usage.output)
        all.put(key, d)
        val cutoff = day.minusDays(400).toString()
        all.keys().asSequence().toList().filter { it < cutoff }.forEach { all.remove(it) }
        file.writeText(all.toString())
    }

    @Synchronized
    fun summary(today: LocalDate = LocalDate.now()): Summary = summarize(read(), today)

    private fun read(): JSONObject =
        if (!file.exists()) JSONObject() else runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    companion object {
        /** Today; the 7 days ending today; this calendar month. */
        fun summarize(days: JSONObject, today: LocalDate): Summary {
            fun total(from: LocalDate): Totals {
                var calls = 0
                var dollars = 0.0
                var tokens = 0L
                var d = from
                while (!d.isAfter(today)) {
                    days.optJSONObject(d.toString())?.let {
                        calls += it.optInt("calls")
                        dollars += it.optDouble("dollars", 0.0)
                        tokens += it.optLong("input") + it.optLong("output")
                    }
                    d = d.plusDays(1)
                }
                return Totals(calls, dollars, tokens)
            }
            return Summary(total(today), total(today.minusDays(6)), total(today.withDayOfMonth(1)))
        }
    }
}
