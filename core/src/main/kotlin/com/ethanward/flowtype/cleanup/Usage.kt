package com.ethanward.flowtype.cleanup

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
        // 2026-10-24: up to a 100K-token prompt; ours never comes near it.
        "claude-haiku-5-5" to Rate(0.10, 0.01, 0.50),
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

/** Where [Cleaner] reports each request's tokens; the app keeps daily totals. */
fun interface UsageSink {
    fun record(config: CleanupConfig, usage: Usage)
}
