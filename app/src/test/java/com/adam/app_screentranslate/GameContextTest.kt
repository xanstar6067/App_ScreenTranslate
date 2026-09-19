package com.adam.app_screentranslate

import com.adam.app_screentranslate.data.TranslationStore
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.translation.ai.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GameContextTest {
    private val nikke = GameProfile("com.proximabeta.nikke", "GODDESS OF VICTORY: NIKKE", customName = "NIKKE",
        notes = "Commander is addressed informally.")
    private val glossary = listOf(
        GlossaryEntry("Rapture", "Рапчер"),
        GlossaryEntry("Rapture Queen", "Королева рапчеров"),
        GlossaryEntry("Commander", "Командир"),
        GlossaryEntry("Ark", "Ковчег", TermKind.LOCATION),
        GlossaryEntry("NIKKE", "NIKKE", keep = true),
        GlossaryEntry("Rapi", "Рапи", TermKind.CHARACTER))

    private fun block(id: Long, text: String, top: Float = id * 30f) =
        ScreenTextBlock(id, text, Box(0f, top, 200f, top + 20f), detectedLanguage = "en")

    /** Answers every packet with exactly its own ids, and remembers what it was sent. */
    private class RecordingEngine : AiEngine {
        val systems = mutableListOf<String>()
        val payloads = mutableListOf<String>()
        override val provider = AiProvider.XAI
        override suspend fun models(token: String) = emptyList<AiModelInfo>()
        override fun usable(models: List<AiModelInfo>) = models
        override fun describe(model: String) = emptyList<String>()
        override suspend fun research(token: String, model: String, system: String, user: String,
                                      effort: AiEffort, search: Boolean) = AiAnswer("")
        override suspend fun translate(token: String, model: String, system: String, user: String,
                                       effort: AiEffort): String {
            systems += system; payloads += user
            val blocks = JSONObject(user).getJSONArray("blocks")
            val fragments = (0 until blocks.length()).joinToString(",") { i ->
                val id = blocks.getJSONObject(i).getLong("id")
                """{"source_block_ids":[$id],"corrected_source_text":"t$id","translated_text":"п$id"}"""
            }
            return """{"fragments":[$fragments]}"""
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

    // --- Какие термины уходят в запрос -----------------------------------------------------------

    @Test fun aTermMatchesTheStartOfAWordOnly() {
        assertTrue(AiContext.mentions("raptures approaching", "rapture"))
        assertTrue(AiContext.mentions("the rapture's core", "rapture"))
        assertTrue(AiContext.mentions("ai core online", "ai"))
        assertFalse("\"ai\" must not be found inside \"said\"", AiContext.mentions("she said so", "ai"))
        assertFalse(AiContext.mentions("darkness falls", "ark"))
        // Scripts without spaces have no word start: a term matches anywhere in the line.
        assertTrue(AiContext.mentions("敵ラプチャーが来る", "ラプチャー"))
        assertFalse(AiContext.mentions("anything", "  "))
    }

    @Test fun onlyTermsOnScreenAreSentLongestFirst() {
        val sent = AiContext.relevant(glossary, listOf("The RAPTURE QUEEN attacks", "Commander!", "Darkness falls"))
        assertEquals(listOf("Rapture Queen", "Commander", "Rapture"), sent.map { it.term })
    }

    @Test fun aHugeGlossaryIsCapped() {
        val many = (1..100).map { GlossaryEntry("Term$it", "Термин$it") }
        val sent = AiContext.relevant(many, listOf(many.joinToString(" ") { it.term }))
        assertEquals(AiContext.MAX_GLOSSARY, sent.size)
    }

    @Test fun glossaryTravelsInThePayloadOnlyWhenThereIsOne() {
        val screen = listOf(block(1, "NIKKE"))
        assertFalse(JSONObject(AiProtocol.payload(screen, "English", "Russian")).has("glossary"))

        val terms = JSONObject(AiProtocol.payload(screen, "English", "Russian", glossary))
            .getJSONArray("glossary")
        val byTerm = (0 until terms.length()).map { terms.getJSONObject(it) }.associateBy { it.getString("term") }
        assertEquals("Рапчер", byTerm.getValue("Rapture").getString("translation"))
        // "Do not translate" is sent as a translation equal to the term, which the prompt explains.
        assertEquals("NIKKE", byTerm.getValue("NIKKE").getString("translation"))
        // Names are marked as names; ordinary terms carry no kind at all.
        assertEquals("character", byTerm.getValue("Rapi").getString("kind"))
        assertEquals("location", byTerm.getValue("Ark").getString("kind"))
        assertFalse(byTerm.getValue("Rapture").has("kind"))
    }

    // --- Промпт ---------------------------------------------------------------------------------

    @Test fun systemPromptNamesTheGameOnlyWhenGivenOne() {
        val with = AiPrompts.system(AiPrompts.DEFAULT, "English", "Russian", repair = true, game = nikke)
        assertTrue(with.contains("\"NIKKE\""))
        assertTrue(with.contains("com.proximabeta.nikke"))
        assertTrue(with.contains("Commander is addressed informally."))

        val without = AiPrompts.system(AiPrompts.DEFAULT, "English", "Russian", repair = true)
        assertFalse(without.contains("Android package"))
        assertFalse(without.contains("{{"))
        // The glossary rule belongs to the contract and holds with or without a game.
        assertTrue(without.contains("glossary is authoritative"))
    }

    @Test fun blankNotesLeaveNoEmptyHeading() {
        val prompt = AiPrompts.system(AiPrompts.DEFAULT, "English", "Russian", repair = true, game = nikke.copy(notes = "  "))
        assertFalse(prompt.contains("Notes from the player"))
    }

    // --- Профиль и настройки ----------------------------------------------------------------------

    @Test fun anEnabledProfileOverridesOnlyWhatItSets() {
        val general = AppSettings(source = "auto", target = "ru")
        assertEquals(general, general.forGame(null))
        assertEquals(general, general.forGame(nikke.copy(source = "ja", enabled = false)))
        val japanese = general.forGame(nikke.copy(source = "ja"))
        assertEquals("ja", japanese.source)
        assertEquals("ru", japanese.target)
    }

    @Test fun titleFallsBackFromCustomNameToLabelToPackage() {
        assertEquals("NIKKE", nikke.title)
        assertEquals("GODDESS OF VICTORY: NIKKE", nikke.copy(customName = " ").title)
        assertEquals("com.proximabeta.nikke", nikke.copy(customName = "", label = "").title)
    }

    // --- Перевод с контекстом ---------------------------------------------------------------------

    @Test fun translatorSendsTheGameAndOnlyTheTermsOfEachPacket() = runBlocking {
        val engine = RecordingEngine()
        // Nine blocks make two packets: the first mentions Rapture, the second Commander.
        val screen = (1L..8L).map { block(it, if (it == 1L) "Raptures approaching" else "Line $it") } +
            block(9, "Commander, look!")
        val outcome = AiTranslator(engine).translate(screen, AppSettings(), AiSettings(model = "grok-4"), "token",
            null, GameContext(nikke, glossary)) { }
        assertTrue(outcome.untranslated.isEmpty())
        assertEquals(2, engine.payloads.size)
        assertTrue(engine.systems.all { it.contains("com.proximabeta.nikke") })

        fun terms(payload: String) = JSONObject(payload).optJSONArray("glossary")
            ?.let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("term") } }.orEmpty()
        assertEquals(listOf("Rapture"), terms(engine.payloads[0]))
        assertEquals(listOf("Commander"), terms(engine.payloads[1]))
    }

    @Test fun withoutContextNothingAboutTheGameLeaves() = runBlocking {
        val engine = RecordingEngine()
        AiTranslator(engine).translate(listOf(block(1, "Raptures approaching")), AppSettings(),
            AiSettings(model = "grok-4"), "token", null, game = null) { }
        assertFalse(engine.systems.single().contains("nikke", ignoreCase = true))
        assertFalse(JSONObject(engine.payloads.single()).has("glossary"))
    }

    @Test fun cachedTranslationsAreKeptApartByContext() = runBlocking {
        val engine = RecordingEngine()
        val store = MemoryStore()
        val translator = AiTranslator(engine)
        val oneToOne = AiSettings(model = "grok-4", repair = false)
        val screen = listOf(block(1, "Raptures approaching"))
        val context = GameContext(nikke, glossary)

        translator.translate(screen, AppSettings(), oneToOne, "token", store, context) { }
        translator.translate(screen, AppSettings(), oneToOne, "token", store, context) { }
        assertEquals("The same context is served from the cache", 1, engine.payloads.size)

        translator.translate(screen, AppSettings(), oneToOne, "token", store, null) { }
        assertEquals("No context is a different translation", 2, engine.payloads.size)

        val edited = context.copy(glossary = glossary.map { if (it.term == "Rapture") it.copy(translation = "Восторг") else it })
        translator.translate(screen, AppSettings(), oneToOne, "token", store, edited) { }
        assertEquals("An edited glossary is a different translation", 3, engine.payloads.size)
    }

    @Test fun fingerprintIgnoresGlossaryOrderButNotItsContent() {
        val a = AiContext.fingerprint(GameContext(nikke, glossary))
        assertEquals(a, AiContext.fingerprint(GameContext(nikke, glossary.reversed())))
        assertNotEquals(a, AiContext.fingerprint(GameContext(nikke, glossary.dropLast(1))))
        assertNotEquals(a, AiContext.fingerprint(GameContext(nikke.copy(notes = "Other"), glossary)))
        assertEquals(12, a.length)
    }
}
