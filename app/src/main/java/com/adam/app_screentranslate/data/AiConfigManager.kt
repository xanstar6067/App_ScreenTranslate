package com.adam.app_screentranslate.data

import android.content.Context
import com.adam.app_screentranslate.model.AiFallback
import com.adam.app_screentranslate.model.AiModelInfo
import com.adam.app_screentranslate.model.AiProvider
import com.adam.app_screentranslate.model.AiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI configuration: the choices, the cached model listings and the encrypted keys, in their own
 * preferences file so that clearing AI settings never touches the translator's own.
 *
 * Key, chosen model and model list are kept per provider. Switching between Grok and Gemini is then
 * just a switch: what the other provider was set up with is still there when you come back.
 */
class AiConfigManager(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val secure = SecureStore(prefs)

    private val mutable = MutableStateFlow(read())
    val settings = mutable.asStateFlow()

    private val mutableModels = MutableStateFlow(readModels(mutable.value.provider))
    /** The last listing fetched for the active provider, so the dropdown survives a restart. */
    val models = mutableModels.asStateFlow()

    private val mutableToken = MutableStateFlow(secure.has(mutable.value.provider.name))
    val hasToken = mutableToken.asStateFlow()

    fun update(value: AiSettings) {
        val previous = mutable.value
        prefs.edit().putString("provider", value.provider.name)
            .putString(modelKey(value.provider), value.model)
            .putString("fallback", value.fallback.name)
            .putBoolean("repair", value.repair).putString("prompt", value.prompt).apply()
        mutable.value = value
        if (value.provider != previous.provider) {
            // Everything provider-scoped follows the switch, including which model is selected.
            val restored = value.copy(model = prefs.getString(modelKey(value.provider), "") ?: "")
            mutable.value = restored
            mutableModels.value = readModels(value.provider)
            mutableToken.value = secure.has(value.provider.name)
        }
    }

    fun saveModels(provider: AiProvider, models: List<AiModelInfo>) {
        val array = JSONArray()
        models.forEach { model ->
            array.put(JSONObject().put("id", model.id)
                .put("aliases", JSONArray(model.aliases))
                .put("input_modalities", JSONArray(model.inputModalities))
                .put("output_modalities", JSONArray(model.outputModalities))
                .put("max_prompt_length", model.maxPromptLength ?: 0))
        }
        prefs.edit().putString(modelsKey(provider), array.toString()).apply()
        if (provider == mutable.value.provider) mutableModels.value = models
    }

    fun token(): String = secure.token(mutable.value.provider.name)

    fun saveToken(value: String): Boolean {
        val provider = mutable.value.provider
        return secure.save(provider.name, value).also { mutableToken.value = secure.has(provider.name) }
    }

    /** Forgetting a key must also forget which models it could reach. */
    fun clearProvider() {
        val provider = mutable.value.provider
        secure.clear(provider.name)
        prefs.edit().remove(modelsKey(provider)).remove(modelKey(provider)).apply()
        mutable.value = mutable.value.copy(model = "")
        mutableModels.value = emptyList()
        mutableToken.value = false
    }

    private fun read(): AiSettings {
        val provider = AiProvider.entries.firstOrNull { it.name == prefs.getString("provider", null) } ?: AiProvider.XAI
        return AiSettings(
            provider = provider,
            model = prefs.getString(modelKey(provider), "") ?: "",
            fallback = AiFallback.entries.firstOrNull { it.name == prefs.getString("fallback", null) } ?: AiFallback.AUTO,
            repair = prefs.getBoolean("repair", true),
            prompt = prefs.getString("prompt", "game") ?: "game")
    }

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
                    item.optInt("max_prompt_length").takeIf { it > 0 })
            }
        }.getOrDefault(emptyList())
    }

    private fun modelKey(provider: AiProvider) = "model.${provider.name}"
    private fun modelsKey(provider: AiProvider) = "models.${provider.name}"

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
}
