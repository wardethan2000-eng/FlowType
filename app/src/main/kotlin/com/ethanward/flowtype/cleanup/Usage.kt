package com.ethanward.flowtype.cleanup

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Tokens one cleanup request used. [estimated]: the request was cut short, or the provider didn't say, so they're our guess. */
data class Usage(val input: Int, val cached: Int, val output: Int, val estimated: Boolean = false)

/**
 * What cleanup costs, from the tokens each request used and the provider's
 * list prices (PLAN §6), for the models whose prices we know. An estimate:
 * the provider's bill is the final word. Other models count tokens only.
 */
object Prices {
    /** Dollars per million tokens: input, cached input, output. */
    data class Rate(val input: Double, val cached: Double, val output: Double)

    // Checked 2026-09-25 (PLAN §11 sources); the fast tier is twice the default.
    // gpt-4.1-nano's cached rate is from memory, unverified.
    // Claude's, 2026-10-01: cached is the cache-read price. Cache writes
    // (2× input for the one-hour cache) aren't told apart, so a first call
    // is undercounted a little.
    private val RATES = mapOf(
        "gpt-6-luna" to Rate(0.10, 0.01, 0.50),
        "gpt-4.1-nano" to Rate(0.10, 0.025, 0.40),
        "claude-haiku-4-5" to Rate(1.00, 0.10, 5.00),
        "claude-sonnet-5-5" to Rate(2.00, 0.20, 10.00),
    )

    /** Null for a model whose prices we don't know. */
    fun rate(config: CleanupConfig): Rate? {
        val base = RATES[config.model] ?: return null
        return if (config.serviceTier == "fast") Rate(base.input * 2, base.cached * 2, base.output * 2) else base
    }

    fun known(config: CleanupConfig) = rate(config) != null

    fun dollars(config: CleanupConfig, u: Usage): Double {
        val r = rate(config) ?: return 0.0
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

    /** [tokens]: input and output together, for models with no known price. */
    data class Totals(val calls: Int, val dollars: Double, val tokens: Long = 0)
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
