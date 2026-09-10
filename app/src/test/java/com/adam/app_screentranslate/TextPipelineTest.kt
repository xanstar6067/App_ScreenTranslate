package com.adam.app_screentranslate

import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.ocr.*
import com.adam.app_screentranslate.translation.*
import org.junit.Assert.*
import org.junit.Test

class TextPipelineTest {
    private fun block(text: String, y: Float, x: Float = 0f, width: Float = 200f, paragraph: Int = -1): ScreenTextBlock {
        val box = Box(x, y, x+width, y+20)
        return ScreenTextBlock(y.toLong(), text, box, listOf(OcrLine(text, box)),
            script = ScriptDetector.detect(text), paragraph = paragraph)
    }
    private fun line(text: String, box: Box, paragraph: Int = -1) = ScreenTextBlock(
        box.top.toLong(), text, box, listOf(OcrLine(text, box)),
        script = ScriptDetector.detect(text), paragraph = paragraph)
    @Test fun joinsParagraphAcrossOcrBlocks() {
        val result = TextBlockReconstructor().reconstruct(listOf(
            block("We have to leave", 0f), block("this place before", 26f), block("the sun rises.", 52f)), MergeMode.NORMAL)
        assertEquals(1, result.size)
        assertEquals("We have to leave this place before the sun rises.", result.single().originalText)
        assertEquals(72f, result.single().boundingBox.bottom)
    }
    @Test fun keepsAWrappedSentenceTogetherWhenItsTailIsOneShortWord() {
        // A game subtitle: generous line spacing, and a last line that is one word with neither
        // descenders nor width. Every geometric threshold alone calls these two separate texts.
        val result = TextBlockReconstructor().reconstruct(listOf(
            line("Look, it's a lift! We'll get to the top floor with", Box(886f, 672f, 1637f, 706f)),
            line("this.", Box(886f, 730f, 955f, 750f))), MergeMode.NORMAL)
        assertEquals(1, result.size)
        assertEquals("Look, it's a lift! We'll get to the top floor with this.", result.single().originalText)
    }

    @Test fun theRecognizersOwnParagraphIsNotEnoughToGlueAMenu() {
        // One native block can hold a whole menu; its grouping must not override what the text says.
        val result = TextBlockReconstructor().reconstruct(listOf(
            block("New Game", 0f, paragraph = 7), block("Settings", 30f, paragraph = 7),
            block("Exit", 60f, paragraph = 7)), MergeMode.AGGRESSIVE)
        assertEquals(3, result.size)
    }

    @Test fun theRecognizersOwnParagraphSurvivesWideLineSpacing() {
        // Japanese carries no letter case, so only the reported paragraph can vouch for the wrap.
        val result = TextBlockReconstructor().reconstruct(listOf(
            line("エレベーターだ、これで最上階まで", Box(400f, 600f, 1000f, 640f), paragraph = 3),
            line("行ける", Box(400f, 672f, 560f, 704f), paragraph = 3)), MergeMode.NORMAL)
        assertEquals(1, result.size)
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
    @Test fun rewritesLookAlikeLettersIntoOneAlphabet() {
        // Latin K and o inside a Russian word: the recognizer mixed alphabets, the game did not.
        assertEquals("\u041A\u043E\u043C\u0430\u043D\u0434\u0430",
            TextNormalizer.harmonizeScript("Ko\u043C\u0430\u043D\u0434\u0430"))
        assertEquals("Sweep", TextNormalizer.harmonizeScript("Sweep"))
        assertEquals("Lv. 160", TextNormalizer.harmonizeScript("Lv. 160"))
        // Nothing to gain when the word stays mixed after the rewrite.
        assertEquals("\u0431test", TextNormalizer.harmonizeScript("\u0431test"))
    }
    @Test fun countersAreNotSentToTheTranslator() {
        assertFalse(TextNormalizer.isTranslatable("100,314/300"))
        assertFalse(TextNormalizer.isTranslatable("992.34 M"))
        assertFalse(TextNormalizer.isTranslatable("22.6%"))
        assertTrue(TextNormalizer.isTranslatable("Lv. 160"))
        assertTrue(TextNormalizer.isTranslatable("Sweep"))
        assertTrue(TextNormalizer.isTranslatable("\u65E5"))
    }
    @Test fun dropsNumericHudRows() {
        val result = TextBlockReconstructor().reconstruct(listOf(
            block("Attack", 0f), block("11,503", 0f, x = 300f), block("22.6%", 26f, x = 300f)), MergeMode.NORMAL)
        assertEquals(listOf("Attack"), result.map { it.originalText })
    }

    @Test fun aWrappedSentenceSurvivesTheCautiousMergeMode() {
        // The same subtitle under the tightest setting. The merge mode weighs guesses about lines
        // that might belong together; this pair is not a guess, the text says so itself.
        for (mode in MergeMode.entries) {
            val result = TextBlockReconstructor().reconstruct(listOf(
                line("Look, it's a lift! We'll get to the top floor with", Box(886f, 672f, 1637f, 706f)),
                line("this.", Box(886f, 730f, 955f, 750f))), mode)
            assertEquals("Mode $mode split the sentence", 1, result.size)
        }
    }

    @Test fun aShortLabelStillJoinsTheLineThatDidNotFinish() {
        // Two lines that read as buttons by every rule about controls, and as one sentence by the
        // comma at the end of the first. The comma wins.
        val result = TextBlockReconstructor().reconstruct(listOf(
            line("Go north,", Box(100f, 100f, 260f, 132f)),
            line("Then wait", Box(100f, 140f, 280f, 172f))), MergeMode.NORMAL)
        assertEquals(1, result.size)
        assertEquals("Go north, Then wait", result.single().originalText)
    }

    @Test fun oneParagraphOfTwoSentencesStaysOneCard() {
        // A dialogue box the recognizer read as a single paragraph. Splitting it at the full stop
        // costs one card its context and the screen a card it did not need.
        val result = TextBlockReconstructor().reconstruct(listOf(
            line("The gate is closed.", Box(300f, 900f, 900f, 936f), paragraph = 5),
            line("We need another way in.", Box(300f, 944f, 980f, 980f), paragraph = 5)), MergeMode.NORMAL)
        assertEquals(1, result.size)
        assertEquals("The gate is closed. We need another way in.", result.single().originalText)
    }

    @Test fun aFinishedSentenceWithoutTheRecognizersWordStaysApart() {
        val result = TextBlockReconstructor().reconstruct(listOf(
            line("The gate is closed.", Box(300f, 900f, 900f, 936f)),
            line("We need another way in.", Box(300f, 944f, 980f, 980f))), MergeMode.NORMAL)
        assertEquals(2, result.size)
    }

    @Test fun japaneseLinesJoinWithoutAnInventedSpace() {
        assertEquals("エレベーターだ、これで最上階まで行ける",
            TextBlockReconstructor.joinLines(listOf("エレベーターだ、これで最上階まで", "行ける")))
        // Korean is written with spaces, so a wrap there is a space.
        assertEquals("로그인 하십시오", TextBlockReconstructor.joinLines(listOf("로그인", "하십시오")))
        assertEquals("Login here", TextBlockReconstructor.joinLines(listOf("Login", "here")))
    }

    @Test fun aWrappedJapaneseParagraphReachesTheTranslatorUnbroken() {
        val result = TextBlockReconstructor().reconstruct(listOf(
            line("エレベーターだ、これで最上階まで", Box(400f, 600f, 1000f, 640f), paragraph = 3),
            line("行ける", Box(400f, 672f, 560f, 704f), paragraph = 3)), MergeMode.NORMAL)
        assertEquals("エレベーターだ、これで最上階まで行ける", result.single().originalText)
    }

    @Test fun anEngineThatReportsNoUsefulConfidenceIsNotThrownAway() {
        // Some builds of a recognizer fill the confidence field with zeroes. Read literally that
        // wipes out everything it recognized; read as the absence of evidence it changes nothing.
        val blank = listOf(
            line("Start the mission", Box(100f, 100f, 400f, 132f)).copy(confidence = 0f),
            line("Return to base", Box(100f, 300f, 400f, 332f)).copy(confidence = 0f))
        assertEquals(2, TextBlockReconstructor().reconstruct(blank, MergeMode.NORMAL).size)
    }

    @Test fun aLineItsOwnEngineDoubtsIsStillDropped() {
        val judged = listOf(
            line("Start the mission", Box(100f, 100f, 400f, 132f)).copy(confidence = .92f),
            line("Rtuin 1o basc", Box(100f, 300f, 400f, 332f)).copy(confidence = .21f))
        assertEquals(listOf("Start the mission"),
            TextBlockReconstructor().reconstruct(judged, MergeMode.NORMAL).map { it.originalText })
    }

    @Test fun aDoubtingEngineDoesNotSilenceAConfidentOne() {
        // Tesseract grades itself on its own scale; its numbers must not decide whether ML Kit's
        // lines are worth keeping.
        val mixed = listOf(
            line("Start the mission", Box(100f, 100f, 400f, 132f)).copy(confidence = 0f),
            line("Начать задание", Box(100f, 300f, 400f, 332f))
                .copy(confidence = .88f, engine = OcrEngine.TESSERACT))
        assertEquals(2, TextBlockReconstructor().reconstruct(mixed, MergeMode.NORMAL).size)
    }

    @Test fun paragraphsDoNotDependOnTheOrderTheLinesArriveIn() {
        val lines = listOf(
            line("We have to leave", Box(100f, 100f, 500f, 132f)),
            line("this place before", Box(100f, 140f, 500f, 172f)),
            line("the sun rises.", Box(100f, 180f, 460f, 212f)),
            line("New Game", Box(1400f, 100f, 1600f, 132f)),
            line("Settings", Box(1400f, 140f, 1600f, 172f)))
        val forward = TextBlockReconstructor().reconstruct(lines, MergeMode.NORMAL)
        val backward = TextBlockReconstructor().reconstruct(lines.reversed(), MergeMode.NORMAL)
        assertEquals(forward.map { it.originalText }, backward.map { it.originalText })
        assertEquals(3, forward.size)
    }
}
