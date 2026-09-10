package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiModelInfo
import com.adam.app_screentranslate.model.Box
import com.adam.app_screentranslate.model.ScreenTextBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A failed xAI call. [reason] is for the user and may quote the server; [message] stays free of it,
 * because a message is what ends up in a log and the server echoes what we sent.
 */
class XaiException(val status: Int, val reason: String) : IOException("xAI request failed ($status)")

/** One line of the connection report. */
data class AiCheckLine(val ok: Boolean, val text: String)

/** The one call the translator makes. Kept separate so orchestration is testable without a network. */
interface AiEngine {
    suspend fun translate(token: String, model: String, system: String, user: String): String
}

/**
 * Everything Lenslate asks of xAI: the model list and one non-streaming completion. The overlay
 * draws only finished text, so there is no reason to carry the streaming machinery.
 *
 * xAI serves text through two different APIs and the newer models answer only on the newer one, so
 * the endpoint is discovered rather than assumed: each model is tried on its likely API first and
 * on the other one if that is refused, and what worked is remembered for the rest of the session.
 */
class XaiClient(private val base: String = "https://api.x.ai/v1") : AiEngine {
    /** How structured output is requested. A model that refuses one rung falls to the next. */
    enum class Transport { SCHEMA, OBJECT, PLAIN }

    // A reasoning model thinks before it answers, and the whole screen waits for it. These are far
    // above what a web translator needs; below them a normal packet was being cut off mid-thought.
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    private val transports = ConcurrentHashMap<String, Transport>()
    private val responsesApi = ConcurrentHashMap<String, Boolean>()
    private val json = "application/json".toMediaType()

    /** What the last successful call used, for the connection report. */
    fun transportOf(model: String): Transport? = transports[model]
    fun endpointOf(model: String): String? = responsesApi[model]?.let { if (it) "/responses" else "/chat/completions" }

    suspend fun models(token: String): List<AiModelInfo> {
        val language = try { fetch(get(token, "language-models")) } catch (e: CancellationException) { throw e }
            catch (e: XaiException) { if (e.status in listOf(401, 403)) throw e else null }
            catch (_: IOException) { null }
        if (language != null) {
            val models = obj(language).optJSONArray("models")
            if (models != null) return (0 until models.length()).mapNotNull { models.optJSONObject(it)?.asModel() }
        }
        // Older and self-hosted xAI-compatible gateways only answer the minimal listing.
        val data = obj(fetch(get(token, "models"))).optJSONArray("data")
            ?: throw XaiException(0, "Ответ со списком моделей не распознан.")
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.asModel() }
    }

    override suspend fun translate(token: String, model: String, system: String, user: String): String {
        var last: XaiException? = null
        for (viaResponses in responsesApi[model]?.let { listOf(it) } ?: endpointOrder(model)) {
            var transport = transports[model] ?: Transport.SCHEMA
            while (true) {
                try {
                    val raw = fetch(build(token, model, system, user, transport, viaResponses))
                    transports[model] = transport
                    responsesApi[model] = viaResponses
                    return if (viaResponses) AiProtocol.responsesContent(raw) else AiProtocol.chatContent(raw)
                } catch (e: XaiException) {
                    last = e
                    if (!e.refusedStructure()) break
                    transport = when (transport) {
                        Transport.SCHEMA -> Transport.OBJECT
                        Transport.OBJECT -> Transport.PLAIN
                        Transport.PLAIN -> break
                    }
                }
            }
        }
        throw last ?: XaiException(0, "Запрос не выполнен.")
    }

    /**
     * A ping proves nothing. Only sending the real schema to the chosen model shows whether that
     * model can answer in the shape the overlay needs, so the check sends it.
     */
    suspend fun check(token: String, model: String): Pair<List<AiCheckLine>, List<AiModelInfo>> {
        val report = mutableListOf<AiCheckLine>()
        val available = try {
            models(token).also { report += AiCheckLine(true, "API доступен, ключ действителен") }
        } catch (e: CancellationException) { throw e }
        catch (e: XaiException) {
            report += AiCheckLine(false, when (e.status) {
                401, 403 -> "${e.status} — токен отклонён"
                429 -> "429 — лимит запросов исчерпан"
                in 500..599 -> "${e.status} — сервер xAI недоступен"
                else -> e.reason
            })
            return report to emptyList()
        } catch (_: IOException) {
            report += AiCheckLine(false, "Сеть недоступна")
            return report to emptyList()
        }
        val usable = XaiModels.textTranslationModels(available)
        report += AiCheckLine(usable.isNotEmpty(), "Текстовых моделей: ${usable.size}")
        if (model.isBlank()) {
            report += AiCheckLine(false, "Модель не выбрана")
            return report to usable
        }
        if (usable.none { it.id == model }) {
            report += AiCheckLine(false, "Модель $model недоступна этому ключу")
            return report to usable
        }
        report += AiCheckLine(true, "Модель $model доступна")
        try {
            val answer = translate(token, model,
                AiPrompts.system(AiPrompts.DEFAULT, "en", "ru", repair = true),
                AiProtocol.payload(PROBE, "en", "ru"))
            AiProtocol.validate(AiProtocol.parse(answer), PROBE, allowMerge = true)
            report += AiCheckLine(true, "Эндпоинт: ${endpointOf(model)}")
            report += AiCheckLine(true, when (transports[model]) {
                Transport.SCHEMA, null -> "Структурированный вывод: json_schema"
                Transport.OBJECT -> "Структурированный вывод: json_object (схема не поддержана)"
                Transport.PLAIN -> "Структурированный вывод: только разбор текста"
            })
        } catch (e: CancellationException) { throw e }
        catch (e: AiFormatException) { report += AiCheckLine(false, "Модель ответила вне схемы: ${e.reason}") }
        catch (e: XaiException) { report += AiCheckLine(false, "Пробный запрос отклонён (${e.status}): ${e.reason}") }
        catch (_: IOException) { report += AiCheckLine(false, "Пробный запрос не дошёл") }
        catch (e: Exception) { report += AiCheckLine(false, "Сбой пробного запроса: ${e.javaClass.simpleName}") }
        return report to usable
    }

    private fun endpointOrder(model: String): List<Boolean> =
        if (prefersResponsesApi(model)) listOf(true, false) else listOf(false, true)

    private fun build(token: String, model: String, system: String, user: String,
                      transport: Transport, viaResponses: Boolean): Request {
        val body = if (viaResponses) responsesBody(model, system, user, transport)
        else completionBody(model, system, user, transport)
        return Request.Builder().url(if (viaResponses) "$base/responses" else "$base/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(json)).build()
    }

    private fun completionBody(model: String, system: String, user: String, transport: Transport): JSONObject {
        val body = JSONObject().put("model", model).put("temperature", 0).put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        when (transport) {
            Transport.SCHEMA -> body.put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", AiProtocol.SCHEMA_NAME)
                    .put("strict", true).put("schema", AiProtocol.schema())))
            Transport.OBJECT -> body.put("response_format", JSONObject().put("type", "json_object"))
            Transport.PLAIN -> Unit
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
    private fun responsesBody(model: String, system: String, user: String, transport: Transport): JSONObject {
        val body = JSONObject().put("model", model).put("stream", false).put("temperature", 0)
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("input", JSONArray().put(input("system", system)).put(input("user", user)))
        when (transport) {
            Transport.SCHEMA -> body.put("text", JSONObject().put("format", JSONObject()
                .put("type", "json_schema").put("name", AiProtocol.SCHEMA_NAME)
                .put("strict", true).put("schema", AiProtocol.schema())))
            Transport.OBJECT -> body.put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
            Transport.PLAIN -> Unit
        }
        return body
    }

    private fun input(role: String, text: String) = JSONObject().put("role", role)
        .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))

    private fun get(token: String, path: String) =
        Request.Builder().url("$base/$path").header("Authorization", "Bearer $token").build()

    private fun obj(raw: String): JSONObject = try { JSONObject(raw) }
        catch (_: Exception) { throw XaiException(0, "Ответ сервера не является JSON.") }

    private suspend fun fetch(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw XaiException(it.code, describe(it.code, body))
                        if (body.isBlank()) throw XaiException(it.code, "Пустой ответ сервера.")
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    /**
     * The server message is the only thing that explains a 400, so it is shown. It can echo the
     * request, which is why it reaches the screen and never a log — see invariant 15.
     */
    private fun describe(status: Int, body: String): String {
        val server = runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")?.ifBlank { null }
                ?: root.optString("error").ifBlank { null } ?: root.optString("message").ifBlank { null }
        }.getOrNull()?.take(200) ?: body.take(200).ifBlank { null }
        return when (status) {
            401, 403 -> "Неверный или отозванный API token"
            429 -> "Исчерпан лимит запросов"
            in 500..599 -> "Сервер xAI недоступен ($status)"
            else -> server ?: "HTTP $status"
        }
    }

    /** Only a complaint about the answer format earns a step down the transport ladder. */
    private fun XaiException.refusedStructure(): Boolean {
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

        /** Two blocks of one broken sentence: exactly what the schema exists to put back together. */
        private val PROBE = listOf(
            ScreenTextBlock(1, "Compensation o", Box(0f, 0f, 100f, 20f)),
            ScreenTextBlock(2, "f maintenance", Box(0f, 20f, 100f, 40f)))
    }
}
