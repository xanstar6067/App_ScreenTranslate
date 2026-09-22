package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiEffort
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
 * response schema, and which thinking setting it takes. Gemini 2.5 and 3 think by default, and
 * that is the difference between a Flash model answering in seconds and in tens of seconds, so the
 * level comes from the settings and walks down [AiReasoning.geminiLadder] when a model refuses it.
 *
 * The key travels in the x-goog-api-key header, never as a query parameter: a URL ends up in logs,
 * proxies and crash reports in a way a header does not.
 */
class GeminiClient(private val base: String = "https://generativelanguage.googleapis.com/v1beta") : AiEngine {
    override val provider = AiProvider.GEMINI
    private val http = AiHttp()
    private val transports = ConcurrentHashMap<String, AiTransport>()
    /** Per model and level: the index of the rung the model accepted. */
    private val thinking = ConcurrentHashMap<String, Int>()
    private val json = "application/json".toMediaType()

    override fun usable(models: List<AiModelInfo>) = GeminiModels.textTranslationModels(models)

    override fun describe(model: String): List<String> = listOf(
        when (transports[model]) {
            AiTransport.SCHEMA, null -> "Структурированный вывод: responseSchema"
            AiTransport.OBJECT -> "Структурированный вывод: JSON без схемы"
            AiTransport.PLAIN -> "Структурированный вывод: только разбор текста"
        },
        "Размышления: " + (thinking.entries.firstOrNull { it.key.startsWith("$model|") }?.let { (key, rung) ->
            val effort = AiEffort.valueOf(key.substringAfterLast('|'))
            AiReasoning.describe(AiReasoning.geminiLadder(model, effort).getOrNull(rung))
        } ?: "—"))

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

    override suspend fun translate(token: String, model: String, system: String, user: String, effort: AiEffort): String {
        var transport = transports[model] ?: AiTransport.SCHEMA
        val ladder = AiReasoning.geminiLadder(model, effort)
        val remembered = "$model|${effort.name}"
        var rung = thinking[remembered] ?: 0
        while (true) {
            try {
                val raw = http.fetch(build(token, model, system, user, transport, ladder[rung], search = false, temperature = 0.0))
                transports[model] = transport
                thinking[remembered] = rung
                return AiProtocol.geminiContent(raw)
            } catch (e: AiHttpException) {
                if (e.status !in 400..422) throw e
                when {
                    // Pro models refuse a zero budget, some refuse a level, pre-2.5 know no thinking.
                    ladder[rung] != null && AiReasoning.refusesReasoning(e.reason) && rung < ladder.lastIndex -> rung++
                    transport == AiTransport.SCHEMA -> transport = AiTransport.OBJECT
                    transport == AiTransport.OBJECT -> transport = AiTransport.PLAIN
                    else -> throw e
                }
            }
        }
    }

    /**
     * Grounding with Google Search. Gemini 2.5 refuses to combine it with a JSON response type, so
     * research asks for plain text and [GameResearch] recovers the object from it.
     */
    override suspend fun research(token: String, model: String, system: String, user: String,
                                  effort: AiEffort, search: Boolean): AiAnswer {
        val remarks = mutableListOf<String>()
        var searching = search
        val ladder = AiReasoning.geminiLadder(model, effort)
        var rung = 0
        while (true) {
            try {
                val raw = http.fetch(build(token, model, system, user, AiTransport.PLAIN, ladder[rung],
                    searching, temperature = 0.2), patient = true)
                val answer = AiResearchProtocol.geminiAnswer(raw)
                remarks += "Размышления: " + AiReasoning.describe(ladder[rung])
                if (searching) remarks += "Поиск Google: источников — ${answer.sources.size}"
                return answer.copy(remarks = remarks)
            } catch (e: AiHttpException) {
                if (e.status !in 400..422) throw e
                when {
                    searching && AiReasoning.refusesSearch(e.reason) -> {
                        searching = false
                        remarks += "Модель отказалась от поиска Google — ответ по её собственным знаниям"
                    }
                    ladder[rung] != null && AiReasoning.refusesReasoning(e.reason) && rung < ladder.lastIndex -> rung++
                    else -> throw e
                }
            }
        }
    }

    private fun build(token: String, model: String, system: String, user: String, transport: AiTransport,
                      thinks: GeminiThinking?, search: Boolean, temperature: Double): Request {
        val generation = JSONObject().put("temperature", temperature)
        when (transport) {
            AiTransport.SCHEMA -> generation.put("responseMimeType", "application/json")
                .put("responseSchema", AiProtocol.geminiSchema())
            AiTransport.OBJECT -> generation.put("responseMimeType", "application/json")
            AiTransport.PLAIN -> Unit
        }
        if (thinks != null) generation.put("thinkingConfig", JSONObject().apply {
            thinks.level?.let { put("thinkingLevel", it) }
            thinks.budget?.let { put("thinkingBudget", it) }
        })
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", generation)
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
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
    /** Named for Gemini alone; media and embedding families are common to every provider. */
    private val excluded = listOf("aqa", "veo", "live", "computer-use")

    fun textTranslationModels(models: List<AiModelInfo>): List<AiModelInfo> =
        models.filter { isTextTranslationModel(it) }.distinctBy { it.id }.sortedByDescending { it.id }

    fun isTextTranslationModel(model: AiModelInfo): Boolean {
        val id = model.id.lowercase()
        if (excluded.any { id.contains(it) }) return false
        if (MediaModels.isMedia(model)) return false
        // Methods arrive in outputModalities; an empty list means a gateway that does not report them.
        return model.outputModalities.isEmpty() || model.outputModalities.any { it == "generateContent" }
    }
}
