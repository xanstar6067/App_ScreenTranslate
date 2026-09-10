package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.data.TranslationStore
import com.adam.app_screentranslate.model.*
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.Locale

/** What the AI pass could not deliver. Empty [untranslated] means the whole screen is done. */
data class AiOutcome(val untranslated: List<ScreenTextBlock>, val reason: String?)

/**
 * The AI translation pass. It works on blocks the reconstructor already assembled, sends them in
 * reading order in small packets, and only accepts an answer that maps back onto those blocks
 * exactly. Whatever it cannot deliver comes back in [AiOutcome] for the caller to fall back on.
 */
class AiTranslator(private val client: AiEngine = XaiClient()) {

    suspend fun translate(
        blocks: List<ScreenTextBlock>, settings: AppSettings, ai: AiSettings, token: String,
        cache: TranslationStore?, onResult: suspend (ScreenTextBlock) -> Unit
    ): AiOutcome {
        if (token.isBlank()) return AiOutcome(blocks, "Не указан API token xAI.")
        if (ai.model.isBlank()) return AiOutcome(blocks, "Не выбрана модель xAI.")
        val target = language(settings.target)
        val source = language(settings.source)
        val provider = "xai:${ai.model}"
        val pending = blocks.filter { it.detectedLanguage != settings.target }.toMutableList()

        // Merging makes a fragment depend on its neighbours, so a per-block cache stops being
        // correct. Without merging the model is a 1:1 translator and the existing table fits.
        val store = cache?.takeIf { !ai.repair }
        if (store != null) for (block in pending.toList()) {
            val request = TranslationRequest(block.id, block.originalText, block.detectedLanguage ?: "auto", settings.target)
            val hit = try { store.get(provider, request) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (hit != null) { pending -= block; onResult(block.copy(translatedText = hit.text)) }
        }

        val failed = mutableListOf<ScreenTextBlock>()
        var reason: String? = null
        val system = AiPrompts.system(ai.prompt, source, target, ai.repair)
        for (batch in batches(readingOrder(pending))) {
            val fragments = try { request(token, ai.model, system, batch, source, target, ai.repair) }
            catch (e: CancellationException) { throw e }
            catch (e: AiFormatException) { failed += batch; reason = e.reason; continue }
            catch (e: XaiException) { failed += batch; reason = e.reason; continue }
            catch (_: IOException) { failed += batch; reason = "Сеть недоступна."; continue }
            val known = batch.associateBy { it.id }
            for (fragment in fragments) {
                val members = readingOrder(fragment.sourceBlockIds.mapNotNull { known[it] })
                if (members.isEmpty()) continue
                val merged = merge(members, fragment)
                if (store != null && members.size == 1) {
                    val request = TranslationRequest(merged.id, members.first().originalText,
                        merged.detectedLanguage ?: "auto", settings.target)
                    try { store.put(request, TranslationResult(merged.id, fragment.translatedText, null, provider)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Cache failure must not discard a translation. */ }
                }
                onResult(merged)
            }
        }
        return AiOutcome(failed, reason)
    }

    /**
     * One packet, and one second chance. Constrained decoding makes a malformed answer unlikely but
     * not impossible, and handing the rejection reason back is far cheaper than losing the packet.
     */
    private suspend fun request(
        token: String, model: String, system: String, batch: List<ScreenTextBlock>,
        source: String, target: String, repair: Boolean
    ): List<AiFragment> {
        val payload = AiProtocol.payload(batch, source, target)
        return try { answer(token, model, system, payload, batch, repair) }
        catch (e: AiFormatException) {
            answer(token, model, system,
                "$payload\n\nYour previous answer was rejected: ${e.reason} Return only corrected JSON.",
                batch, repair)
        }
    }

    private suspend fun answer(
        token: String, model: String, system: String, user: String,
        batch: List<ScreenTextBlock>, repair: Boolean
    ): List<AiFragment> {
        val fragments = AiProtocol.parse(client.translate(token, model, system, user))
        AiProtocol.validate(fragments, batch, allowMerge = repair)
        return fragments
    }

    private fun merge(members: List<ScreenTextBlock>, fragment: AiFragment): ScreenTextBlock {
        val first = members.first()
        if (members.size == 1)
            return first.copy(originalText = fragment.correctedSourceText, translatedText = fragment.translatedText)
        val area = members.sumOf { it.boundingBox.area.toDouble() }
        return first.copy(
            originalText = fragment.correctedSourceText, translatedText = fragment.translatedText,
            boundingBox = members.map { it.boundingBox }.reduce(Box::union),
            lines = members.flatMap { it.lines },
            confidence = members.mapNotNull { it.confidence }.minOrNull(),
            backgroundLuminance = (members.sumOf { it.backgroundLuminance * it.boundingBox.area.toDouble() } / area).toFloat())
    }

    companion object {
        /** A spoiled answer should cost part of a screen, not all of it, and keep the grammar small. */
        const val BLOCKS_PER_REQUEST = 8
        const val CHARS_PER_REQUEST = 3000

        /**
         * Blocks are cut into packets in reading order so that neighbours stay together: the model
         * can only join what it sees in one request.
         */
        fun batches(blocks: List<ScreenTextBlock>): List<List<ScreenTextBlock>> {
            val result = mutableListOf<List<ScreenTextBlock>>()
            var batch = mutableListOf<ScreenTextBlock>()
            var size = 0
            for (block in blocks) {
                if (batch.isNotEmpty() && (batch.size >= BLOCKS_PER_REQUEST || size + block.originalText.length > CHARS_PER_REQUEST)) {
                    result += batch; batch = mutableListOf(); size = 0
                }
                batch += block; size += block.originalText.length
            }
            if (batch.isNotEmpty()) result += batch
            return result
        }

        /** Rows top to bottom, and inside a row left to right — how the text was meant to be read. */
        fun readingOrder(blocks: List<ScreenTextBlock>): List<ScreenTextBlock> {
            val rows = mutableListOf<MutableList<ScreenTextBlock>>()
            for (block in blocks.sortedBy { it.boundingBox.top }) {
                val anchor = rows.lastOrNull()?.first()
                if (anchor != null && block.boundingBox.top < anchor.boundingBox.bottom - anchor.boundingBox.height * .5f)
                    rows.last() += block
                else rows += mutableListOf(block)
            }
            return rows.flatMap { row -> row.sortedBy { it.boundingBox.left } }
        }

        /** The model reads a language name far more reliably than a two-letter tag. */
        fun language(code: String): String =
            if (code == "auto") "auto-detect"
            else Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
    }
}
