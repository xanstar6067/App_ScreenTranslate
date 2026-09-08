package com.adam.app_screentranslate

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adam.app_screentranslate.capture.ScreenCaptureManager
import com.adam.app_screentranslate.data.SettingsManager
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.overlay.OverlayController
import com.adam.app_screentranslate.overlay.OverlayRenderer
import com.adam.app_screentranslate.service.TranslationService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun dragWorksAtEveryOpacityWithAndWithoutTranslations() {
        assertTrue("Overlay permission must be granted on the test emulator", Settings.canDrawOverlays(context))
        context.stopService(Intent(context, TranslationService::class.java))
        instrumentation.waitForIdleSync()
        val settings = SettingsManager(context)
        val (w, h) = ScreenCaptureManager.screenSize(context)
        val landscape = w > h
        val previousPosition = settings.position(landscape)
        var controller: OverlayController? = null
        try {
            for (translated in listOf(false, true)) for (opacity in listOf(.2f, .5f, .7f, 1f)) {
                settings.savePosition(landscape, .5f, .5f)
                instrumentation.runOnMainSync {
                    controller = OverlayController(context, settings, {}, {}).also {
                        it.configure(settings.settings.value.copy(buttonOpacity=opacity))
                        it.show()
                        if (translated) it.showTranslations(listOf(ScreenTextBlock(1, "Original", Box(30f, 100f, w-30f, h-200f), translatedText="Translation")))
                    }
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(200)
                val down = SystemClock.uptimeMillis()
                fun send(action: Int, x: Float, y: Float) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                send(MotionEvent.ACTION_DOWN, w/2f, h/2f)
                for (step in 1..10) {
                    SystemClock.sleep(16)
                    send(MotionEvent.ACTION_MOVE, w/2f-step*12, h/2f+step*6)
                }
                send(MotionEvent.ACTION_UP, w/2f-120, h/2f+60)
                instrumentation.waitForIdleSync()
                val moved = settings.position(landscape)
                assertTrue("Drag failed at opacity=$opacity translated=$translated", moved.first < .47f && moved.second > .5f)
                instrumentation.runOnMainSync { controller?.close(); controller=null }
            }
        } finally {
            instrumentation.runOnMainSync { controller?.close() }
            settings.savePosition(landscape, previousPosition.first, previousPosition.second)
        }
    }

    @Test fun shortTranslationCoversOriginalHeight() {
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                val renderer = OverlayRenderer(context)
                renderer.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                renderer.layout(0, 0, 800, 1000)
                renderer.render(listOf(ScreenTextBlock(1, "Long source paragraph", Box(100f, 200f, 600f, 500f), translatedText="OK")), AppSettings(opacity=1f), 800, 1000)
                renderer.draw(Canvas(bitmap))
            }
            assertEquals("Original paragraph bottom must be covered", 255, bitmap.getPixel(350, 490) ushr 24)
            assertEquals("Unrelated area must stay transparent", 0, bitmap.getPixel(700, 650) ushr 24)
        } finally { bitmap.recycle() }
    }
}
