package com.adam.app_screentranslate

import com.adam.app_screentranslate.model.AiModelInfo
import com.adam.app_screentranslate.translation.ai.AiPricing
import com.adam.app_screentranslate.translation.ai.MediaModels
import com.adam.app_screentranslate.translation.ai.ModelSearch
import com.adam.app_screentranslate.translation.ai.OpenRouterModels
import com.adam.app_screentranslate.translation.ai.PriceTier
import com.adam.app_screentranslate.translation.ai.XaiModels
import org.junit.Assert.*
import org.junit.Test

/** The catalogue: what is offered, how it is found, and what it costs to press the button. */
class AiCatalogTest {
    private fun model(id: String, vararg aliases: String) = AiModelInfo(id, aliases.toList())
    private fun priced(id: String, prompt: Double?, completion: Double?) =
        AiModelInfo(id, promptPrice = prompt, completionPrice = completion)

    // --- Что вообще не показывается --------------------------------------------------------------

    @Test fun pictureVideoAndSoundModelsAreNotOffered() {
        val rejected = listOf("openai/dall-e-3", "black-forest-labs/flux-pro", "stability/stable-diffusion-3",
            "google/veo-3.0", "openai/sora-2", "kwai/kling-video", "openai/whisper-large-v3",
            "openai/gpt-4o-audio-preview", "elevenlabs/tts-multilingual", "openai/gpt-4-vision-preview",
            "bytedance/seedream-4", "mistral/mistral-ocr", "meta/llama-guard-4", "openai/omni-moderation",
            "cohere/rerank-v3", "openai/text-embedding-3-large")
        rejected.forEach { assertTrue(it, MediaModels.isMedia(model(it))) }
    }

    @Test fun ordinaryTextModelsSurvive() {
        val kept = listOf("openai/gpt-5", "anthropic/claude-sonnet-4.5", "google/gemini-3-pro-preview",
            "google/gemma-3-27b-it", "meta-llama/llama-4-maverick", "deepseek/deepseek-v3.2",
            "qwen/qwen3-max", "x-ai/grok-4.20")
        kept.forEach { assertTrue(it, MediaModels.isText(model(it))) }
    }

    @Test fun modalitiesDecideWhenTheNameSaysNothing() {
        // A model that emits a picture is a generator whatever it is called.
        assertTrue(MediaModels.isMedia(AiModelInfo("vendor/quiet-1", outputModalities = listOf("text", "image"))))
        // One that cannot be given text cannot be given a screen.
        assertTrue(MediaModels.isMedia(AiModelInfo("vendor/quiet-2", inputModalities = listOf("audio"),
            outputModalities = listOf("text"))))
        // Reading pictures as well as text is fine: the screen never leaves the device anyway.
        assertTrue(MediaModels.isText(AiModelInfo("vendor/quiet-3", inputModalities = listOf("text", "image"),
            outputModalities = listOf("text"))))
        // A listing that reports no modalities at all is a text listing.
        assertTrue(MediaModels.isText(AiModelInfo("vendor/quiet-4")))
    }

    @Test fun theRouterCatalogueIsFilteredAndGroupedByVendor() {
        val models = listOf(model("openai/gpt-5"), model("openai/dall-e-3"), model("anthropic/claude-opus-4.1"),
            model("stabilityai/sdxl-turbo"), model("google/gemma-3-27b-it"))
        assertEquals(listOf("anthropic/claude-opus-4.1", "google/gemma-3-27b-it", "openai/gpt-5"),
            OpenRouterModels.textTranslationModels(models).map { it.id })
    }

    // --- Поиск ------------------------------------------------------------------------------------

    private val catalogue = listOf(
        model("google/gemini-3-pro-preview", "Google: Gemini 3 Pro"),
        model("google/gemini-2.5-flash", "Google: Gemini 2.5 Flash"),
        model("google/gemma-3-27b-it", "Google: Gemma 3 27B"),
        model("anthropic/claude-sonnet-4.5", "Anthropic: Claude Sonnet 4.5"),
        model("anthropic/claude-opus-4.1", "Anthropic: Claude Opus 4.1"),
        model("openai/gpt-5", "OpenAI: GPT-5"),
        model("x-ai/grok-4.20", "xAI: Grok 4.20"))

    /** The ask: typing "gem" brings back gemini *and* gemma. */
    @Test fun aFragmentMatchesEveryNameThatStartsWithIt() {
        assertEquals(listOf("google/gemini-2.5-flash", "google/gemini-3-pro-preview", "google/gemma-3-27b-it"),
            ModelSearch.apply(catalogue, "gem").map { it.id }.sorted())
    }

    @Test fun wordsMayBeTypedInAnyOrderAndFindSeparatedParts() {
        assertEquals(listOf("anthropic/claude-sonnet-4.5"), ModelSearch.apply(catalogue, "sonnet claude").map { it.id })
        assertEquals(listOf("anthropic/claude-sonnet-4.5"), ModelSearch.apply(catalogue, "claude sonnet").map { it.id })
        // Two words that never meet in one model find nothing rather than everything.
        assertTrue(ModelSearch.apply(catalogue, "claude gemini").isEmpty())
    }

    @Test fun separatorsAndCaseAreIgnored() {
        assertEquals(listOf("openai/gpt-5"), ModelSearch.apply(catalogue, "gpt5").map { it.id })
        assertEquals(listOf("openai/gpt-5"), ModelSearch.apply(catalogue, "GPT-5").map { it.id })
        assertEquals(listOf("x-ai/grok-4.20"), ModelSearch.apply(catalogue, "grok420").map { it.id })
        // The display name is searched too: the id alone says nothing about "27B".
        assertEquals(listOf("google/gemma-3-27b-it"), ModelSearch.apply(catalogue, "27b").map { it.id })
    }

    @Test fun theModelsOwnNameOutranksItsVendor() {
        val models = listOf(model("openai/gpt-5"), model("gpt-labs/writer-1"))
        assertEquals(listOf("openai/gpt-5", "gpt-labs/writer-1"), ModelSearch.apply(models, "gpt").map { it.id })
    }

    @Test fun anEmptyQueryChangesNothing() {
        assertEquals(catalogue, ModelSearch.apply(catalogue, "   "))
    }

    @Test fun anIdMayBeTypedByHandWhenTheListingIsBehind() {
        // A model can exist before the provider's listing admits it, or before a key is allowed it.
        listOf("grok-4.7", "openai/gpt-5.5", "claude-opus-4-5", "gemini-3-flash").forEach {
            assertTrue(it, ModelSearch.looksLikeModelId(it))
        }
        // A search is not an id: words, spaces and stray punctuation stay a search.
        listOf("gem", "claude sonnet", "  ", "?!", "ab").forEach {
            assertFalse(it, ModelSearch.looksLikeModelId(it))
        }
    }

    @Test fun theTwoXaiListingsAreMergedRatherThanChosenBetween() {
        val rich = listOf(AiModelInfo("grok-4.6", outputModalities = listOf("text"), promptPrice = 3.0))
        // The minimal listing knows a newly shipped model that the rich one has not caught up with.
        val plain = listOf(AiModelInfo("grok-4.6"), AiModelInfo("grok-4.7"))
        val merged = XaiModels.merge(rich, plain)
        assertEquals(listOf("grok-4.6", "grok-4.7"), merged.map { it.id })
        // Where both know a model, the listing with modalities and prices is the one kept.
        assertEquals(3.0, merged.first().promptPrice!!, .001)
    }

    // --- Цена -------------------------------------------------------------------------------------

    @Test fun tiersFollowWhatOneScreenCosts() {
        // gemini-flash-lite: cents per hundred screens. gpt-5-pro: half a dollar per screen.
        assertEquals(PriceTier.CHEAP, AiPricing.tier(priced("flash-lite", .10, .40)))
        assertEquals(PriceTier.MODERATE, AiPricing.tier(priced("grok-4", 3.0, 15.0)))
        assertEquals(PriceTier.EXPENSIVE, AiPricing.tier(priced("heavy", 10.0, 50.0)))
        assertEquals(PriceTier.DANGEROUS, AiPricing.tier(priced("claude-opus", 15.0, 75.0)))
        assertEquals(PriceTier.DANGEROUS, AiPricing.tier(priced("gpt-5-pro", 15.0, 120.0)))
        assertEquals(PriceTier.FREE, AiPricing.tier(priced("free-model", 0.0, 0.0)))
    }

    /** Gemini publishes no prices at all, and silence must not read as "free". */
    @Test fun anUnpricedModelIsNeitherFreeNorWarnedAbout() {
        val unpriced = priced("gemini-3-pro-preview", null, null)
        assertEquals(PriceTier.UNKNOWN, AiPricing.tier(unpriced))
        assertNull(AiPricing.perScreen(unpriced))
        assertNull(AiPricing.summary(unpriced))
        assertFalse(AiPricing.warns(unpriced))
        // Half a price is no price: a listing that states one side is not to be extrapolated from.
        assertEquals(PriceTier.UNKNOWN, AiPricing.tier(priced("half", 3.0, null)))
    }

    @Test fun onlyTheDangerousTierIsConfirmedBeforeItIsChosen() {
        assertFalse(AiPricing.warns(priced("grok-4", 3.0, 15.0)))
        assertFalse(AiPricing.warns(priced("heavy", 10.0, 50.0)))
        assertTrue(AiPricing.warns(priced("claude-opus", 15.0, 75.0)))
        assertTrue(AiPricing.warns(priced("o1-pro", 150.0, 600.0)))
    }

    /** A price list that changes its units must not turn every model into a warning. */
    @Test fun anAbsurdPriceIsTreatedAsNoPrice() {
        assertEquals(PriceTier.UNKNOWN, AiPricing.tier(priced("misread", 30000.0, 150000.0)))
        assertEquals(PriceTier.UNKNOWN, AiPricing.tier(priced("negative", -1.0, -1.0)))
    }

    @Test fun moneyReadsAsAPriceListWritesIt() {
        assertEquals("15", AiPricing.money(15.0))
        assertEquals("1.25", AiPricing.money(1.25))
        assertEquals("0.3", AiPricing.money(0.30))
        assertEquals("0.024", AiPricing.money(0.0243))
        // Fractions of a cent still cost something, so they never round down to a bare zero.
        assertEquals("0.001", AiPricing.money(0.00004))
        assertEquals("0", AiPricing.money(0.0))
    }

    @Test fun theSummaryNamesBothPricesAndTheScreen() {
        val summary = AiPricing.summary(priced("grok-4", 3.0, 15.0))!!
        assertTrue(summary, summary.startsWith("$3 / $15 за 1M токенов"))
        assertTrue(summary, summary.contains("за экран"))
        assertEquals("бесплатная модель", AiPricing.summary(priced("free", 0.0, 0.0)))
    }
}
