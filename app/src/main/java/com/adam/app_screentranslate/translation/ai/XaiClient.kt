package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiEffort
import com.adam.app_screentranslate.model.AiModelInfo
import com.adam.app_screentranslate.model.AiProvider
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * xAI. Two APIs serve text and the newer models answer on the newer one, so the endpoint is
 * discovered rather than assumed: each model is tried on its likely API first and on the other one
 * if that is refused, and what worked is remembered for the rest of the session.
 */
class XaiClient(private val base: String = "https://api.x.ai/v1") : AiEngine {
    override val provider = AiProvider.XAI
    private val http = AiHttp()
    private val transports = ConcurrentHashMap<String, AiTransport>()
    private val responsesApi = ConcurrentHashMap<String, Boolean>()
    /** Per model and level: which rung of [AiReasoning.xaiLadder] the model accepted. */
    private val efforts = ConcurrentHashMap<String, String>()
    private val json = "application/json".toMediaType()

    override fun usable(models: List<AiModelInfo>) = XaiModels.textTranslationModels(models)

    override fun describe(model: String): List<String> = listOf(
        "Эндпоинт: " + (responsesApi[model]?.let { if (it) "/responses" else "/chat/completions" } ?: "—"),
        when (transports[model]) {
            AiTransport.SCHEMA, null -> "Структурированный вывод: json_schema"
            AiTransport.OBJECT -> "Структурированный вывод: json_object (схема не поддержана)"
            AiTransport.PLAIN -> "Структурированный вывод: только разбор текста"
        },
        "Рассуждение: " + (efforts.entries.firstOrNull { it.key.startsWith("$model|") }?.value
            ?.let { if (it == NONE) "по умолчанию модели" else it } ?: "—"))

    override suspend fun models(token: String): List<AiModelInfo> {
        val language = try { http.fetch(get(token, "language-models")) } catch (e: CancellationException) { throw e }
            catch (e: AiHttpException) { if (e.status in listOf(401, 403)) throw e else null }
            catch (_: IOException) { null }
        if (language != null) {
            val models = obj(language).optJSONArray("models")
            if (models != null) return (0 until models.length()).mapNotNull { models.optJSONObject(it)?.asModel() }
        }
        // Older and self-hosted xAI-compatible gateways only answer the minimal listing.
        val data = obj(http.fetch(get(token, "models"))).optJSONArray("data")
            ?: throw AiHttpException(0, "Ответ со списком моделей не распознан.")
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.asModel() }
    }

    override suspend fun translate(token: String, model: String, system: String, user: String, effort: AiEffort): String {
        var last: AiHttpException? = null
        val ladder = AiReasoning.xaiLadder(model, effort)
        val remembered = "$model|${effort.name}"
        for (viaResponses in responsesApi[model]?.let { listOf(it) } ?: endpointOrder(model)) {
            var transport = transports[model] ?: AiTransport.SCHEMA
            var rung = efforts[remembered]?.let { value -> ladder.indexOfFirst { (it ?: NONE) == value }.coerceAtLeast(0) } ?: 0
            while (true) {
                try {
                    val raw = http.fetch(build(token, model, system, user, transport, viaResponses, ladder[rung]))
                    transports[model] = transport
                    responsesApi[model] = viaResponses
                    efforts[remembered] = ladder[rung] ?: NONE
                    return if (viaResponses) AiProtocol.responsesContent(raw) else AiProtocol.chatContent(raw)
                } catch (e: AiHttpException) {
                    last = e
                    // The other endpoint answers a refused key, a spent limit or an outage the same way.
                    if (e.status in listOf(401, 403, 429) || e.status >= 500) throw e
                    // A model without the reasoning parameter is asked again with the next rung.
                    if (e.status in 400..422 && ladder[rung] != null && AiReasoning.refusesReasoning(e.reason)
                        && rung < ladder.lastIndex) { rung++; continue }
                    if (!e.refusedStructure()) break
                    transport = when (transport) {
                        AiTransport.SCHEMA -> AiTransport.OBJECT
                        AiTransport.OBJECT -> AiTransport.PLAIN
                        AiTransport.PLAIN -> break
                    }
                }
            }
        }
        throw last ?: AiHttpException(0, "Запрос не выполнен.")
    }

    private fun endpointOrder(model: String): List<Boolean> =
        if (prefersResponsesApi(model)) listOf(true, false) else listOf(false, true)

    /**
     * Research always goes to the Responses API when search is wanted: xAI's server-side
     * web_search tool lives only there. Without search the endpoint the model answered on before
     * is reused. The answer is free text; [GameResearch] recovers the JSON from it.
     */
    override suspend fun research(token: String, model: String, system: String, user: String,
                                  effort: AiEffort, search: Boolean, limit: Int,
                                  onProgress: (suspend (AiProgress) -> Unit)?): AiAnswer {
        val remarks = mutableListOf<String>()
        var searching = search
        var capped = limit > 0
        var streaming = onProgress != null
        val ladder = AiReasoning.xaiLadder(model, effort)
        var rung = 0
        val endpoints = if (search) listOf(true) else responsesApi[model]?.let { listOf(it) } ?: endpointOrder(model)
        var last: AiHttpException? = null
        for (viaResponses in endpoints) {
            while (true) {
                try {
                    val body = if (viaResponses) responsesBody(model, system, user, AiTransport.PLAIN, ladder[rung])
                        else completionBody(model, system, user, AiTransport.PLAIN, ladder[rung])
                    body.put("temperature", 0.2).put("stream", streaming)
                    if (searching) {
                        val tool = JSONObject().put("type", "web_search")
                        if (capped) tool.put("max_search_results", limit)
                        body.put("tools", JSONArray().put(tool))
                    }
                    val answer = if (streaming) {
                        val reader = XaiStreamReader()
                        http.stream(request(token, viaResponses, body), patient = true) { event ->
                            reader.event(event)?.let { onProgress!!(it) }
                        }
                        reader.answer()
                    } else AiResearchProtocol.xaiAnswer(http.fetch(request(token, viaResponses, body), patient = true))
                    responsesApi[model] = viaResponses
                    remarks += "Рассуждение: " + (ladder[rung] ?: "по умолчанию модели")
                    if (searching) remarks += "Веб-поиск: источников — ${answer.sources.size}" +
                        (if (capped) " (ограничение $limit)" else "")
                    return answer.copy(remarks = remarks)
                } catch (e: AiFormatException) {
                    // A stream whose shape we do not know yields nothing. Losing the preview is far
                    // better than losing the answer, so the same request goes again unstreamed.
                    if (!streaming) throw e
                    streaming = false
                    remarks += "Потоковый ответ не распознан — запрос повторён без предпросмотра"
                } catch (e: AiHttpException) {
                    last = e
                    if (e.status in listOf(401, 403, 429) || e.status !in 400..422) throw e
                    when {
                        // Each of these is dropped on its own, weakest first: losing the cap or the
                        // live preview costs nothing, losing the search costs the whole point.
                        streaming && AiStreaming.refused(e.reason) -> streaming = false
                        capped && AiStreaming.refusedLimit(e.reason) -> {
                            capped = false
                            remarks += "Провайдер не принял ограничение числа источников"
                        }
                        searching && AiReasoning.refusesSearch(e.reason) -> {
                            searching = false
                            remarks += "Модель отказалась от веб-поиска — ответ по её собственным знаниям"
                        }
                        ladder[rung] != null && AiReasoning.refusesReasoning(e.reason) && rung < ladder.lastIndex -> rung++
                        search -> throw e
                        else -> break
                    }
                }
            }
        }
        throw last ?: AiHttpException(0, "Запрос не выполнен.")
    }

    private fun build(token: String, model: String, system: String, user: String,
                      transport: AiTransport, viaResponses: Boolean, effort: String?): Request {
        val body = if (viaResponses) responsesBody(model, system, user, transport, effort)
        else completionBody(model, system, user, transport, effort)
        return request(token, viaResponses, body)
    }

    private fun request(token: String, viaResponses: Boolean, body: JSONObject): Request {
        return Request.Builder().url(if (viaResponses) "$base/responses" else "$base/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(json)).build()
    }

    private fun completionBody(model: String, system: String, user: String, transport: AiTransport,
                               effort: String?): JSONObject {
        val body = JSONObject().put("model", model).put("temperature", 0).put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (effort != null) body.put("reasoning_effort", effort)
        when (transport) {
            AiTransport.SCHEMA -> body.put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", AiProtocol.SCHEMA_NAME)
                    .put("strict", true).put("schema", AiProtocol.schema())))
            AiTransport.OBJECT -> body.put("response_format", JSONObject().put("type", "json_object"))
            AiTransport.PLAIN -> Unit
        }
        return body
    }

    /**
     * The Responses API flattens the schema into text.format and takes messages as typed content.
     *
     * Reasoning effort comes from the settings and is low for translation by default. The server
     * default is high, and a grok-4 model then spends thousands of tokens deliberating over a line
     * of interface text — seconds of latency per packet on a screen the user is waiting to read.
     */
    private fun responsesBody(model: String, system: String, user: String, transport: AiTransport,
                              effort: String?): JSONObject {
        val body = JSONObject().put("model", model).put("stream", false).put("temperature", 0)
            .put("input", JSONArray().put(input("system", system)).put(input("user", user)))
        if (effort != null) body.put("reasoning", JSONObject().put("effort", effort))
        when (transport) {
            AiTransport.SCHEMA -> body.put("text", JSONObject().put("format", JSONObject()
                .put("type", "json_schema").put("name", AiProtocol.SCHEMA_NAME)
                .put("strict", true).put("schema", AiProtocol.schema())))
            AiTransport.OBJECT -> body.put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
            AiTransport.PLAIN -> Unit
        }
        return body
    }

    private fun input(role: String, text: String) = JSONObject().put("role", role)
        .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))

    private fun get(token: String, path: String) =
        Request.Builder().url("$base/$path").header("Authorization", "Bearer $token").build()

    private fun obj(raw: String): JSONObject = try { JSONObject(raw) }
        catch (_: Exception) { throw AiHttpException(0, "Ответ сервера не является JSON.") }

    /** Only a complaint about the answer format earns a step down the transport ladder. */
    private fun AiHttpException.refusedStructure(): Boolean {
        if (status !in 400..422) return false
        val lower = reason.lowercase()
        return listOf("response_format", "json_schema", "schema", "structured", "format", "json").any { lower.contains(it) }
    }

    private fun JSONObject.asModel(): AiModelInfo? {
        val id = optString("id").ifBlank { return null }
        return AiModelInfo(id, optJSONArray("aliases").strings(),
            optJSONArray("input_modalities").strings(), optJSONArray("output_modalities").strings(),
            optInt("max_prompt_length").takeIf { it > 0 },
            price("prompt_text_token_price"), price("completion_text_token_price"))
    }

    /**
     * The language-models listing prices tokens in an integer unit of its own: grok-3 comes back as
     * 30000 / 150000 for its published $3 / $15 per million, so the unit is a ten-thousandth of a
     * dollar per million tokens. The minimal /models listing carries no prices, and 0 there means
     * "not stated" rather than "free" — hence the null.
     */
    private fun JSONObject.price(field: String): Double? =
        optDouble(field, 0.0).takeIf { it > 0 }?.div(PRICE_UNITS_PER_DOLLAR)

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    companion object {
        private const val NONE = "none"
        private const val PRICE_UNITS_PER_DOLLAR = 10_000.0

        /**
         * grok-4 and newer answer on the Responses API; older families on chat completions. Read by
         * hand rather than by pattern — Android's regex engine is stricter than the desktop JVM the
         * tests run on, and a model id is not worth that risk.
         */
        fun prefersResponsesApi(model: String): Boolean {
            val lower = model.lowercase()
            if (!lower.startsWith("grok-")) return false
            val major = lower.removePrefix("grok-").takeWhile { it.isDigit() }.toIntOrNull() ?: return false
            return major >= 4
        }
    }
}
