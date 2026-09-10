package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiFragment
import com.adam.app_screentranslate.model.Box
import com.adam.app_screentranslate.model.ScreenTextBlock
import org.json.JSONArray
import org.json.JSONObject

/** The model answered, but not in a shape that can be drawn. The reason is fed back on retry. */
class AiFormatException(val reason: String) : Exception(reason)

/**
 * The wire contract with xAI: the schema that constrains decoding, the request payload, and the
 * two checks the answer has to survive. Everything here is deliberately free of Android so the
 * rules that decide whether a screen gets drawn are covered by JVM tests.
 */
object AiProtocol {
    const val SCHEMA_NAME = "screen_translation"

    /**
     * Flat on purpose. Every field required, no additional properties, no nesting past one array of
     * objects: the simpler the grammar, the less a model drifts out of it.
     */
    fun schema(): JSONObject {
        val fragment = JSONObject()
            .put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("source_block_ids", "corrected_source_text", "translated_text")))
            .put("properties", JSONObject()
                .put("source_block_ids", JSONObject().put("type", "array")
                    .put("items", JSONObject().put("type", "integer")))
                .put("corrected_source_text", JSONObject().put("type", "string"))
                .put("translated_text", JSONObject().put("type", "string")))
        return JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("fragments")))
            .put("properties", JSONObject().put("fragments", JSONObject().put("type", "array").put("items", fragment)))
    }

    /**
     * The same contract in the dialect Gemini speaks: an OpenAPI 3.0 subset with upper-case type
     * names and no `additionalProperties`, which it rejects outright. `propertyOrdering` is a Gemini
     * hint that keeps the generated fields in a stable order.
     */
    fun geminiSchema(): JSONObject {
        val fields = listOf("source_block_ids", "corrected_source_text", "translated_text")
        val fragment = JSONObject().put("type", "OBJECT")
            .put("required", JSONArray(fields)).put("propertyOrdering", JSONArray(fields))
            .put("properties", JSONObject()
                .put("source_block_ids", JSONObject().put("type", "ARRAY")
                    .put("items", JSONObject().put("type", "INTEGER")))
                .put("corrected_source_text", JSONObject().put("type", "STRING"))
                .put("translated_text", JSONObject().put("type", "STRING")))
        return JSONObject().put("type", "OBJECT").put("required", JSONArray(listOf("fragments")))
            .put("properties", JSONObject().put("fragments", JSONObject().put("type", "ARRAY").put("items", fragment)))
    }

    fun payload(blocks: List<ScreenTextBlock>, source: String, target: String): String {
        val array = JSONArray()
        blocks.forEach { array.put(JSONObject().put("id", it.id).put("text", it.originalText)) }
        return JSONObject().put("source_language", source).put("target_language", target)
            .put("blocks", array).toString()
    }

    /**
     * Constrained decoding should make this unnecessary, but a model whose strict support quietly
     * degrades wraps the answer in a fence or a sentence. Recovering that costs nothing.
     */
    fun unwrap(raw: String): String {
        var text = raw.trim()
        val fence = Regex("```[a-zA-Z]*\\s*")
        if (fence.containsMatchIn(text)) {
            text = text.substringAfter(fence.find(text)!!.value).substringBeforeLast("```").trim()
        }
        if (text.startsWith("{") || text.startsWith("[")) return text
        val open = text.indexOfFirst { it == '{' || it == '[' }
        val close = text.indexOfLast { it == '}' || it == ']' }
        if (open < 0 || close <= open) throw AiFormatException("The answer contained no JSON.")
        return text.substring(open, close + 1)
    }

    private fun obj(raw: String): JSONObject = try { JSONObject(raw) }
        catch (_: Exception) { throw AiFormatException("The server answer was not a JSON object.") }

    /** The /chat/completions envelope. */
    fun chatContent(raw: String): String {
        val message = obj(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiFormatException("The completion carried no message.")
        return message.optString("content", "").ifBlank { throw AiFormatException("The completion was empty.") }
    }

    /**
     * The /responses envelope. A reasoning model puts its thinking in its own output item, which
     * carries no answer and must not be concatenated into one.
     */
    fun responsesContent(raw: String): String {
        val root = obj(raw)
        root.optString("output_text", "").takeIf { it.isNotBlank() }?.let { return it }
        val output = root.optJSONArray("output") ?: throw AiFormatException("The response carried no output.")
        val answer = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") == "reasoning") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) answer.append(content.optJSONObject(j)?.optString("text").orEmpty())
        }
        return answer.toString().ifBlank { throw AiFormatException("The response was empty.") }
    }

    /**
     * The Gemini envelope. A refusal or a cut-off answer arrives as a finishReason rather than an
     * HTTP error, so it has to be read here or it looks like malformed JSON.
     */
    fun geminiContent(raw: String): String {
        val root = obj(raw)
        root.optJSONObject("promptFeedback")?.optString("blockReason")?.ifBlank { null }
            ?.let { throw AiFormatException("Gemini blocked the request ($it).") }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiFormatException("The answer carried no candidates.")
        val finish = candidate.optString("finishReason", "")
        if (finish.isNotBlank() && finish != "STOP")
            throw AiFormatException("Gemini stopped early ($finish).")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiFormatException("The candidate carried no parts.")
        val answer = StringBuilder()
        for (i in 0 until parts.length()) answer.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        return answer.toString().ifBlank { throw AiFormatException("The answer was empty.") }
    }

    fun parse(raw: String): List<AiFragment> {
        val text = unwrap(raw)
        val fragments = try {
            if (text.startsWith("[")) JSONArray(text)
            else JSONObject(text).optJSONArray("fragments")
                ?: throw AiFormatException("The object had no \"fragments\" array.")
        } catch (e: AiFormatException) { throw e }
        catch (_: Exception) { throw AiFormatException("The answer was not valid JSON.") }
        if (fragments.length() == 0) throw AiFormatException("The \"fragments\" array was empty.")
        return (0 until fragments.length()).map { i ->
            val item = fragments.optJSONObject(i) ?: throw AiFormatException("Fragment $i was not an object.")
            val ids = item.optJSONArray("source_block_ids")
                ?: throw AiFormatException("Fragment $i had no \"source_block_ids\" array.")
            if (ids.length() == 0) throw AiFormatException("Fragment $i had an empty \"source_block_ids\".")
            AiFragment(
                (0 until ids.length()).map {
                    // A model that answers "21" instead of 21 is still telling us which block it means.
                    ids.optLong(it, Long.MIN_VALUE).takeIf { id -> id != Long.MIN_VALUE }
                        ?: throw AiFormatException("Fragment $i had a non-integer block id.")
                },
                item.optString("corrected_source_text", ""), item.optString("translated_text", ""))
        }
    }

    /** A merge whose blocks are scattered is not a repaired sentence, whatever the JSON says. */
    const val MERGE_AREA_RATIO = 2.5f

    fun validate(fragments: List<AiFragment>, blocks: List<ScreenTextBlock>, allowMerge: Boolean) {
        val known = blocks.associateBy { it.id }
        val seen = mutableSetOf<Long>()
        for (fragment in fragments) {
            if (!allowMerge && fragment.sourceBlockIds.size > 1)
                throw AiFormatException("Joining is disabled: every fragment must carry exactly one id.")
            if (fragment.translatedText.isBlank()) throw AiFormatException("A fragment had an empty translated_text.")
            if (fragment.correctedSourceText.isBlank()) throw AiFormatException("A fragment had an empty corrected_source_text.")
            val members = fragment.sourceBlockIds.map { id ->
                if (!seen.add(id)) throw AiFormatException("Block id $id appeared in more than one fragment.")
                known[id] ?: throw AiFormatException("Block id $id was not in the request.")
            }
            if (members.size > 1) {
                val union = members.map { it.boundingBox }.reduce(Box::union)
                if (union.area > MERGE_AREA_RATIO * members.sumOf { it.boundingBox.area.toDouble() })
                    throw AiFormatException("Blocks ${fragment.sourceBlockIds} are too far apart on screen to be one fragment.")
            }
        }
        val missing = known.keys - seen
        if (missing.isNotEmpty()) throw AiFormatException("Block ids ${missing.sorted()} were left out of the answer.")
    }
}
