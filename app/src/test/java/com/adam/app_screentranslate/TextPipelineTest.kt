package com.adam.app_screentranslate

import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.ocr.*
import com.adam.app_screentranslate.translation.*
import org.junit.Assert.*
import org.junit.Test

class TextPipelineTest {
    private fun block(text: String, y: Float, x: Float = 0f, width: Float = 200f): ScreenTextBlock {
        val box = Box(x, y, x+width, y+20)
        return ScreenTextBlock(y.toLong(), text, box, listOf(OcrLine(text, box)), script = ScriptDetector.detect(text))
    }
    @Test fun joinsParagraphAcrossOcrBlocks() {
        val result = TextBlockReconstructor().reconstruct(listOf(
            block("We have to leave", 0f), block("this place before", 26f), block("the sun rises.", 52f)), MergeMode.NORMAL)
        assertEquals(1, result.size)
        assertEquals("We have to leave this place before the sun rises.", result.single().originalText)
        assertEquals(72f, result.single().boundingBox.bottom)
    }
    @Test fun preservesSeparateMenuButtons() {
        val result = TextBlockReconstructor().reconstruct(listOf(
            block("New Game", 0f), block("Settings", 26f), block("Exit", 52f)), MergeMode.AGGRESSIVE)
        assertEquals(3, result.size)
    }
    @Test fun splitsMenuInsideOneNativeOcrBlock() {
        val lines = listOf(block("New Game", 0f), block("Settings", 26f), block("Exit", 52f))
        val native = lines.first().copy(originalText = "New Game Settings Exit", boundingBox = Box(0f, 0f, 200f, 72f),
            lines = lines.flatMap { it.lines })
        assertEquals(3, TextBlockReconstructor().reconstruct(listOf(native), MergeMode.NORMAL).size)
    }
    @Test fun columnsDoNotMerge() {
        val result = TextBlockReconstructor().reconstruct(listOf(
            block("a long sentence here", 0f, width=100f), block("and its other column", 26f, x=180f, width=100f)), MergeMode.AGGRESSIVE)
        assertEquals(2, result.size)
    }
    @Test fun duplicateCjkWinsOverLatinGuess() {
        val result = TextBlockReconstructor().reconstruct(listOf(block("ログイン", 0f), block("UI7I", 1f)), MergeMode.NORMAL)
        assertEquals(1, result.size)
        assertEquals("ログイン", result.single().originalText)
    }
    @Test fun repeatedTextAtDifferentPositionsSurvives() {
        val result = TextBlockReconstructor().reconstruct(listOf(block("START", 0f), block("START", 200f)), MergeMode.NORMAL)
        assertEquals(2, result.size)
    }
    @Test fun normalizesWithoutLosingCaseOrPunctuation() {
        assertEquals("Mission Complete!", TextNormalizer.normalize(" \nMission\u200B  Complete!\u00A0"))
    }
    @Test fun preservesMixedScriptBlock() {
        assertEquals(TextScript.MIXED, ScriptDetector.detect("Mission スタート"))
        assertEquals(TextScript.KOREAN, ScriptDetector.detect("로그인"))
        assertEquals(TextScript.JAPANESE, ScriptDetector.detect("ゲームを続ける"))
    }
    @Test fun reconstructsHyphenatedWord() {
        assertEquals("extraordinary", TextBlockReconstructor.joinLines(listOf("extra-", "ordinary")))
    }
    @Test fun batchesHaveBoundedSizeAndSingleLanguagePair() {
        val requests = listOf(
            TranslationRequest(1, "a".repeat(500), "en", "ru"),
            TranslationRequest(2, "b".repeat(500), "en", "ru"),
            TranslationRequest(3, "abc", "ja", "ru"))
        val batches = RequestBatcher.batches(requests)
        assertEquals(3, batches.size)
        assertTrue(batches.all { it.sumOf { r -> r.text.length } <= 900 })
    }
    @Test fun splitsVeryLongCjkWithoutLosingContent() {
        val text = "日".repeat(2500)
        val pieces = RequestBatcher.split(text)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= 900 })
    }
    @Test fun avoidsSplittingSurrogatePair() {
        val text = "a".repeat(899) + "🎮" + "b".repeat(30)
        val pieces = RequestBatcher.split(text)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.none { Character.isHighSurrogate(it.last()) })
    }
    @Test fun escapesUserHtmlAsText() {
        assertEquals("&lt;script&gt; &amp; &quot;Hi&quot;", escapeHtml("<script> & \"Hi\""))
    }
}
