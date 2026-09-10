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
import com.adam.app_screentranslate.capture.FrameAnalysis
import com.adam.app_screentranslate.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

class OverlayRenderer(context: Context) : View(context) {
    private data class Label(
        val id: Long, val box: RectF, val layout: StaticLayout,
        /** Card background: the colour of the interface plate when there is one. */
        val fill: Int, val dark: Boolean, val angle: Float,
        /** A card on a known plate first paints the plate back over the original glyphs. */
        val erase: Boolean
    )

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

    /** What the captured screen looks like. Held only while its translations are on screen. */
    var composition: FrameAnalysis = FrameAnalysis.none

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
        val fills = HashMap<Long, Int>()
        val plates = HashMap<Long, Box>()
        val erased = HashSet<Long>()
        for (block in visible) {
            // The plate the text is written on decides both how far the card may go and its colour.
            val panel = composition.panel(block.boundingBox)
            val darkCard = when (settings.background) {
                BackgroundStyle.AUTO -> panel?.let { luminance(it.color) < .5f } ?: (block.backgroundLuminance >= .5f)
                BackgroundStyle.DARK -> true
                BackgroundStyle.LIGHT -> false
            }
            texts[block.id] = block.translatedText.orEmpty()
            dark[block.id] = darkCard
            if (panel != null) plates[block.id] = panel.box
            // Matching the plate makes the card read as part of the interface instead of a sticker.
            val plate = if (settings.background == BackgroundStyle.AUTO) panel?.color else null
            fills[block.id] = plate ?: if (darkCard) Color.rgb(15, 23, 36) else Color.rgb(246, 249, 251)
            if (plate != null) erased += block.id
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
            visible.map { LabelLayout.Request(it.id, it.boundingBox, it.lines.size, it.angle, plates[it.id]) },
            screenWidth.toFloat(), screenHeight.toFloat(), style, measure, { composition.quiet(it) }
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
                fills.getValue(placement.id), dark[placement.id] == true, placement.angle,
                erased.contains(placement.id)
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
        composition = FrameAnalysis.none
    }

    private fun luminance(color: Int) = (.2126f * ((color shr 16) and 255) +
        .7152f * ((color shr 8) and 255) + .0722f * (color and 255)) / 255f

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
    private fun labelAt(x: Float, y: Float): Long? = labels.lastOrNull { label ->
        val offset = offsets[label.id]
        val dx = offset?.x ?: 0f
        val dy = offset?.y ?: 0f
        val centreX = label.box.centerX() + dx
        val centreY = label.box.centerY() + dy
        // A turned card is hit in its own frame: the touch is turned back around the same centre.
        val radians = -label.angle * PI.toFloat() / 180f
        val localX = centreX + (x - centreX) * cos(radians) - (y - centreY) * sin(radians)
        val localY = centreY + (x - centreX) * sin(radians) + (y - centreY) * cos(radians)
        localX >= label.box.left + dx && localX <= label.box.right + dx &&
            localY >= label.box.top + dy && localY <= label.box.bottom + dy
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
        // The original glyphs go first, painted over with the plate they are written on. Every card
        // is erased before any card is drawn, so one card never wipes out the neighbour beside it.
        for (label in labels) {
            if (!label.erase) continue
            place(box, label)
            background.color = label.fill
            background.alpha = 255
            turned(canvas, box, label.angle) { canvas.drawRoundRect(box, radius, radius, background) }
        }
        for (label in labels) {
            place(box, label)
            turned(canvas, box, label.angle) {
                background.color = label.fill
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
        }
        canvas.restoreToCount(saved)
    }

    private fun place(box: RectF, label: Label) {
        box.set(label.box)
        offsets[label.id]?.let { box.offset(it.x, it.y) }
    }

    /**
     * Draws with the canvas turned by the tilt of the recognized text, around the centre of the
     * card — the one point the layout guaranteed to sit on the original glyphs.
     */
    private inline fun turned(canvas: Canvas, box: RectF, angle: Float, draw: () -> Unit) {
        if (angle == 0f) { draw(); return }
        val saved = canvas.save()
        canvas.rotate(angle, box.centerX(), box.centerY())
        draw()
        canvas.restoreToCount(saved)
    }
}
