package com.adam.app_screentranslate.capture

import android.graphics.Bitmap
import kotlin.math.max

/**
 * Turns a captured frame into the coarse grid the overlay reasons about. The only thing that
 * survives this call is a few thousand averaged colours; the frame itself is released by the
 * caller together with the one OCR works on.
 */
object FrameAnalyzer {
    /** Cells along the longer side: fine enough to find a dialog edge, coarse enough to be free. */
    private const val CELLS = 110

    fun analyze(bitmap: Bitmap): FrameAnalysis {
        val longer = max(bitmap.width, bitmap.height)
        if (longer <= 0 || bitmap.isRecycled) return FrameAnalysis.none
        val cell = max(6, longer / CELLS)
        val columns = (bitmap.width + cell - 1) / cell
        val rows = (bitmap.height + cell - 1) / cell
        if (columns < 4 || rows < 4) return FrameAnalysis.none
        val colors = IntArray(columns * rows)
        // Scaling averages each cell in native code; reading the full frame into Java would not.
        val small = Bitmap.createScaledBitmap(bitmap, columns, rows, true)
        try {
            small.getPixels(colors, 0, columns, 0, 0, columns, rows)
        } catch (_: Exception) {
            return FrameAnalysis.none
        } finally {
            // createScaledBitmap hands back its input when no scaling was needed.
            if (small !== bitmap) small.recycle()
        }
        return FrameAnalysis(columns, rows, 0f, 0f,
            bitmap.width.toFloat() / columns, bitmap.height.toFloat() / rows, colors)
    }
}
