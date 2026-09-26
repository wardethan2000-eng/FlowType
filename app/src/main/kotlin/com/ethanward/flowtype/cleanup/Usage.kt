package com.ethanward.flowtype.cleanup

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Tokens one OpenAI request used. [estimated]: the request was cut short, so they're our guess. */
data class Usage(val input: Int, val cached: Int, val output: Int, val estimated: Boolean = false)

/**
 * What cleanup costs, from the tokens each request used and OpenAI's list
 * prices (PLAN §6). An estimate: the OpenAI bill is the final word.
 */
object Prices {
    /** Dollars per million tokens: input, cached input, output. */
    data class Rate(val input: Double, val cached: Double, val output: Double)

    // Checked 2026-09-25 (PLAN §11 sources); the fast tier is twice the default.
    // gpt-4.1-nano's cached rate is from memory, unverified.
    private val LUNA = Rate(0.10, 0.01, 0.50)
    private val NANO = Rate(0.10, 0.025, 0.40)

    fun rate(config: CleanupConfig): Rate {
        val base = if (config.model == CleanupConfig.NANO.model) NANO else LUNA
        return if (config.serviceTier == "fast") Rate(base.input * 2, base.cached * 2, base.output * 2) else base
    }

    fun dollars(config: CleanupConfig, u: Usage): Double {
        val r = rate(config)
        val fresh = (u.input - u.cached).coerceAtLeast(0)
        return (fresh * r.input + u.cached * r.cached + u.output * r.output) / 1_000_000.0
    }

    /** "$0.12", "$0.034", "$0.0041", "$0". */
    fun format(dollars: Double): String = when {
        dollars <= 0.0 -> "\$0"
        dollars >= 1 -> "\$%.2f".format(dollars)
        dollars >= 0.01 -> "\$%.3f".format(dollars)
        dollars >= 0.0001 -> "\$%.4f".format(dollars)
        else -> "under \$0.0001"
    }
}

/**
 * Daily totals in usage.json (private storage): calls and dollars per day,
 * kept a year. Holds no text.
 */
class UsageStore(context: Context) {
    private val file = File(context.filesDir, "usage.json")

    data class Totals(val calls: Int, val dollars: Double)
    data class Summary(val today: Totals, val week: Totals, val month: Totals)

    @Synchronized
    fun record(config: CleanupConfig, usage: Usage, day: LocalDate = LocalDate.now()) {
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
                var d = from
                while (!d.isAfter(today)) {
                    days.optJSONObject(d.toString())?.let {
                        calls += it.optInt("calls")
                        dollars += it.optDouble("dollars", 0.0)
                    }
                    d = d.plusDays(1)
                }
                return Totals(calls, dollars)
            }
            return Summary(total(today), total(today.minusDays(6)), total(today.withDayOfMonth(1)))
        }
    }
}
