package com.adam.app_screentranslate

import com.adam.app_screentranslate.translation.ai.AiFormatException
import com.adam.app_screentranslate.translation.ai.AiStage
import com.adam.app_screentranslate.translation.ai.AiStreamReader
import com.adam.app_screentranslate.translation.ai.AiStreaming
import com.adam.app_screentranslate.translation.ai.GeminiStreamReader
import com.adam.app_screentranslate.translation.ai.RouterStreamReader
import com.adam.app_screentranslate.translation.ai.XaiStreamReader
import org.junit.Assert.*
import org.junit.Test

/**
 * The live preview of a long request. Three providers stream three different shapes, and a reader
 * that throws on an unknown event would lose the whole answer rather than one line of preview.
 */
class AiStreamTest {
    private fun feed(reader: AiStreamReader, vararg events: String) = events.map { reader.event(it) }

    // --- xAI ----------------------------------------------------------------------------------

    @Test fun responsesEventsBuildTheAnswerAndNameTheStage() {
        val reader = XaiStreamReader()
        val shown = feed(reader,
            """{"type":"response.web_search_call.in_progress"}""",
            """{"type":"response.reasoning_summary_text.delta","delta":"hmm"}""",
            """{"type":"response.output_text.delta","delta":"{\"a\""}""",
            """{"type":"response.output_text.delta","delta":":1}"}""")
        assertEquals(AiStage.SEARCHING, shown[0]?.note)
        assertEquals(AiStage.THINKING, shown[1]?.note)
        assertEquals(AiStage.WRITING, shown[2]?.note)
        // The preview is the whole text so far, not the delta: the page shows its tail.
        assertEquals("{\"a\"", shown[2]?.text)
        assertEquals("{\"a\":1}", reader.answer().text)
        // Thinking is never mistaken for the answer.
        assertFalse(reader.answer().text.contains("hmm"))
    }

    @Test fun theClosingObjectIsWhereTheSearchedPagesAre() {
        val reader = XaiStreamReader()
        feed(reader, """{"type":"response.output_text.delta","delta":"{}"}""",
            """{"type":"response.completed","response":{"output":[{"type":"message","content":[
               {"text":"{}","annotations":[{"url":"https://fandom.com/x","title":"Wiki"}]}]}]}}""")
        val answer = reader.answer()
        assertEquals("{}", answer.text)
        assertEquals(listOf("https://fandom.com/x"), answer.sources.map { it.url })
    }

    @Test fun chatCompletionChunksAreReadByTheSameReader() {
        val reader = XaiStreamReader()
        feed(reader, """{"choices":[{"delta":{"content":"ab"}}]}""",
            """{"choices":[{"delta":{"content":"c"}}]}""")
        assertEquals("abc", reader.answer().text)
    }

    // --- Gemini -------------------------------------------------------------------------------

    @Test fun thoughtPartsAreShownAsThinkingAndKeptOutOfTheAnswer() {
        val reader = GeminiStreamReader()
        val shown = feed(reader,
            """{"candidates":[{"content":{"parts":[{"text":"planning","thought":true}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"{\"a\":"}]},
               "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://a/","title":"A"}}]}}]}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"1}"}]}}]}""")
        assertEquals(AiStage.THINKING, shown[0]?.note)
        val answer = reader.answer()
        assertEquals("{\"a\":1}", answer.text)
        assertEquals(listOf("https://a/"), answer.sources.map { it.url })
    }

    /** A refusal arrives as a finishReason at status 200, exactly as in the unstreamed envelope. */
    @Test fun aStreamThatStopsEarlyIsRejected() {
        val reader = GeminiStreamReader()
        feed(reader, """{"candidates":[{"content":{"parts":[{"text":"{"}]}}]}""",
            """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[]}}]}""")
        assertThrows(AiFormatException::class.java) { reader.answer() }
    }

    // --- OpenRouter ---------------------------------------------------------------------------

    @Test fun routerDeltasCarryTextReasoningAndCitations() {
        val reader = RouterStreamReader()
        val shown = feed(reader,
            """{"choices":[{"delta":{"reasoning":"hmm"}}]}""",
            """{"choices":[{"delta":{"content":"{}","annotations":[
               {"type":"url_citation","url_citation":{"url":"https://b/","title":"B"}}]}}]}""")
        assertEquals(AiStage.THINKING, shown[0]?.note)
        val answer = reader.answer()
        assertEquals("{}", answer.text)
        assertEquals(listOf("https://b/"), answer.sources.map { it.url })
    }

    // --- Общее для всех -------------------------------------------------------------------------

    @Test fun anUnknownOrBrokenEventIsIgnoredRatherThanFatal() {
        listOf(XaiStreamReader(), GeminiStreamReader(), RouterStreamReader()).forEach { reader ->
            assertNull(reader.event("not json at all"))
            assertNull(reader.event("""{"type":"something.new.entirely"}"""))
            assertNull(reader.event("""{"unrelated":true}"""))
        }
    }

    @Test fun aStreamThatSaidNothingIsNotAnEmptyAnswer() {
        listOf(XaiStreamReader(), GeminiStreamReader(), RouterStreamReader()).forEach { reader ->
            assertThrows(AiFormatException::class.java) { reader.answer() }
        }
    }

    // --- Отказы ---------------------------------------------------------------------------------

    @Test fun aRefusalOfStreamingIsToldApartFromARefusalOfTheCap() {
        assertTrue(AiStreaming.refused("Unsupported parameter: 'stream'"))
        assertTrue(AiStreaming.refused("text/event-stream is not available for this model"))
        assertFalse(AiStreaming.refused("Unsupported parameter: 'reasoning'"))

        assertTrue(AiStreaming.refusedLimit("Unknown field: max_search_results"))
        assertTrue(AiStreaming.refusedLimit("plugins.0.max_results is not supported"))
        assertFalse(AiStreaming.refusedLimit("web search is not available for this model"))
    }
}
