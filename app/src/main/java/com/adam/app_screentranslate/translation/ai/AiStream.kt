package com.adam.app_screentranslate.translation.ai

import org.json.JSONObject

/**
 * A long request, watched while it runs. Filling a game profile can take minutes — searching,
 * thinking, then writing — and without this the screen can only show a clock and hope.
 *
 * [text] is what has been written so far, whole, not a delta: the page shows its tail. [note] is
 * what the model is doing right now, when the provider says so.
 */
data class AiProgress(val text: String = "", val note: String? = null)

/**
 * Reading a 4xx that is about how the request was made rather than about what it asked for. Both
 * of these are dropped before the search is: a lost preview or a lost cap costs the user nothing,
 * a lost search costs them the answer.
 */
object AiStreaming {
    fun refused(reason: String): Boolean {
        val lower = reason.lowercase(java.util.Locale.ROOT)
        return listOf("stream", "sse", "event-stream").any { lower.contains(it) }
    }

    /** Named before the search itself is blamed: "max_search_results" also contains "search". */
    fun refusedLimit(reason: String): Boolean {
        val lower = reason.lowercase(java.util.Locale.ROOT)
        return listOf("max_search_results", "max_results", "max search", "search_parameters").any { lower.contains(it) }
    }
}

/** What the provider says it is busy with. The wording is the user's, not the API's. */
object AiStage {
    const val SEARCHING = "Ищет в сети…"
    const val THINKING = "Размышляет…"
    const val WRITING = "Пишет ответ…"
}

/**
 * One provider's event stream folded into an answer. Every provider streams a different shape, and
 * all three shapes still move, so a reader ignores what it does not recognize rather than failing:
 * a missed event costs a line of preview, a thrown one costs the whole request.
 *
 * Pure Kotlin, so the shapes are covered by JVM tests like the rest of the wire contract.
 */
interface AiStreamReader {
    /** One SSE payload. Returns what to show, or null when the event carries nothing to show. */
    fun event(raw: String): AiProgress?
    /** Everything seen so far, as a finished answer. */
    fun answer(): AiAnswer
}

/** Text is accumulated; sources are collected wherever a provider happens to mention them. */
internal abstract class BaseStreamReader : AiStreamReader {
    protected val text = StringBuilder()
    protected val sources = mutableListOf<AiSource>()

    protected fun json(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

    protected fun write(delta: String?): AiProgress? {
        if (delta.isNullOrEmpty()) return null
        text.append(delta)
        return AiProgress(text.toString(), AiStage.WRITING)
    }

    override fun answer(): AiAnswer = AiAnswer(
        text.toString().ifBlank { throw AiFormatException("The stream carried no answer.") },
        sources.distinctBy { it.url })
}

/**
 * xAI. The Responses API narrates itself — searches, reasoning and text arrive as typed events —
 * and closes with the whole response object, which is where the citations are. Chat completions
 * only ever send text deltas, so both shapes are read by one reader.
 */
internal class XaiStreamReader : BaseStreamReader() {
    private var completed: String? = null

    override fun event(raw: String): AiProgress? {
        val root = json(raw) ?: return null
        if (root.has("choices")) return write(root.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("delta")?.optString("content"))
        val type = root.optString("type")
        return when {
            type.endsWith("output_text.delta") -> write(root.optString("delta"))
            type.contains("web_search") -> AiProgress(text.toString(), AiStage.SEARCHING)
            type.contains("reasoning") -> AiProgress(text.toString(), AiStage.THINKING)
            type == "response.completed" -> {
                completed = root.optJSONObject("response")?.toString()
                null
            }
            else -> null
        }
    }

    override fun answer(): AiAnswer {
        // The closing object is the only place the searched pages are listed in full.
        completed?.let { whole ->
            runCatching { AiResearchProtocol.xaiAnswer(whole) }.getOrNull()?.let { finished ->
                return AiAnswer(text.toString().ifBlank { finished.text }, finished.sources)
            }
        }
        return super.answer()
    }
}

/**
 * Gemini. Every event is a whole GenerateContentResponse over a slice of the answer: thinking
 * arrives as parts marked `thought`, and the grounding metadata builds up across events. A refusal
 * still comes as a finishReason rather than an error, so it is caught here as it is in the
 * unstreamed envelope.
 */
internal class GeminiStreamReader : BaseStreamReader() {
    private var refusal: String? = null

    override fun event(raw: String): AiProgress? {
        val root = json(raw) ?: return null
        root.optJSONObject("promptFeedback")?.optString("blockReason")?.ifBlank { null }
            ?.let { refusal = "Gemini blocked the request ($it)." }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        candidate.optString("finishReason", "").takeIf { it.isNotBlank() && it != "STOP" }
            ?.let { refusal = "Gemini stopped early ($it)." }
        candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                val url = web.optString("uri", "")
                if (url.isNotBlank()) sources += AiSource(
                    web.optString("title", "").ifBlank { AiResearchProtocol.host(url) }, url)
            }
        }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null
        var progress: AiProgress? = null
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) {
                progress = AiProgress(text.toString(), AiStage.THINKING)
                continue
            }
            write(part.optString("text"))?.let { progress = it }
        }
        return progress
    }

    override fun answer(): AiAnswer {
        refusal?.let { throw AiFormatException(it) }
        return super.answer()
    }
}

/**
 * OpenRouter. OpenAI-shaped deltas, with the thinking in a field of its own and the searched pages
 * as annotations on the last one. Its keep-alive comment lines never reach here — the transport
 * drops them — but an event that is not JSON still might, and is ignored.
 */
internal class RouterStreamReader : BaseStreamReader() {
    override fun event(raw: String): AiProgress? {
        val choice = json(raw)?.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return null
        delta.optJSONArray("annotations")?.let { notes ->
            for (i in 0 until notes.length()) {
                val note = notes.optJSONObject(i) ?: continue
                val citation = note.optJSONObject("url_citation") ?: note
                val url = citation.optString("url", "").ifBlank { citation.optString("uri", "") }
                if (url.startsWith("http")) sources += AiSource(
                    citation.optString("title", "").ifBlank { AiResearchProtocol.host(url) }, url)
            }
        }
        write(delta.optString("content"))?.let { return it }
        // Reasoning is asked to be excluded, but a model that sends it anyway is still working.
        if (delta.optString("reasoning").isNotEmpty()) return AiProgress(text.toString(), AiStage.THINKING)
        return null
    }
}
