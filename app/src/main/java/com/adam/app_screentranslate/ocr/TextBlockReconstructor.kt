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
        val selected = mutableListOf<ScreenTextBlock>()
        val candidates = input.flatMap { block ->
            if (block.lines.isEmpty()) {
                val text = TextNormalizer.harmonizeScript(block.originalText)
                listOf(block.copy(originalText = text, script = ScriptDetector.detect(text)))
            } else block.lines.map { line ->
                val text = TextNormalizer.harmonizeScript(line.text)
                block.copy(originalText = text, boundingBox = line.box, lines = listOf(line),
                    confidence = line.confidence ?: block.confidence, script = ScriptDetector.detect(text))
            }
        }
        for (block in candidates.filter { TextNormalizer.isTranslatable(it.originalText) && (it.confidence ?: 1f) >= .55f }
            .sortedByDescending { quality(it) }) {
            if (selected.none { duplicate(it, block) }) selected += block
        }
        val result = selected.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left })).toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in result.indices) for (j in i + 1 until result.size) {
                val a = result[i]; val b = result[j]
                if (canMerge(a, b, mode)) {
                    val text = joinLines(listOf(a.originalText, b.originalText))
                    result[i] = a.copy(originalText = text, boundingBox = a.boundingBox.union(b.boundingBox),
                        lines = a.lines + b.lines, script = ScriptDetector.detect(text))
                    result.removeAt(j)
                    changed = true
                    break@outer
                }
            }
        }
        return result.mapIndexed { i, b -> b.copy(id = i.toLong(), originalText = TextNormalizer.normalize(b.originalText)) }
    }

    private fun splitObviousControls(block: ScreenTextBlock): List<ScreenTextBlock> {
        if (block.lines.size < 2 || block.lines.any { abs(it.angle) > 15f }) return listOf(block)
        val lines = block.lines.sortedBy { it.box.top }
        val groups = mutableListOf(mutableListOf(lines.first()))
        for (line in lines.drop(1)) {
            val prev = groups.last().last()
            fun label(text: String) = text.length < 24 && text.split(' ').size <= 3 && text.firstOrNull()?.isUpperCase() == true
            val gap = line.box.top - prev.box.bottom
            if (gap > (line.box.height + prev.box.height) * .55f ||
                (label(prev.text) && label(line.text) && gap > 0)) groups += mutableListOf(line)
            else groups.last() += line
        }
        if (groups.size == 1) return listOf(block)
        return groups.map { group ->
            val text = joinLines(group.map { it.text })
            block.copy(originalText = text, lines = group, boundingBox = group.map { it.box }.reduce { a, b -> a.union(b) },
                script = ScriptDetector.detect(text))
        }
    }

    private fun quality(b: ScreenTextBlock): Float {
        val letters = b.originalText.count(Char::isLetter).coerceAtLeast(1)
        val cjk = b.originalText.count { it in '\u3040'..'\u9FFF' || it in '\uAC00'..'\uD7AF' }
        val strayScriptPenalty = if (cjk in 1..2 && cjk.toFloat()/letters < .25f) .12f else 0f
        // Confidence is model evidence; a CJK character alone is never evidence of correctness.
        return (b.confidence ?: .65f) - strayScriptPenalty
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
        val gap = rb.top - ra.bottom
        if (gap < -h * .15f || gap > h * mode.gap || max(ah, bh) / min(ah, bh) > 1.45f) return false
        if (a.lines.any { abs(it.angle) > 15f } || b.lines.any { abs(it.angle) > 15f }) return false
        val overlapX = (min(ra.right, rb.right) - max(ra.left, rb.left)).coerceAtLeast(0f) / min(ra.width, rb.width)
        if (overlapX < .65f || abs(ra.left - rb.left) > h * .8f) return false
        if (a.script != b.script && a.script != TextScript.MIXED && b.script != TextScript.MIXED) return false
        val first = a.originalText.trim(); val second = b.originalText.trim()
        if (first.lastOrNull() in listOf('.', '!', '?', '。', '！', '？', ':')) return false
        // Short title-case labels are separate controls, even at tight line spacing.
        val labelA = first.split(' ').size <= 3 && first.length < 24
        val labelB = second.split(' ').size <= 3 && second.length < 24
        if (labelA && labelB && first.firstOrNull()?.isUpperCase() == true && second.firstOrNull()?.isUpperCase() == true) return false
        return true
    }
    companion object {
        fun joinLines(lines: List<String>): String = lines.fold("") { out, next ->
            when {
                out.isEmpty() -> next.trim()
                out.endsWith("-") && next.firstOrNull()?.isLowerCase() == true -> out.dropLast(1) + next.trim()
                else -> "$out ${next.trim()}"
            }
        }
    }
}
