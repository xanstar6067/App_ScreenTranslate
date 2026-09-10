package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiModelInfo
import com.adam.app_screentranslate.model.AiProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Gemini. One endpoint, but two things have to be negotiated per model: whether it accepts a
 * response schema, and whether it lets us switch its thinking off. Gemini 2.5 thinks by default, and
 * that is the difference between a Flash model answering in seconds and in tens of seconds.
 *
 * The key travels in the x-goog-api-key header, never as a query parameter: a URL ends up in logs,
 * proxies and crash reports in a way a header does not.
 */
class GeminiClient(private val base: String = "https://generativelanguage.googleapis.com/v1beta") : AiEngine {
    override val provider = AiProvider.GEMINI
    private val http = AiHttp()
    private val transports = ConcurrentHashMap<String, AiTransport>()
    private val thinking = ConcurrentHashMap<String, Boolean>()
    private val json = "application/json".toMediaType()

    override fun usable(models: List<AiModelInfo>) = GeminiModels.textTranslationModels(models)

    override fun describe(model: String): List<String> = listOf(
        when (transports[model]) {
            AiTransport.SCHEMA, null -> "Структурированный вывод: responseSchema"
            AiTransport.OBJECT -> "Структурированный вывод: JSON без схемы"
            AiTransport.PLAIN -> "Структурированный вывод: только разбор текста"
        },
        if (thinking[model] == true) "Размышления: включены (отключить не удалось)"
        else "Размышления: отключены")

    override suspend fun models(token: String): List<AiModelInfo> {
        val result = mutableListOf<AiModelInfo>()
        var page: String? = null
        // The listing is paginated and the default page is small; a key can see well over fifty.
        for (attempt in 0 until 5) {
            val url = StringBuilder("$base/models?pageSize=200")
            if (page != null) url.append("&pageToken=").append(page)
            val root = obj(http.fetch(Request.Builder().url(url.toString()).header(KEY, token).build()))
            val models = root.optJSONArray("models") ?: break
            for (i in 0 until models.length()) models.optJSONObject(i)?.asModel()?.let { result += it }
            page = root.optString("nextPageToken", "").ifBlank { null } ?: break
        }
        if (result.isEmpty()) throw AiHttpException(0, "Ответ со списком моделей не распознан.")
        return result
    }

    override suspend fun translate(token: String, model: String, system: String, user: String): String {
        var transport = transports[model] ?: AiTransport.SCHEMA
        var thinks = thinking[model] ?: false
        while (true) {
            try {
                val raw = http.fetch(build(token, model, system, user, transport, thinks))
                transports[model] = transport
                thinking[model] = thinks
                return AiProtocol.geminiContent(raw)
            } catch (e: AiHttpException) {
                if (e.status !in 400..422) throw e
                val lower = e.reason.lowercase()
                when {
                    // Pro models refuse a zero budget, and pre-2.5 models know nothing of thinking.
                    !thinks && (lower.contains("thinking") || lower.contains("budget")) -> thinks = true
                    transport == AiTransport.SCHEMA -> transport = AiTransport.OBJECT
                    transport == AiTransport.OBJECT -> transport = AiTransport.PLAIN
                    else -> throw e
                }
            }
        }
    }

    private fun build(token: String, model: String, system: String, user: String,
                      transport: AiTransport, thinks: Boolean): Request {
        val generation = JSONObject().put("temperature", 0)
        when (transport) {
            AiTransport.SCHEMA -> generation.put("responseMimeType", "application/json")
                .put("responseSchema", AiProtocol.geminiSchema())
            AiTransport.OBJECT -> generation.put("responseMimeType", "application/json")
            AiTransport.PLAIN -> Unit
        }
        if (!thinks) generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", generation)
        return Request.Builder().url("$base/models/$model:generateContent").header(KEY, token)
            .post(body.toString().toRequestBody(json)).build()
    }

    private fun obj(raw: String): JSONObject = try { JSONObject(raw) }
        catch (_: Exception) { throw AiHttpException(0, "Ответ сервера не является JSON.") }

    private fun JSONObject.asModel(): AiModelInfo? {
        // The listing names models "models/gemini-2.5-flash"; requests use the bare id.
        val id = optString("name").removePrefix("models/").ifBlank { return null }
        val methods = optJSONArray("supportedGenerationMethods")
        return AiModelInfo(id,
            aliases = listOfNotNull(optString("displayName").ifBlank { null }),
            outputModalities = (0 until (methods?.length() ?: 0)).mapNotNull { methods?.optString(it) },
            maxPromptLength = optInt("inputTokenLimit").takeIf { it > 0 })
    }

    private companion object { const val KEY = "x-goog-api-key" }
}

/**
 * Gemini exposes far more than chat: embeddings, image and video generation, live audio. Only a
 * model that answers generateContent with text can translate a screen.
 */
object GeminiModels {
    private val excluded = listOf("embedding", "embed", "aqa", "imagen", "veo", "tts",
        "image", "audio", "live", "computer-use")

    fun textTranslationModels(models: List<AiModelInfo>): List<AiModelInfo> =
        models.filter { isTextTranslationModel(it) }.distinctBy { it.id }.sortedByDescending { it.id }

    fun isTextTranslationModel(model: AiModelInfo): Boolean {
        val id = model.id.lowercase()
        if (excluded.any { id.contains(it) }) return false
        // Methods arrive in outputModalities; an empty list means a gateway that does not report them.
        return model.outputModalities.isEmpty() || model.outputModalities.any { it == "generateContent" }
    }
}
