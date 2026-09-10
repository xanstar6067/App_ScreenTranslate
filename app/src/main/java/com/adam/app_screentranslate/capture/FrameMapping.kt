package com.adam.app_screentranslate.capture

import com.adam.app_screentranslate.model.Box
import kotlin.math.min

/**
 * Recognized coordinates are pixels of the captured frame, overlay coordinates are pixels of the
 * screen. The two match while the virtual display has the same shape as the display it mirrors.
 * A rotation that reaches only one of them leaves the mirror letterboxed: the screen is scaled to
 * fit the frame and centred in it. Mapping every box back removes that offset instead of drawing
 * translations far away from their text.
 */
object FrameMapping {
    /** Uniform scale and centring of the frame over the screen, shared by boxes and by grids. */
    data class Transform(val scale: Float, val offsetX: Float, val offsetY: Float) {
        val identity get() = scale == 1f && offsetX == 0f && offsetY == 0f
        fun x(value: Float) = (value - offsetX) / scale
        fun y(value: Float) = (value - offsetY) / scale
        fun length(value: Float) = value / scale
        fun box(box: Box) = Box(x(box.left), y(box.top), x(box.right), y(box.bottom))
    }

    private val identity = Transform(1f, 0f, 0f)

    fun transform(frameWidth: Int, frameHeight: Int, screenWidth: Int, screenHeight: Int): Transform {
        if (frameWidth <= 0 || frameHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return identity
        if (frameWidth == screenWidth && frameHeight == screenHeight) return identity
        val scale = min(frameWidth.toFloat() / screenWidth, frameHeight.toFloat() / screenHeight)
        if (scale <= 0f) return identity
        return Transform(scale, (frameWidth - screenWidth * scale) / 2f, (frameHeight - screenHeight * scale) / 2f)
    }

    fun toScreen(box: Box, frameWidth: Int, frameHeight: Int, screenWidth: Int, screenHeight: Int): Box {
        val transform = transform(frameWidth, frameHeight, screenWidth, screenHeight)
        return if (transform.identity) box else transform.box(box)
    }
}
