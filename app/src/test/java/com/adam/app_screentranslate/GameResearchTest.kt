package com.adam.app_screentranslate

import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.translation.ai.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GameResearchTest {
    private val nikke = GameProfile("com.proximabeta.nikke", label = "NIKKE", notes = "Командира зовут на «вы».")
    private val known = listOf(GlossaryEntry("Rapture", "Рапчер", TermKind.FACTION, id = 7),
        GlossaryEntry("NIKKE", "NIKKE", keep = true, id = 8))
    private fun request(kinds: Set<TermKind> = TermKind.entries.toSet(), notes: Boolean = true, limit: Int = 30) =
        ResearchRequest(nikke, known, "English", "Russian", kinds, notes, limit, "главы 1–3")

    private fun answer(vararg terms: String, notes: String = "Военная фантастика.", found: Boolean = true) =
        """{"game_found":$found,"official_title":"GODDESS OF VICTORY: NIKKE","notes":"$notes","terms":[${terms.joinToString(",")}]}"""
    private fun term(term: String, translation: String, kind: String = "character", keep: Boolean = false,
                     basis: String = "official", gender: String? = null): String {
        val genderField = if (gender != null) ",\"gender\":\"$gender\"" else ""
        return """{"term":"$term","translation":"$translation","kind":"$kind"$genderField,"keep":$keep,"basis":"$basis","comment":"c"}"""
    }

    // --- Рассуждение и поиск ----------------------------------------------------------------------

    @Test fun xaiLevelsFollowModelFamily() {
        assertEquals(AiEffort.entries, AiReasoning.levels(AiProvider.XAI, "grok-4.5"))
        assertEquals(listOf(AiEffort.LOW, AiEffort.HIGH), AiReasoning.levels(AiProvider.XAI, "grok-3-mini-fast"))
        assertTrue(AiReasoning.levels(AiProvider.XAI, "grok-4-fast-non-reasoning").isEmpty())
        assertTrue(AiReasoning.levels(AiProvider.XAI, "grok-3").isEmpty())
        assertTrue(AiReasoning.searchable(AiProvider.XAI, "grok-4.20"))
        assertFalse(AiReasoning.searchable(AiProvider.XAI, "grok-3"))
    }

    /**
     * A model shipped after this code was written. Nothing about it is special — that is the point:
     * the version is read from the id, so grok-4.7 gets the same levels, the same ladder and the
     * same endpoint as every other grok-4, with no entry anywhere naming it.
     */
    @Test fun aModelReleasedLaterIsHandledByVersionRatherThanByName() {
        assertEquals(4, AiReasoning.grokMajor("grok-4.7"))
        assertEquals(AiEffort.entries, AiReasoning.levels(AiProvider.XAI, "grok-4.7"))
        assertTrue(AiReasoning.searchable(AiProvider.XAI, "grok-4.7"))
        assertTrue(XaiClient.prefersResponsesApi("grok-4.7"))
        assertEquals(listOf("low", null), AiReasoning.xaiLadder("grok-4.7", AiEffort.MINIMAL))
        assertEquals(listOf("medium", "high", null), AiReasoning.xaiLadder("grok-4.7", AiEffort.MEDIUM))
        assertEquals(listOf("high", null), AiReasoning.xaiLadder("grok-4.7", AiEffort.HIGH))
        // The dated and non-reasoning builds of the same family keep their own answers.
        assertEquals(AiEffort.entries, AiReasoning.levels(AiProvider.XAI, "grok-4.20-0309-reasoning"))
        assertTrue(AiReasoning.levels(AiProvider.XAI, "grok-4.20-0309-non-reasoning").isEmpty())
        assertEquals(listOf(null), AiReasoning.xaiLadder("grok-4.20-0309-non-reasoning", AiEffort.HIGH))
    }

    @Test fun xaiLadderNeverDisablesReasoningAndEndsWithoutIt() {
        assertEquals(listOf("low", null), AiReasoning.xaiLadder("grok-4.5", AiEffort.MINIMAL))
        assertEquals(listOf("medium", "high", null), AiReasoning.xaiLadder("grok-4.5", AiEffort.MEDIUM))
        assertEquals(listOf("low", null), AiReasoning.xaiLadder("grok-3-mini", AiEffort.MEDIUM))
        assertEquals(listOf("high", null), AiReasoning.xaiLadder("grok-3-mini", AiEffort.HIGH))
        assertEquals(listOf<String?>(null), AiReasoning.xaiLadder("grok-2", AiEffort.HIGH))
    }

    @Test fun geminiVersionIsReadByHand() {
        assertEquals(2.5, AiReasoning.gemini("gemini-2.5-flash-lite")!!, 0.0)
        assertEquals(3.0, AiReasoning.gemini("gemini-3-pro-preview")!!, 0.0)
        assertEquals(3.1, AiReasoning.gemini("gemini-3.1-pro")!!, 0.0)
        assertEquals(3.0, AiReasoning.gemini("gemini-flash-latest")!!, 0.0)
        assertNull(AiReasoning.gemini("gemma-3-27b-it"))
        assertTrue(AiReasoning.levels(AiProvider.GEMINI, "gemini-2.0-flash").isEmpty())
        assertTrue(AiReasoning.searchable(AiProvider.GEMINI, "gemini-2.0-flash"))
        assertFalse(AiReasoning.searchable(AiProvider.GEMINI, "gemma-3-27b-it"))
    }

    @Test fun geminiLadderUsesLevelOnThreeAndBudgetOnTwoFive() {
        assertEquals(listOf(GeminiThinking(level = "minimal"), GeminiThinking(level = "low"), null),
            AiReasoning.geminiLadder("gemini-3-flash-preview", AiEffort.MINIMAL))
        assertEquals(listOf(GeminiThinking(level = "medium"), GeminiThinking(level = "high"), null),
            AiReasoning.geminiLadder("gemini-3-pro-preview", AiEffort.MEDIUM))
        // Pro refuses a zero budget; 128 is its floor.
        assertEquals(listOf(GeminiThinking(budget = 0), GeminiThinking(budget = 128), null),
            AiReasoning.geminiLadder("gemini-2.5-pro", AiEffort.MINIMAL))
        assertEquals(listOf(GeminiThinking(budget = 24576), null), AiReasoning.geminiLadder("gemini-2.5-flash", AiEffort.HIGH))
        assertEquals(listOf<GeminiThinking?>(null), AiReasoning.geminiLadder("gemini-2.0-flash", AiEffort.HIGH))
    }

    @Test fun refusalsAreRecognizedByWhatTheyName() {
        assertTrue(AiReasoning.refusesReasoning("Unsupported parameter: reasoning_effort"))
        assertTrue(AiReasoning.refusesReasoning("Thinking budget is invalid for this model"))
        assertFalse(AiReasoning.refusesReasoning("Model not found"))
        assertTrue(AiReasoning.refusesSearch("Tool web_search is not supported"))
        assertTrue(AiReasoning.refusesSearch("Search Grounding is not supported"))
        assertFalse(AiReasoning.refusesSearch("Request too large"))
    }

    // --- Что уходит в запрос ------------------------------------------------------------------------

    @Test fun payloadCarriesTheProfileAndOnlyKnownTermNames() {
        val root = JSONObject(GameResearch.payload(request(kinds = setOf(TermKind.CHARACTER, TermKind.LOCATION))))
        assertEquals("NIKKE", root.getJSONObject("game").getString("title"))
        assertEquals("com.proximabeta.nikke", root.getJSONObject("game").getString("package"))
        assertEquals("Командира зовут на «вы».", root.getString("player_notes"))
        assertEquals("главы 1–3", root.getString("focus"))
        assertEquals(listOf("character", "location"),
            root.getJSONArray("wanted_kinds").let { a -> (0 until a.length()).map { a.getString(it) } })
        val names = root.getJSONArray("known_terms")
        assertEquals(listOf("Rapture", "NIKKE"), (0 until names.length()).map { names.getString(it) })
        // Only term names leave the device: the player's renderings stay.
        assertFalse(root.toString().contains("Рапчер"))
    }

    @Test fun knownTermsAreCapped() {
        val many = (1..500).map { GlossaryEntry("T$it", "t") }
        val root = JSONObject(GameResearch.payload(ResearchRequest(nikke, many, "English", "Russian")))
        assertEquals(GameResearch.MAX_KNOWN, root.getJSONArray("known_terms").length())
    }

    @Test fun promptMentionsSearchOnlyWhenOn() {
        assertTrue(GameResearch.system(true).contains("Search the web"))
        assertTrue(GameResearch.system(false).contains("no web access"))
        assertFalse(GameResearch.system(true).contains("\${"))
        assertTrue(GameResearch.system(true).contains("\"gender\""))
    }

    // --- Разбор ответа ----------------------------------------------------------------------------

    @Test fun parseCleansEveryEntry() {
        val raw = "Here you go:\n```json\n" + answer(
            term("Rapi", "Рапи"),
            term("rapi", "Рэпи"),                              // repeat, other case
            term("Ark", "Ковчег", kind = "location", basis = "community"),
            term("", "пусто"),                                 // no term
            term("Bullet", "", kind = "term"),                 // no translation, not kept
            term("Tetra Line", "Tetra Line", kind = "faction"),// equal to term: kept
            term("Outpost", "Аванпост", kind = "weird", basis = "guess")) + "\n```"
        val result = GameResearch.parse(raw, request())
        assertTrue(result.found)
        assertEquals("GODDESS OF VICTORY: NIKKE", result.title)
        assertEquals(listOf("Rapi", "Ark", "Tetra Line", "Outpost"), result.terms.map { it.entry.term })
        assertEquals(TermBasis.COMMUNITY, result.terms[1].basis)
        assertTrue(result.terms[2].entry.keep)
        assertEquals(TermKind.TERM, result.terms[3].entry.kind)
        assertEquals(TermBasis.SUGGESTED, result.terms[3].basis)
    }

    @Test fun genderIsKeptOnlyForACharacter() {
        val raw = answer(term("Rapi", "Рапи", gender = "female"),
            term("Ark", "Ковчег", kind = "location", gender = "female"),
            term("Anis", "Анис", gender = "unknown"), term("Neon", "Неон"))
        val result = GameResearch.parse(raw, request())
        val byTerm = result.terms.associateBy { it.entry.term }
        assertEquals(Gender.FEMALE, byTerm.getValue("Rapi").entry.gender)
        // A location has no gender of its own, even when the model sends one.
        assertEquals(Gender.UNKNOWN, byTerm.getValue("Ark").entry.gender)
        assertEquals(Gender.UNKNOWN, byTerm.getValue("Anis").entry.gender)
        assertEquals(Gender.UNKNOWN, byTerm.getValue("Neon").entry.gender)
    }

    @Test fun parseDropsUnwantedKindsAndRespectsLimitAndNotesSwitch() {
        val raw = answer(term("Rapi", "Рапи"), term("Ark", "Ковчег", kind = "location"),
            term("Anis", "Анис"), term("Neon", "Неон"))
        val result = GameResearch.parse(raw, request(kinds = setOf(TermKind.CHARACTER), notes = false, limit = 2))
        assertEquals(listOf("Rapi", "Anis"), result.terms.map { it.entry.term })
        assertEquals("", result.notes)
    }

    @Test fun unknownGameIsAnAnswerNotAnError() {
        val result = GameResearch.parse(answer(notes = "", found = false), request())
        assertFalse(result.found)
        assertTrue(result.terms.isEmpty())
    }

    @Test(expected = AiFormatException::class)
    fun emptyAnswerIsRejected() { GameResearch.parse(answer(notes = ""), request()) }

    @Test(expected = AiFormatException::class)
    fun proseIsRejected() { GameResearch.parse("I could not find this game.", request()) }

    @Test fun compareSortsAgainstTheGlossary() {
        val terms = listOf(
            ResearchTerm(GlossaryEntry("rapture", "рапчер", TermKind.FACTION)),
            ResearchTerm(GlossaryEntry("NIKKE", "Никке")),
            ResearchTerm(GlossaryEntry("Ark", "Ковчег", TermKind.LOCATION)))
        val proposals = GameResearch.compare(terms, known)
        assertEquals(listOf(TermStatus.SAME, TermStatus.CONFLICT, TermStatus.NEW), proposals.map { it.status })
        assertEquals(8L, proposals[1].existing?.id)
    }

    @Test fun compareFlagsAGenderChangeAsAConflictEvenWithTheSameRendering() {
        val withGender = known + GlossaryEntry("Rapi", "Рапи", TermKind.CHARACTER, gender = Gender.FEMALE, id = 9)
        val same = GameResearch.compare(listOf(ResearchTerm(GlossaryEntry("Rapi", "Рапи", TermKind.CHARACTER, gender = Gender.FEMALE))), withGender)
        assertEquals(TermStatus.SAME, same.single().status)
        val regendered = GameResearch.compare(listOf(ResearchTerm(GlossaryEntry("Rapi", "Рапи", TermKind.CHARACTER, gender = Gender.NEUTER))), withGender)
        assertEquals(TermStatus.CONFLICT, regendered.single().status)
    }

    @Test fun notesAreAppendedAfterThePlayers() {
        assertEquals("Мои.\n\nИх.", GameResearch.appendNotes("Мои.", "Их."))
        assertEquals("Их.", GameResearch.appendNotes("", "Их."))
        assertEquals("Мои. Их.", GameResearch.appendNotes("Мои. Их.", "Их."))
    }

    // --- Конверты ответа ----------------------------------------------------------------------------

    @Test fun xaiResponsesAnswerSkipsToolItemsAndCollectsSources() {
        val raw = """{"output":[
            {"type":"reasoning","summary":[]},
            {"type":"web_search_call","status":"completed"},
            {"type":"message","content":[{"type":"output_text","text":"{\"a\":1}",
              "annotations":[{"type":"url_citation","url":"https://nikke.fandom.com/wiki/Rapi","title":"Rapi"}]}]}],
            "citations":["https://www.nikke-en.com/","https://nikke.fandom.com/wiki/Rapi"]}"""
        val answer = AiResearchProtocol.xaiAnswer(raw)
        assertEquals("{\"a\":1}", answer.text)
        assertEquals(listOf("https://nikke.fandom.com/wiki/Rapi", "https://www.nikke-en.com/"), answer.sources.map { it.url })
        assertEquals("nikke-en.com", answer.sources[1].title)
    }

    @Test fun xaiChatAnswerReadsTheMessage() {
        val raw = """{"choices":[{"message":{"content":"{}"}}],"citations":["https://x.ai/"]}"""
        assertEquals("{}", AiResearchProtocol.xaiAnswer(raw).text)
        assertEquals(1, AiResearchProtocol.xaiAnswer(raw).sources.size)
    }

    @Test fun routerAnswerReadsUrlCitations() {
        val raw = """{"choices":[{"message":{"content":"{\"a\":1}","annotations":[
            {"type":"url_citation","url_citation":{"url":"https://www.fandom.com/wiki/x","title":"Wiki"}},
            {"type":"url_citation","url_citation":{"url":"https://www.fandom.com/wiki/x","title":"Wiki"}},
            {"type":"file","file":{"name":"x"}}]}}]}"""
        val answer = AiResearchProtocol.routerAnswer(raw)
        assertEquals("{\"a\":1}", answer.text)
        // The same page cited twice is one source; an annotation without a web address is none.
        assertEquals(listOf("https://www.fandom.com/wiki/x"), answer.sources.map { it.url })
        assertEquals(listOf("Wiki"), answer.sources.map { it.title })
    }

    @Test(expected = AiFormatException::class)
    fun routerAnswerWithoutContentIsRejected() {
        AiResearchProtocol.routerAnswer("""{"choices":[{"message":{"content":""}}]}""")
    }

    @Test fun geminiAnswerSkipsThoughtsAndReadsGrounding() {
        val raw = """{"candidates":[{"finishReason":"STOP","content":{"parts":[
            {"text":"thinking...","thought":true},{"text":"{\"b\":2}"}]},
            "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://vertexaisearch.cloud.google.com/x","title":"fandom.com"}},
            {"web":{"uri":""}}]}}]}"""
        val answer = AiResearchProtocol.geminiAnswer(raw)
        assertEquals("{\"b\":2}", answer.text)
        assertEquals(listOf("fandom.com"), answer.sources.map { it.title })
    }

    @Test(expected = AiFormatException::class)
    fun geminiCutOffIsReported() {
        AiResearchProtocol.geminiAnswer("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{"}]}}]}""")
    }

    // --- Оркестрация ----------------------------------------------------------------------------------

    private class ScriptedEngine(vararg replies: AiAnswer) : AiEngine {
        val queue = ArrayDeque(replies.toList())
        val calls = mutableListOf<Triple<String, AiEffort, Boolean>>()
        val models = mutableListOf<String>()
        val systems = mutableListOf<String>()
        override val provider = AiProvider.XAI
        override suspend fun models(token: String) = emptyList<AiModelInfo>()
        override fun usable(models: List<AiModelInfo>) = models
        override fun describe(model: String) = emptyList<String>()
        override suspend fun translate(token: String, model: String, system: String, user: String, effort: AiEffort) = ""
        val limits = mutableListOf<Int>()
        override suspend fun research(token: String, model: String, system: String, user: String,
                                      effort: AiEffort, search: Boolean, limit: Int,
                                      onProgress: (suspend (AiProgress) -> Unit)?): AiAnswer {
            calls += Triple(user, effort, search)
            models += model
            limits += limit
            systems += system
            return queue.removeFirst()
        }
    }

    // The researcher reads its own model, not the one the screen translator was set to.
    private val settings = AiSettings(model = "gemini-flash-latest", provider = AiProvider.GEMINI,
        researchProvider = AiProvider.XAI, researchModel = "grok-4.5",
        researchEffort = AiEffort.HIGH, researchSearch = true)

    /**
     * The bug this guards: asked for locations and factions only, the model returned characters
     * relabelled as locations. It read wanted_kinds as a target to fill, helped along by an example
     * that always showed a character.
     */
    @Test fun anExcludedKindIsNamedAndNeverShownAsAnExample() {
        val prompt = GameResearch.system(search = false, kinds = setOf(TermKind.LOCATION, TermKind.FACTION))
        assertTrue(prompt, prompt.contains("wants only these kinds: faction, location"))
        // Both lists keep the declaration order of TermKind, not the order they were picked in.
        assertTrue(prompt, prompt.contains("Do NOT return term, character"))
        assertTrue(prompt, prompt.contains("Never relabel"))
        // The example must not hand the model a kind it was just told not to return.
        assertTrue(prompt, prompt.contains("\"kind\": \"faction\""))
        assertFalse(prompt, prompt.contains("\"kind\": \"character\""))
        assertFalse(prompt, prompt.contains("\"gender\": \"female\""))
    }

    @Test fun withEveryKindWantedThereIsNothingToForbid() {
        val prompt = GameResearch.system(search = true)
        assertFalse(prompt, prompt.contains("Do NOT return"))
        assertTrue(prompt, prompt.contains("\"gender\": \"female\""))
    }

    @Test fun thePayloadNamesTheExcludedKindsToo() {
        val root = JSONObject(GameResearch.payload(request(kinds = setOf(TermKind.LOCATION))))
        assertEquals(listOf("location"), root.getJSONArray("wanted_kinds").let { a ->
            (0 until a.length()).map { a.getString(it) } })
        assertEquals(listOf("term", "character", "faction"), root.getJSONArray("excluded_kinds").let { a ->
            (0 until a.length()).map { a.getString(it) } })
    }

    @Test fun researcherPutsTheChosenKindsInThePromptAndTheCapInTheRequest() = runBlocking {
        val engine = ScriptedEngine(AiAnswer(answer(term("Eden", "\u042d\u0434\u0435\u043d"))))
        val kinds = setOf(TermKind.LOCATION)
        GameResearcher(engine).research(request(kinds = kinds), settings.copy(researchSearchLimit = 5), "token")
        assertTrue(engine.systems.single().contains("Do NOT return"))
        assertEquals(listOf(5), engine.limits)
    }

    @Test fun researcherPassesSettingsAndSources() = runBlocking {
        val engine = ScriptedEngine(AiAnswer(answer(term("Rapi", "Рапи")),
            listOf(AiSource("fandom", "https://f/")), listOf("Веб-поиск: источников — 1")))
        val result = GameResearcher(engine).research(request(), settings, "token")
        assertEquals("grok-4.5", engine.models.single())
        assertEquals(AiEffort.HIGH, engine.calls.single().second)
        assertTrue(engine.calls.single().third)
        assertEquals(listOf("https://f/"), result.sources.map { it.url })
        assertEquals(listOf("Веб-поиск: источников — 1"), result.remarks)
    }

    @Test fun malformedAnswerIsReformattedNotResearchedAgain() = runBlocking {
        val engine = ScriptedEngine(AiAnswer("Rapi is Рапи, Anis is Анис.", listOf(AiSource("a", "https://a/"))),
            AiAnswer(answer(term("Rapi", "Рапи"), term("Anis", "Анис"))))
        val result = GameResearcher(engine).research(request(), settings, "token")
        assertEquals(2, result.terms.size)
        val retry = engine.calls[1]
        assertTrue(retry.first.contains("Rapi is Рапи"))
        assertEquals(AiEffort.MINIMAL, retry.second)
        assertFalse(retry.third)
        // The search result of the first answer is kept.
        assertEquals(listOf("https://a/"), result.sources.map { it.url })
    }

    @Test fun missingKeyOrModelFailsBeforeAnyRequest() = runBlocking {
        val engine = ScriptedEngine()
        for ((ai, token) in listOf(settings to "", settings.copy(researchModel = "") to "token")) {
            try { GameResearcher(engine).research(request(), ai, token); fail() }
            catch (_: AiHttpException) { }
        }
        assertTrue(engine.calls.isEmpty())
    }
}
