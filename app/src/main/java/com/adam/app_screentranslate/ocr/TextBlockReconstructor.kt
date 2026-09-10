package com.adam.app_screentranslate.ocr

import com.adam.app_screentranslate.model.*
import kotlin.math.*

object TextNormalizer {
    fun normalize(text: String) = text.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replace(Regex("[\\s\\u00A0]+"), " ").trim()

    private val latinToCyrillic = mapOf(
        'A' to '\u0410', 'B' to '\u0412', 'C' to '\u0421', 'E' to '\u0415', 'H' to '\u041D',
        'K' to '\u041A', 'M' to '\u041C', 'O' to '\u041E', 'P' to '\u0420', 'T' to '\u0422',
        'X' to '\u0425', 'Y' to '\u0423', 'a' to '\u0430', 'c' to '\u0441', 'e' to '\u0435',
        'o' to '\u043E', 'p' to '\u0440', 'x' to '\u0445', 'y' to '\u0443')
    private val cyrillicToLatin = latinToCyrillic.entries.associate { (latin, cyrillic) -> cyrillic to latin }
    private val words = Regex("[\\p{L}\\p{M}\\p{Nd}]+")

    /**
     * A recognizer that runs Latin and Cyrillic together spells single words in both alphabets.
     * Such a word matches no dictionary and reaches the translator as nonsense, so every look-alike
     * letter is rewritten into the alphabet the rest of the word already uses.
     */
    fun harmonizeScript(text: String): String = words.replace(text) { match -> harmonizeWord(match.value) }

    private fun harmonizeWord(token: String): String {
        var cyrillic = 0
        var latin = 0
        for (c in token) when {
            c in '\u0400'..'\u052F' -> cyrillic++
            c in 'A'..'Z' || c in 'a'..'z' -> latin++
        }
        if (cyrillic == 0 || latin == 0) return token
        val map = if (cyrillic >= latin) latinToCyrillic else cyrillicToLatin
        val converted = token.map { map[it] ?: it }.joinToString("")
        // A word that stays mixed was no look-alike; the original spelling is the safer answer.
        val mixed = converted.any { it in '\u0400'..'\u052F' } &&
            converted.any { it in 'A'..'Z' || it in 'a'..'z' }
        return if (mixed) token else converted
    }

    /**
     * Counters, timers and stat rows carry no language. Translating them wastes requests and fills
     * the screen with cards that repeat the digits already visible underneath.
     */
    fun isTranslatable(text: String): Boolean {
        val letters = text.count(Char::isLetter)
        if (letters == 0) return false
        // A single CJK glyph is a whole word.
        if (text.any { it in '\u3040'..'\u9FFF' || it in '\uAC00'..'\uD7AF' }) return true
        val dense = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        return letters >= 2 && letters.toFloat() / dense >= .25f
    }
}

object ScriptDetector {
    fun detect(text: String): TextScript {
        val latin = text.any { it in 'A'..'Z' || it in 'a'..'z' || it in '\u00C0'..'\u024F' }
        val japanese = text.any { it in '\u3040'..'\u30FF' || it in '\u3400'..'\u9FFF' }
        val korean = text.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }
        val cyrillic = text.any { it in '\u0400'..'\u052F' }
        return when {
            listOf(latin, japanese, korean, cyrillic).count { it } > 1 -> TextScript.MIXED
            cyrillic -> TextScript.CYRILLIC
            japanese -> TextScript.JAPANESE
            korean -> TextScript.KOREAN
            latin -> TextScript.LATIN
            else -> TextScript.UNKNOWN
        }
    }
}

class TextBlockReconstructor {
    fun reconstruct(input: List<ScreenTextBlock>, mode: MergeMode): List<ScreenTextBlock> {
        // Arbitrate at LINE level: different models often return different paragraph boundaries.
        val candidates = input.flatMap { block ->
            if (block.lines.isEmpty()) {
                val text = TextNormalizer.harmonizeScript(block.originalText)
                listOf(block.copy(originalText = text, script = ScriptDetector.detect(text)))
            } else block.lines.map { line ->
                val text = TextNormalizer.harmonizeScript(line.text)
                block.copy(originalText = text, boundingBox = line.box, lines = listOf(line),
                    confidence = line.confidence ?: block.confidence, script = ScriptDetector.detect(text),
                    angle = line.angle)
            }
        }.filter { TextNormalizer.isTranslatable(it.originalText) }
        val judged = judging(candidates)
        val selected = mutableListOf<ScreenTextBlock>()
        for (block in candidates.filter { it.engine !in judged || (it.confidence ?: 1f) >= MIN_CONFIDENCE }
            .sortedByDescending { quality(it, judged) }) {
            if (selected.none { duplicate(it, block) }) selected += block
        }
        val ordered = selected.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        return paragraphs(ordered, mode)
            .mapIndexed { i, b -> b.copy(id = i.toLong(), originalText = TextNormalizer.normalize(b.originalText)) }
    }

    /**
     * Engines whose confidence is worth listening to. A recognizer that reports nothing, or the
     * same low number for every line it read, is filling the field in rather than judging its own
     * work; taking it at face value would throw away everything that engine recognized.
     */
    private fun judging(candidates: List<ScreenTextBlock>): Set<OcrEngine> =
        candidates.groupBy { it.engine }.filterValues { group ->
            val reported = group.mapNotNull { it.confidence }
            reported.size * 2 >= group.size && reported.any { it >= MIN_CONFIDENCE }
        }.keys

    /**
     * Reassembles paragraphs out of single lines. Every line looks for the one line below that
     * continues it, and every line can be continued once: a paragraph is a chain, not a cluster.
     * Linking instead of repeatedly merging in place keeps the decision on the real geometry of
     * two lines rather than on the drifting rectangle around everything joined so far, and makes
     * the result independent of the order the pairs happen to be visited in.
     */
    private fun paragraphs(ordered: List<ScreenTextBlock>, mode: MergeMode): List<ScreenTextBlock> {
        val next = IntArray(ordered.size) { -1 }
        val continued = BooleanArray(ordered.size)
        for (i in ordered.indices) {
            var best = -1
            var bestGap = Float.MAX_VALUE
            for (j in i + 1 until ordered.size) {
                // Sorted by top edge: past this distance nothing below can be the same paragraph.
                if (ordered[j].boundingBox.top - ordered[i].boundingBox.bottom >
                    ordered[i].boundingBox.height * SEARCH) break
                if (continued[j] || !canMerge(ordered[i], ordered[j], mode)) continue
                val gap = ordered[j].boundingBox.top - ordered[i].boundingBox.bottom
                if (gap < bestGap) { best = j; bestGap = gap }
            }
            if (best >= 0) { next[i] = best; continued[best] = true }
        }
        val result = mutableListOf<ScreenTextBlock>()
        for (i in ordered.indices) {
            if (continued[i]) continue
            var block = ordered[i]
            var at = next[i]
            while (at >= 0) {
                val tail = ordered[at]
                val text = joinLines(listOf(block.originalText, tail.originalText))
                block = block.copy(originalText = text, boundingBox = block.boundingBox.union(tail.boundingBox),
                    lines = block.lines + tail.lines, script = ScriptDetector.detect(text),
                    paragraph = if (block.paragraph == tail.paragraph) block.paragraph else -1)
                at = next[at]
            }
            result += block
        }
        return result.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
    }

    private fun quality(b: ScreenTextBlock, judged: Set<OcrEngine>): Float {
        val letters = b.originalText.count(Char::isLetter).coerceAtLeast(1)
        val cjk = b.originalText.count { it in '\u3040'..'\u9FFF' || it in '\uAC00'..'\uD7AF' }
        val strayScriptPenalty = if (cjk in 1..2 && cjk.toFloat()/letters < .25f) .12f else 0f
        // Confidence is model evidence; a CJK character alone is never evidence of correctness.
        val reported = if (b.engine in judged) b.confidence else null
        return (reported ?: NEUTRAL) - strayScriptPenalty
    }
    private fun duplicate(a: ScreenTextBlock, b: ScreenTextBlock): Boolean {
        val overlap = a.boundingBox.intersection(b.boundingBox)
        val containment = overlap / min(a.boundingBox.area, b.boundingBox.area)
        val iou = overlap / (a.boundingBox.area + b.boundingBox.area - overlap)
        val x = TextNormalizer.normalize(a.originalText).lowercase()
        val y = TextNormalizer.normalize(b.originalText).lowercase()
        return (iou > .45f) || (containment > .75f && min(a.boundingBox.height, b.boundingBox.height) / max(a.boundingBox.height, b.boundingBox.height) > .6f) ||
            (containment > .8f && (x.contains(y) || y.contains(x))) ||
            (containment > .65f && similarity(x, y) > .65f)
    }
    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        // Bigrams avoid a quadratic edit-distance table for long OCR paragraphs.
        val x = a.windowed(2).toSet(); val y = b.windowed(2).toSet()
        return (2f * x.intersect(y).size) / (x.size + y.size).coerceAtLeast(1)
    }
    private fun canMerge(a: ScreenTextBlock, b: ScreenTextBlock, mode: MergeMode): Boolean {
        val ra = a.boundingBox; val rb = b.boundingBox
        val ah = a.lines.map { it.box.height }.average().takeUnless { it.isNaN() }?.toFloat() ?: ra.height
        val bh = b.lines.map { it.box.height }.average().takeUnless { it.isNaN() }?.toFloat() ?: rb.height
        val h = (ah + bh) / 2f
        if (a.lines.any { abs(it.angle) > 15f } || b.lines.any { abs(it.angle) > 15f }) return false
        if (a.script != b.script && a.script != TextScript.MIXED && b.script != TextScript.MIXED) return false
        val first = a.originalText.trim(); val second = b.originalText.trim()
        /*
         * Geometry alone cannot tell the last line of a subtitle from the row of buttons under it,
         * so the thresholds below have to stay tight — and a wrapped sentence whose tail is one
         * short word then falls outside every one of them. Two things do know better, and they are
         * not interchangeable. The paragraph the recognizer reported groups lines that look alike,
         * which one native block full of menu buttons also does; it may stretch the geometry but
         * never speaks about the text. A sentence that has plainly not ended is a statement about
         * the text itself, and it outranks every rule written for controls below.
         */
        val sameParagraph = a.paragraph >= 0 && a.paragraph == b.paragraph
        val unfinished = continues(first, second)
        // A finished sentence starts a card of its own unless the recognizer read both lines as one
        // paragraph: only it can tell a wrapped paragraph from the next control underneath it.
        if (first.lastOrNull() in SENTENCE_END && !sameParagraph) return false
        val related = sameParagraph || unfinished
        val gap = rb.top - ra.bottom
        // A line the text itself announced sits wherever the game's own leading put it. The merge
        // mode governs the guesses; it does not get to overrule the wrap that was already declared.
        val allowed = h * when {
            unfinished -> max(mode.gap, UNFINISHED_GAP)
            sameParagraph -> mode.gap * RELATED_GAP
            else -> mode.gap
        }
        if (gap < -h * .15f || gap > allowed) return false
        if (max(ah, bh) / min(ah, bh) > (if (related) 1.9f else 1.45f)) return false
        val overlapX = (min(ra.right, rb.right) - max(ra.left, rb.left)).coerceAtLeast(0f) / min(ra.width, rb.width)
        if (overlapX < (if (related) .3f else .65f)) return false
        if (abs(ra.left - rb.left) > h * (if (related) 1.6f else .8f)) return false
        // Short title-case labels are separate controls, even at tight line spacing and even when
        // the recognizer read them as one paragraph. This is what keeps a menu a menu — but a menu
        // item never ends on a comma and is never followed by a word that opens in lower case.
        val labelA = first.split(' ').size <= 3 && first.length < 24
        val labelB = second.split(' ').size <= 3 && second.length < 24
        if (labelA && labelB && !unfinished &&
            first.firstOrNull()?.isUpperCase() == true && second.firstOrNull()?.isUpperCase() == true) return false
        return true
    }

    /**
     * Whether [second] reads as the continuation of [first]. A line that ends mid phrase and one
     * that opens in lower case are the same sentence wrapped by the game's own text box; translating
     * the halves apart produces two wrong translations instead of one right one.
     */
    private fun continues(first: String, second: String): Boolean {
        val end = first.lastOrNull() ?: return false
        val start = second.firstOrNull() ?: return false
        if (end in SENTENCE_END) return false
        return start.isLowerCase() || end == ',' || end == '-' || end == '—'
    }
    companion object {
        private val SENTENCE_END = listOf('.', '!', '?', '。', '！', '？', ':')
        /** How far below a line its own wrapped tail can sit, in line heights, whatever the mode. */
        private const val UNFINISHED_GAP = 1.6f
        /** How much the geometry may be stretched for two lines the recognizer itself grouped. */
        private const val RELATED_GAP = 1.8f
        /** Below this a recognizer is not reporting evidence, only filling the field in. */
        private const val MIN_CONFIDENCE = .55f
        /** Score of a line whose engine reported nothing worth comparing. */
        private const val NEUTRAL = .65f
        /** How far below a line, in its own heights, its continuation is still looked for. */
        private const val SEARCH = 4f

        fun joinLines(lines: List<String>): String = lines.fold("") { out, next ->
            val piece = next.trim()
            when {
                out.isEmpty() -> piece
                piece.isEmpty() -> out
                out.endsWith("-") && piece.firstOrNull()?.isLowerCase() == true -> out.dropLast(1) + piece
                // Japanese and Chinese are written without spaces. A space invented at a line wrap
                // changes the text the translator reads and the key it is cached under.
                glued(out.last()) && glued(piece.first()) -> out + piece
                else -> "$out $piece"
            }
        }

        /** Scripts that carry no spaces of their own. Korean is written with them and is not here. */
        private fun glued(c: Char) = c in '\u3000'..'\u303F' || c in '\u3040'..'\u30FF' ||
            c in '\u3400'..'\u4DBF' || c in '\u4E00'..'\u9FFF' || c in '\uFF01'..'\uFF60'
    }
}
