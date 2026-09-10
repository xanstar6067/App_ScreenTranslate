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

    /** The same style the renderer builds on a normal phone: dp turned into device pixels. */
    private val density = 2.75f
    private val phone = LabelLayout.Style(padding = 3f * density, minTextSize = 11f * density,
        maxTextSize = 30f * density, hardMinTextSize = 7f * density, minCardWidth = 64f * density)

    /** Stand-in for text metrics: glyphs are half as wide as they are tall, lines stack at 1.2. */
    private fun metrics(texts: Map<Long, String>) = object : LabelLayout.Measure {
        override fun height(id: Long, width: Float, textSize: Float): Float {
            val perLine = (width / (textSize * .5f)).toInt().coerceAtLeast(1)
            val text = texts.getValue(id)
            val lines = ((text.length + perLine - 1) / perLine).coerceAtLeast(1)
            return lines * textSize * 1.2f
        }
        override fun lineWidth(id: Long, textSize: Float) = texts.getValue(id).length * textSize * .5f
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

    @Test fun captionShrinksInsteadOfBreakingIntoSyllables() {
        // A game button: a narrow box whose Russian label is far longer than the English original.
        val source = Box(2200f, 20f, 2260f, 80f)
        val texts = mapOf(4L to "Пропустить")
        val measure = metrics(texts)
        val placement = LabelLayout.place(listOf(LabelLayout.Request(4, source, 1)),
            2400f, 1080f, style, measure).single()
        assertTrue("The label must stay on one line", measure.lineWidth(4, placement.textSize) <= placement.textWidth + .5f)
        assertTrue("Fitting one line is worth some shrinking", placement.textSize < style.maxTextSize)
        assertTrue("But not below the caption floor", placement.textSize >= style.singleLineMinTextSize)
        assertTrue("The card stays on its button", overlap(placement.box, source) > source.area * .9f)
    }

    @Test fun neighbourStandingDiagonallyIsNotCovered() {
        // The card grows right and down at once; a box on that diagonal used to be ignored.
        val long = Box(100f, 100f, 300f, 140f)
        val diagonal = Box(360f, 200f, 560f, 240f)
        val texts = mapOf(
            1L to "Очень длинный перевод, которому не хватает исходной рамки и который вынужден расти в свободное место рядом с ней",
            2L to "Соседняя надпись")
        val placements = LabelLayout.place(
            listOf(LabelLayout.Request(1, long, 2), LabelLayout.Request(2, diagonal, 1)),
            1200f, 800f, style, metrics(texts))
        val grown = placements.first { it.id == 1L }
        assertTrue("The translation had to grow", grown.box.area > long.area * 1.5f)
        assertEquals("A diagonal neighbour blocks growth too", 0f, overlap(grown.box, diagonal), 0f)
        assertEquals(0f, overlap(grown.box, placements.first { it.id == 2L }.box), 0f)
    }

    @Test fun cardNeverGrowsOffTheInterfacePlate() {
        // A subtitle on a dialog bar: the translation is far too long for the bar, and the artwork
        // around it is not a place a card may spill onto.
        val source = Box(300f, 900f, 900f, 950f)
        val plate = Box(240f, 860f, 960f, 1000f)
        val texts = mapOf(3L to "Эта комната выглядит совсем иначе. Вероятно, это койка охранников, и ключ должен быть где-то здесь")
        val placement = LabelLayout.place(
            listOf(LabelLayout.Request(3, source, 2, bounds = plate)),
            2400f, 1080f, style, metrics(texts)).single()
        assertTrue("Card must stay on the plate", placement.box.left >= plate.left - .5f)
        assertTrue("Card must stay on the plate", placement.box.right <= plate.right + .5f)
        assertTrue("Card must stay on the plate", placement.box.bottom <= plate.bottom + .5f)
        assertTrue("The card still covers its own line", overlap(placement.box, source) > source.area * .9f)
    }

    @Test fun tiltedTextGetsATurnedCardOfItsOwnSize() {
        // What the recognizer reports for a line of about 300x40 turned by 15 degrees.
        val source = Box(1000f, 500f, 1300.2f, 616.3f)
        val texts = mapOf(5L to "Внутренние коммуникации")
        val placement = LabelLayout.place(listOf(LabelLayout.Request(5, source, 1, angle = 15f)),
            2400f, 1080f, style, metrics(texts)).single()
        assertEquals("The card is turned with the text", 15f, placement.angle, 0f)
        // The upright box around tilted text is far taller than the text; the card is not.
        assertTrue("Card follows the text, not the upright box around it", placement.box.height < 60f)
        assertEquals("A turned card keeps the centre of the text",
            (source.left + source.right) / 2f, (placement.box.left + placement.box.right) / 2f, .5f)
        assertEquals((source.top + source.bottom) / 2f, (placement.box.top + placement.box.bottom) / 2f, .5f)
    }

    @Test fun aReportedWobbleIsNotATilt() {
        val source = Box(1000f, 500f, 1300f, 560f)
        val texts = mapOf(6L to "Неопознанные связи")
        val placement = LabelLayout.place(listOf(LabelLayout.Request(6, source, 1, angle = 2f)),
            2400f, 1080f, style, metrics(texts)).single()
        assertEquals(0f, placement.angle, 0f)
        assertEquals(source.left - style.padding, placement.box.left, .5f)
    }

    @Test fun growthPrefersFlatInterfaceOverArtwork() {
        // Equal room on both sides of the text, but the left half of the screen is artwork.
        val source = Box(600f, 500f, 900f, 540f)
        val texts = mapOf(9L to "Длинный перевод короткой строки, которому нужно куда-то расти")
        val artwork = Box(0f, 0f, 600f, 1080f)
        val placement = LabelLayout.place(
            listOf(LabelLayout.Request(9, source, 1)), 1500f, 1080f, style, metrics(texts)
        ) { box -> if (overlap(box, artwork) > box.area * .5f) 0f else 1f }.single()
        assertTrue("The card must not spread over the artwork", placement.box.left >= artwork.right - style.padding - .5f)
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

    /** Every card sits inside the free rectangle, so no pair of cards may ever overlap. */
    private fun assertNoOverlaps(placements: List<LabelLayout.Placement>) {
        for (i in placements.indices) for (j in i + 1 until placements.size) {
            val a = placements[i]; val b = placements[j]
            assertEquals("Card ${a.id} overlaps card ${b.id}", 0f, overlap(a.box, b.box), .5f)
        }
    }

    @Test fun aDenseStatListNeverPilesCardsOnTopOfEachOther() {
        // The stat sheet of a mobile game: twelve rows at one line pitch, each English label
        // replaced by a far longer Russian one. This is the screen that used to end up as a pile.
        val labels = listOf(
            "Атака", "Защита", "Здоровье", "Скорость", "Шанс критического удара",
            "Критический урон", "Снижение критического урона", "Проникновение",
            "Увеличение урона", "Снижение урона", "Эффективность", "Устойчивость")
        val sources = labels.indices.map { i -> Box(694f, 460f + i * 46f, 900f, 460f + i * 46f + 32f) }
        val texts = labels.indices.associate { it.toLong() to labels[it] }
        val placements = LabelLayout.place(
            sources.mapIndexed { i, box -> LabelLayout.Request(i.toLong(), box, 1) },
            2400f, 1080f, phone, metrics(texts))
        assertNoOverlaps(placements)
        val measure = metrics(texts)
        for ((index, placement) in placements.withIndex()) {
            assertTrue("Card $index must still cover its own text",
                overlap(placement.box, sources[index]) > sources[index].area * .9f)
            // Not overlapping is only half of it: a row this size holds one line of the size the
            // game itself wrote at, so nothing here has any business being cut off.
            assertTrue("Card $index had to cut its translation off",
                measure.height(placement.id, placement.textWidth, placement.textSize) <= placement.box.height + .5f)
            assertTrue("Card $index shrank below the text it replaces",
                placement.textSize >= sources[index].height * .7f)
        }
    }

    @Test fun aCardNeverLeavesTheRoomItWasGiven() {
        // A row squeezed between two neighbours: the translation cannot fit, and the card used to
        // grow through them anyway instead of shrinking the text.
        val above = Box(100f, 100f, 400f, 140f)
        val source = Box(100f, 150f, 400f, 190f)
        val below = Box(100f, 200f, 400f, 240f)
        val texts = mapOf(
            1L to "Верх", 2L to "Совершенно неуместно длинный перевод короткой строки интерфейса, " +
                "который не помещается ни в свою рамку, ни в промежуток между соседями", 3L to "Низ")
        val placements = LabelLayout.place(listOf(
            LabelLayout.Request(1, above, 1), LabelLayout.Request(2, source, 1),
            LabelLayout.Request(3, below, 1)), 1200f, 800f, style, metrics(texts))
        val middle = placements.first { it.id == 2L }
        assertEquals("A card must not reach the row above", 0f, overlap(middle.box, above), .5f)
        assertEquals("A card must not reach the row below", 0f, overlap(middle.box, below), .5f)
        assertNoOverlaps(placements)
    }

    @Test fun aTurnedCardStaysInsideItsPlate() {
        // A tilted panel header: the upright box around the turned card must stay on the plate.
        val source = Box(1000f, 500f, 1300.2f, 616.3f)
        val plate = Box(960f, 470f, 1360f, 650f)
        val texts = mapOf(5L to "Внутренние коммуникации, а также всё остальное, что туда не влезает")
        val placement = LabelLayout.place(
            listOf(LabelLayout.Request(5, source, 1, angle = 15f, bounds = plate)),
            2400f, 1080f, style, metrics(texts)).single()
        val radians = placement.angle * Math.PI.toFloat() / 180f
        val across = kotlin.math.abs(kotlin.math.cos(radians))
        val down = kotlin.math.abs(kotlin.math.sin(radians))
        val width = placement.box.width * across + placement.box.height * down
        val height = placement.box.width * down + placement.box.height * across
        val centreX = (placement.box.left + placement.box.right) / 2f
        val centreY = (placement.box.top + placement.box.bottom) / 2f
        assertTrue("Turned card leaves the plate on the left", centreX - width / 2f >= plate.left - .5f)
        assertTrue("Turned card leaves the plate on the right", centreX + width / 2f <= plate.right + .5f)
        assertTrue("Turned card leaves the plate on top", centreY - height / 2f >= plate.top - .5f)
        assertTrue("Turned card leaves the plate at the bottom", centreY + height / 2f <= plate.bottom + .5f)
    }

    @Test fun aCrowdedScreenNeverProducesASinglePairOfOverlappingCards() {
        // A whole game screen at once: a HUD row, a stat sheet, a tilted panel header and a
        // subtitle, with translations from one word to a full sentence. No arrangement of them
        // may put one card over another, whatever the layout has to give up to avoid it.
        val requests = mutableListOf<LabelLayout.Request>()
        val texts = HashMap<Long, String>()
        var id = 0L
        fun add(box: Box, text: String, lines: Int = 1, angle: Float = 0f, bounds: Box? = null) {
            requests += LabelLayout.Request(id, box, lines, angle, bounds)
            texts[id] = text
            id++
        }
        for (i in 0 until 8) {
            add(Box(40f + i * 210f, 30f, 40f + i * 210f + 150f, 66f), "Показатель номер $i интерфейса")
        }
        for (i in 0 until 14) {
            add(Box(694f, 200f + i * 44f, 700f + 110f + (i % 5) * 22f, 200f + i * 44f + 32f),
                "Совершенно непомерно длинное название характеристики $i")
        }
        for (i in 0 until 6) {
            add(Box(1500f, 260f + i * 70f, 1500f + 260f, 260f + i * 70f + 40f),
                "Навык $i: очень подробное описание, которому решительно не хватает места", lines = 2)
        }
        add(Box(1000f, 500f, 1300.2f, 616.3f), "Внутренние коммуникации", angle = 15f,
            bounds = Box(960f, 470f, 1360f, 650f))
        add(Box(300f, 940f, 2100f, 1000f),
            "Смотрите, это лифт! Мы поднимемся на верхний этаж с его помощью.", bounds = Box(280f, 920f, 2120f, 1020f))
        val placements = LabelLayout.place(requests, 2400f, 1080f, phone, metrics(texts))
        assertEquals(requests.size, placements.size)
        assertNoOverlaps(placements)
        for (placement in placements) {
            val source = requests.first { it.id == placement.id }.source
            assertTrue("Card ${placement.id} lost the text it belongs to",
                overlap(placement.box, source) > 0f)
            assertTrue("Card ${placement.id} left the screen",
                placement.box.left >= -1f && placement.box.top >= -1f &&
                    placement.box.right <= 2401f && placement.box.bottom <= 1081f)
        }
    }

    @Test fun theTextScaleSettingStillEnlargesACardThatHasTheRoom() {
        // Cards take the size of the glyphs they replace, so the setting has to reach that size
        // rather than only the readable floor, which a roomy card never touches.
        val source = Box(400f, 400f, 900f, 440f)
        val texts = mapOf(1L to "Начать")
        val plain = LabelLayout.place(listOf(LabelLayout.Request(1, source, 1)),
            2400f, 1080f, phone, metrics(texts)).single()
        val large = LabelLayout.place(listOf(LabelLayout.Request(1, source, 1)),
            2400f, 1080f, phone.copy(textScale = 1.5f), metrics(texts)).single()
        assertTrue("Raising the text scale must raise the text", large.textSize > plain.textSize * 1.4f)
    }
}
