package com.adam.app_screentranslate.overlay

import com.adam.app_screentranslate.model.Box
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
        /** A caption is worth this much shrinking to stay on one line instead of breaking apart. */
        val singleLineMinTextSize: Float = minTextSize * .8f,
        /**
         * What the reader asked translations to be, relative to the text they replace. A card
         * starts at the size the game itself wrote at, so this is the only place the setting can
         * still be heard.
         */
        val textScale: Float = 1f,
        val widthGrowth: Float = 2.2f,
        val heightGrowth: Float = 3.5f
    )

    data class Request(
        val id: Long, val source: Box, val lines: Int,
        /** Tilt of the recognized text in degrees; the card is drawn turned by the same amount. */
        val angle: Float = 0f,
        /** Flat interface plate the text sits on. A card never grows off its own plate. */
        val bounds: Box? = null
    )

    data class Placement(val id: Long, val box: Box, val textWidth: Float, val textSize: Float, val angle: Float = 0f)

    interface Measure {
        /** Height of the text of [id] laid out in [width] pixels at [textSize]. */
        fun height(id: Long, width: Float, textSize: Float): Float
        /** Width the whole text of [id] needs on a single unbroken line at [textSize]. */
        fun lineWidth(id: Long, textSize: Float): Float
    }

    /** What the screen itself looks like where a card is about to go. */
    fun interface Space {
        /** Share of [box] free of screen detail, from 0 (artwork) to 1 (flat surface). */
        fun quiet(box: Box): Float
    }

    private val anywhere = Space { 1f }

    /** Recognized line height includes ascent and descent slack; glyphs use roughly this much of it. */
    private const val LINE_TO_TEXT = .78f
    /** Above this width a box is a real line of text, and its left margin carries meaning. */
    private const val CENTRED_SOURCE = 1.5f
    /** Below this a reported tilt is recognizer noise; above it the arithmetic stops being sound. */
    private const val MIN_ANGLE = 4f
    private const val MAX_ANGLE = 32f
    /** Growing over artwork costs the reader more than growing over a flat surface. */
    private const val QUIET_WEIGHT = .45f
    /** Only the space a card can actually reach says anything about where it should grow. */
    private const val REACH = 3f

    fun place(
        requests: List<Request>, screenWidth: Float, screenHeight: Float,
        style: Style, measure: Measure, space: Space = anywhere
    ): List<Placement> {
        val screen = Box(0f, 0f, max(screenWidth, 1f), max(screenHeight, 1f))
        val sources = requests.map { clamp(it.source, screen) }
        val placed = mutableListOf<Box>()
        val result = mutableListOf<Placement>()
        for ((index, request) in requests.withIndex()) {
            val source = sources[index]
            var tilt = if (abs(request.angle) in MIN_ANGLE..MAX_ANGLE) request.angle else 0f
            // Tilted text is laid out in its own upright frame and turned back when it is drawn.
            val local = (if (tilt != 0f) oriented(source, tilt) else null) ?: source.also { tilt = 0f }
            // Other recognized text is an obstacle even before it gets a card of its own.
            val obstacles = sources.filterIndexed { i, other -> i != index && overlap(other, source) <= 0f } + placed
            val limit = request.bounds?.let { clamp(it, screen) } ?: screen
            val room = room(source, obstacles, limit, style, space)
            // A turned card is laid out in its own frame, so the free rectangle has to be turned
            // with it: what the card may use is the largest such frame around the centre of the
            // text whose upright shadow still fits in the room.
            val frame = if (tilt == 0f) room else turned(local, source, room, tilt)
            // The size the original glyphs were drawn at. A dense screen writes smaller than the
            // readable floor of this app, and forcing the floor there makes a translation that no
            // longer fits the row it belongs to: what the reader could already read, they can read.
            val preferred = (local.height / request.lines.coerceAtLeast(1) * LINE_TO_TEXT * style.textScale)
                .coerceIn(min(style.hardMinTextSize, style.maxTextSize), style.maxTextSize)
            val placement = fit(request.id, local, frame, screen, preferred, request.lines <= 1, tilt, style, measure)
            result += placement
            placed += if (tilt == 0f) placement.box else upright(placement.box, tilt)
        }
        return result
    }

    private fun fit(
        id: Long, source: Box, room: Box, screen: Box, preferred: Float,
        singleLine: Boolean, tilt: Float, style: Style, measure: Measure
    ): Placement {
        // Everything the card may occupy, padding included. The free rectangle is what keeps a card
        // off its neighbours, so no step below may ask for more space than it holds.
        val fullWidth = (max(room.right - room.left, source.width) - 2 * style.padding).coerceAtLeast(source.width)
        val fullHeight = (max(room.bottom - room.top, source.height) - 2 * style.padding).coerceAtLeast(source.height)
        // Inside it a card still never grows more than a card's worth: a translation that runs
        // across the screen has lost the text it belongs to.
        val maxWidth = min(fullWidth, max(source.width * style.widthGrowth, style.minCardWidth))
            .coerceAtLeast(source.width)
        val maxHeight = min(fullHeight, max(source.height * style.heightGrowth, preferred * 4f))
            .coerceAtLeast(source.height)
        // The readable floor never rises above the size the text already had on screen.
        val floor = min(style.minTextSize, preferred)
        val caption = min(style.singleLineMinTextSize, preferred)
        // 0. A caption broken into syllables is unreadable; a smaller unbroken line is not.
        if (singleLine) {
            largestOnOneLine(id, maxWidth, maxHeight, preferred, caption, measure)?.let { size ->
                val width = min(measure.lineWidth(id, size), maxWidth)
                // A tiny box is an icon or a button: its label reads best centred on it.
                val centred = source.width < style.minCardWidth * CENTRED_SOURCE
                return placement(id, source, width, size, measure.height(id, width, size), room, screen, style, centred, tilt)
            }
        }
        // 1. The translation reads best exactly where the original text was.
        largestFitting(id, source.width, source.height, preferred, floor, measure)?.let {
            return placement(id, source, source.width, it, source.height, room, screen, style, false, tilt)
        }
        // 2. Grow into free space instead of shrinking the text below the readable floor.
        for (width in widths(source.width, maxWidth)) {
            val size = largestFitting(id, width, maxHeight, preferred, floor, measure) ?: continue
            return placement(id, source, width, size, measure.height(id, width, size), room, screen, style, false, tilt)
        }
        // 3. The rest of the free rectangle belongs to this card too. Spending it costs the reader
        // nothing, while every size below this point costs readability.
        if (fullWidth > maxWidth || fullHeight > maxHeight) {
            for (width in widths(maxWidth, fullWidth)) {
                val size = largestFitting(id, width, fullHeight, preferred, floor, measure) ?: continue
                return placement(id, source, width, size, measure.height(id, width, size), room, screen, style, false, tilt)
            }
        }
        // 4. A caption with no room to wrap is still readable one small line at a time.
        if (singleLine) {
            largestOnOneLine(id, fullWidth, fullHeight, caption, style.hardMinTextSize, measure)?.let { size ->
                val width = min(measure.lineWidth(id, size), fullWidth)
                val centred = source.width < style.minCardWidth * CENTRED_SOURCE
                return placement(id, source, width, size, measure.height(id, width, size), room, screen, style, centred, tilt)
            }
        }
        // 5. Dense screen: a complete translation at a small size beats a clipped one. When even
        // that does not fit, the card still stops at the free rectangle and the renderer cuts the
        // tail off, because a card spilling over its neighbour costs two translations, not one.
        val size = largestFitting(id, fullWidth, fullHeight, floor, style.hardMinTextSize, measure)
            ?: style.hardMinTextSize
        return placement(id, source, fullWidth, size, measure.height(id, fullWidth, size), room, screen, style, false, tilt)
    }

    private fun placement(
        id: Long, source: Box, textWidth: Float, textSize: Float, textHeight: Float,
        room: Box, screen: Box, style: Style, centred: Boolean, tilt: Float
    ): Placement {
        // A short translation still covers the whole original text: nothing of it may stay visible.
        // Staying inside the free rectangle is the harder promise of the two, so a card with room
        // for only one of them gives up its padding first and the margin around its glyphs last.
        val width = fitted(max(textWidth, source.width), source.width, room.right - room.left, style.padding)
        val height = fitted(max(textHeight, source.height), source.height, room.bottom - room.top, style.padding)
        // A card that will be turned keeps the centre of the text as its own: that is the one point
        // the rotation leaves in place, so it is the only anchor that survives it.
        if (tilt != 0f) {
            val x = source.left + source.width / 2f - width / 2f
            val y = source.top + source.height / 2f - height / 2f
            return Placement(id, Box(x, y, x + width, y + height), width - 2 * style.padding, textSize, tilt)
        }
        val left = if (centred) source.left + source.width / 2f - width / 2f else source.left - style.padding
        val top = source.top - style.padding
        // The free rectangle is what keeps neighbours uncovered, so the card is confined to it
        // first; growing sideways out of the anchor is the same clamp seen from the other end.
        val x = into(into(left, width, room.left, room.right), width, screen.left, screen.right)
        val y = into(into(top, height, room.top, room.bottom), height, screen.top, screen.bottom)
        return Placement(id, Box(x, y, x + width, y + height), width - 2 * style.padding, textSize, 0f)
    }

    /**
     * Card size along one axis: [wanted] plus padding, cut back to the room the card was given and
     * never below [must], the extent of the original glyphs the card has to cover.
     */
    private fun fitted(wanted: Float, must: Float, room: Float, padding: Float): Float =
        max(min(wanted + 2 * padding, max(room, must)), must)

    /** Keeps a [size]-wide span inside [low]..[high], giving up on the far edge when it cannot fit. */
    private fun into(value: Float, size: Float, low: Float, high: Float): Float =
        if (high - low <= size) low else value.coerceIn(low, high - size)

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

    /**
     * Largest size at which the whole text is one unbroken line inside [maxWidth] and [maxHeight].
     * A line that fits the width but not the height would be drawn and then cut off by the card.
     */
    private fun largestOnOneLine(
        id: Long, maxWidth: Float, maxHeight: Float, from: Float, to: Float, measure: Measure
    ): Float? {
        fun fits(size: Float): Boolean {
            val line = measure.lineWidth(id, size)
            return line <= maxWidth && measure.height(id, min(line, maxWidth), size) <= maxHeight
        }
        if (maxWidth <= 0f || from < to) return null
        if (fits(from)) return from
        if (!fits(to)) return null
        var low = to
        var high = from
        repeat(5) {
            val mid = (low + high) / 2f
            if (fits(mid)) low = mid else high = mid
        }
        return low
    }

    /**
     * Free rectangle around [source]: the plate the text sits on, or the screen, cut back by every
     * neighbour that reaches into it. A neighbour standing diagonally blocks growth just as much as
     * one directly beside the text, so the cut is chosen by what it costs, not by which side the
     * neighbour happens to sit on. Of two cuts that cost the same, the calmer one wins: a card
     * spread over flat interface hides less of the screen than one spread over artwork.
     */
    private fun room(source: Box, obstacles: List<Box>, limit: Box, style: Style, space: Space): Box {
        var left = min(limit.left, source.left)
        var top = min(limit.top, source.top)
        var right = max(limit.right, source.right)
        var bottom = max(limit.bottom, source.bottom)
        val reach = Box(source.left - source.width * REACH, source.top - source.height * REACH,
            source.right + source.width * REACH, source.bottom + source.height * REACH)
        // Nearest first: the closest neighbour decides the shape, the distant ones rarely intrude.
        for (other in obstacles.sortedBy { distance(source, it) }) {
            if (other.right <= left || other.left >= right || other.bottom <= top || other.top >= bottom) continue
            var best: Box? = null
            var bestScore = 0f
            for (option in listOf(
                Box(max(left, other.right + style.padding), top, right, bottom),
                Box(left, top, min(right, other.left - style.padding), bottom),
                Box(left, max(top, other.bottom + style.padding), right, bottom),
                Box(left, top, right, min(bottom, other.top - style.padding))
            )) {
                if (option.left > source.left || option.top > source.top ||
                    option.right < source.right || option.bottom < source.bottom) continue
                val calm = space.quiet(intersect(option, reach))
                val score = span(option) * (1f - QUIET_WEIGHT + QUIET_WEIGHT * calm)
                if (best == null || score > bestScore) { best = option; bestScore = score }
            }
            val chosen = best ?: continue
            left = chosen.left; top = chosen.top; right = chosen.right; bottom = chosen.bottom
        }
        // A neighbour may already cover the source; the room always contains it.
        return Box(min(left, source.left), min(top, source.top), max(right, source.right), max(bottom, source.bottom))
    }

    /**
     * The free rectangle of a turned card, expressed in the frame the card is laid out in.
     *
     * A turned card is pinned to the centre of its text, so it can only use the part of the room
     * that lies symmetrically around that centre; what is left of the room on the far side is out
     * of reach. Turning that reachable rectangle back gives the card's own limits, and the card is
     * never allowed below the line of glyphs it has to cover.
     */
    private fun turned(local: Box, source: Box, room: Box, tilt: Float): Box {
        val x = (source.left + source.right) / 2f
        val y = (source.top + source.bottom) / 2f
        val across = min(x - room.left, room.right - x).coerceAtLeast(local.width / 2f)
        val down = min(y - room.top, room.bottom - y).coerceAtLeast(local.height / 2f)
        val reachable = oriented(Box(x - across, y - down, x + across, y + down), tilt) ?: local
        val width = max(reachable.width, local.width) / 2f
        val height = max(reachable.height, local.height) / 2f
        return Box(x - width, y - height, x + width, y + height)
    }

    /**
     * The recognizer reports both the tilt of a line and the upright rectangle drawn around it.
     * The rectangle of the text itself follows from the two, and for a tilted line it is much
     * smaller than the upright one — which is why an upright card on tilted text looks misplaced.
     */
    private fun oriented(source: Box, angle: Float): Box? {
        val radians = angle * PI.toFloat() / 180f
        val across = abs(cos(radians))
        val down = abs(sin(radians))
        val determinant = across * across - down * down
        if (abs(determinant) < .2f) return null
        val width = (source.width * across - source.height * down) / determinant
        val height = (source.height * across - source.width * down) / determinant
        if (width < 1f || height < 1f) return null
        val x = (source.left + source.right) / 2f
        val y = (source.top + source.bottom) / 2f
        return Box(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
    }

    /** Upright rectangle around a card that will be drawn turned: what its neighbours must avoid. */
    private fun upright(box: Box, angle: Float): Box {
        val radians = angle * PI.toFloat() / 180f
        val across = abs(cos(radians))
        val down = abs(sin(radians))
        val width = box.width * across + box.height * down
        val height = box.width * down + box.height * across
        val x = (box.left + box.right) / 2f
        val y = (box.top + box.bottom) / 2f
        return Box(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
    }

    private fun span(box: Box) = (box.right - box.left) * (box.bottom - box.top)

    private fun intersect(a: Box, b: Box) = Box(
        max(a.left, b.left), max(a.top, b.top),
        max(min(a.right, b.right), max(a.left, b.left)), max(min(a.bottom, b.bottom), max(a.top, b.top)))

    private fun distance(a: Box, b: Box): Float {
        val x = max(max(a.left - b.right, b.left - a.right), 0f)
        val y = max(max(a.top - b.bottom, b.top - a.bottom), 0f)
        return x * x + y * y
    }

    private fun overlap(a: Box, b: Box): Float =
        (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f) *
            (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)

    private fun clamp(box: Box, screen: Box) = Box(
        box.left.coerceIn(screen.left, screen.right), box.top.coerceIn(screen.top, screen.bottom),
        box.right.coerceIn(screen.left, screen.right), box.bottom.coerceIn(screen.top, screen.bottom)
    )
}
