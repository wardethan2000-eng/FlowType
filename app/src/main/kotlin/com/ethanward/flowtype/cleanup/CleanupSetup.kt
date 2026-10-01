package com.ethanward.flowtype.cleanup

import android.content.Context
import com.ethanward.flowtype.Prefs

/**
 * The cleanup the settings choose right now (LAUNCH §A2): which provider,
 * which model, where requests go and the saved key. Read fresh for each
 * dictation, so a change in settings takes effect on the next one.
 */
class CleanupSetup(
    val preset: ProviderPreset,
    val config: CleanupConfig,
    /** Null when "your own server" has no address yet. */
    val baseUrl: String?,
    /** The saved key; null when there's none (fine for a server that needs none). */
    val key: String?,
) {
    /** It can run: an address, a model, and a key unless this server needs none. */
    val ready: Boolean get() = baseUrl != null && config.model.isNotEmpty() && (key != null || preset.keyOptional)

    /** What [Cleaner] sends as the key: blank for a server that takes none. */
    val keyToSend: String? get() = key ?: if (preset.keyOptional) "" else null

    fun provider(): CleanupProvider = Providers.provider(preset, baseUrl ?: preset.baseUrl.orEmpty())

    /** "Luna, fast tier", "claude-haiku-4-5": for summaries. */
    val modelLabel: String get() = config.label

    companion object {
        /**
         * The same as `current(…).ready`, without decrypting the key: cheap
         * enough for the main thread when a dictation starts.
         */
        fun looksReady(context: Context, prefs: Prefs = Prefs(context)): Boolean {
            val preset = Providers.byId(prefs.cleanupProvider)
            if (Providers.baseUrl(preset, prefs.customServer) == null) return false
            val model = if (preset.protocol == Protocol.OPENAI_RESPONSES) "set"
            else prefs.providerModel(preset.id) ?: preset.suggestedModel.orEmpty()
            return model.isNotEmpty() && (preset.keyOptional || ApiKeyStore(context, preset.id).has())
        }

        fun current(context: Context, prefs: Prefs = Prefs(context)): CleanupSetup {
            val preset = Providers.byId(prefs.cleanupProvider)
            val config = if (preset.protocol == Protocol.OPENAI_RESPONSES) {
                CleanupConfig.byId(prefs.cleanupModel)
            } else {
                CleanupConfig.of(preset, prefs.providerModel(preset.id) ?: preset.suggestedModel.orEmpty())
            }
            val key = ApiKeyStore(context, preset.id).load()?.takeIf { it.isNotEmpty() }
            return CleanupSetup(preset, config, Providers.baseUrl(preset, prefs.customServer), key)
        }
    }
}
