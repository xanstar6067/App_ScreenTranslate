package com.adam.app_screentranslate.data

import android.content.Context
import com.adam.app_screentranslate.model.AiFallback
import com.adam.app_screentranslate.model.AiModelInfo
import com.adam.app_screentranslate.model.AiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * xAI configuration: the choices, the cached model listing, and the encrypted token, all in their
 * own preferences file so that clearing AI settings never touches the translator's own.
 */
class AiConfigManager(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val secure = SecureStore(prefs)

    private val mutable = MutableStateFlow(AiSettings(
        model = prefs.getString("model", "") ?: "",
        fallback = AiFallback.entries.firstOrNull { it.name == prefs.getString("fallback", null) } ?: AiFallback.AUTO,
        repair = prefs.getBoolean("repair", true),
        prompt = prefs.getString("prompt", "game") ?: "game"))
    val settings = mutable.asStateFlow()

    private val mutableModels = MutableStateFlow(readModels())
    /** The last listing the user fetched, so the dropdown survives a restart without a request. */
    val models = mutableModels.asStateFlow()

    private val mutableToken = MutableStateFlow(secure.has())
    val hasToken = mutableToken.asStateFlow()

    fun update(value: AiSettings) {
        prefs.edit().putString("model", value.model).putString("fallback", value.fallback.name)
            .putBoolean("repair", value.repair).putString("prompt", value.prompt).apply()
        mutable.value = value
    }

    fun saveModels(models: List<AiModelInfo>) {
        val array = JSONArray()
        models.forEach { model ->
            array.put(JSONObject().put("id", model.id)
                .put("aliases", JSONArray(model.aliases))
                .put("input_modalities", JSONArray(model.inputModalities))
                .put("output_modalities", JSONArray(model.outputModalities))
                .put("max_prompt_length", model.maxPromptLength ?: 0))
        }
        prefs.edit().putString("models", array.toString()).apply()
        mutableModels.value = models
    }

    fun token(): String = secure.token()

    fun saveToken(value: String): Boolean =
        secure.save(value).also { mutableToken.value = secure.has() }

    fun clearToken() { secure.clear(); mutableToken.value = false }

    /** Forgetting the key must also forget which model it could reach. */
    fun clearAll() {
        prefs.edit().clear().apply()
        mutable.value = AiSettings()
        mutableModels.value = emptyList()
        mutableToken.value = false
    }

    private fun readModels(): List<AiModelInfo> {
        val raw = prefs.getString("models", null) ?: return emptyList()
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

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
}
