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
 */
class XaiClient(private val base: String = "https://api.x.ai/v1") : AiEngine {
    /** How structured output is requested. A model that rejects one rung falls to the next. */
    enum class Transport { SCHEMA, OBJECT, PLAIN }

    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    private val transports = ConcurrentHashMap<String, Transport>()
    private val json = "application/json".toMediaType()

    suspend fun models(token: String): List<AiModelInfo> {
        val language = try { fetch(get(token, "language-models")) } catch (e: CancellationException) { throw e }
            catch (e: XaiException) { if (e.status in listOf(401, 403)) throw e else null }
            catch (_: IOException) { null }
        if (language != null) {
            val models = JSONObject(language).optJSONArray("models")
            if (models != null) return (0 until models.length()).mapNotNull { models.optJSONObject(it)?.asModel() }
        }
        // Older and self-hosted xAI-compatible gateways only answer the minimal listing.
        val data = JSONObject(fetch(get(token, "models"))).optJSONArray("data")
            ?: throw XaiException(0, "Ответ со списком моделей не распознан.")
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.asModel() }
    }

    override suspend fun translate(token: String, model: String, system: String, user: String): String {
        var transport = transports[model] ?: Transport.SCHEMA
        while (true) {
            try {
                val text = fetch(completion(token, model, system, user, transport))
                transports[model] = transport
                return content(text)
            } catch (e: XaiException) {
                val next = when {
                    !e.rejectedStructure() -> throw e
                    transport == Transport.SCHEMA -> Transport.OBJECT
                    transport == Transport.OBJECT -> Transport.PLAIN
                    else -> throw e
                }
                transport = next
            }
        }
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
            report += AiCheckLine(true, when (transports[model]) {
                Transport.SCHEMA, null -> "Структурированный вывод: json_schema"
                Transport.OBJECT -> "Структурированный вывод: json_object (схема не поддержана)"
                Transport.PLAIN -> "Структурированный вывод: только разбор текста"
            })
        } catch (e: CancellationException) { throw e }
        catch (e: AiFormatException) { report += AiCheckLine(false, "Модель ответила вне схемы: ${e.reason}") }
        catch (e: XaiException) { report += AiCheckLine(false, "Пробный запрос отклонён: ${e.reason}") }
        catch (_: IOException) { report += AiCheckLine(false, "Пробный запрос не дошёл") }
        return report to usable
    }

    private fun get(token: String, path: String) =
        Request.Builder().url("$base/$path").header("Authorization", "Bearer $token").build()

    private fun completion(token: String, model: String, system: String, user: String, transport: Transport): Request {
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
        return Request.Builder().url("$base/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(json)).build()
    }

    private fun content(raw: String): String {
        val choices = JSONObject(raw).optJSONArray("choices")
            ?: throw XaiException(0, "Ответ без choices.")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw XaiException(0, "Ответ без message.")
        return message.optString("content", "").ifBlank { throw XaiException(0, "Модель вернула пустой ответ.") }
    }

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

    private fun describe(status: Int, body: String): String {
        val server = runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")?.ifBlank { null }
                ?: root.optString("error").ifBlank { null } ?: root.optString("message").ifBlank { null }
        }.getOrNull()?.take(200)
        return when (status) {
            401, 403 -> "Неверный или отозванный API token"
            404 -> server ?: "Эндпоинт или модель не найдены"
            429 -> "Исчерпан лимит запросов"
            in 500..599 -> "Сервер xAI недоступен ($status)"
            else -> server ?: "HTTP $status"
        }
    }

    /** Only a complaint about the answer format earns a step down the transport ladder. */
    private fun XaiException.rejectedStructure(): Boolean {
        if (status !in 400..422) return false
        val lower = reason.lowercase()
        return listOf("response_format", "json_schema", "schema", "structured", "json").any { lower.contains(it) }
    }

    private fun JSONObject.asModel(): AiModelInfo? {
        val id = optString("id").ifBlank { return null }
        return AiModelInfo(id, optJSONArray("aliases").strings(),
            optJSONArray("input_modalities").strings(), optJSONArray("output_modalities").strings(),
            optInt("max_prompt_length").takeIf { it > 0 })
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    private companion object {
        /** Two blocks of one broken sentence: exactly what the schema exists to put back together. */
        val PROBE = listOf(
            ScreenTextBlock(1, "Compensation o", Box(0f, 0f, 100f, 20f)),
            ScreenTextBlock(2, "f maintenance", Box(0f, 20f, 100f, 40f)))
    }
}
