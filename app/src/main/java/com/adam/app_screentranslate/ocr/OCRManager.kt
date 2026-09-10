package com.adam.app_screentranslate.ocr

import android.graphics.Bitmap
import android.content.Context
import com.adam.app_screentranslate.model.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class OCRManager(private val context: Context) : AutoCloseable {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val language = LanguageIdentification.getClient()
    suspend fun recognize(bitmap: Bitmap, source: String, mode: MergeMode): List<ScreenTextBlock> = withContext(Dispatchers.Default) {
        val models: List<TextRecognizer> = when (source) {
            "auto" -> listOf(latin, japanese, korean)
            "ja" -> listOf(japanese, latin)
            "ko" -> listOf(korean, latin)
            else -> listOf(latin)
        }
        // ML Kit tasks cannot be cancelled. Wait for native readers before recycling the Bitmap.
        val raw = withContext(NonCancellable) {
            supervisorScope {
                models.mapIndexed { modelIndex, model -> async {
                    try {
                        model.process(InputImage.fromBitmap(bitmap, 0)).await().textBlocks.mapIndexedNotNull { blockIndex, b ->
                            val rect = b.boundingBox ?: return@mapIndexedNotNull null
                            val lines = b.lines.mapNotNull { l ->
                                val r = l.boundingBox ?: return@mapNotNull null
                                OcrLine(l.text, Box(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()),
                                    l.elements.mapNotNull { e -> e.boundingBox?.let {
                                        OcrElement(e.text, Box(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()))
                                    } }, l.angle, l.confidence)
                            }
                            val text = TextBlockReconstructor.joinLines(lines.map { it.text }).ifBlank { b.text }
                            ScreenTextBlock(0, text, Box(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()),
                                lines = lines, script = ScriptDetector.detect(text),
                                angle = lines.map { it.angle }.sorted().let { a -> if (a.isEmpty()) 0f else a[a.size / 2] },
                                // Which paragraph the recognizer put these lines in. Its own grouping
                                // is evidence the geometry alone cannot provide; models never share ids.
                                paragraph = modelIndex * PARAGRAPHS_PER_MODEL + blockIndex,
                                confidence = lines.mapNotNull { it.confidence }.average().takeUnless { it.isNaN() }?.toFloat())
                        }
                    } catch (e: Exception) { emptyList() }
                } }.awaitAll().flatten()
            }
        }
        ensureActive()
        val russian = if (source == "auto" || source == "ru") CyrillicRecognizer(context).recognize(bitmap) else emptyList()
        ensureActive()
        // An independently recognized Cyrillic line must veto Latin look-alike guesses in that region.
        val supported = raw.filter { candidate -> russian.none { ru ->
            ru.boundingBox.intersection(candidate.boundingBox) / candidate.boundingBox.area > .7f &&
                (ru.confidence ?: 0f) >= .75f
        } } + russian
        TextBlockReconstructor().reconstruct(supported, mode).map { block ->
            val resolved = when (block.script) {
                TextScript.CYRILLIC -> "ru"
                TextScript.MIXED if block.detectedLanguage == "ru" -> "ru"
                TextScript.MIXED -> "auto"
                TextScript.JAPANESE -> "ja"
                TextScript.KOREAN -> "ko"
                else -> if (source !in listOf("auto", "ja", "ko")) source else
                    language.identifyLanguage(block.originalText).await().takeUnless { it == "und" } ?: "auto"
            }
            block.copy(detectedLanguage = resolved, backgroundLuminance = luminance(bitmap, block.boundingBox))
        }
    }
    private fun luminance(bitmap: Bitmap, box: Box): Float {
        var total = 0f; var count = 0
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, bitmap.height)
        for (y in top until bottom step ((bottom-top)/12).coerceAtLeast(1))
            for (x in left until right step ((right-left)/12).coerceAtLeast(1)) {
                val c = bitmap.getPixel(x, y)
                total += (.2126f * ((c shr 16) and 255) + .7152f * ((c shr 8) and 255) + .0722f * (c and 255)) / 255f
                count++
            }
        return total / count.coerceAtLeast(1)
    }
    override fun close() { latin.close(); japanese.close(); korean.close(); language.close() }
    private companion object { const val PARAGRAPHS_PER_MODEL = 100_000 }
}
