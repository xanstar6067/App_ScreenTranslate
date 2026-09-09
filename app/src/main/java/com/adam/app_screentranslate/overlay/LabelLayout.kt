package com.adam.app_screentranslate.overlay

import com.adam.app_screentranslate.model.Box
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of the translation cards. Pure Kotlin, so the placement is covered by JVM tests.
 *
 * A card never leaves the text it belongs to. It keeps the recognized footprint whenever the
 * translation fits inside it and only grows into space that no other recognized text occupies.
 * Relocating a translation to a free part of the screen is never done: the position is the only
 * link the reader has between the card and the original glyphs.
 */
object LabelLayout {
    data class Style(
        /** Card padding around the text. */
        val padding: Float,
        /** Smallest size still worth reading; below it a card grows instead of shrinking further. */
        val minTextSize: Float,
        val maxTextSize: Float,
        /** Absolute floor for a card that has no room to grow at all. */
        val hardMinTextSize: Float,
        /** Single glyphs and icons produce tiny boxes that still need a readable card. */
        val minCardWidth: Float,
        val widthGrowth: Float = 2.2f,
        val heightGrowth: Float = 3.5f
    )

    data class Request(val id: Long, val source: Box, val lines: Int)
    data class Placement(val id: Long, val box: Box, val textWidth: Float, val textSize: Float)

    fun interface Measure {
        /** Height of the text of [id] laid out in [width] pixels at [textSize]. */
        fun height(id: Long, width: Float, textSize: Float): Float
    }

    /** Recognized line height includes ascent and descent slack; glyphs use roughly this much of it. */
    private const val LINE_TO_TEXT = .78f

    fun place(
        requests: List<Request>, screenWidth: Float, screenHeight: Float,
        style: Style, measure: Measure
    ): List<Placement> {
        val screen = Box(0f, 0f, max(screenWidth, 1f), max(screenHeight, 1f))
        val sources = requests.map { clamp(it.source, screen) }
        val placed = mutableListOf<Box>()
        val result = mutableListOf<Placement>()
        for ((index, request) in requests.withIndex()) {
            val source = sources[index]
            val preferred = (source.height / request.lines.coerceAtLeast(1) * LINE_TO_TEXT)
                .coerceIn(min(style.minTextSize, style.maxTextSize), style.maxTextSize)
            // Other recognized text is an obstacle even before it gets a card of its own.
            val obstacles = sources.filterIndexed { i, other -> i != index && overlap(other, source) <= 0f } + placed
            val placement = fit(request.id, source, room(source, obstacles, screen, style), screen, preferred, style, measure)
            result += placement
            placed += placement.box
        }
        return result
    }

    private fun fit(
        id: Long, source: Box, room: Box, screen: Box, preferred: Float, style: Style, measure: Measure
    ): Placement {
        // 1. The translation reads best exactly where the original text was.
        largestFitting(id, source.width, source.height, preferred, style.minTextSize, measure)?.let {
            return placement(id, source, source.width, it, source.height, screen, style)
        }
        // 2. Grow into free space instead of shrinking the text below the readable floor.
        // The card is anchored at the source, so only the room to the right of and below it counts.
        val roomWidth = room.right - source.left + style.padding
        val roomHeight = room.bottom - source.top + style.padding
        val maxWidth = (min(roomWidth, max(source.width * style.widthGrowth, style.minCardWidth)) - 2 * style.padding)
            .coerceAtLeast(source.width)
        val maxHeight = (min(roomHeight, max(source.height * style.heightGrowth, preferred * 4f)) - 2 * style.padding)
            .coerceAtLeast(source.height)
        for (width in widths(source.width, maxWidth)) {
            val size = largestFitting(id, width, maxHeight, preferred, style.minTextSize, measure) ?: continue
            return placement(id, source, width, size, measure.height(id, width, size), screen, style)
        }
        // 3. Dense screen: a complete translation at a small size beats a clipped one.
        val size = largestFitting(id, maxWidth, maxHeight, style.minTextSize, style.hardMinTextSize, measure)
            ?: style.hardMinTextSize
        return placement(id, source, maxWidth, size, measure.height(id, maxWidth, size), screen, style)
    }

    private fun placement(
        id: Long, source: Box, textWidth: Float, textSize: Float, textHeight: Float, screen: Box, style: Style
    ): Placement {
        val width = textWidth + 2 * style.padding
        // A short translation still covers the whole original paragraph.
        val height = max(textHeight, source.height) + 2 * style.padding
        var left = source.left - style.padding
        var top = source.top - style.padding
        if (left + width > screen.right) left = screen.right - width
        if (top + height > screen.bottom) top = screen.bottom - height
        left = max(left, screen.left)
        top = max(top, screen.top)
        return Placement(id, Box(left, top, left + width, top + height), textWidth, textSize)
    }

    /** Widening keeps the original line rhythm; growing down is the fallback the caller gets anyway. */
    private fun widths(source: Float, limit: Float): List<Float> =
        listOf(source, source * 1.3f, source * 1.7f, limit)
            .map { min(max(it, 1f), max(limit, 1f)) }.distinct()

    private fun largestFitting(
        id: Long, width: Float, maxHeight: Float, from: Float, to: Float, measure: Measure
    ): Float? {
        if (width <= 0f || from < to) return null
        if (measure.height(id, width, from) <= maxHeight) return from
        if (measure.height(id, width, to) > maxHeight) return null
        var low = to
        var high = from
        repeat(5) {
            val mid = (low + high) / 2f
            if (measure.height(id, width, mid) <= maxHeight) low = mid else high = mid
        }
        return low
    }

    /** Free rectangle around [source]: the screen, cut back by every neighbour that blocks a side. */
    private fun room(source: Box, obstacles: List<Box>, screen: Box, style: Style): Box {
        var left = screen.left
        var top = screen.top
        var right = screen.right
        var bottom = screen.bottom
        for (other in obstacles) {
            val vertical = min(other.bottom, source.bottom) - max(other.top, source.top)
            val horizontal = min(other.right, source.right) - max(other.left, source.left)
            if (vertical > 0f && horizontal <= 0f) {
                if (other.right <= source.left) left = max(left, other.right + style.padding)
                else right = min(right, other.left - style.padding)
            } else if (horizontal > 0f && vertical <= 0f) {
                if (other.bottom <= source.top) top = max(top, other.bottom + style.padding)
                else bottom = min(bottom, other.top - style.padding)
            }
        }
        // Neighbours may overlap the source itself; the room always contains it.
        return Box(min(left, source.left), min(top, source.top), max(right, source.right), max(bottom, source.bottom))
    }

    private fun overlap(a: Box, b: Box): Float =
        (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f) *
            (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)

    private fun clamp(box: Box, screen: Box) = Box(
        box.left.coerceIn(screen.left, screen.right), box.top.coerceIn(screen.top, screen.bottom),
        box.right.coerceIn(screen.left, screen.right), box.bottom.coerceIn(screen.top, screen.bottom)
    )
}
