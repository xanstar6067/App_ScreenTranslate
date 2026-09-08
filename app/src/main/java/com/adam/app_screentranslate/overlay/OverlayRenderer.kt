package com.adam.app_screentranslate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.adam.app_screentranslate.model.*

class OverlayRenderer(context: Context) : View(context) {
    private data class Label(val box: RectF, val layout: StaticLayout, val dark: Boolean)
    private var labels = emptyList<Label>()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var opacity = .9f
    fun render(blocks: List<ScreenTextBlock>, settings: AppSettings, screenWidth: Int, screenHeight: Int) {
        opacity = settings.opacity
        val padding = 4 * density
        val placed = mutableListOf<Label>()
        for (block in blocks.sortedBy { it.boundingBox.top }) {
            val text = block.translatedText ?: continue
            val source = block.boundingBox
            val w = (source.width + padding * 2).coerceAtLeast(72 * density).coerceAtMost(screenWidth.toFloat())
            val x = (source.left - padding).coerceIn(0f, (screenWidth - w).coerceAtLeast(0f))
            val dark = when (settings.background) {
                BackgroundStyle.AUTO -> block.backgroundLuminance >= .5f
                BackgroundStyle.DARK -> true
                BackgroundStyle.LIGHT -> false
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.WHITE else Color.rgb(18, 27, 41)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val minSize = 10 * density * settings.textScale
            var size = ((source.height / block.lines.size.coerceAtLeast(1)) * .85f * settings.textScale)
                .coerceIn(minSize, 26 * density * settings.textScale)
            // Constant 1 is also Layout.BREAK_STRATEGY_HIGH_QUALITY on API 23–28.
            @SuppressLint("InlinedApi")
            fun layout(): StaticLayout {
                paint.textSize = size
                return StaticLayout.Builder.obtain(text, 0, text.length, paint, (w-padding*2).toInt().coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build()
            }
            var textLayout = layout()
            while (textLayout.height > source.height && size > minSize) {
                size = (size - density).coerceAtLeast(minSize); textLayout = layout()
            }
            // A short translation must still cover the complete original paragraph.
            var h = maxOf(textLayout.height.toFloat(), source.height) + padding * 2
            // Prefer shrinking before moving expanded translations.
            while (h > screenHeight && size > 8 * density) {
                size -= density; textLayout = layout(); h = maxOf(textLayout.height.toFloat(), source.height) + padding*2
            }
            var y = (source.top - padding).coerceIn(0f, (screenHeight-h).coerceAtLeast(0f))
            var box = RectF(x, y, x+w, y+h)
            val overlaps = { candidate: RectF -> placed.any { RectF.intersects(it.box, candidate) } }
            if (overlaps(box)) {
                val candidates = placed.flatMap { listOf(it.box.bottom + 2*density, it.box.top - h - 2*density) }
                    .plus(0f).filter { it >= 0 && it + h <= screenHeight }
                    .sortedBy { kotlin.math.abs(it-y) }
                val free = candidates.map { RectF(x, it, x+w, it+h) }.firstOrNull { !overlaps(it) }
                // Dense screens: preserve every translation in one readable, scroll-free text panel.
                if (free == null || h > screenHeight) {
                    renderCombined(blocks, settings, screenWidth, screenHeight)
                    return
                }
                box = free
            }
            placed += Label(box, textLayout, dark)
        }
        labels = placed
        invalidate()
    }
    private fun renderCombined(blocks: List<ScreenTextBlock>, settings: AppSettings, w: Int, h: Int) {
        val text = blocks.sortedBy { it.boundingBox.top }.mapNotNull { it.translatedText }.joinToString("\n\n")
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14*density*settings.textScale }
        val pad = 8*density
        fun make() = StaticLayout.Builder.obtain(text, 0, text.length, paint, (w-pad*2).toInt().coerceAtLeast(1))
            .setIncludePad(false).build()
        var layout = make()
        while (layout.height > h-pad*2 && paint.textSize > 8*density) { paint.textSize -= density; layout = make() }
        labels = listOf(Label(RectF(pad/2, pad/2, w-pad/2, (layout.height+pad).coerceAtMost(h.toFloat())), layout, true))
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // OCR coordinates refer to the physical display; window origin can be inset by the OS.
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        val saved = canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        val padding = 4*density
        for (label in labels) {
            background.color = if (label.dark) Color.rgb(15, 23, 36) else Color.rgb(246, 249, 251)
            background.alpha = (opacity*255).toInt()
            canvas.drawRoundRect(label.box, 5*density, 5*density, background)
            canvas.save()
            canvas.clipRect(label.box)
            canvas.translate(label.box.left+padding, label.box.top+padding)
            label.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restoreToCount(saved)
    }
}
