package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiEffort
import com.adam.app_screentranslate.model.AiSettings
import com.adam.app_screentranslate.model.GameProfile
import com.adam.app_screentranslate.model.Gender
import com.adam.app_screentranslate.model.GlossaryEntry
import com.adam.app_screentranslate.model.TermKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A page the model's search leaned on. */
data class AiSource(val title: String, val url: String)

/**
 * A free-form answer, as opposed to the translation contract. [remarks] say what the request
 * actually negotiated — search dropped, reasoning stepped down — in words for the user.
 */
data class AiAnswer(val text: String, val sources: List<AiSource> = emptyList(), val remarks: List<String> = emptyList())

/** Where a proposed rendering comes from. The user decides differently about each. */
enum class TermBasis(val label: String, val wire: String) {
    OFFICIAL("Официальная локализация", "official"),
    COMMUNITY("Принято у игроков", "community"),
    SUGGESTED("Предложение ИИ", "suggested")
}

data class ResearchTerm(val entry: GlossaryEntry, val basis: TermBasis = TermBasis.SUGGESTED, val comment: String = "")

/** How a proposal relates to the glossary the game already has. */
enum class TermStatus { NEW, SAME, CONFLICT }

data class ResearchProposal(val term: ResearchTerm, val status: TermStatus, val existing: GlossaryEntry? = null)

/**
 * What the player asked the tool for. [source] and [target] are language names as the model reads
 * them. [known] is the glossary the game already has: only its terms leave the device, so that the
 * model spends its answer on what is missing.
 */
data class ResearchRequest(
    val profile: GameProfile, val known: List<GlossaryEntry>, val source: String, val target: String,
    val kinds: Set<TermKind> = TermKind.entries.toSet(), val notes: Boolean = true,
    val limit: Int = 30, val focus: String = ""
)

data class ResearchResult(
    val found: Boolean, val title: String, val notes: String, val terms: List<ResearchTerm>,
    val sources: List<AiSource> = emptyList(), val remarks: List<String> = emptyList()
)

/**
 * The contract for filling a game profile. Pure Kotlin, so what leaves the device and what is
 * accepted back are covered by JVM tests, like the translation contract in [AiProtocol].
 *
 * The answer is asked for as plain JSON rather than through a response schema: both providers
 * refuse, on some models, to combine their search tool with constrained decoding, and a refused
 * search is worth more here than a guaranteed shape. [AiProtocol.unwrap] recovers the object.
 */
object GameResearch {
    /** The terms of a long-running game can run into thousands; the list is only a hint. */
    const val MAX_KNOWN = 300
    const val MAX_TERM = 80
    const val MAX_NOTES = 1500
    val LIMITS = listOf(15, 30, 60)

    fun system(search: Boolean): String = """
        You are a video game localization researcher. For one game you build a reference that a
        translator uses while translating the game's screens: a short brief and a glossary of the
        game's own names and terms.

        ${if (search) """Search the web before answering: the official site, store pages, the game's wiki, and above
        all the game's official localization into the target language, if one exists. Prefer what
        the game itself uses over anything else."""
        else """You have no web access. Rely on what you know, and mark every rendering you are not sure the
        game uses as "suggested"."""}

        Rules:
        - Identify the game from its title and Android package. If you cannot identify it with
          confidence, set game_found to false and return no terms rather than guessing.
        - "term" is written exactly as the player sees it in the game in the source language: the
          original spelling and capitalization. Never translate the term field.
        - "translation" is in the target language. Use the official localization when the game has
          one (basis "official"), otherwise the rendering players commonly use (basis "community"),
          otherwise your own careful suggestion (basis "suggested").
        - Names that the official localization keeps as they are — titles, brands, names written in
          Latin letters — get keep: true and a translation equal to the term.
        - kind is one of: character, faction, location, term. "term" covers items, abilities,
          currencies, game mechanics and interface words specific to this game.
        - for a character, set "gender" to their own sex or grammatical gender in the target
          language: male, female, neuter or plural. Omit it, or use unknown, when the game never
          makes it clear. Never set gender for a faction, location or term.
        - Choose what a player actually reads on screen often. Skip generic words any dictionary
          translates correctly ("Settings", "Attack", "Level").
        - Never repeat an entry of known_terms. Only include kinds listed in wanted_kinds, and at
          most max_terms entries, the most frequent first.
        - "comment" is at most one short sentence in the target language: who or what it is.
        - When write_notes is true, "notes" is a brief for the translator in the target language,
          under 800 characters: genre and setting in one line; tone and register of the dialogue;
          how characters address each other (formal or informal); naming conventions; what must
          stay untranslated. No plot spoilers beyond the premise. When write_notes is false, "notes"
          is an empty string.
        - The player's own notes, if any, are authoritative; do not contradict them.

        Answer with one JSON object and nothing else — no markdown, no citation marks inside strings:
        {"game_found": true, "official_title": "...", "notes": "...",
         "terms": [{"term": "...", "translation": "...", "kind": "character", "gender": "female",
                    "keep": false, "basis": "official", "comment": "..."}]}
    """.trimIndent()

    /** Everything about the game that leaves the device, in one place. No screen text, ever. */
    fun payload(request: ResearchRequest): String {
        val profile = request.profile
        val game = JSONObject().put("title", profile.title).put("package", profile.packageName)
        if (profile.label.isNotBlank() && profile.label != profile.title) game.put("store_label", profile.label)
        val root = JSONObject().put("game", game)
            .put("source_language", request.source).put("target_language", request.target)
            .put("wanted_kinds", JSONArray(TermKind.entries.filter { it in request.kinds }.map { it.wire }))
            .put("max_terms", request.limit).put("write_notes", request.notes)
        profile.notes.trim().takeIf { it.isNotEmpty() }?.let { root.put("player_notes", it) }
        request.focus.trim().takeIf { it.isNotEmpty() }?.let { root.put("focus", it) }
        val known = request.known.map { it.term }.distinct().take(MAX_KNOWN)
        if (known.isNotEmpty()) root.put("known_terms", JSONArray(known))
        return root.toString()
    }

    /**
     * Everything the model says is cleaned before the user sees it: an entry that could not be
     * saved, repeats itself, belongs to a kind nobody asked for or spills past the limit is dropped
     * here rather than offered.
     */
    fun parse(raw: String, request: ResearchRequest): ResearchResult {
        val root = try { JSONObject(AiProtocol.unwrap(raw)) }
            catch (e: AiFormatException) { throw e }
            catch (_: Exception) { throw AiFormatException("The answer was not a JSON object.") }
        val found = root.optBoolean("game_found", true)
        val title = root.optString("official_title", "").trim().take(100)
        val notes = if (request.notes) root.optString("notes", "").trim().take(MAX_NOTES) else ""
        val array = root.optJSONArray("terms") ?: JSONArray()
        val seen = mutableSetOf<String>()
        val terms = mutableListOf<ResearchTerm>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val term = item.optString("term", "").trim()
            if (term.isEmpty() || term.length > MAX_TERM) continue
            val kind = TermKind.entries.firstOrNull { it.wire == item.optString("kind").trim().lowercase(Locale.ROOT) }
                ?: TermKind.TERM
            if (kind !in request.kinds) continue
            val translation = item.optString("translation", "").trim().take(MAX_TERM)
            val keep = item.optBoolean("keep", false) || translation.equals(term, ignoreCase = false)
            if (!keep && translation.isEmpty()) continue
            if (!seen.add(term.lowercase(Locale.ROOT))) continue
            val basis = TermBasis.entries.firstOrNull { it.wire == item.optString("basis").trim().lowercase(Locale.ROOT) }
                ?: TermBasis.SUGGESTED
            val gender = if (kind == TermKind.CHARACTER)
                Gender.entries.firstOrNull { it.wire == item.optString("gender").trim().lowercase(Locale.ROOT) } ?: Gender.UNKNOWN
            else Gender.UNKNOWN
            terms += ResearchTerm(GlossaryEntry(term, if (keep) term else translation, kind, keep, gender), basis,
                item.optString("comment", "").trim().take(200))
            if (terms.size >= request.limit) break
        }
        if (found && terms.isEmpty() && notes.isEmpty())
            throw AiFormatException("The answer had neither terms nor notes.")
        return ResearchResult(found, title, notes, terms)
    }

    /**
     * Sorts the proposals against the glossary. A term the glossary already renders the same way
     * is only shown; one it renders differently is offered, but never overwrites silently.
     */
    fun compare(terms: List<ResearchTerm>, existing: List<GlossaryEntry>): List<ResearchProposal> {
        val byTerm = existing.associateBy { it.term.trim().lowercase(Locale.ROOT) }
        return terms.map { proposal ->
            val known = byTerm[proposal.entry.term.lowercase(Locale.ROOT)]
            when {
                known == null -> ResearchProposal(proposal, TermStatus.NEW)
                known.keep == proposal.entry.keep && known.gender == proposal.entry.gender &&
                    known.rendering.equals(proposal.entry.rendering, ignoreCase = true) ->
                    ResearchProposal(proposal, TermStatus.SAME, known)
                else -> ResearchProposal(proposal, TermStatus.CONFLICT, known)
            }
        }
    }

    /** Proposed notes added to the player's own: theirs first, because theirs are authoritative. */
    fun appendNotes(current: String, proposed: String): String {
        val mine = current.trim()
        val theirs = proposed.trim()
        return when {
            theirs.isEmpty() -> mine
            mine.isEmpty() -> theirs
            mine.contains(theirs) -> mine
            else -> "$mine\n\n$theirs"
        }
    }
}

/**
 * Runs one research request. A malformed answer is not asked for again from scratch — that would
 * repeat the whole search — but handed back to be reformatted, without search and with minimal
 * reasoning, which costs seconds rather than minutes.
 */
class GameResearcher(private val engine: AiEngine) {
    suspend fun research(request: ResearchRequest, ai: AiSettings, token: String): ResearchResult {
        if (token.isBlank()) throw AiHttpException(0, "Не указан API-ключ ${ai.provider.label}.")
        if (ai.model.isBlank()) throw AiHttpException(0, "Не выбрана модель ${ai.provider.label}.")
        val system = GameResearch.system(ai.researchSearch)
        val first = engine.research(token, ai.model, system, GameResearch.payload(request),
            ai.researchEffort, ai.researchSearch)
        val (result, answers) = try { GameResearch.parse(first.text, request) to listOf(first) }
        catch (e: AiFormatException) {
            val repair = "Your previous answer was rejected: ${e.reason}\n" +
                "Rewrite it as the JSON object the instructions describe, keeping its content. " +
                "Wanted kinds: ${request.kinds.joinToString { it.wire }}; at most ${request.limit} terms.\n\n" +
                "Previous answer:\n${first.text.take(20_000)}"
            val second = engine.research(token, ai.model, GameResearch.system(search = false), repair,
                AiEffort.MINIMAL, search = false)
            GameResearch.parse(second.text, request) to listOf(first, second)
        }
        return result.copy(sources = answers.flatMap { it.sources }.distinctBy { it.url },
            remarks = answers.first().remarks)
    }
}

/**
 * The envelopes of a research answer: the text and the pages the provider's search used. The
 * search tools answer in their own shapes and those shapes still move, so every field is optional
 * and a source without a web address is simply not listed.
 */
object AiResearchProtocol {
    private fun obj(raw: String): JSONObject = try { JSONObject(raw) }
        catch (_: Exception) { throw AiFormatException("The server answer was not a JSON object.") }

    /** Both xAI endpoints: /chat/completions carries choices, /responses carries output items. */
    fun xaiAnswer(raw: String): AiAnswer {
        val root = obj(raw)
        val sources = mutableListOf<AiSource>()
        val text = if (root.has("choices")) AiProtocol.chatContent(raw) else {
            val output = root.optJSONArray("output")
            val answer = StringBuilder()
            if (output != null) for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                // Reasoning and the search calls themselves are output items too, with no answer in them.
                if (item.optString("type", "message") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    answer.append(part.optString("text", ""))
                    val notes = part.optJSONArray("annotations") ?: continue
                    for (k in 0 until notes.length()) notes.optJSONObject(k)?.let { source(it) }?.let { sources += it }
                }
            }
            answer.toString().ifBlank { root.optString("output_text", "") }
                .ifBlank { throw AiFormatException("The response was empty.") }
        }
        root.optJSONArray("citations")?.let { list ->
            for (i in 0 until list.length()) {
                val item = list.opt(i)
                if (item is JSONObject) source(item)?.let { sources += it }
                else list.optString(i, "").takeIf { it.startsWith("http") }?.let { sources += AiSource(host(it), it) }
            }
        }
        return AiAnswer(text, sources.distinctBy { it.url })
    }

    /** Gemini: thought parts are skipped, grounding chunks become sources. */
    fun geminiAnswer(raw: String): AiAnswer {
        val root = obj(raw)
        root.optJSONObject("promptFeedback")?.optString("blockReason")?.ifBlank { null }
            ?.let { throw AiFormatException("Gemini blocked the request ($it).") }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiFormatException("The answer carried no candidates.")
        val finish = candidate.optString("finishReason", "")
        if (finish.isNotBlank() && finish != "STOP") throw AiFormatException("Gemini stopped early ($finish).")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiFormatException("The candidate carried no parts.")
        val answer = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            answer.append(part.optString("text", ""))
        }
        val sources = mutableListOf<AiSource>()
        candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                val url = web.optString("uri", "")
                if (url.isBlank()) continue
                sources += AiSource(web.optString("title", "").ifBlank { host(url) }, url)
            }
        }
        return AiAnswer(answer.toString().ifBlank { throw AiFormatException("The answer was empty.") },
            sources.distinctBy { it.url })
    }

    private fun source(item: JSONObject): AiSource? {
        val url = item.optString("url", "").ifBlank { item.optString("uri", "") }
        if (!url.startsWith("http")) return null
        return AiSource(item.optString("title", "").ifBlank { host(url) }, url)
    }

    /** "https://www.example.com/wiki/x" → "example.com". Read by hand, not by regex. */
    fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")
}
