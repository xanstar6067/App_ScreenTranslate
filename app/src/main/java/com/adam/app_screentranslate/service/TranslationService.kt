package com.adam.app_screentranslate.service

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.adam.app_screentranslate.*
import com.adam.app_screentranslate.capture.ScreenCaptureManager
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.ocr.OCRManager
import com.adam.app_screentranslate.overlay.OverlayController
import com.adam.app_screentranslate.translation.TranslationManager
import kotlinx.coroutines.*

class TranslationService : Service() {
    private val app get() = application as TranslatorApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capture: ScreenCaptureManager? = null
    private var overlay: OverlayController? = null
    private var processing: Job? = null
    private var state = ControlState.READY
    private var generation = 0
    private var failed = false
    private val translations by lazy { TranslationManager(app.cache) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (capture != null) return START_NOT_STICKY
        try {
            app.session.value = SessionState(SessionPhase.STARTING)
            startNotification()
            check(Settings.canDrawOverlays(this))
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("projection", Intent::class.java)
                else intent?.getParcelableExtra<Intent>("projection")
            requireNotNull(data)
            capture = ScreenCaptureManager(this, { stopSelf() }, { invalidateFrame() }).also {
                it.start(intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED, data)
            }
            overlay = OverlayController(this, app.settings, { tap() }, { openSettings() }).also { it.show(); it.state(state) }
            app.session.value = SessionState(SessionPhase.ACTIVE)
            scope.launch {
                var last = app.settings.settings.value
                app.settings.settings.collect { settings ->
                    if (settings != last) {
                        last = settings
                        invalidateFrame()
                        overlay?.configure(settings)
                    }
                }
            }
        } catch (_: Exception) {
            failed = true
            app.session.value = SessionState(SessionPhase.ERROR, "Не удалось начать захват. Проверьте разрешения и включите переводчик снова.")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun tap() {
        when (state) {
            ControlState.PROCESSING -> Unit
            ControlState.TRANSLATED -> { overlay?.clear(); setState(ControlState.READY) }
            ControlState.READY -> translateScreen()
        }
    }
    private fun setState(value: ControlState) {
        state = value
        overlay?.state(value)
        app.session.value = app.session.value.copy(control = value)
    }
    private fun translateScreen() {
        if (processing?.isActive == true) return
        val previous = processing
        val frameGeneration = ++generation
        val settings = app.settings.settings.value
        setState(ControlState.PROCESSING)
        overlay?.clear()
        overlay?.hideControl()
        processing = scope.launch {
            previous?.join()
            val visible = mutableListOf<ScreenTextBlock>()
            try {
                val bitmap = capture?.capture() ?: error("No projection")
                overlay?.state(ControlState.PROCESSING)
                val blocks = try {
                    withContext(Dispatchers.Default) {
                        if (ScreenCaptureManager.isBlank(bitmap)) throw CaptureUnavailable()
                        OCRManager().use { it.recognize(bitmap, settings.source, settings.merge) }
                    }
                } finally { bitmap.recycle() }
                ensureActive()
                if (blocks.isEmpty()) {
                    notice("Текст на экране не найден")
                } else {
                    val errors = translations.translate(blocks, settings) { block ->
                        withContext(Dispatchers.Main.immediate) {
                            if (frameGeneration == generation) {
                                visible += block
                                overlay?.showTranslations(visible.toList())
                            }
                        }
                    }
                    if (errors > 0) notice(if (visible.isEmpty()) "Перевод недоступен. Проверьте сеть или смените сервис."
                        else "Часть блоков не переведена: $errors")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: CaptureUnavailable) { notice("Не удалось получить изображение. Возможно, приложение запрещает захват содержимого.") }
            catch (_: Exception) { notice("Не удалось обработать экран. Попробуйте ещё раз.") }
            finally {
                if (frameGeneration == generation) setState(if (visible.isEmpty()) ControlState.READY else ControlState.TRANSLATED)
            }
        }
    }
    private fun invalidateFrame() {
        generation++
        processing?.cancel()
        overlay?.onRotation()
        setState(ControlState.READY)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val (w, h) = ScreenCaptureManager.screenSize(this)
        capture?.resize(w, h)
        overlay?.onRotation()
    }
    private fun notice(text: String) {
        app.session.value = app.session.value.copy(message = text)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun startNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Экранный переводчик", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TranslationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Экранный переводчик активен").setContentText("Нажмите, чтобы открыть Lenslate")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(R.drawable.ic_notification, "Выключить", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notification)
    }
    override fun onDestroy() {
        generation++
        scope.cancel()
        overlay?.close(); overlay = null
        capture?.close(); capture = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!failed) app.session.value = SessionState()
        super.onDestroy()
    }
    private class CaptureUnavailable : Exception()
    companion object {
        const val ACTION_STOP = "com.adam.app_screentranslate.STOP"
        const val CHANNEL = "translation_session"
    }
}
