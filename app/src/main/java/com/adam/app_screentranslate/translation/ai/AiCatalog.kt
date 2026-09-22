package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiModelInfo
import java.util.Locale

/**
 * What a model is, what it costs and how it is found in a list of hundreds. Pure Kotlin: every
 * provider's catalogue passes through here, so one set of JVM tests covers all three.
 *
 * Names are read by hand rather than by pattern — Android's regex engine is stricter than the
 * desktop JVM these tests run on.
 */
object MediaModels {
    /**
     * A model that makes or reads pictures, video or sound. Lenslate sends text and draws text, so
     * these can never do its work; on OpenRouter they are most of the catalogue. Matching is on the
     * name because that is all the listings agree on.
     *
     * "vision" is here on purpose: such a model still answers in text, but it exists to look at
     * images, and the screen never leaves the device (invariant 1).
     */
    private val names = listOf(
        "image", "-vision", "vision-", "photo", "video", "audio", "speech", "voice", "-tts", "tts-",
        "whisper", "transcribe", "diffusion", "dall-e", "flux", "imagen", "veo-", "sora",
        "kling", "seedream", "seedance", "recraft", "ideogram", "midjourney", "stable-",
        "embed", "rerank", "moderation", "-guard", "guard-", "ocr")

    /** Modalities that say the same thing as the name, when a listing reports them. */
    private val media = listOf("image", "video", "audio", "speech")

    fun isMedia(model: AiModelInfo): Boolean {
        val spellings = (listOf(model.id) + model.aliases).map { it.lowercase(Locale.ROOT) }
        if (spellings.any { name -> names.any { name.contains(it) } }) return true
        // Anything that emits a picture, a video or sound is a generator, whatever it is called.
        if (model.outputModalities.any { out -> media.any { out.equals(it, true) } }) return true
        // Text in is not negotiable; a model that only takes audio or video cannot be given a screen.
        return model.inputModalities.isNotEmpty() && model.inputModalities.none { it.equals("text", true) }
    }

    /** The other half of the same question: does this model take text and answer in text? */
    fun isText(model: AiModelInfo): Boolean {
        if (isMedia(model)) return false
        // An empty list means a listing that reports no modalities; those listings are text-only.
        return model.outputModalities.isEmpty() || model.outputModalities.any { it.equals("text", true) }
    }
}

/**
 * Finding one model among several hundred. Typing "gem" has to bring back both gemini and gemma,
 * and "claude sonnet" has to find "anthropic/claude-sonnet-4.5" although the words are not
 * adjacent and are separated by a dash rather than a space.
 *
 * So: every word of the query must appear somewhere in the model's id or its display name, as a
 * substring, with separators ignored on a second pass. Words, not one string, because the order a
 * user types them in is not the order a vendor names things in.
 */
object ModelSearch {
    private const val SEPARATORS = "-_/. :,"

    fun apply(models: List<AiModelInfo>, query: String): List<AiModelInfo> {
        val words = query.lowercase(Locale.ROOT).split(' ', '\t', '\n').filter { it.isNotBlank() }
        if (words.isEmpty()) return models
        return models.filter { matches(it, words) }.sortedBy { rank(it, words.first()) }
    }

    fun matches(model: AiModelInfo, words: List<String>): Boolean {
        val spellings = (listOf(model.id) + model.aliases).map { it.lowercase(Locale.ROOT) }
        val haystack = spellings + spellings.map { strip(it) }
        return words.all { word ->
            val bare = strip(word)
            haystack.any { it.contains(word) || (bare.isNotEmpty() && it.contains(bare)) }
        }
    }

    /**
     * A match on the model's own name comes before a match on the vendor in front of it: searching
     * "gemini" should not bury google/gemini-3-pro under a vendor called "geminilabs".
     */
    private fun rank(model: AiModelInfo, word: String): String {
        val id = model.id.lowercase(Locale.ROOT)
        val name = id.substringAfterLast('/')
        val tier = when {
            name.startsWith(word) || strip(name).startsWith(strip(word)) -> 0
            name.contains(word) -> 1
            else -> 2
        }
        return "$tier$id"
    }

    /**
     * Whether what was typed could be a model id rather than a search. A provider can ship a model
     * hours before its listing mentions it, and refusing to send an id the user knows is real would
     * make the app the only thing standing between them and a model they are paying for.
     */
    fun looksLikeModelId(query: String): Boolean {
        val value = query.trim()
        if (value.length < 3 || value.any { it.isWhitespace() }) return false
        if (!value.first().isLetterOrDigit()) return false
        return value.any { it.isDigit() } || value.contains('/') || value.contains('-')
    }

    private fun strip(value: String) = value.filterNot { it in SEPARATORS }
}

/**
 * How much one screen is likely to cost with this model. The scale is what the user is actually
 * exposed to: a provider's price list per million tokens says little, but "twenty cents every time
 * you tap the button" says everything.
 */
enum class PriceTier(val label: String) {
    UNKNOWN("цена неизвестна"), FREE("бесплатная"), CHEAP("дешёвая"), MODERATE("умеренная"),
    EXPENSIVE("дорогая"), DANGEROUS("очень дорогая")
}

object AiPricing {
    /**
     * One screen, roughly: a busy screen is three packets, each carrying the system prompt and up
     * to eight blocks, and the answer is the same text translated. Reasoning is charged as output
     * and is not counted here — it is what makes a dear model dearer still.
     */
    const val SCREEN_PROMPT_TOKENS = 3500
    const val SCREEN_COMPLETION_TOKENS = 1200

    /** Above this a published price is not believable and is treated as no price at all. */
    private const val ABSURD_PER_MILLION = 10_000.0

    private const val CHEAP_SCREEN = .01
    private const val MODERATE_SCREEN = .05
    /** Ten cents a tap is a hundred taps to ten dollars: past here the choice is confirmed. */
    private const val EXPENSIVE_SCREEN = .10

    /** Dollars for one screen, or null when the provider publishes no prices. */
    fun perScreen(model: AiModelInfo): Double? {
        val prompt = believable(model.promptPrice) ?: return null
        val completion = believable(model.completionPrice) ?: return null
        return prompt * SCREEN_PROMPT_TOKENS / 1_000_000 + completion * SCREEN_COMPLETION_TOKENS / 1_000_000
    }

    fun tier(model: AiModelInfo): PriceTier {
        val screen = perScreen(model) ?: return PriceTier.UNKNOWN
        return when {
            screen <= 0 -> PriceTier.FREE
            screen < CHEAP_SCREEN -> PriceTier.CHEAP
            screen < MODERATE_SCREEN -> PriceTier.MODERATE
            screen < EXPENSIVE_SCREEN -> PriceTier.EXPENSIVE
            else -> PriceTier.DANGEROUS
        }
    }

    /** A model this dear is confirmed before it is chosen: one tap of the button can cost a dollar. */
    fun warns(model: AiModelInfo) = tier(model) == PriceTier.DANGEROUS

    /**
     * "$3 / $15 за 1M токенов · ≈ $0.03 за экран", or null when there is nothing to say.
     *
     * [screen] is false where the model is not the one translating screens: the glossary researcher
     * makes one long request of its own, and quoting a per-screen figure there would be a made-up
     * number rather than a useful one.
     */
    fun summary(model: AiModelInfo, screen: Boolean = true): String? {
        val cost = perScreen(model) ?: return null
        if (cost <= 0) return "бесплатная модель"
        val prices = "$${money(model.promptPrice ?: 0.0)} / $${money(model.completionPrice ?: 0.0)} за 1M токенов"
        return if (screen) "$prices  ·  ≈ $${money(cost)} за экран" else prices
    }

    /**
     * Dollars as a price list writes them: trailing zeros away, and never a bare "$0" for something
     * that costs money — a tenth of a cent rounded down to nothing would be the one misleading case.
     */
    fun money(value: Double): String {
        if (value <= 0) return "0"
        if (value < .001) return "0.001"
        val text = String.format(Locale.US, if (value < 1) "%.3f" else "%.2f", value)
        return text.trimEnd('0').trimEnd('.').ifEmpty { "0.001" }
    }

    private fun believable(price: Double?) = price?.takeIf { it >= 0 && it <= ABSURD_PER_MILLION }
}
