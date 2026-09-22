package com.adam.app_screentranslate

import com.adam.app_screentranslate.data.TranslationStore
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.translation.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiTranslationTest {
    private fun block(id: Long, text: String = "Start", top: Float = 0f, left: Float = 0f,
                      width: Float = 100f, height: Float = 20f, language: String? = "en") =
        ScreenTextBlock(id, text, Box(left, top, left + width, top + height), detectedLanguage = language)

    private class FakeEngine(val answer: suspend (String) -> String) : AiEngine {
        var calls = 0
        override val provider = AiProvider.XAI
        override suspend fun models(token: String) = emptyList<AiModelInfo>()
        override fun usable(models: List<AiModelInfo>) = models
        override fun describe(model: String) = emptyList<String>()
        override suspend fun research(token: String, model: String, system: String, user: String,
                                      effort: AiEffort, search: Boolean) = AiAnswer("")
        override suspend fun translate(token: String, model: String, system: String, user: String,
                                       effort: AiEffort): String {
            calls++
            return answer(user)
        }
    }
    private class MemoryStore : TranslationStore {
        val values = mutableMapOf<String, TranslationResult>()
        private fun key(provider: String, r: TranslationRequest) = "$provider:${r.source}:${r.target}:${r.text}"
        override suspend fun get(provider: String, request: TranslationRequest) =
            values[key(provider, request)]?.copy(id = request.id)
        override suspend fun put(request: TranslationRequest, result: TranslationResult) {
            values[key(result.provider, request)] = result
        }
    }
    private fun fragments(vararg body: String) = """{"fragments":[${body.joinToString(",")}]}"""
    private fun fragment(ids: String, source: String, translated: String) =
        """{"source_block_ids":$ids,"corrected_source_text":"$source","translated_text":"$translated"}"""

    // --- Список моделей -------------------------------------------------------------------------

    @Test fun modelListKeepsOnlyTextModels() {
        val models = listOf(
            AiModelInfo("grok-4.1-fast", outputModalities = listOf("text"), inputModalities = listOf("text", "image")),
            AiModelInfo("grok-4", outputModalities = listOf("text"), inputModalities = listOf("text")),
            AiModelInfo("grok-3"),
            AiModelInfo("grok-2-image", outputModalities = listOf("image")),
            AiModelInfo("grok-imagine-video-01", outputModalities = listOf("video")),
            AiModelInfo("text-embedding-3", outputModalities = listOf("text")),
            AiModelInfo("grok-4.20-multi-agent", outputModalities = listOf("text")))
        assertEquals(listOf("grok-4.1-fast", "grok-4", "grok-3"),
            XaiModels.textTranslationModels(models).map { it.id })
    }

    @Test fun aliasAloneDisqualifiesAGenerator() {
        assertFalse(XaiModels.isTextTranslationModel(AiModelInfo("m-1", aliases = listOf("grok-imagine-image"))))
        // A model that also emits pictures is a generator wearing a text output modality.
        assertFalse(XaiModels.isTextTranslationModel(AiModelInfo("m-2", outputModalities = listOf("text", "image"))))
        assertTrue(XaiModels.isTextTranslationModel(AiModelInfo("m-3", outputModalities = listOf("text"))))
    }

    @Test fun geminiListKeepsOnlyModelsThatGenerateText() {
        val generate = listOf("generateContent", "countTokens")
        val models = listOf(
            AiModelInfo("gemini-2.5-flash", outputModalities = generate),
            AiModelInfo("gemini-2.5-pro", outputModalities = generate),
            AiModelInfo("gemini-embedding-001", outputModalities = listOf("embedContent")),
            AiModelInfo("imagen-4.0-generate-001", outputModalities = listOf("predict")),
            AiModelInfo("veo-3.0-generate-001", outputModalities = listOf("predictLongRunning")),
            AiModelInfo("gemini-2.5-flash-image", outputModalities = generate),
            AiModelInfo("gemini-live-2.5-flash", outputModalities = generate),
            AiModelInfo("gemini-2.5-flash-tts", outputModalities = generate))
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"),
            GeminiModels.textTranslationModels(models).map { it.id })
    }

    /** Gemini rejects a JSON Schema: it wants the OpenAPI subset, upper case and no extras. */
    @Test fun geminiSchemaUsesTheOpenApiDialect() {
        val schema = AiProtocol.geminiSchema()
        assertEquals("OBJECT", schema.getString("type"))
        assertFalse(schema.has("additionalProperties"))
        val fragment = schema.getJSONObject("properties").getJSONObject("fragments").getJSONObject("items")
        assertEquals("OBJECT", fragment.getString("type"))
        assertFalse(fragment.has("additionalProperties"))
        assertEquals(3, fragment.getJSONArray("required").length())
        assertEquals("ARRAY", fragment.getJSONObject("properties").getJSONObject("source_block_ids").getString("type"))
        assertEquals("INTEGER", fragment.getJSONObject("properties")
            .getJSONObject("source_block_ids").getJSONObject("items").getString("type"))
    }

    @Test fun geminiEnvelopeReadsPartsAndReportsRefusals() {
        assertEquals("ANSWER", AiProtocol.geminiContent(
            """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"ANS"},{"text":"WER"}]}}]}"""))
        // A cut-off or refused answer arrives as a finishReason, not as an HTTP error.
        assertThrows(AiFormatException::class.java) {
            AiProtocol.geminiContent("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{"}]}}]}""")
        }
        assertThrows(AiFormatException::class.java) {
            AiProtocol.geminiContent("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        }
        assertThrows(AiFormatException::class.java) { AiProtocol.geminiContent("""{"candidates":[]}""") }
    }

    @Test fun routerListDropsWhatCannotTranslateAndGroupsByVendor() {
        val text = listOf("text")
        val models = listOf(
            AiModelInfo("openai/gpt-5", inputModalities = listOf("text", "image"), outputModalities = text),
            AiModelInfo("anthropic/claude-sonnet-4.5", inputModalities = text, outputModalities = text),
            AiModelInfo("google/gemini-2.5-flash-image", inputModalities = text, outputModalities = listOf("image")),
            AiModelInfo("openai/text-embedding-3-large", outputModalities = text),
            AiModelInfo("black-forest-labs/flux-1.1-pro", outputModalities = listOf("image")),
            AiModelInfo("openai/whisper-large", inputModalities = listOf("audio"), outputModalities = text),
            // A gateway that reports no modalities at all still lists text models.
            AiModelInfo("meta-llama/llama-4-maverick"))
        assertEquals(listOf("anthropic/claude-sonnet-4.5", "meta-llama/llama-4-maverick", "openai/gpt-5"),
            OpenRouterModels.textTranslationModels(models).map { it.id })
    }

    @Test fun routerReasoningIsSwitchedOffBeforeItIsShortened() {
        // Minimal means "do not think at all" first, and only then "think as little as you can".
        assertEquals(listOf(RouterReasoning(enabled = false), RouterReasoning(effort = "low"), null),
            AiReasoning.routerLadder("openai/gpt-5", AiEffort.MINIMAL))
        assertEquals(listOf(RouterReasoning(effort = "high"), null),
            AiReasoning.routerLadder("anthropic/claude-sonnet-4.5", AiEffort.HIGH))
        // The last rung of every ladder sends no reasoning parameter at all.
        AiEffort.entries.forEach { assertNull(AiReasoning.routerLadder("x/y", it).last()) }
        assertEquals("отключено", AiReasoning.describe(RouterReasoning(enabled = false)))
        assertEquals("medium", AiReasoning.describe(RouterReasoning(effort = "medium")))
        assertEquals("по умолчанию модели", AiReasoning.describe(null as RouterReasoning?))
    }

    /** An id says nothing about a model behind the router, so no level is hidden from the user. */
    @Test fun routerOffersEveryLevelAndAlwaysSearches() {
        assertEquals(AiEffort.entries, AiReasoning.levels(AiProvider.OPENROUTER, "qwen/qwen3-max"))
        assertTrue(AiReasoning.searchable(AiProvider.OPENROUTER, "qwen/qwen3-max"))
    }

    // --- Промпты --------------------------------------------------------------------------------

    @Test fun emptyPlaceholderTakesItsWholeLineAway() {
        assertEquals("Target: Russian\nEnd", AiPrompts.render(
            "Target: {{TARGET_LANGUAGE}}\nGlossary: {{GLOSSARY}}\nEnd",
            mapOf("TARGET_LANGUAGE" to "Russian", "GLOSSARY" to "")))
    }

    @Test fun unknownPlaceholderNeverReachesTheModel() {
        assertEquals("B", AiPrompts.render("A {{NOPE}}\nB", emptyMap()))
    }

    @Test fun substitutionHandlesRepeats_spacing_andStrayBraces() {
        val values = mapOf("A" to "1", "B" to "2")
        assertEquals("1 and 2 and 1", AiPrompts.render("{{A}} and {{ B }} and {{A}}", values))
        // A brace that opens nothing is text, not a syntax error.
        assertEquals("use {} and { {A}", AiPrompts.render("use {} and { {A}", values))
        assertEquals("half {{A", AiPrompts.render("half {{A", values))
        // Blank runs left by dropped lines collapse instead of stacking up.
        assertEquals("x\n\ny", AiPrompts.render("x\n\n{{GONE}}\n{{GONE}}\n\ny", values))
    }

    @Test fun responsesApiIsPreferredForGrok4AndNewer() {
        listOf("grok-4.6", "grok-4", "grok-4.20-0309-reasoning", "GROK-9")
            .forEach { assertTrue(it, XaiClient.prefersResponsesApi(it)) }
        listOf("grok-3", "grok-2-1212", "grok-beta", "some-other-model")
            .forEach { assertFalse(it, XaiClient.prefersResponsesApi(it)) }
    }

    @Test fun repairSwitchRewritesTheJoiningRule() {
        val on = AiPrompts.system("game", "auto-detect", "Russian", repair = true)
        val off = AiPrompts.system("game", "auto-detect", "Russian", repair = false)
        assertTrue(on.contains("join a word broken across blocks"))
        assertTrue(off.contains("Never join blocks"))
        assertFalse(off.contains("join a word broken across blocks"))
        listOf(on, off).forEach {
            assertTrue(it.contains("Russian"))
            assertFalse("Unresolved template reached the model", it.contains("{{"))
        }
    }

    // --- Разбор ответа --------------------------------------------------------------------------

    @Test fun unwrapSurvivesFencesAndChatter() {
        assertEquals("""{"a":1}""", AiProtocol.unwrap("```json\n{\"a\":1}\n```"))
        assertEquals("""{"a":1}""", AiProtocol.unwrap("Here you go:\n{\"a\":1}\nHope that helps."))
        assertEquals("""{"a":1}""", AiProtocol.unwrap("""{"a":1}"""))
    }

    @Test fun parseAcceptsBareArrayAndQuotedIds() {
        val parsed = AiProtocol.parse("""[{"source_block_ids":["7"],"corrected_source_text":"A","translated_text":"А"}]""")
        assertEquals(listOf(7L), parsed.single().sourceBlockIds)
        assertEquals("А", parsed.single().translatedText)
    }

    @Test fun parseRejectsWhatCannotBeDrawn() {
        assertThrows(AiFormatException::class.java) { AiProtocol.parse("sorry, I cannot help with that") }
        assertThrows(AiFormatException::class.java) { AiProtocol.parse("""{"answer":[]}""") }
        assertThrows(AiFormatException::class.java) { AiProtocol.parse("""{"fragments":[]}""") }
        assertThrows(AiFormatException::class.java) {
            AiProtocol.parse(fragments(fragment("[]", "A", "А")))
        }
    }

    // --- Конверты двух API ----------------------------------------------------------------------

    @Test fun chatEnvelopeYieldsMessageContent() {
        assertEquals("""{"fragments":[]}""", AiProtocol.chatContent(
            """{"choices":[{"message":{"role":"assistant","content":"{\"fragments\":[]}"}}]}"""))
        assertThrows(AiFormatException::class.java) { AiProtocol.chatContent("""{"choices":[]}""") }
        assertThrows(AiFormatException::class.java) {
            AiProtocol.chatContent("""{"choices":[{"message":{"content":""}}]}""")
        }
        assertThrows(AiFormatException::class.java) { AiProtocol.chatContent("<html>502</html>") }
    }

    /** A reasoning model emits its thinking as its own output item; it is not part of the answer. */
    @Test fun responsesEnvelopeSkipsTheReasoningItem() {
        assertEquals("ANSWER", AiProtocol.responsesContent(
            """{"output":[{"type":"reasoning","content":[{"type":"text","text":"thinking"}]},
               {"type":"message","content":[{"type":"output_text","text":"ANSWER"}]}]}"""))
        assertEquals("SHORTCUT", AiProtocol.responsesContent(
            """{"output_text":"SHORTCUT","output":[{"type":"message","content":[{"text":"ignored"}]}]}"""))
        assertThrows(AiFormatException::class.java) {
            AiProtocol.responsesContent("""{"output":[{"type":"reasoning","content":[{"text":"only thinking"}]}]}""")
        }
        assertThrows(AiFormatException::class.java) { AiProtocol.responsesContent("""{"id":"x"}""") }
    }

    // --- Смысловая валидация --------------------------------------------------------------------

    @Test fun everyBlockMustBeCoveredExactlyOnce() {
        val blocks = listOf(block(1), block(2, top = 20f))
        val onlyOne = AiProtocol.parse(fragments(fragment("[1]", "A", "А")))
        assertEquals("Block ids [2] were left out of the answer.",
            assertThrows(AiFormatException::class.java) {
                AiProtocol.validate(onlyOne, blocks, allowMerge = true)
            }.reason)
        val twice = AiProtocol.parse(fragments(fragment("[1]", "A", "А"), fragment("[1]", "A", "А")))
        assertThrows(AiFormatException::class.java) { AiProtocol.validate(twice, blocks, allowMerge = true) }
    }

    @Test fun inventedIdIsRejected() {
        val answer = AiProtocol.parse(fragments(fragment("[1]", "A", "А"), fragment("[9]", "B", "Б")))
        assertThrows(AiFormatException::class.java) {
            AiProtocol.validate(answer, listOf(block(1)), allowMerge = true)
        }
    }

    @Test fun emptyTranslationIsRejected() {
        val answer = AiProtocol.parse(fragments(fragment("[1]", "A", "")))
        assertThrows(AiFormatException::class.java) {
            AiProtocol.validate(answer, listOf(block(1)), allowMerge = true)
        }
    }

    /** A menu whose items sit far apart is not one repaired sentence, whatever the JSON claims. */
    @Test fun scatteredBlocksMayNotBeJoined() {
        val menu = listOf(block(1, "Settings", top = 0f), block(2, "Exit", top = 900f))
        val joined = AiProtocol.parse(fragments(fragment("[1,2]", "Settings Exit", "Настройки Выход")))
        assertThrows(AiFormatException::class.java) { AiProtocol.validate(joined, menu, allowMerge = true) }

        val neighbours = listOf(block(1, "Compensation o", top = 0f), block(2, "f maintenance", top = 20f))
        AiProtocol.validate(joined, neighbours, allowMerge = true)
    }

    @Test fun joiningIsRefusedWhenRepairIsOff() {
        val neighbours = listOf(block(1, top = 0f), block(2, top = 20f))
        val joined = AiProtocol.parse(fragments(fragment("[1,2]", "A B", "А Б")))
        assertThrows(AiFormatException::class.java) { AiProtocol.validate(joined, neighbours, allowMerge = false) }
    }

    // --- Порядок чтения и пакеты ----------------------------------------------------------------

    @Test fun readingOrderIsRowsThenColumns() {
        val blocks = listOf(
            block(4, top = 100f, left = 200f), block(2, top = 0f, left = 200f),
            block(3, top = 100f, left = 0f), block(1, top = 0f, left = 0f),
            block(5, top = 104f, left = 400f))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L),
            AiTranslator.readingOrder(blocks).map { it.id })
    }

    @Test fun batchesRespectBothLimits() {
        val many = (1L..20L).map { block(it, top = it * 30f) }
        val packets = AiTranslator.batches(many)
        assertEquals(3, packets.size)
        assertTrue(packets.all { it.size <= AiTranslator.BLOCKS_PER_REQUEST })
        assertEquals(many.map { it.id }, packets.flatten().map { it.id })

        val long = (1L..4L).map { block(it, "x".repeat(1200), top = it * 30f) }
        assertTrue(AiTranslator.batches(long).all { packet ->
            packet.size == 1 || packet.sumOf { it.originalText.length } <= AiTranslator.CHARS_PER_REQUEST
        })
    }

    @Test fun languageNamesAreWhatTheModelReads() {
        assertEquals("Russian", AiTranslator.language("ru"))
        assertEquals("Japanese", AiTranslator.language("ja"))
        assertEquals("auto-detect", AiTranslator.language("auto"))
    }

    // --- Оркестрация ----------------------------------------------------------------------------

    @Test fun joinedFragmentBecomesOneCardOverBothBlocks() = runBlocking {
        val blocks = listOf(block(1, "Compensation o", top = 0f), block(2, "f maintenance", top = 20f))
        val engine = FakeEngine {
            fragments(fragment("[1,2]", "Compensation of maintenance", "Компенсация за обслуживание"))
        }
        val shown = mutableListOf<ScreenTextBlock>()
        val outcome = AiTranslator(engine).translate(
            blocks, AppSettings(mode = TranslationMode.AI), AiSettings(model = "grok-4"), "token", null) { shown += it }
        assertTrue(outcome.untranslated.isEmpty())
        val card = shown.single()
        assertEquals("Компенсация за обслуживание", card.translatedText)
        assertEquals("Compensation of maintenance", card.originalText)
        assertEquals(Box(0f, 0f, 100f, 40f), card.boundingBox)
    }

    @Test fun rejectedAnswerIsRetriedWithItsReasonAndThenSucceeds() = runBlocking {
        val blocks = listOf(block(1, "A", top = 0f), block(2, "B", top = 40f))
        var second: String? = null
        val engine = FakeEngine { user ->
            if (second == null && !user.contains("rejected")) fragments(fragment("[1]", "A", "А"))
            else { second = user; fragments(fragment("[1]", "A", "А"), fragment("[2]", "B", "Б")) }
        }
        val shown = mutableListOf<ScreenTextBlock>()
        val outcome = AiTranslator(engine).translate(
            blocks, AppSettings(), AiSettings(model = "grok-4"), "token", null) { shown += it }
        assertEquals(2, engine.calls)
        assertTrue("The rejection reason must reach the model", second!!.contains("were left out"))
        assertTrue(outcome.untranslated.isEmpty())
        assertEquals(listOf("А", "Б"), shown.map { it.translatedText })
    }

    @Test fun answerThatNeverParsesIsHandedBackForFallback() = runBlocking {
        val blocks = listOf(block(1), block(2, top = 40f))
        val engine = FakeEngine { "I'm sorry, I can't do that." }
        val outcome = AiTranslator(engine).translate(
            blocks, AppSettings(), AiSettings(model = "grok-4"), "token", null) { }
        assertEquals(2, engine.calls)
        assertEquals(listOf(1L, 2L), outcome.untranslated.map { it.id })
        assertNotNull(outcome.reason)
    }

    /** Three packets of eight: enough to tell "stopped at once" from "tried every packet". */
    private val crowd = (1L..24L).map { block(it, "Line $it", top = it * 30f) }

    @Test fun refusedKeyStopsAtTheFirstPacketAndHandsEverythingToFallback() = runBlocking {
        val engine = FakeEngine { throw AiHttpException(401, "Неверный или отозванный API-ключ") }
        val outcome = AiTranslator(engine).translate(crowd, AppSettings(), AiSettings(model = "grok-4"), "token", null) { }
        assertEquals("A dead key must not be retried packet after packet", 1, engine.calls)
        assertEquals(crowd.map { it.id }, outcome.untranslated.map { it.id })
        assertEquals("Неверный или отозванный API-ключ", outcome.reason)
    }

    @Test fun lostNetworkStopsAtTheFirstPacket() = runBlocking {
        val engine = FakeEngine { throw java.io.IOException("timeout") }
        val outcome = AiTranslator(engine).translate(crowd, AppSettings(), AiSettings(model = "grok-4"), "token", null) { }
        assertEquals(1, engine.calls)
        assertEquals(crowd.size, outcome.untranslated.size)
    }

    @Test fun packetSpecificRefusalLetsTheOtherPacketsThrough() = runBlocking {
        var call = 0
        val engine = FakeEngine { user ->
            // Only the first packet is refused; the rest are answered with exactly their own ids.
            if (call++ == 0) throw AiHttpException(400, "Prompt too long")
            val ids = Regex("\"id\":(\\d+)").findAll(user).map { it.groupValues[1].toInt() }.toList()
            fragments(*ids.map { fragment("[$it]", "Line $it", "Строка $it") }.toTypedArray())
        }
        val shown = mutableListOf<ScreenTextBlock>()
        val outcome = AiTranslator(engine).translate(crowd, AppSettings(), AiSettings(model = "grok-4"), "token", null) { shown += it }
        assertEquals(3, engine.calls)
        assertEquals((1L..8L).toList(), outcome.untranslated.map { it.id })
        assertEquals(16, shown.size)
    }

    @Test fun cacheIsUsedOnlyWhenJoiningIsOff() = runBlocking {
        val answer = fragments(fragment("[1]", "Start", "Старт"))
        val screen = listOf(block(1, "Start"))

        val plain = FakeEngine { answer }
        val store = MemoryStore()
        val translator = AiTranslator(plain)
        val settings = AppSettings()
        val without = AiSettings(model = "grok-4", repair = false)
        translator.translate(screen, settings, without, "token", store) { }
        translator.translate(screen, settings, without, "token", store) { }
        assertEquals("The second screen must come from the cache", 1, plain.calls)

        val joining = FakeEngine { answer }
        val other = MemoryStore()
        AiTranslator(joining).apply {
            translate(screen, settings, AiSettings(model = "grok-4"), "token", other) { }
            translate(screen, settings, AiSettings(model = "grok-4"), "token", other) { }
        }
        assertEquals("Joining makes a fragment depend on neighbours, so nothing may be cached", 2, joining.calls)
        assertTrue(other.values.isEmpty())
    }

    @Test fun textAlreadyInTargetLanguageNeverReachesTheModel() = runBlocking {
        val engine = FakeEngine { fail("The model must not be called"); "" }
        val outcome = AiTranslator(engine).translate(
            listOf(block(1, "Старт", language = "ru")), AppSettings(target = "ru"),
            AiSettings(model = "grok-4"), "token", null) { }
        assertEquals(0, engine.calls)
        assertTrue(outcome.untranslated.isEmpty())
    }

    @Test fun missingTokenOrModelSendsTheWholeScreenToFallback() = runBlocking {
        val engine = FakeEngine { fail("The model must not be called"); "" }
        val screen = listOf(block(1), block(2, top = 40f))
        val translator = AiTranslator(engine)
        assertEquals(screen, translator.translate(screen, AppSettings(), AiSettings(model = "grok-4"), "", null) { }.untranslated)
        assertEquals(screen, translator.translate(screen, AppSettings(), AiSettings(), "token", null) { }.untranslated)
        assertEquals(0, engine.calls)
    }
}
