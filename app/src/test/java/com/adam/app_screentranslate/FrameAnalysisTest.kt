package com.adam.app_screentranslate

import com.adam.app_screentranslate.capture.FrameAnalysis
import com.adam.app_screentranslate.capture.FrameMapping
import com.adam.app_screentranslate.model.Box
import org.junit.Assert.*
import org.junit.Test

class FrameAnalysisTest {
    private val columns = 40
    private val rows = 30
    private val cell = 10f
    private val plate = 0xFF202838.toInt()

    /**
     * A synthetic screen: noisy artwork everywhere, one flat interface plate over it and a line of
     * text drawn on that plate. Neighbouring cells of artwork differ by everything they can.
     */
    private fun screen(withPlate: Boolean): FrameAnalysis {
        val colors = IntArray(columns * rows) { i ->
            val column = i % columns
            val row = i / columns
            val onPlate = withPlate && column in 5..25 && row in 10..18
            val isText = onPlate && row in 13..14 && column in 10..20
            when {
                isText -> if (column % 2 == 0) 0xFFF0F0F0.toInt() else plate
                onPlate -> plate
                (column + row) % 2 == 0 -> 0xFFFFFFFF.toInt()
                else -> 0xFF101010.toInt()
            }
        }
        return FrameAnalysis(columns, rows, 0f, 0f, cell, cell, colors)
    }

    private val text = Box(100f, 130f, 210f, 149f)

    @Test fun findsThePlateTheTextIsWrittenOn() {
        val panel = screen(withPlate = true).panel(text)
        assertNotNull("A flat plate under the text must be found", panel)
        requireNotNull(panel)
        // The rim of the plate is an edge, so the search stops one cell short of it on two sides.
        assertEquals(50f, panel.box.left, cell)
        assertEquals(100f, panel.box.top, cell)
        assertEquals(260f, panel.box.right, 2 * cell)
        assertEquals(190f, panel.box.bottom, 2 * cell)
        assertTrue("The plate must not swallow the artwork around it", panel.box.right < 300f)
        assertEquals(plate, panel.color)
    }

    @Test fun textOnArtworkHasNoPlate() {
        assertNull(screen(withPlate = false).panel(text))
    }

    @Test fun tellsFlatInterfaceFromArtwork() {
        val analysis = screen(withPlate = true)
        assertEquals(1f, analysis.quiet(Box(60f, 105f, 95f, 125f)), .01f)
        assertEquals(0f, analysis.quiet(Box(300f, 200f, 380f, 280f)), .01f)
    }

    @Test fun anEmptyAnalysisNeverConstrainsAnything() {
        assertNull(FrameAnalysis.none.panel(text))
        assertEquals(1f, FrameAnalysis.none.quiet(text), 0f)
    }

    @Test fun followsTheFrameOntoTheScreen() {
        // A letterboxed capture: the grid has to move and shrink exactly like the boxes do.
        val transform = FrameMapping.transform(1080, 2400, 2400, 1080)
        val moved = screen(withPlate = true).onScreen(transform)
        val movedText = transform.box(text)
        val panel = moved.panel(movedText)
        assertNotNull(panel)
        requireNotNull(panel)
        assertEquals(transform.x(50f), panel.box.left, moved.cellWidth)
        assertEquals(plate, panel.color)
    }
}
