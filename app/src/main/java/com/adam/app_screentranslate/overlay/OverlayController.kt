package com.adam.app_screentranslate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.input.InputManager
import android.os.Build
import android.view.*
import com.adam.app_screentranslate.capture.FrameAnalysis
import com.adam.app_screentranslate.capture.ScreenCaptureManager
import com.adam.app_screentranslate.data.SettingsManager
import com.adam.app_screentranslate.model.*
import kotlin.math.*

class OverlayController(
    private val context: Context, private val settings: SettingsManager,
    private val onTap: () -> Unit, private val onLongPress: () -> Unit
) : AutoCloseable {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val button = ControlView(context)
    private val translation = OverlayRenderer(context)
    private var controlAttached = false
    private var translationAttached = false
    private var size = ScreenCaptureManager.screenSize(context)
    private var config = settings.settings.value
    private val controlParams = params(56, 56, false)
    private val translationParams = params(-1, -1, true)
    private var editing = false
    init {
        applyTouchMode()
        installGestures()
    }
    /**
     * Two modes for one window. Normally it passes every touch to the app below, and Android 12+
     * counts even such a window as occluding, so its opacity is capped. In edit mode it takes the
     * touches instead, the cards can be dragged, and the cap no longer applies.
     */
    private fun applyTouchMode() {
        translationParams.flags = if (editing)
            translationParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else translationParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        translationParams.alpha = if (!editing && Build.VERSION.SDK_INT >= 31)
            context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch.coerceAtMost(.8f) else 1f
    }
    /** Edit mode only makes sense while translations are on screen; it never outlives them. */
    fun setEditing(value: Boolean) {
        val target = value && translationAttached
        if (editing == target) return
        editing = target
        translation.editing = editing
        applyTouchMode()
        if (translationAttached) runCatching { wm.updateViewLayout(translation, translationParams) }
        // The control must keep receiving touches above a window that now takes them.
        if (controlAttached) {
            runCatching { wm.removeViewImmediate(button) }
            controlAttached = false
            ensureAttached()
        }
        button.editing = editing
        button.invalidate()
    }
    val isEditing get() = editing
    // Capture coordinates are physical pixels from the left edge, regardless of locale.
    @SuppressLint("RtlHardcoded")
    private fun params(w: Int, h: Int, passThrough: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }
    fun show() {
        configure(config)
        ensureAttached()
    }
    /** Keyguard, display changes and OEM window cleanups can drop an overlay window silently. */
    fun ensureAttached() {
        if (controlAttached) {
            // Updating a window the system already removed fails; only then is a new one needed.
            if (runCatching { wm.updateViewLayout(button, controlParams) }.isSuccess) return
            controlAttached = false
            runCatching { wm.removeViewImmediate(button) }
        }
        runCatching { wm.addView(button, controlParams) }.onSuccess { controlAttached = true }
    }
    fun configure(value: AppSettings) {
        config = value; size = ScreenCaptureManager.screenSize(context)
        // Keep the input surface stable; transparency is purely a drawing property.
        controlParams.alpha = 1f
        button.visualOpacity = value.buttonOpacity.coerceIn(.2f, 1f)
        button.invalidate()
        val diameter = (value.buttonSize.dp*density).roundToInt()
        controlParams.width = diameter; controlParams.height = diameter
        val position = settings.position(size.first > size.second)
        controlParams.x = (position.first*(size.first-diameter).coerceAtLeast(0)).roundToInt()
        controlParams.y = (position.second*(size.second-diameter).coerceAtLeast(0)).roundToInt()
        clamp()
        if (controlAttached) runCatching { wm.updateViewLayout(button, controlParams) }
    }
    fun onRotation() { clear(); configure(config) }
    fun state(state: ControlState) { button.state = state; button.visibility = View.VISIBLE; button.invalidate() }
    fun hideControl() { button.visibility = View.INVISIBLE }
    /** What the frame behind the translations looks like; released together with them. */
    fun setComposition(value: FrameAnalysis) { translation.composition = value }
    fun showTranslations(blocks: List<ScreenTextBlock>) {
        translation.render(blocks, config, size.first, size.second)
        if (!translationAttached) {
            wm.addView(translation, translationParams); translationAttached = true
            // Keep the control above the translation window, including after each clear/show.
            if (controlAttached) {
                runCatching { wm.removeViewImmediate(button) }
                controlAttached = false
                ensureAttached()
            }
        }
    }
    fun clear() {
        setEditing(false)
        translation.reset()
        if (translationAttached) { runCatching { wm.removeViewImmediate(translation) }; translationAttached = false }
    }
    private fun clamp() {
        controlParams.x = controlParams.x.coerceIn(0, (size.first-controlParams.width).coerceAtLeast(0))
        controlParams.y = controlParams.y.coerceIn(0, (size.second-controlParams.height).coerceAtLeast(0))
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun installGestures() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longPressed = false
        val longPress = Runnable { longPressed = true; onLongPress() }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = controlParams.x; startY = controlParams.y
                    dragging = false; longPressed = false
                    button.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX-downX; val dy = event.rawY-downY
                    if (hypot(dx, dy) > slop) { dragging = true; button.removeCallbacks(longPress) }
                    if (dragging) {
                        controlParams.x = startX+dx.roundToInt(); controlParams.y = startY+dy.roundToInt()
                        clamp(); wm.updateViewLayout(button, controlParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    button.removeCallbacks(longPress)
                    if (dragging) settings.savePosition(size.first > size.second,
                        controlParams.x.toFloat()/(size.first-controlParams.width).coerceAtLeast(1),
                        controlParams.y.toFloat()/(size.second-controlParams.height).coerceAtLeast(1))
                    else if (!longPressed) { button.performClick(); onTap() }
                }
                MotionEvent.ACTION_CANCEL -> button.removeCallbacks(longPress)
            }
            true
        }
    }
    override fun close() {
        clear()
        if (controlAttached) { runCatching { wm.removeViewImmediate(button) }; controlAttached = false }
    }
    private class ControlView(context: Context) : View(context) {
        var visualOpacity = 1f
        var editing = false
        var state = ControlState.READY
            set(value) {
                field = value
                contentDescription = when(value) {
                    ControlState.READY -> "Перевести экран. Удерживайте для настроек"
                    ControlState.PROCESSING -> "Переводим экран"
                    ControlState.TRANSLATED -> "Очистить перевод"
                    ControlState.PAUSED -> "Захват экрана остановлен. Нажмите, чтобы разрешить снова"
                }
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (visualOpacity * 255).roundToInt())
            val r = width/2f
            paint.style = Paint.Style.FILL
            paint.color = when {
                editing -> Color.rgb(140, 200, 230)
                state == ControlState.PAUSED -> Color.rgb(255, 202, 124)
                else -> Color.rgb(92, 237, 196)
            }
            canvas.drawCircle(r, height/2f, r-2, paint)
            paint.color = Color.rgb(10, 30, 39)
            if (editing) {
                // A tick, because the only thing left to do in edit mode is to finish it.
                paint.style = Paint.Style.STROKE; paint.strokeWidth = width*.08f
                paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                canvas.drawLines(floatArrayOf(
                    width*.3f, height*.52f, width*.44f, height*.66f,
                    width*.44f, height*.66f, width*.71f, height*.36f), paint)
                canvas.restoreToCount(layer)
                return
            }
            when(state) {
                ControlState.READY -> {
                    paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    paint.textSize = width*.32f; paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("A⇄", r, height*.61f, paint)
                }
                ControlState.TRANSLATED -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = width*.06f; paint.strokeCap = Paint.Cap.ROUND
                    canvas.drawLine(width*.35f, height*.35f, width*.65f, height*.65f, paint)
                    canvas.drawLine(width*.65f, height*.35f, width*.35f, height*.65f, paint)
                }
                ControlState.PAUSED -> {
                    paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    paint.textSize = width * .46f; paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("!", r, height * .66f, paint)
                }
                ControlState.PROCESSING -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = width*.055f; paint.strokeCap = Paint.Cap.ROUND
                    val angle = (android.os.SystemClock.uptimeMillis()%1200)/1200f*360
                    canvas.drawArc(width*.3f, height*.3f, width*.7f, height*.7f, angle, 260f, false, paint)
                    postInvalidateOnAnimation()
                }
            }
            canvas.restoreToCount(layer)
        }
    }
}
