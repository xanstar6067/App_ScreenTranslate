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
        override suspend fun translate(token: String, model: String, system: String, user: String): String {
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

    // --- Промпты --------------------------------------------------------------------------------

    @Test fun emptyPlaceholderTakesItsWholeLineAway() {
        assertEquals("Target: Russian\nEnd", AiPrompts.render(
            "Target: {{TARGET_LANGUAGE}}\nGlossary: {{GLOSSARY}}\nEnd",
            mapOf("TARGET_LANGUAGE" to "Russian", "GLOSSARY" to "")))
    }

    @Test fun unknownPlaceholderNeverReachesTheModel() {
        assertEquals("B", AiPrompts.render("A {{NOPE}}\nB", emptyMap()))
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
