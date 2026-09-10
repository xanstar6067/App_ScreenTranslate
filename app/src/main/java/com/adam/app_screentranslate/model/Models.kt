package com.adam.app_screentranslate.model

import kotlin.math.max
import kotlin.math.min

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = (right - left).coerceAtLeast(1f)
    val height get() = (bottom - top).coerceAtLeast(1f)
    val area get() = width * height
    fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    fun intersection(other: Box): Float = (min(right, other.right) - max(left, other.left)).coerceAtLeast(0f) *
        (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0f)
}
enum class TextScript { LATIN, CYRILLIC, JAPANESE, KOREAN, MIXED, UNKNOWN }
/** Which recognizer produced a block. Two engines never share a confidence scale. */
enum class OcrEngine { MLKIT, TESSERACT }
enum class MergeMode(val label: String, val gap: Float) { CAUTIOUS("Осторожное", .35f), NORMAL("Нормальное", .65f), AGGRESSIVE("Агрессивное", 1f) }
enum class ProviderMode(val label: String) { AUTO("Автоматически"), GOOGLE("Google"), YANDEX("Yandex") }
enum class TranslationMode(val label: String) { WEB("Обычный"), AI("ИИ") }
/** Which ordinary translator picks up when xAI cannot answer. NONE reports the error instead. */
enum class AiFallback(val label: String) { NONE("Не использовать"), GOOGLE("Google"), YANDEX("Yandex"), AUTO("Автоматически") }
fun AiFallback.provider(): ProviderMode? = when (this) {
    AiFallback.NONE -> null
    AiFallback.GOOGLE -> ProviderMode.GOOGLE
    AiFallback.YANDEX -> ProviderMode.YANDEX
    AiFallback.AUTO -> ProviderMode.AUTO
}
enum class BackgroundStyle(val label: String) { AUTO("Автоматический контраст"), DARK("Тёмный"), LIGHT("Светлый") }
enum class ButtonSize(val label: String, val dp: Int) { SMALL("Маленький", 44), MEDIUM("Средний", 56), LARGE("Большой", 68) }
enum class SessionPhase { OFF, STARTING, ACTIVE, PAUSED, ERROR }
enum class ControlState { READY, PROCESSING, TRANSLATED, PAUSED }
data class SessionState(val phase: SessionPhase = SessionPhase.OFF, val message: String = "", val control: ControlState = ControlState.READY)
data class OcrElement(val text: String, val box: Box)
data class OcrLine(val text: String, val box: Box, val elements: List<OcrElement> = emptyList(), val angle: Float = 0f, val confidence: Float? = null)
data class ScreenTextBlock(
    val id: Long, val originalText: String, val boundingBox: Box,
    val lines: List<OcrLine> = emptyList(), val detectedLanguage: String? = null,
    val script: TextScript = TextScript.UNKNOWN, val confidence: Float? = null,
    /** Tilt of the recognized text in degrees, as reported by the recognizer. */
    val angle: Float = 0f,
    /** Paragraph the recognizer itself put this text in; -1 when it reported no grouping. */
    val paragraph: Int = -1,
    /** The recognizer this block came from. Its confidence means nothing outside that engine. */
    val engine: OcrEngine = OcrEngine.MLKIT,
    val translatedText: String? = null, val backgroundLuminance: Float = .5f
)
data class TranslationRequest(val id: Long, val text: String, val source: String, val target: String)
data class TranslationResult(val id: Long, val text: String, val detectedLanguage: String?, val provider: String)
data class AppSettings(
    val target: String = "ru", val source: String = "auto", val provider: ProviderMode = ProviderMode.AUTO,
    val merge: MergeMode = MergeMode.NORMAL, val background: BackgroundStyle = BackgroundStyle.AUTO,
    val opacity: Float = .9f, val textScale: Float = 1f, val buttonSize: ButtonSize = ButtonSize.MEDIUM,
    val cacheEnabled: Boolean = true, val buttonOpacity: Float = 1f, val ocrPreview: Boolean = false,
    val mode: TranslationMode = TranslationMode.WEB
)
/** xAI configuration. The token lives apart from this, encrypted; see SecureStore. */
data class AiSettings(
    val model: String = "", val fallback: AiFallback = AiFallback.AUTO,
    val repair: Boolean = true, val prompt: String = "game"
)
/** One model as the xAI models API describes it. Only what model choice actually needs. */
data class AiModelInfo(
    val id: String, val aliases: List<String> = emptyList(),
    val inputModalities: List<String> = emptyList(), val outputModalities: List<String> = emptyList(),
    val maxPromptLength: Int? = null
)
/** One translation the model returned, bound to the recognized blocks it was built from. */
data class AiFragment(val sourceBlockIds: List<Long>, val correctedSourceText: String, val translatedText: String)
object Languages {
    // Curated shared target languages; source OCR is intentionally narrower.
    val targets = linkedMapOf("ru" to "Русский", "en" to "Английский", "ja" to "Японский", "ko" to "Корейский",
        "de" to "Немецкий", "fr" to "Французский", "es" to "Испанский", "it" to "Итальянский",
        "pt" to "Португальский", "uk" to "Украинский", "pl" to "Польский", "tr" to "Турецкий",
        "zh" to "Китайский", "ar" to "Арабский", "hi" to "Хинди", "nl" to "Нидерландский",
        "sv" to "Шведский", "cs" to "Чешский", "fi" to "Финский", "id" to "Индонезийский")
    val sources = linkedMapOf("auto" to "Авто / смешанный", "en" to "Английский", "ru" to "Русский", "ja" to "Японский", "ko" to "Корейский",
        "de" to "Немецкий", "fr" to "Французский", "es" to "Испанский", "it" to "Итальянский", "pt" to "Португальский",
        "pl" to "Польский", "tr" to "Турецкий", "nl" to "Нидерландский")
}
