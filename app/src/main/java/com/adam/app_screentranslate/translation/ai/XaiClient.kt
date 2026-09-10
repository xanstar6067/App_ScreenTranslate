package com.adam.app_screentranslate.translation.ai

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
    private val json = "application/json".toMediaType()

    override fun usable(models: List<AiModelInfo>) = XaiModels.textTranslationModels(models)

    override fun describe(model: String): List<String> = listOf(
        "Эндпоинт: " + (responsesApi[model]?.let { if (it) "/responses" else "/chat/completions" } ?: "—"),
        when (transports[model]) {
            AiTransport.SCHEMA, null -> "Структурированный вывод: json_schema"
            AiTransport.OBJECT -> "Структурированный вывод: json_object (схема не поддержана)"
            AiTransport.PLAIN -> "Структурированный вывод: только разбор текста"
        })

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

    override suspend fun translate(token: String, model: String, system: String, user: String): String {
        var last: AiHttpException? = null
        for (viaResponses in responsesApi[model]?.let { listOf(it) } ?: endpointOrder(model)) {
            var transport = transports[model] ?: AiTransport.SCHEMA
            while (true) {
                try {
                    val raw = http.fetch(build(token, model, system, user, transport, viaResponses))
                    transports[model] = transport
                    responsesApi[model] = viaResponses
                    return if (viaResponses) AiProtocol.responsesContent(raw) else AiProtocol.chatContent(raw)
                } catch (e: AiHttpException) {
                    last = e
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

    private fun build(token: String, model: String, system: String, user: String,
                      transport: AiTransport, viaResponses: Boolean): Request {
        val body = if (viaResponses) responsesBody(model, system, user, transport)
        else completionBody(model, system, user, transport)
        return Request.Builder().url(if (viaResponses) "$base/responses" else "$base/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(json)).build()
    }

    private fun completionBody(model: String, system: String, user: String, transport: AiTransport): JSONObject {
        val body = JSONObject().put("model", model).put("temperature", 0).put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
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
     * Reasoning effort is pinned low on purpose. It defaults to high, and a grok-4 model then spends
     * thousands of tokens deliberating over a line of interface text — seconds of latency per packet
     * on a screen the user is waiting to read, for a task that needs care, not deliberation.
     */
    private fun responsesBody(model: String, system: String, user: String, transport: AiTransport): JSONObject {
        val body = JSONObject().put("model", model).put("stream", false).put("temperature", 0)
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("input", JSONArray().put(input("system", system)).put(input("user", user)))
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
            optInt("max_prompt_length").takeIf { it > 0 })
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    companion object {
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
