package com.adam.app_screentranslate.data

import android.content.Context
import com.adam.app_screentranslate.model.AiEffort
import com.adam.app_screentranslate.model.AiFallback
import com.adam.app_screentranslate.model.AiModelInfo
import com.adam.app_screentranslate.model.AiProvider
import com.adam.app_screentranslate.model.AiRole
import com.adam.app_screentranslate.model.AiSettings
import com.adam.app_screentranslate.model.providerFor
import com.adam.app_screentranslate.model.withModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI configuration: the choices, the cached model listings and the encrypted keys, in their own
 * preferences file so that clearing AI settings never touches the translator's own.
 *
 * Keys and model listings are kept per provider; the chosen model per provider *and* role. Picking
 * Gemini for screen translation therefore brings back the Gemini model translation was last set to,
 * and leaves both the Grok choice and the model the researcher uses exactly where they were.
 */
class AiConfigManager(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val secure = SecureStore(prefs)

    private val mutable = MutableStateFlow(read())
    val settings = mutable.asStateFlow()

    private val mutableModels = MutableStateFlow(AiProvider.entries.associateWith { readModels(it) })
    /** The last listing fetched for each provider, so the dropdowns survive a restart. */
    val models = mutableModels.asStateFlow()

    private val mutableTokens = MutableStateFlow(providersWithToken())
    /** Which providers currently hold a key. Both roles read it, for two different providers. */
    val tokens = mutableTokens.asStateFlow()

    fun update(value: AiSettings) {
        val choice = AiChoices.apply(mutable.value, value, ::remembered)
        val editor = prefs.edit()
        choice.remember.forEach { (role, kept) -> editor.putString(modelKey(role, kept.first), kept.second) }
        editor.putString("provider", value.provider.name)
            .putString("provider.research", value.researchProvider.name)
            .putString("fallback", value.fallback.name)
            .putBoolean("repair", value.repair).putString("prompt", value.prompt)
            .putBoolean("context", value.context).putString("effort", value.effort.name)
            .putString("research.effort", value.researchEffort.name)
            .putBoolean("research.search", value.researchSearch).apply()
        mutable.value = choice.settings
    }

    fun saveModels(provider: AiProvider, models: List<AiModelInfo>) {
        val array = JSONArray()
        models.forEach { model ->
            array.put(JSONObject().put("id", model.id)
                .put("aliases", JSONArray(model.aliases))
                .put("input_modalities", JSONArray(model.inputModalities))
                .put("output_modalities", JSONArray(model.outputModalities))
                .put("max_prompt_length", model.maxPromptLength ?: 0)
                // -1 keeps "the provider states no price" apart from a genuinely free model.
                .put("prompt_price", model.promptPrice ?: -1.0)
                .put("completion_price", model.completionPrice ?: -1.0))
        }
        prefs.edit().putString(modelsKey(provider), array.toString()).apply()
        mutableModels.value = mutableModels.value + (provider to models)
    }

    /** The model [provider] was last set to in [role], or what the single-role settings held. */
    fun remembered(role: AiRole, provider: AiProvider): String =
        prefs.getString(modelKey(role, provider), null)
            ?: prefs.getString(legacyModelKey(provider), "").orEmpty()

    /** Always asked for by provider: the two roles may well be on two different ones. */
    fun token(provider: AiProvider): String = secure.token(provider.name)

    fun saveToken(provider: AiProvider, value: String): Boolean =
        secure.save(provider.name, value).also { mutableTokens.value = providersWithToken() }

    /** Forgetting a key must also forget which models it could reach. */
    fun clearProvider(provider: AiProvider) {
        secure.clear(provider.name)
        val editor = prefs.edit().remove(modelsKey(provider)).remove(legacyModelKey(provider))
        AiRole.entries.forEach { editor.remove(modelKey(it, provider)) }
        editor.apply()
        var next = mutable.value
        AiRole.entries.forEach { if (next.providerFor(it) == provider) next = next.withModel(it, "") }
        mutable.value = next
        mutableModels.value = mutableModels.value + (provider to emptyList())
        mutableTokens.value = providersWithToken()
    }

    private fun providersWithToken() = AiProvider.entries.filter { secure.has(it.name) }.toSet()

    private fun read(): AiSettings {
        val provider = provider("provider")
        val research = AiProvider.entries.firstOrNull { it.name == prefs.getString("provider.research", null) }
        return AiSettings(
            provider = provider,
            model = remembered(AiRole.TRANSLATE, provider),
            // Before the roles were split there was one provider for both; it stays the default.
            researchProvider = research ?: provider,
            researchModel = remembered(AiRole.RESEARCH, research ?: provider),
            fallback = AiFallback.entries.firstOrNull { it.name == prefs.getString("fallback", null) } ?: AiFallback.AUTO,
            repair = prefs.getBoolean("repair", true),
            prompt = prefs.getString("prompt", "game") ?: "game",
            context = prefs.getBoolean("context", true),
            effort = effort(prefs.getString("effort", null), AiEffort.MINIMAL),
            researchEffort = effort(prefs.getString("research.effort", null), AiEffort.MEDIUM),
            researchSearch = prefs.getBoolean("research.search", true))
    }

    private fun provider(key: String) =
        AiProvider.entries.firstOrNull { it.name == prefs.getString(key, null) } ?: AiProvider.XAI

    private fun effort(name: String?, default: AiEffort) = AiEffort.entries.firstOrNull { it.name == name } ?: default

    private fun readModels(provider: AiProvider): List<AiModelInfo> {
        val raw = prefs.getString(modelsKey(provider), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                AiModelInfo(item.optString("id").ifBlank { return@mapNotNull null },
                    item.optJSONArray("aliases").strings(),
                    item.optJSONArray("input_modalities").strings(),
                    item.optJSONArray("output_modalities").strings(),
                    item.optInt("max_prompt_length").takeIf { it > 0 },
                    item.optDouble("prompt_price", -1.0).takeIf { it >= 0 },
                    item.optDouble("completion_price", -1.0).takeIf { it >= 0 })
            }
        }.getOrDefault(emptyList())
    }

    private fun modelKey(role: AiRole, provider: AiProvider) = "model.${role.name}.${provider.name}"
    /** What one provider's single model was stored under before translation and research split. */
    private fun legacyModelKey(provider: AiProvider) = "model.${provider.name}"
    private fun modelsKey(provider: AiProvider) = "models.${provider.name}"

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
}
