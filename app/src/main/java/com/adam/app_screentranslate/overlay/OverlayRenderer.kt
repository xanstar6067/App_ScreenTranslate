package com.adam.app_screentranslate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.adam.app_screentranslate.model.*
import kotlin.math.roundToInt

class OverlayRenderer(context: Context) : View(context) {
    private data class Label(val box: RectF, val layout: StaticLayout, val dark: Boolean)

    private var labels = emptyList<Label>()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val density = resources.displayMetrics.density
    /** Text height by text, width and size: streaming results re-render the same labels many times. */
    private val heights = HashMap<String, Float>()
    private var opacity = .9f

    fun render(blocks: List<ScreenTextBlock>, settings: AppSettings, screenWidth: Int, screenHeight: Int) {
        opacity = settings.opacity
        val scale = settings.textScale
        val style = LabelLayout.Style(
            padding = 3f * density,
            minTextSize = 11f * density * scale,
            maxTextSize = 30f * density * scale,
            hardMinTextSize = 7f * density,
            minCardWidth = 64f * density
        )
        val visible = blocks.filter { !it.translatedText.isNullOrBlank() }.distinctBy { it.id }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val texts = HashMap<Long, String>()
        val paints = HashMap<Long, TextPaint>()
        val centred = HashMap<Long, Boolean>()
        val dark = HashMap<Long, Boolean>()
        for (block in visible) {
            val darkCard = when (settings.background) {
                BackgroundStyle.AUTO -> block.backgroundLuminance >= .5f
                BackgroundStyle.DARK -> true
                BackgroundStyle.LIGHT -> false
            }
            texts[block.id] = block.translatedText.orEmpty()
            dark[block.id] = darkCard
            // A single recognized line is usually a caption or a button: centring keeps it on its control.
            centred[block.id] = block.lines.size <= 1
            paints[block.id] = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (darkCard) Color.WHITE else Color.rgb(18, 27, 41)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                // The card background is semi transparent by design; the outline keeps glyphs readable.
                setShadowLayer(1.6f * density, 0f, 0f, if (darkCard) Color.BLACK else Color.WHITE)
            }
        }
        val measure = LabelLayout.Measure { id, width, size ->
            val text = texts[id].orEmpty()
            heights.getOrPut(key(text, width, size)) {
                build(text, paints.getValue(id), width, size, centred[id] == true).height.toFloat()
            }
        }
        val placements = LabelLayout.place(
            visible.map { LabelLayout.Request(it.id, it.boundingBox, it.lines.size) },
            screenWidth.toFloat(), screenHeight.toFloat(), style, measure
        )
        if (heights.size > 2048) heights.clear()
        labels = placements.map { placement ->
            Label(
                RectF(placement.box.left, placement.box.top, placement.box.right, placement.box.bottom),
                build(texts[placement.id].orEmpty(), paints.getValue(placement.id),
                    placement.textWidth, placement.textSize, centred[placement.id] == true),
                dark[placement.id] == true
            )
            // Smaller cards draw last: an unavoidable overlap must not swallow a short label.
        }.sortedByDescending { it.box.width() * it.box.height() }
        invalidate()
    }

    private fun key(text: String, width: Float, size: Float) =
        "${text.length}:${text.hashCode()}:${width.roundToInt()}:${(size * 4).roundToInt()}"

    // Constant 1 is also Layout.BREAK_STRATEGY_HIGH_QUALITY on API 26-28.
    @SuppressLint("InlinedApi")
    private fun build(text: String, paint: TextPaint, width: Float, size: Float, centred: Boolean): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.roundToInt().coerceAtLeast(1))
            .setAlignment(if (centred) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // OCR coordinates refer to the physical display; window origin can be inset by the OS.
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        val saved = canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        val padding = 3f * density
        val radius = 5f * density
        for (label in labels) {
            background.color = if (label.dark) Color.rgb(15, 23, 36) else Color.rgb(246, 249, 251)
            background.alpha = (opacity * 255).roundToInt().coerceIn(0, 255)
            canvas.drawRoundRect(label.box, radius, radius, background)
            border.color = if (label.dark) Color.WHITE else Color.rgb(18, 27, 41)
            border.alpha = (opacity * 46).roundToInt().coerceIn(0, 255)
            border.strokeWidth = density
            canvas.drawRoundRect(label.box, radius, radius, border)
            canvas.save()
            canvas.clipRect(label.box)
            canvas.translate(
                label.box.left + padding,
                label.box.top + ((label.box.height() - label.layout.height) / 2f).coerceAtLeast(padding)
            )
            label.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restoreToCount(saved)
    }
}
