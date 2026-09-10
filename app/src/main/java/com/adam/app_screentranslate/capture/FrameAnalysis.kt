package com.adam.app_screentranslate.capture

import com.adam.app_screentranslate.model.Box
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * A coarse description of what the captured screen looks like: one average colour per cell of a
 * small grid, roughly a hundred cells across. It is enough to tell flat interface from artwork,
 * which is what decides where a translation may grow and what colour it should have.
 *
 * The grid stays in memory for exactly as long as the translations it positions. Like every other
 * product of a frame it never reaches the network, the disk or the log.
 *
 * Pure Kotlin, so both the free-space rule and the panel search are covered by JVM tests.
 */
class FrameAnalysis(
    val columns: Int, val rows: Int,
    val originX: Float, val originY: Float,
    val cellWidth: Float, val cellHeight: Float,
    private val colors: IntArray
) {
    /** A flat rectangle of interface: a dialog, a bar or a button plate, and the colour it has. */
    class Panel(val box: Box, val color: Int)

    val empty get() = columns <= 0 || rows <= 0 || colors.size < columns * rows

    /** Cells whose neighbours differ sharply hold artwork, glyphs or an edge, not flat interface. */
    private val detailed: BooleanArray by lazy(LazyThreadSafetyMode.NONE) {
        if (empty) return@lazy BooleanArray(0)
        BooleanArray(columns * rows) { i ->
            val column = i % columns
            val row = i / columns
            var edge = 0
            if (column + 1 < columns) edge = max(edge, difference(colors[i], colors[i + 1]))
            if (row + 1 < rows) edge = max(edge, difference(colors[i], colors[i + columns]))
            edge > DETAIL
        }
    }

    /** The same grid described in screen pixels instead of frame pixels. */
    fun onScreen(transform: FrameMapping.Transform): FrameAnalysis =
        if (empty || transform.identity) this
        else FrameAnalysis(columns, rows, transform.x(originX), transform.y(originY),
            transform.length(cellWidth), transform.length(cellHeight), colors)

    /** Share of [box] that carries no screen detail, from 0 (busy artwork) to 1 (flat surface). */
    fun quiet(box: Box): Float {
        if (empty) return 1f
        var total = 0
        var calm = 0
        for (row in row(box.top)..row(box.bottom)) for (column in column(box.left)..column(box.right)) {
            total++
            if (!detailed[row * columns + column]) calm++
        }
        return if (total == 0) 1f else calm.toFloat() / total
    }

    /**
     * The flat interface plate the text of [box] sits on, grown outwards from the text until the
     * surface stops being itself. The reference colour follows the surface as it grows, so a panel
     * shaded by a gradient or seen at an angle still counts as one plate, while a hard edge —
     * the border of a dialog, the outline of a button — ends the search.
     *
     * Null when the text lies on artwork: there is no plate to keep the card inside of.
     */
    fun panel(box: Box): Panel? {
        if (empty) return null
        var left = column(box.left)
        var right = column(box.right)
        var top = row(box.top)
        var bottom = row(box.bottom)
        var reference = surroundings(left, right, top, bottom) ?: return null
        val widthLimit = ((right - left + 1) * GROWTH).toInt().coerceAtLeast(4)
        val heightLimit = ((bottom - top + 1) * GROWTH).toInt().coerceAtLeast(4)
        var growing = true
        while (growing) {
            growing = false
            if (left > 0 && right - left + 1 < widthLimit) verticalRun(left - 1, top, bottom, reference)?.let {
                reference = blend(reference, it); left--; growing = true
            }
            if (right < columns - 1 && right - left + 1 < widthLimit) verticalRun(right + 1, top, bottom, reference)?.let {
                reference = blend(reference, it); right++; growing = true
            }
            if (top > 0 && bottom - top + 1 < heightLimit) horizontalRun(top - 1, left, right, reference)?.let {
                reference = blend(reference, it); top--; growing = true
            }
            if (bottom < rows - 1 && bottom - top + 1 < heightLimit) horizontalRun(bottom + 1, left, right, reference)?.let {
                reference = blend(reference, it); bottom++; growing = true
            }
        }
        val panel = Box(originX + left * cellWidth, originY + top * cellHeight,
            originX + (right + 1) * cellWidth, originY + (bottom + 1) * cellHeight)
        // A plate no bigger than the text itself is not a plate, only the text's own background.
        return if (panel.area >= box.area * MIN_PANEL) Panel(panel, reference) else null
    }

    /** Flat colour immediately around the text, which is the colour of whatever it is written on. */
    private fun surroundings(left: Int, right: Int, top: Int, bottom: Int): Int? {
        var red = 0L; var green = 0L; var blue = 0L; var count = 0
        for (row in (top - 1)..(bottom + 1)) for (column in (left - 1)..(right + 1)) {
            if (row < 0 || column < 0 || row >= rows || column >= columns) continue
            val inside = row in top..bottom && column in left..right
            if (inside || detailed[row * columns + column]) continue
            val color = colors[row * columns + column]
            red += (color shr 16) and 255; green += (color shr 8) and 255; blue += color and 255; count++
        }
        if (count < MIN_SURROUNDING) return null
        return pack((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun verticalRun(column: Int, top: Int, bottom: Int, reference: Int): Int? {
        val cells = IntArray(bottom - top + 1) { (top + it) * columns + column }
        return average(cells, reference)
    }

    private fun horizontalRun(row: Int, left: Int, right: Int, reference: Int): Int? {
        val cells = IntArray(right - left + 1) { row * columns + left + it }
        return average(cells, reference)
    }

    /**
     * Average colour of a row or column of cells, or null when too few of them match [reference].
     * Colour alone decides here. The cells next to the glyphs are edges by definition, yet they
     * are still the surface the glyphs are written on, and artwork fails the colour test anyway.
     */
    private fun average(cells: IntArray, reference: Int): Int? {
        var red = 0L; var green = 0L; var blue = 0L; var matching = 0
        for (index in cells) {
            val color = colors[index]
            if (difference(color, reference) > TOLERANCE) continue
            red += (color shr 16) and 255; green += (color shr 8) and 255; blue += color and 255; matching++
        }
        if (cells.isEmpty() || matching.toFloat() / cells.size < AGREEMENT) return null
        return pack((red / matching).toInt(), (green / matching).toInt(), (blue / matching).toInt())
    }

    private fun column(x: Float) = floor((x - originX) / max(cellWidth, .001f)).toInt().coerceIn(0, columns - 1)
    private fun row(y: Float) = floor((y - originY) / max(cellHeight, .001f)).toInt().coerceIn(0, rows - 1)

    private fun blend(kept: Int, added: Int) = pack(
        ((kept shr 16 and 255) * 3 + (added shr 16 and 255)) / 4,
        ((kept shr 8 and 255) * 3 + (added shr 8 and 255)) / 4,
        ((kept and 255) * 3 + (added and 255)) / 4)

    private fun difference(a: Int, b: Int) = max(
        abs((a shr 16 and 255) - (b shr 16 and 255)),
        max(abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255))))

    private fun pack(red: Int, green: Int, blue: Int) =
        (255 shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    companion object {
        val none = FrameAnalysis(0, 0, 0f, 0f, 1f, 1f, IntArray(0))
        /** Neighbouring cells of one flat surface stay within this much of each other. */
        private const val DETAIL = 26
        /** How far a colour may drift from the surface already accepted and still belong to it. */
        private const val TOLERANCE = 30
        private const val AGREEMENT = .75f
        private const val MIN_SURROUNDING = 3
        private const val GROWTH = 7f
        private const val MIN_PANEL = 1.6f
    }
}
