package com.ethanward.flowtype.cleanup

/** How a provider is spoken to (LAUNCH §A1). */
enum class Protocol { OPENAI_RESPONSES, CHAT_COMPLETIONS, ANTHROPIC }

/**
 * A provider people can pick for "your own AI" (LAUNCH §A3). The
 * subscription doesn't use these: it always runs the model we choose.
 */
data class ProviderPreset(
    /** Stored in settings, and names the provider's key file. Never change one. */
    val id: String,
    val label: String,
    val protocol: Protocol,
    /** Where requests go; null for "your own server", where the user types it. */
    val baseUrl: String?,
    /** Preselected when the provider's model list has it. Null: the user picks. */
    val suggestedModel: String?,
    /** Where to get a key; null when there's nowhere to send people. */
    val keyUrl: String?,
    /** What this provider's keys start with, to catch a key pasted under the wrong provider. Null: no check. */
    val keyPrefix: String?,
    /** A server on your own computer may need no key at all. */
    val keyOptional: Boolean = false,
    /** One line under the name. */
    val blurb: String,
)

object Providers {
    val OPENAI = ProviderPreset(
        "openai", "OpenAI", Protocol.OPENAI_RESPONSES, "https://api.openai.com", null,
        "https://platform.openai.com/api-keys", "sk-",
        blurb = "Luna on the fast tier: the quickest and cheapest we've measured.",
    )
    val ANTHROPIC = ProviderPreset(
        "anthropic", "Anthropic (Claude)", Protocol.ANTHROPIC, AnthropicMessages.URL, "claude-haiku-4-5",
        "https://console.anthropic.com/settings/keys", "sk-ant-",
        blurb = "Claude. Haiku costs about ten times what Luna does; Sonnet more.",
    )
    val GEMINI = ProviderPreset(
        "gemini", "Google Gemini", Protocol.CHAT_COMPLETIONS, "https://generativelanguage.googleapis.com/v1beta/openai", null,
        "https://aistudio.google.com/apikey", "AIza",
        blurb = "Gemini, with a free tier.",
    )
    val GROQ = ProviderPreset(
        "groq", "Groq", Protocol.CHAT_COMPLETIONS, "https://api.groq.com/openai/v1", null,
        "https://console.groq.com/keys", "gsk_",
        blurb = "Open models on very fast hardware, with a free tier.",
    )
    val OPENROUTER = ProviderPreset(
        "openrouter", "OpenRouter", Protocol.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1", null,
        "https://openrouter.ai/keys", "sk-or-",
        blurb = "One key for hundreds of models from many companies.",
    )
    val CUSTOM = ProviderPreset(
        "custom", "Your own server", Protocol.CHAT_COMPLETIONS, null, null,
        null, null, keyOptional = true,
        blurb = "Anything that speaks OpenAI's chat format: Ollama or LM Studio on your computer, or a server you run.",
    )

    val ALL = listOf(OPENAI, ANTHROPIC, GEMINI, GROQ, OPENROUTER, CUSTOM)
    val DEFAULT = OPENAI

    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** Null if [key] is fine for [preset], else what's wrong, for the screen. */
    fun problemWithKey(preset: ProviderPreset, key: String): String? = when {
        key.isEmpty() && preset.keyOptional -> null
        key.length < 10 -> "That's too short to be a key."
        preset.keyPrefix != null && !key.startsWith(preset.keyPrefix) ->
            "That doesn't look like a ${preset.label} key. Theirs start with ${preset.keyPrefix}"
        else -> null
    }

    /**
     * The address requests go to. "Your own server" takes what the user
     * typed: a bare `http://192.168.1.5:11434` gets Ollama's `/v1`.
     */
    fun baseUrl(preset: ProviderPreset, typed: String?): String? {
        preset.baseUrl?.let { return it }
        val t = typed?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if ("://" in t) t else "http://$t"
        return if (withScheme.substringAfter("://").contains('/')) withScheme else "$withScheme/v1"
    }

    /** One instance per address, kept, so each keeps its warm connection. */
    private val made = HashMap<String, CleanupProvider>()

    @Synchronized
    fun provider(preset: ProviderPreset, baseUrl: String): CleanupProvider =
        made.getOrPut("${preset.protocol}|$baseUrl") {
            when (preset.protocol) {
                Protocol.OPENAI_RESPONSES -> OpenAiResponses()
                Protocol.CHAT_COMPLETIONS -> ChatCompletions(baseUrl)
                Protocol.ANTHROPIC -> AnthropicMessages(baseUrl)
            }
        }
}
