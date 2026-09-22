package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiEffort
import com.adam.app_screentranslate.model.AiModelInfo
import com.adam.app_screentranslate.model.AiProvider
import com.adam.app_screentranslate.model.Box
import com.adam.app_screentranslate.model.ScreenTextBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A failed call to an AI provider. [reason] is for the user and may quote the server; [message]
 * stays free of it, because a message is what ends up in a log and the server echoes what we sent.
 */
class AiHttpException(val status: Int, val reason: String) : IOException("AI request failed ($status)")

/** One line of the connection report. */
data class AiCheckLine(val ok: Boolean, val text: String)

/** How structured output is asked for. A model that refuses one rung is retried on the next. */
enum class AiTransport { SCHEMA, OBJECT, PLAIN }

/**
 * One AI provider behind one interface. The translator only ever calls [translate]; the rest exists
 * so the settings screen can list models and explain what a model actually negotiated.
 */
interface AiEngine {
    val provider: AiProvider
    suspend fun models(token: String): List<AiModelInfo>
    suspend fun translate(token: String, model: String, system: String, user: String,
                          effort: AiEffort = AiEffort.MINIMAL): String
    /**
     * A free-form request with the provider's own web search when [search] is set. Used to fill a
     * game profile, where a slow careful answer is worth waiting for. A model that refuses search
     * or the reasoning level is asked again without it, and the answer says so in its remarks.
     *
     * [limit] caps the pages the provider's search may use; 0 leaves that to the provider. Not
     * every provider accepts a cap, and one that refuses it is asked again without it rather than
     * without search.
     *
     * [onProgress] is called as the answer arrives, so a request that runs for minutes can be
     * watched. The request streams only when a caller asks for progress.
     */
    suspend fun research(token: String, model: String, system: String, user: String,
                         effort: AiEffort, search: Boolean, limit: Int = 0,
                         onProgress: (suspend (AiProgress) -> Unit)? = null): AiAnswer
    /** Keeps only models that can translate text — no image, video, audio or embedding models. */
    fun usable(models: List<AiModelInfo>): List<AiModelInfo>
    /** What the last successful call to [model] negotiated, for the connection report. */
    fun describe(model: String): List<String>
}

/** The one place a provider becomes a client. Everything else speaks [AiEngine]. */
object AiEngines {
    fun create(provider: AiProvider): AiEngine = when (provider) {
        AiProvider.XAI -> XaiClient()
        AiProvider.GEMINI -> GeminiClient()
        AiProvider.OPENROUTER -> OpenRouterClient()
    }
}

/**
 * Shared HTTP for every provider. Timeouts are far above what a web translator needs: a reasoning
 * model thinks before it answers and the whole screen waits for it.
 */
internal class AiHttp {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    /** Research searches the web and may think at length before the first byte of the answer. */
    private val patientClient = client.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS).callTimeout(360, TimeUnit.SECONDS).build()

    /**
     * Server-sent events, one payload at a time. The call is made synchronously because the caller
     * is already off the main thread and the body has to be read as it arrives; cancelling the
     * coroutine cancels the call, which is what unblocks that read.
     *
     * Comment lines are keep-alives — OpenRouter sends them for minutes while a model thinks — and
     * are dropped here so that no reader has to know about them.
     */
    suspend fun stream(request: Request, patient: Boolean = false, onEvent: suspend (String) -> Unit) {
        val call = (if (patient) patientClient else client).newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            response.use {
                val body = it.body ?: throw AiHttpException(it.code, "Пустой ответ сервера.")
                if (!it.isSuccessful) throw AiHttpException(it.code, describe(it.code, body.string()))
                val source = body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank() || line.startsWith(":")) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == DONE) break
                    onEvent(payload)
                }
            }
        } catch (e: IOException) {
            // Cancelling the coroutine cancels the call, which fails the read. The cancellation is
            // the real reason, and the caller must see it as one rather than as a dead network.
            currentCoroutineContext().ensureActive()
            throw e
        } finally { cancellation.dispose() }
    }

    suspend fun fetch(request: Request, patient: Boolean = false): String = suspendCancellableCoroutine { continuation ->
        val call = (if (patient) patientClient else client).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw AiHttpException(it.code, describe(it.code, body))
                        if (body.isBlank()) throw AiHttpException(it.code, "Пустой ответ сервера.")
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
    private companion object { const val DONE = "[DONE]" }

    private fun describe(status: Int, body: String): String {
        val server = runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")?.ifBlank { null }
                ?: root.optString("error").ifBlank { null } ?: root.optString("message").ifBlank { null }
        }.getOrNull()?.take(200) ?: body.take(200).ifBlank { null }
        return when (status) {
            401, 403 -> "Неверный или отозванный API-ключ"
            429 -> "Исчерпан лимит запросов"
            in 500..599 -> "Сервер провайдера недоступен ($status)"
            else -> server ?: "HTTP $status"
        }
    }
}

/**
 * The connection report. A ping proves nothing: only sending the real schema to the chosen model
 * shows whether that model can answer in the shape the overlay needs, so the check sends it.
 */
object AiConnection {
    /** Two blocks of one broken sentence: exactly what the schema exists to put back together. */
    private val PROBE = listOf(
        ScreenTextBlock(1, "Compensation o", Box(0f, 0f, 100f, 20f)),
        ScreenTextBlock(2, "f maintenance", Box(0f, 20f, 100f, 40f)))

    suspend fun check(engine: AiEngine, token: String, model: String,
              effort: AiEffort = AiEffort.MINIMAL): Pair<List<AiCheckLine>, List<AiModelInfo>> {
        val report = mutableListOf<AiCheckLine>()
        if (token.isBlank()) {
            report += AiCheckLine(false, "Ключ ${engine.provider.label} не задан")
            return report to emptyList()
        }
        val available = try {
            engine.models(token).also { report += AiCheckLine(true, "API доступен, ключ действителен") }
        } catch (e: CancellationException) { throw e }
        catch (e: AiHttpException) {
            report += AiCheckLine(false, when (e.status) {
                401, 403 -> "${e.status} — ключ отклонён"
                429 -> "429 — лимит запросов исчерпан"
                in 500..599 -> "${e.status} — сервер недоступен"
                else -> e.reason
            })
            return report to emptyList()
        } catch (_: IOException) {
            report += AiCheckLine(false, "Сеть недоступна")
            return report to emptyList()
        } catch (e: Exception) {
            report += AiCheckLine(false, "Сбой запроса моделей: ${e.javaClass.simpleName}")
            return report to emptyList()
        }
        val usable = engine.usable(available)
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
            val answer = engine.translate(token, model,
                AiPrompts.system(AiPrompts.DEFAULT, "English", "Russian", repair = true),
                AiProtocol.payload(PROBE, "English", "Russian"), effort)
            AiProtocol.validate(AiProtocol.parse(answer), PROBE, allowMerge = true)
            engine.describe(model).forEach { report += AiCheckLine(true, it) }
        } catch (e: CancellationException) { throw e }
        catch (e: AiFormatException) { report += AiCheckLine(false, "Модель ответила вне схемы: ${e.reason}") }
        catch (e: AiHttpException) { report += AiCheckLine(false, "Пробный запрос отклонён (${e.status}): ${e.reason}") }
        catch (_: IOException) { report += AiCheckLine(false, "Пробный запрос не дошёл") }
        catch (e: Exception) { report += AiCheckLine(false, "Сбой пробного запроса: ${e.javaClass.simpleName}") }
        return report to usable
    }
}
