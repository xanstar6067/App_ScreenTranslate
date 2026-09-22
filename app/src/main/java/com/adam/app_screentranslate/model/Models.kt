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
/** Which ordinary translator picks up when the AI cannot answer. NONE reports the error instead. */
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
data class SessionState(
    val phase: SessionPhase = SessionPhase.OFF, val message: String = "", val control: ControlState = ControlState.READY,
    /** Title of the game the last translation was made in, when game detection found one. */
    val game: String? = null
)
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
    val mode: TranslationMode = TranslationMode.WEB,
    /** Off by default: while it is off, usage statistics are never read at all. */
    val gameDetection: Boolean = false
)
/**
 * [short] is what fits in a row of three buttons; [label] is what reads in a sentence.
 */
enum class AiProvider(val label: String, val short: String) {
    XAI("xAI Grok", "Grok"), GEMINI("Google Gemini", "Gemini"), OPENROUTER("OpenRouter", "OpenRouter")
}
/**
 * What a model is chosen for. Screen translation wants a fast model and barely any reasoning;
 * filling a game profile wants a careful one that can search. They are picked separately, provider
 * and all, because the best model for one is rarely the best for the other.
 */
enum class AiRole { TRANSLATE, RESEARCH }
/**
 * How long a model may think. The provider-neutral scale the settings speak; each client maps it
 * onto what the chosen model accepts (reasoning.effort for xAI and OpenRouter,
 * thinkingLevel/thinkingBudget for Gemini) and steps down when the model refuses a level.
 */
enum class AiEffort(val label: String, val hint: String) {
    MINIMAL("Мин.", "Быстрее всего: размышления отключены или сведены к минимуму"),
    LOW("Низкая", "Короткое обдумывание"),
    MEDIUM("Средняя", "Баланс скорости и тщательности"),
    HIGH("Высокая", "Дольше и дороже, но внимательнее")
}
/**
 * AI configuration. The token and the cached model list are kept per provider, and the chosen model
 * per provider *and* role, so switching a role back to a provider restores what it was last set to.
 * The tokens live apart from this, encrypted; see SecureStore.
 */
data class AiSettings(
    val provider: AiProvider = AiProvider.XAI,
    val model: String = "",
    /** Provider and model of the game-profile researcher, chosen apart from the translator's. */
    val researchProvider: AiProvider = AiProvider.XAI,
    val researchModel: String = "",
    val fallback: AiFallback = AiFallback.AUTO,
    val repair: Boolean = true, val prompt: String = "game",
    /** Whether a detected game's name, notes and glossary go into the request. */
    val context: Boolean = true,
    /** Reasoning for screen translation. Minimal by default: the player is waiting for the screen. */
    val effort: AiEffort = AiEffort.MINIMAL,
    /** Reasoning and web search for filling a game profile, where care matters more than speed. */
    val researchEffort: AiEffort = AiEffort.MEDIUM,
    val researchSearch: Boolean = true,
    /**
     * How many pages the provider's search may use. 0 leaves it to the provider, which is the
     * default: a hidden cap that quietly makes answers worse is not something to ship unasked.
     */
    val researchSearchLimit: Int = 0
)
/** The two roles read and written by one name, so nothing has to branch on the role twice. */
fun AiSettings.providerFor(role: AiRole) = if (role == AiRole.RESEARCH) researchProvider else provider
fun AiSettings.modelFor(role: AiRole) = if (role == AiRole.RESEARCH) researchModel else model
fun AiSettings.effortFor(role: AiRole) = if (role == AiRole.RESEARCH) researchEffort else effort
fun AiSettings.withProvider(role: AiRole, value: AiProvider) =
    if (role == AiRole.RESEARCH) copy(researchProvider = value) else copy(provider = value)
fun AiSettings.withModel(role: AiRole, value: String) =
    if (role == AiRole.RESEARCH) copy(researchModel = value) else copy(model = value)
enum class GameOrigin(val label: String) { AUTO("Обнаружена автоматически"), MANUAL("Добавлена вручную") }
/** Characters, factions and locations are names rather than words; the model is told which is which. */
enum class TermKind(val label: String, val single: String, val wire: String) {
    TERM("Термины", "Термин", "term"), CHARACTER("Персонажи", "Персонаж", "character"),
    FACTION("Фракции", "Фракция", "faction"), LOCATION("Локации", "Локация", "location")
}
/** Grammatical gender the target language needs to agree a character's name, pronoun or title with. */
enum class Gender(val label: String, val wire: String) {
    UNKNOWN("Не указан", "unknown"), MALE("Мужской", "male"), FEMALE("Женский", "female"),
    NEUTER("Средний", "neuter"), PLURAL("Мн. число", "plural")
}
data class GlossaryEntry(
    val term: String, val translation: String, val kind: TermKind = TermKind.TERM,
    /** The term stays as it is in the translation, like a title or a brand. */
    val keep: Boolean = false,
    /** Meaningful only for a character: how the target language should agree with them. */
    val gender: Gender = Gender.UNKNOWN, val id: Long = 0
) {
    /** What the translation must contain for this term. */
    val rendering get() = if (keep) term else translation
}
/**
 * What Lenslate knows about one game. A null language or prompt means "as in the general settings":
 * the profile overrides only what the user set in it.
 */
data class GameProfile(
    val packageName: String, val label: String = "", val customName: String = "",
    val origin: GameOrigin = GameOrigin.AUTO, val enabled: Boolean = true,
    val firstSeen: Long = 0, val lastUsed: Long = 0,
    val source: String? = null, val target: String? = null, val prompt: String? = null,
    val notes: String = ""
) {
    val title get() = customName.ifBlank { label.ifBlank { packageName } }
}
/** The game one screen was captured in, with its whole glossary. */
data class GameContext(val profile: GameProfile, val glossary: List<GlossaryEntry>)
/** A disabled profile changes nothing: the screen is translated as if the game were unknown. */
fun AppSettings.forGame(profile: GameProfile?): AppSettings =
    if (profile == null || !profile.enabled) this
    else copy(source = profile.source ?: source, target = profile.target ?: target)
/**
 * One model as a provider's listing describes it. Only what model choice actually needs.
 *
 * Prices are US dollars per million tokens, converted from whatever unit the provider publishes,
 * and null when it publishes none — Gemini's listing carries no prices at all. A price of zero is
 * a free model and is not the same as an unknown one.
 */
data class AiModelInfo(
    val id: String, val aliases: List<String> = emptyList(),
    val inputModalities: List<String> = emptyList(), val outputModalities: List<String> = emptyList(),
    val maxPromptLength: Int? = null,
    val promptPrice: Double? = null, val completionPrice: Double? = null
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
