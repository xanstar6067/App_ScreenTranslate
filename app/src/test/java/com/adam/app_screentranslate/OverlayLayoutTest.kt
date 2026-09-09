package com.adam.app_screentranslate

import com.adam.app_screentranslate.capture.FrameMapping
import com.adam.app_screentranslate.model.Box
import com.adam.app_screentranslate.overlay.LabelLayout
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayLayoutTest {
    private val style = LabelLayout.Style(padding = 3f, minTextSize = 11f, maxTextSize = 30f,
        hardMinTextSize = 7f, minCardWidth = 64f)

    /** Stand-in for text metrics: glyphs are half as wide as they are tall, lines stack at 1.2. */
    private fun metrics(texts: Map<Long, String>) = LabelLayout.Measure { id, width, size ->
        val perLine = (width / (size * .5f)).toInt().coerceAtLeast(1)
        val text = texts.getValue(id)
        val lines = ((text.length + perLine - 1) / perLine).coerceAtLeast(1)
        lines * size * 1.2f
    }

    private fun overlap(a: Box, b: Box) = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f) *
        (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)

    @Test fun translationStaysOnTheTextItBelongsTo() {
        val source = Box(1400f, 620f, 1700f, 664f)
        val texts = mapOf(1L to "Перезарядка: 3 хода")
        val placement = LabelLayout.place(listOf(LabelLayout.Request(1, source, 1)),
            2400f, 1080f, style, metrics(texts)).single()
        assertEquals(source.left - style.padding, placement.box.left, .5f)
        assertEquals(source.top - style.padding, placement.box.top, .5f)
        // A short translation still covers the whole original line.
        assertTrue(placement.box.bottom >= source.bottom)
        assertTrue(placement.box.right >= source.right)
        assertTrue(placement.textSize >= style.minTextSize)
    }

    @Test fun denseScreenKeepsEveryCardOnItsOwnBox() {
        // A game HUD: many short labels spread over the screen, each translation twice as long.
        val sources = (0 until 32).map { i ->
            val column = i % 4
            val row = i / 4
            Box(60f + column * 580f, 40f + row * 128f, 60f + column * 580f + 240f, 40f + row * 128f + 40f)
        }
        val texts = sources.indices.associate { it.toLong() to "Перевод строки номер $it" }
        val requests = sources.mapIndexed { i, box -> LabelLayout.Request(i.toLong(), box, 1) }
        val placements = LabelLayout.place(requests, 2400f, 1080f, style, metrics(texts))
        assertEquals(sources.size, placements.size)
        val lefts = placements.map { it.box.left }.toSet()
        // The old renderer collapsed dense scenes into one panel in the corner.
        assertTrue("Cards must not share a single column", lefts.size > 3)
        for ((index, placement) in placements.withIndex()) {
            val source = sources[index]
            assertEquals("Card $index left", source.left - style.padding, placement.box.left, 1f)
            assertEquals("Card $index top", source.top - style.padding, placement.box.top, 1f)
            assertTrue("Card $index must cover its text", overlap(placement.box, source) > source.area * .9f)
        }
    }

    @Test fun growsIntoFreeSpaceWithoutCoveringTheNeighbour() {
        val left = Box(100f, 100f, 260f, 130f)
        val right = Box(400f, 100f, 560f, 130f)
        val texts = mapOf(1L to "Очень длинный перевод короткой надписи интерфейса, который совсем не помещается в исходную строку", 2L to "Готово")
        val placements = LabelLayout.place(
            listOf(LabelLayout.Request(1, left, 1), LabelLayout.Request(2, right, 1)),
            1200f, 800f, style, metrics(texts))
        val grown = placements.first { it.id == 1L }
        assertTrue("Long translation needs more room than the original", grown.box.height > left.height * 2f)
        assertTrue("Card must stop before the next recognized text", grown.box.right <= right.left)
        assertEquals(0f, overlap(grown.box, placements.first { it.id == 2L }.box), 0f)
    }

    @Test fun neverPlacesACardOutsideTheScreen() {
        val source = Box(2280f, 1020f, 2390f, 1070f)
        val texts = mapOf(7L to "Слишком длинный перевод для угла экрана, который никуда не помещается")
        val placement = LabelLayout.place(listOf(LabelLayout.Request(7, source, 1)),
            2400f, 1080f, style, metrics(texts)).single()
        assertTrue(placement.box.left >= 0f)
        assertTrue(placement.box.top >= 0f)
        assertTrue(placement.box.right <= 2400f)
        assertTrue(placement.box.bottom <= 1080f)
        assertTrue("The whole translation stays readable", placement.textSize >= style.hardMinTextSize)
        assertTrue("The card stays next to its text", abs(placement.box.left - source.left) < 400f)
    }

    @Test fun mapsLetterboxedFrameBackToScreenPixels() {
        // Portrait capture surface still mirroring a landscape display: content is centred with bars.
        val mapped = FrameMapping.toScreen(Box(0f, 957f, 1080f, 1443f), 1080, 2400, 2400, 1080)
        assertEquals(0f, mapped.left, .5f)
        assertEquals(0f, mapped.top, .5f)
        assertEquals(2400f, mapped.right, .5f)
        assertEquals(1080f, mapped.bottom, .5f)
    }

    @Test fun matchingFrameKeepsCoordinatesUntouched() {
        val box = Box(12f, 34f, 56f, 78f)
        assertEquals(box, FrameMapping.toScreen(box, 2400, 1080, 2400, 1080))
    }
}
