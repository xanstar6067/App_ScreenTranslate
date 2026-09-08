package com.adam.app_screentranslate.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class ScreenCaptureManager(
    private val context: Context,
    private val onStopped: () -> Unit,
    private val onResized: () -> Unit
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("screen-frame").apply { start() }
    private val handler = Handler(worker.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Any()
    private var pending: CompletableDeferred<Bitmap>? = null
    var width = 0; private set
    var height = 0; private set
    private var closed = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { if (!closed) onStopped() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!closed) resize(width, height)
        }
    }
    fun start(resultCode: Int, data: Intent) {
        val size = screenSize(context)
        width = size.first; height = size.second
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projection = requireNotNull(manager.getMediaProjection(resultCode, data)).also { it.registerCallback(callback, main) }
        reader = newReader(width, height)
        display = projection!!.createVirtualDisplay("Lenslate capture", width, height,
            context.resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, main)
    }
    private fun newReader(w: Int, h: Int): ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
        setOnImageAvailableListener({ source ->
            synchronized(frameLock) {
                if (closed || source !== reader) return@synchronized
                val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
                image?.use { frame ->
                    val target = pending ?: return@use
                    if (!target.isActive) { pending = null; return@use }
                    try {
                        val plane = frame.planes[0]
                        val stride = plane.rowStride / plane.pixelStride
                        val padded = Bitmap.createBitmap(stride, frame.height, Bitmap.Config.ARGB_8888)
                        val bitmap = try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            Bitmap.createBitmap(padded, 0, 0, frame.width, frame.height)
                        } catch (e: Exception) {
                            padded.recycle()
                            throw e
                        } finally {
                            // createBitmap can return its input if there is no padding.
                            if (stride != frame.width && !padded.isRecycled) padded.recycle()
                        }
                        pending = null
                        if (!target.complete(bitmap)) bitmap.recycle()
                    } catch (e: Exception) {
                        pending = null; target.completeExceptionally(e)
                    }
                }
            }
        }, handler)
    }
    suspend fun capture(): Bitmap = withTimeout(5_000) {
        // Let WindowManager commit the hidden control, then reattach the same display surface.
        // Reattachment requests a fresh composition even when the game is completely static.
        delay(120)
        val target = CompletableDeferred<Bitmap>()
        synchronized(frameLock) {
            check(!closed && display != null) { "Capture session ended" }
            display!!.surface = null
            reader!!.acquireLatestImage()?.close()
            pending = target
            display!!.surface = reader!!.surface
        }
        try { target.await() }
        finally {
            val cancelled = !currentCoroutineContext().isActive
            synchronized(frameLock) {
                if (pending === target) pending = null
                if (cancelled && target.isCompleted && !target.isCancelled) {
                    // A frame delivered at the cancellation boundary still belongs to this request.
                    @OptIn(ExperimentalCoroutinesApi::class)
                    val abandoned = runCatching { target.getCompleted() }.getOrNull()
                    abandoned?.recycle()
                }
                target.cancel()
            }
        }
    }
    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || w == width && h == height || closed) return
        onResized()
        synchronized(frameLock) {
            pending?.cancel(); pending = null
            width = w; height = h
            display?.surface = null
            reader?.close()
            reader = newReader(w, h)
            // Android 14+: exactly one createVirtualDisplay per projection consent.
            display?.resize(w, h, context.resources.displayMetrics.densityDpi)
            display?.surface = reader!!.surface
        }
    }
    override fun close() {
        synchronized(frameLock) {
            if (closed) return
            closed = true
            pending?.cancel(); pending = null
            display?.release(); display = null
            reader?.close(); reader = null
            projection?.unregisterCallback(callback)
            projection?.stop(); projection = null
        }
        worker.quitSafely()
    }
    companion object {
        @Suppress("DEPRECATION")
        fun screenSize(context: Context): Pair<Int, Int> {
            val wm = context.getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.maximumWindowMetrics.bounds
                return b.width() to b.height()
            }
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            return metrics.widthPixels to metrics.heightPixels
        }
        fun isBlank(bitmap: Bitmap): Boolean {
            var max = 0
            for (y in 0 until bitmap.height step (bitmap.height / 24).coerceAtLeast(1))
                for (x in 0 until bitmap.width step (bitmap.width / 24).coerceAtLeast(1)) {
                    val c = bitmap.getPixel(x, y)
                    max = maxOf(max, (c shr 16) and 255, (c shr 8) and 255, c and 255)
                }
            return max < 5
        }
    }
}
