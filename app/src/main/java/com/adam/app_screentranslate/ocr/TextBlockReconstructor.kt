package com.adam.app_screentranslate.ocr

import com.adam.app_screentranslate.model.*
import kotlin.math.*

object TextNormalizer {
    fun normalize(text: String) = text.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replace(Regex("[\\s\\u00A0]+"), " ").trim()
}

object ScriptDetector {
    fun detect(text: String): TextScript {
        val latin = text.any { it in 'A'..'Z' || it in 'a'..'z' || it in '\u00C0'..'\u024F' }
        val japanese = text.any { it in '\u3040'..'\u30FF' || it in '\u3400'..'\u9FFF' }
        val korean = text.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }
        return when {
            listOf(latin, japanese, korean).count { it } > 1 -> TextScript.MIXED
            japanese -> TextScript.JAPANESE
            korean -> TextScript.KOREAN
            latin -> TextScript.LATIN
            else -> TextScript.UNKNOWN
        }
    }
}

class TextBlockReconstructor {
    fun reconstruct(input: List<ScreenTextBlock>, mode: MergeMode): List<ScreenTextBlock> {
        // Prefer script-specific results over a Latin model's guesses at CJK glyphs.
        val selected = mutableListOf<ScreenTextBlock>()
        for (block in input.flatMap { splitObviousControls(it) }.filter { it.originalText.isNotBlank() }.sortedByDescending { quality(it) }) {
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
        val scriptBonus = when (b.script) { TextScript.JAPANESE, TextScript.KOREAN, TextScript.MIXED -> 20f; else -> 0f }
        return scriptBonus + (b.confidence ?: .5f) * 10 + ln(b.originalText.length.coerceAtLeast(1).toFloat())
    }
    private fun duplicate(a: ScreenTextBlock, b: ScreenTextBlock): Boolean {
        val overlap = a.boundingBox.intersection(b.boundingBox)
        val containment = overlap / min(a.boundingBox.area, b.boundingBox.area)
        val iou = overlap / (a.boundingBox.area + b.boundingBox.area - overlap)
        val x = TextNormalizer.normalize(a.originalText).lowercase()
        val y = TextNormalizer.normalize(b.originalText).lowercase()
        return (iou > .55f) || (containment > .8f && (x.contains(y) || y.contains(x))) ||
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
