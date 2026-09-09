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
    fun toScreen(box: Box, frameWidth: Int, frameHeight: Int, screenWidth: Int, screenHeight: Int): Box {
        if (frameWidth <= 0 || frameHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return box
        if (frameWidth == screenWidth && frameHeight == screenHeight) return box
        val scale = min(frameWidth.toFloat() / screenWidth, frameHeight.toFloat() / screenHeight)
        if (scale <= 0f) return box
        val offsetX = (frameWidth - screenWidth * scale) / 2f
        val offsetY = (frameHeight - screenHeight * scale) / 2f
        return Box(
            (box.left - offsetX) / scale, (box.top - offsetY) / scale,
            (box.right - offsetX) / scale, (box.bottom - offsetY) / scale
        )
    }
}
