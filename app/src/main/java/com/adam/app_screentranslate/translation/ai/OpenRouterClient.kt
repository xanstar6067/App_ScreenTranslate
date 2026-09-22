package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiEffort
import com.adam.app_screentranslate.model.AiModelInfo
import com.adam.app_screentranslate.model.AiProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenRouter: one key in front of hundreds of models from every vendor. The API is OpenAI-shaped,
 * so the translation envelope is the same as xAI's chat completions, but two things differ enough
 * to matter.
 *
 * What a model accepts cannot be told from its id here — "anthropic/claude-…" and "openai/gpt-…"
 * sit in the same list with different parameters — so nothing is assumed: the structured-output and
 * the reasoning ladders are walked for every model alike, and what a model accepted is remembered
 * for the rest of the session. OpenRouter itself drops parameters a model does not know, which
 * makes the first rung succeed far more often than it would against a vendor's own API.
 *
 * Web search is not the model's own: OpenRouter runs it beside any model as the "web" plugin and
 * returns the pages it used as url_citation annotations.
 */
class OpenRouterClient(private val base: String = "https://openrouter.ai/api/v1") : AiEngine {
    override val provider = AiProvider.OPENROUTER
    private val http = AiHttp()
    private val transports = ConcurrentHashMap<String, AiTransport>()
    /** Per model and level: the index of the rung of [AiReasoning.routerLadder] the model accepted. */
    private val reasonings = ConcurrentHashMap<String, Int>()
    private val json = "application/json".toMediaType()

    override fun usable(models: List<AiModelInfo>) = OpenRouterModels.textTranslationModels(models)

    override fun describe(model: String): List<String> = listOf(
        when (transports[model]) {
            AiTransport.SCHEMA, null -> "Структурированный вывод: json_schema"
            AiTransport.OBJECT -> "Структурированный вывод: json_object (схема не поддержана)"
            AiTransport.PLAIN -> "Структурированный вывод: только разбор текста"
        },
        "Рассуждение: " + (reasonings.entries.firstOrNull { it.key.startsWith("$model|") }?.let { (key, rung) ->
            val effort = AiEffort.valueOf(key.substringAfterLast('|'))
            AiReasoning.describe(AiReasoning.routerLadder(model, effort).getOrNull(rung))
        } ?: "—"))

    override suspend fun models(token: String): List<AiModelInfo> {
        val data = obj(http.fetch(Request.Builder().url("$base/models").header(AUTH, "Bearer $token").build()))
            .optJSONArray("data") ?: throw AiHttpException(0, "Ответ со списком моделей не распознан.")
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.asModel() }
    }

    override suspend fun translate(token: String, model: String, system: String, user: String, effort: AiEffort): String {
        var transport = transports[model] ?: AiTransport.SCHEMA
        val ladder = AiReasoning.routerLadder(model, effort)
        val remembered = "$model|${effort.name}"
        var rung = reasonings[remembered] ?: 0
        while (true) {
            try {
                val raw = http.fetch(request(token, body(model, system, user, transport, ladder[rung],
                    search = false, temperature = 0.0)))
                transports[model] = transport
                reasonings[remembered] = rung
                return AiProtocol.chatContent(guard(raw))
            } catch (e: AiHttpException) {
                if (e.status !in 400..422) throw e
                when {
                    ladder[rung] != null && AiReasoning.refusesReasoning(e.reason) && rung < ladder.lastIndex -> rung++
                    transport == AiTransport.SCHEMA -> transport = AiTransport.OBJECT
                    transport == AiTransport.OBJECT -> transport = AiTransport.PLAIN
                    else -> throw e
                }
            }
        }
    }

    /**
     * Research asks for plain text and lets [GameResearch] recover the object: the web plugin and a
     * response schema together are refused by part of the catalogue, and a search is worth more
     * here than a guaranteed shape.
     */
    override suspend fun research(token: String, model: String, system: String, user: String,
                                  effort: AiEffort, search: Boolean, limit: Int,
                                  onProgress: (suspend (AiProgress) -> Unit)?): AiAnswer {
        val remarks = mutableListOf<String>()
        var searching = search
        var streaming = onProgress != null
        val ladder = AiReasoning.routerLadder(model, effort)
        var rung = 0
        while (true) {
            try {
                val body = body(model, system, user, AiTransport.PLAIN, ladder[rung], searching,
                    temperature = 0.2, limit = limit, stream = streaming)
                val answer = if (streaming) {
                    val reader = RouterStreamReader()
                    http.stream(request(token, body), patient = true) { event ->
                        reader.event(event)?.let { onProgress!!(it) }
                    }
                    reader.answer()
                } else AiResearchProtocol.routerAnswer(guard(http.fetch(request(token, body), patient = true)))
                remarks += "Рассуждение: " + AiReasoning.describe(ladder[rung])
                if (searching) remarks += "Веб-поиск: источников — ${answer.sources.size}" +
                    (if (limit > 0) " (ограничение $limit)" else "")
                return answer.copy(remarks = remarks)
            } catch (e: AiFormatException) {
                // A stream whose shape we do not know yields nothing. Losing the preview is far
                // better than losing the answer, so the same request goes again unstreamed.
                if (!streaming) throw e
                streaming = false
                remarks += "Потоковый ответ не распознан — запрос повторён без предпросмотра"
            } catch (e: AiHttpException) {
                if (e.status !in 400..422) throw e
                when {
                    streaming && AiStreaming.refused(e.reason) -> streaming = false
                    searching && AiReasoning.refusesSearch(e.reason) -> {
                        searching = false
                        remarks += "Модель отказалась от веб-поиска — ответ по её собственным знаниям"
                    }
                    ladder[rung] != null && AiReasoning.refusesReasoning(e.reason) && rung < ladder.lastIndex -> rung++
                    else -> throw e
                }
            }
        }
    }

    private fun request(token: String, body: JSONObject) = Request.Builder().url("$base/chat/completions")
        .header(AUTH, "Bearer $token").post(body.toString().toRequestBody(json)).build()

    private fun body(model: String, system: String, user: String, transport: AiTransport,
                     reasoning: RouterReasoning?, search: Boolean, temperature: Double,
                     limit: Int = 0, stream: Boolean = false): JSONObject {
        val body = JSONObject().put("model", model).put("stream", stream).put("temperature", temperature)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (reasoning != null) body.put("reasoning", JSONObject().apply {
            reasoning.effort?.let { put("effort", it) }
            reasoning.enabled?.let { put("enabled", it) }
            // The thinking itself is never read here; paying to transfer it only slows the screen.
            if (reasoning.enabled != false) put("exclude", true)
        })
        when (transport) {
            AiTransport.SCHEMA -> body.put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", AiProtocol.SCHEMA_NAME)
                    .put("strict", true).put("schema", AiProtocol.schema())))
            AiTransport.OBJECT -> body.put("response_format", JSONObject().put("type", "json_object"))
            AiTransport.PLAIN -> Unit
        }
        // No hidden cap: the router's own default applies unless the user set a limit.
        if (search) body.put("plugins", JSONArray().put(JSONObject().put("id", "web")
            .apply { if (limit > 0) put("max_results", limit) }))
        return body
    }

    private fun obj(raw: String): JSONObject = try { JSONObject(raw) }
        catch (_: Exception) { throw AiHttpException(0, "Ответ сервера не является JSON.") }

    /**
     * A provider behind the router can fail after the router itself answered 200, and then the
     * error arrives inside the body. Turning it back into an [AiHttpException] is what lets the
     * ladders and the fallback translator see it for what it is.
     */
    private fun guard(raw: String): String {
        val error = obj(raw).optJSONObject("error") ?: return raw
        val status = error.optInt("code", 0).takeIf { it in 100..599 } ?: 0
        throw AiHttpException(status, error.optString("message").ifBlank { "Провайдер не ответил." }.take(200))
    }

    private fun JSONObject.asModel(): AiModelInfo? {
        val id = optString("id").ifBlank { return null }
        val architecture = optJSONObject("architecture")
        val pricing = optJSONObject("pricing")
        return AiModelInfo(id,
            aliases = listOfNotNull(optString("name").ifBlank { null }),
            inputModalities = architecture?.optJSONArray("input_modalities").strings(),
            outputModalities = architecture?.optJSONArray("output_modalities").strings(),
            maxPromptLength = optInt("context_length").takeIf { it > 0 },
            promptPrice = pricing.price("prompt"), completionPrice = pricing.price("completion"))
    }

    /**
     * The catalogue prices tokens one at a time, as decimal strings — "0.000003" is $3 per million —
     * and a free model says "0". A missing field is a price nobody stated, which is not the same.
     */
    private fun JSONObject?.price(field: String): Double? =
        this?.optString(field)?.ifBlank { null }?.toDoubleOrNull()?.times(1_000_000)

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    private companion object { const val AUTH = "Authorization" }
}

/**
 * The catalogue holds everything the router can reach, generators and embedders included, and it
 * is long enough that one unusable entry in the dropdown is a request that can never succeed.
 * Unlike the vendors' own listings it is sorted by id: "anthropic/…", "google/…", "openai/…" put a
 * vendor's models together, which is the only order that makes hundreds of them readable.
 */
object OpenRouterModels {
    /** Named for the router alone; media and embedding families are common to every provider. */
    private val excluded = listOf("sdxl", "sd3", "playground-v", "pollinations")

    fun textTranslationModels(models: List<AiModelInfo>): List<AiModelInfo> =
        models.filter { isTextTranslationModel(it) }.distinctBy { it.id }.sortedBy { it.id }

    fun isTextTranslationModel(model: AiModelInfo): Boolean {
        val id = model.id.lowercase(Locale.ROOT)
        if (excluded.any { id.contains(it) }) return false
        return MediaModels.isText(model)
    }
}
