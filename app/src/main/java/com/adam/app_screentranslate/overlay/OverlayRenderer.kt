package com.adam.app_screentranslate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.adam.app_screentranslate.model.*
import kotlin.math.hypot
import kotlin.math.roundToInt

class OverlayRenderer(context: Context) : View(context) {
    private data class Label(val id: Long, val box: RectF, val layout: StaticLayout, val dark: Boolean)

    private var labels = emptyList<Label>()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val density = resources.displayMetrics.density
    /** Text height by text, width and size: streaming results re-render the same labels many times. */
    private val heights = HashMap<String, Float>()
    private val lineWidths = HashMap<String, Float>()
    /**
     * Manual corrections, in screen pixels, keyed by block. Partial results re-render the same
     * blocks many times over, and a card the user has moved must not jump back under their finger.
     */
    private val offsets = HashMap<Long, PointF>()
    private var opacity = .9f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragged: Long? = null
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    /** In edit mode the window takes touches, so the cards can be dragged and the game cannot. */
    var editing = false
        set(value) {
            field = value
            dragged = null
            invalidate()
        }

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
        val measure = object : LabelLayout.Measure {
            override fun height(id: Long, width: Float, textSize: Float): Float {
                val text = texts[id].orEmpty()
                return heights.getOrPut(key(text, width, textSize)) {
                    build(text, paints.getValue(id), width, textSize, centred[id] == true).height.toFloat()
                }
            }
            override fun lineWidth(id: Long, textSize: Float): Float {
                val text = texts[id].orEmpty()
                return lineWidths.getOrPut(key(text, 0f, textSize)) {
                    paints.getValue(id).let { it.textSize = textSize; it.measureText(text) }
                }
            }
        }
        val placements = LabelLayout.place(
            visible.map { LabelLayout.Request(it.id, it.boundingBox, it.lines.size) },
            screenWidth.toFloat(), screenHeight.toFloat(), style, measure
        )
        if (heights.size > 2048) heights.clear()
        if (lineWidths.size > 2048) lineWidths.clear()
        offsets.keys.retainAll(placements.map { it.id }.toSet())
        labels = placements.map { placement ->
            Label(
                placement.id,
                RectF(placement.box.left, placement.box.top, placement.box.right, placement.box.bottom),
                build(texts[placement.id].orEmpty(), paints.getValue(placement.id),
                    placement.textWidth, placement.textSize, centred[placement.id] == true),
                dark[placement.id] == true
            )
            // Smaller cards draw last: an unavoidable overlap must not swallow a short label.
        }.sortedByDescending { it.box.width() * it.box.height() }
        invalidate()
    }

    /** A new capture invalidates every correction the user made for the previous screen. */
    fun reset() {
        offsets.clear()
        dragged = null
        labels = emptyList()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragged = labelAt(event.rawX, event.rawY)
                lastX = event.rawX; lastY = event.rawY; moved = false
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val id = dragged ?: return true
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                if (!moved && hypot(dx, dy) < slop) return true
                moved = true
                lastX = event.rawX; lastY = event.rawY
                val offset = offsets.getOrPut(id) { PointF() }
                offset.x += dx; offset.y += dy
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragged = null
                invalidate()
            }
        }
        // Every touch is consumed while editing: a half swallowed gesture would reach the app below.
        return true
    }

    /** Topmost card under the point: the draw order puts the smallest one last. */
    private fun labelAt(x: Float, y: Float): Long? = labels.lastOrNull {
        val offset = offsets[it.id]
        val dx = offset?.x ?: 0f
        val dy = offset?.y ?: 0f
        x >= it.box.left + dx && x <= it.box.right + dx && y >= it.box.top + dy && y <= it.box.bottom + dy
    }?.id

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
        val box = RectF()
        for (label in labels) {
            val offset = offsets[label.id]
            box.set(label.box)
            if (offset != null) box.offset(offset.x, offset.y)
            background.color = if (label.dark) Color.rgb(15, 23, 36) else Color.rgb(246, 249, 251)
            background.alpha = (opacity * 255).roundToInt().coerceIn(0, 255)
            canvas.drawRoundRect(box, radius, radius, background)
            if (editing) {
                border.color = if (label.id == dragged) Color.rgb(92, 237, 196) else Color.rgb(140, 200, 230)
                border.alpha = 255
                border.strokeWidth = if (label.id == dragged) 2.5f * density else 1.5f * density
            } else {
                border.color = if (label.dark) Color.WHITE else Color.rgb(18, 27, 41)
                border.alpha = (opacity * 46).roundToInt().coerceIn(0, 255)
                border.strokeWidth = density
            }
            canvas.drawRoundRect(box, radius, radius, border)
            canvas.save()
            canvas.clipRect(box)
            canvas.translate(
                box.left + padding,
                box.top + ((box.height() - label.layout.height) / 2f).coerceAtLeast(padding)
            )
            label.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restoreToCount(saved)
    }
}
