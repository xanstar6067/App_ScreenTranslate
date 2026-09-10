package com.adam.app_screentranslate.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adam.app_screentranslate.*
import com.adam.app_screentranslate.capture.FrameAnalysis
import com.adam.app_screentranslate.capture.FrameAnalyzer
import com.adam.app_screentranslate.capture.FrameMapping
import com.adam.app_screentranslate.capture.ScreenCaptureManager
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.ocr.OCRManager
import com.adam.app_screentranslate.overlay.OverlayController
import com.adam.app_screentranslate.translation.TranslationManager
import com.adam.app_screentranslate.translation.ai.AiTranslator
import kotlinx.coroutines.*

class TranslationService : Service() {
    private val app get() = application as TranslatorApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capture: ScreenCaptureManager? = null
    /**
     * The recognizers load their models on first use and keep them. Rebuilding them for every tap
     * paid that cost again each time; they hold nothing but the models between recognitions.
     * Built on the worker thread that first needs them, read by the main thread on shutdown.
     */
    @Volatile private var ocr: OCRManager? = null
    private var overlay: OverlayController? = null
    private var processing: Job? = null
    private var settingsWatcher: Job? = null
    private var state = ControlState.READY
    private var generation = 0
    private var failed = false
    private var stopping = false
    private var screenEventsRegistered = false
    private val translations by lazy { TranslationManager(app.cache) }
    private val aiTranslator by lazy { AiTranslator() }
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                // What was captured before the screen went off no longer describes what the user sees.
                generation++
                processing?.cancel()
                overlay?.clear()
                if (state != ControlState.PAUSED) setState(ControlState.READY)
            } else resync()
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        // A live session ignores repeated starts; a paused one is re-armed with the new consent.
        if (capture != null) return START_NOT_STICKY
        try {
            app.session.value = SessionState(SessionPhase.STARTING)
            startNotification()
            check(Settings.canDrawOverlays(this))
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("projection", Intent::class.java)
                else intent?.getParcelableExtra<Intent>("projection")
            requireNotNull(data)
            capture = ScreenCaptureManager(this, { projectionLost() }, { invalidateFrame() }).also {
                it.start(intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED, data)
            }
            if (overlay == null) overlay = OverlayController(this, app.settings, { tap() }, { longPress() }).also { it.show() }
            else overlay?.ensureAttached()
            registerScreenEvents()
            app.session.value = SessionState(SessionPhase.ACTIVE)
            state = ControlState.READY
            overlay?.state(state)
            if (settingsWatcher == null) settingsWatcher = scope.launch {
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
        // A tap is how edit mode ends; clearing the translation from inside it would be a surprise.
        if (overlay?.isEditing == true) { overlay?.setEditing(false); return }
        when (state) {
            ControlState.PROCESSING -> Unit
            ControlState.PAUSED -> startActivity(resumeIntent())
            ControlState.TRANSLATED -> { overlay?.clear(); setState(ControlState.READY) }
            ControlState.READY -> translateScreen()
        }
    }
    /**
     * Placement is automatic, and on a crowded game screen it can still put a card badly. Holding
     * the button while translations are up hands the screen over to the user: the overlay takes
     * touches, every card can be dragged, and a tap gives the screen back to the app below.
     */
    private fun longPress() {
        if (state != ControlState.TRANSLATED) { openSettings(); return }
        val editing = overlay?.isEditing != true
        overlay?.setEditing(editing)
        if (editing) notice("Перетащите карточки. Нажмите кнопку, чтобы выйти")
    }
    private fun setState(value: ControlState) {
        state = value
        overlay?.state(value)
        app.session.value = app.session.value.copy(control = value)
    }
    /**
     * The system ends a projection on its own: a screen lock, a policy change or the stop chip.
     * The service and the button stay alive so that recovery is one tap plus a new consent instead
     * of a session reassembled from the app. A stopped token is never reused.
     */
    private fun projectionLost() {
        if (stopping) return
        generation++
        processing?.cancel()
        capture?.close(); capture = null
        overlay?.clear()
        setState(ControlState.PAUSED)
        app.session.value = app.session.value.copy(phase = SessionPhase.PAUSED,
            message = "Android остановил захват экрана. Нажмите кнопку перевода, чтобы разрешить его снова.")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(paused = true))
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
                val frameWidth = bitmap.width
                val frameHeight = bitmap.height
                var composition = FrameAnalysis.none
                val recognized = try {
                    withContext(Dispatchers.Default) {
                        if (ScreenCaptureManager.isBlank(bitmap)) throw CaptureUnavailable()
                        // Reading the frame happens here and only here: what leaves this block is a
                        // coarse colour grid and recognized text, never the picture itself.
                        composition = FrameAnalyzer.analyze(bitmap)
                        engines().recognize(bitmap, settings.source, settings.merge)
                    }
                } finally { bitmap.recycle() }
                ensureActive()
                // Recognition works in frame pixels, the overlay draws in screen pixels.
                val screen = ScreenCaptureManager.screenSize(this@TranslationService)
                val blocks = recognized.map { it.onScreen(frameWidth, frameHeight, screen.first, screen.second) }
                overlay?.setComposition(composition.onScreen(
                    FrameMapping.transform(frameWidth, frameHeight, screen.first, screen.second)))
                if (blocks.isEmpty()) {
                    notice("Текст на экране не найден")
                } else if (settings.ocrPreview) {
                    visible += blocks.map { it.copy(translatedText = it.originalText) }
                    overlay?.showTranslations(visible)
                    notice("Проверка OCR: распознанный текст без отправки переводчику")
                } else {
                    val emit: suspend (ScreenTextBlock) -> Unit = { block ->
                        withContext(Dispatchers.Main.immediate) {
                            if (frameGeneration == generation) {
                                visible += block
                                overlay?.showTranslations(visible.toList())
                            }
                        }
                    }
                    var aiReason: String? = null
                    val errors = if (settings.mode == TranslationMode.AI) {
                        val ai = app.ai.settings.value
                        val token = withContext(Dispatchers.IO) { app.ai.token() }
                        val outcome = aiTranslator.translate(blocks, settings, ai, token, app.cache, emit)
                        aiReason = outcome.reason
                        val fallback = ai.fallback.provider()
                        when {
                            outcome.untranslated.isEmpty() -> 0
                            fallback == null -> outcome.untranslated.size
                            else -> translations.translate(outcome.untranslated, settings.copy(provider = fallback), emit)
                        }
                    } else translations.translate(blocks, settings, emit)
                    if (errors > 0) notice(when {
                        visible.isNotEmpty() -> "Часть блоков не переведена: $errors"
                        aiReason != null -> "ИИ-перевод недоступен: $aiReason"
                        else -> "Перевод недоступен. Проверьте сеть или смените сервис."
                    })
                    else if (visible.isEmpty()) notice("Текст уже на выбранном языке")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: CaptureUnavailable) { notice("Не удалось получить изображение. Возможно, приложение запрещает захват содержимого.") }
            catch (_: Exception) { notice("Не удалось обработать экран. Попробуйте ещё раз.") }
            finally {
                if (frameGeneration == generation && state != ControlState.PAUSED)
                    setState(if (visible.isEmpty()) ControlState.READY else ControlState.TRANSLATED)
            }
        }
    }
    private fun ScreenTextBlock.onScreen(frameWidth: Int, frameHeight: Int, screenWidth: Int, screenHeight: Int): ScreenTextBlock {
        if (frameWidth == screenWidth && frameHeight == screenHeight) return this
        return copy(
            boundingBox = FrameMapping.toScreen(boundingBox, frameWidth, frameHeight, screenWidth, screenHeight),
            lines = lines.map { it.copy(box = FrameMapping.toScreen(it.box, frameWidth, frameHeight, screenWidth, screenHeight)) })
    }
    private fun engines(): OCRManager = ocr ?: OCRManager(this).also { ocr = it }

    private fun invalidateFrame() {
        generation++
        processing?.cancel()
        overlay?.onRotation()
        if (state != ControlState.PAUSED) setState(ControlState.READY)
    }
    /** An unlock can hand the display back rotated, and some systems drop overlay windows meanwhile. */
    private fun resync() {
        overlay?.ensureAttached()
        val (width, height) = ScreenCaptureManager.screenSize(this)
        capture?.resize(width, height)
        overlay?.configure(app.settings.settings.value)
    }
    private fun registerScreenEvents() {
        if (screenEventsRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenEventsRegistered = true
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
    private fun resumeIntent() = Intent(this, MainActivity::class.java).setAction(ACTION_RESUME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    private fun notification(paused: Boolean): Notification {
        val open = PendingIntent.getActivity(this, if (paused) 2 else 0,
            if (paused) resumeIntent() else Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TranslationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Перевод приостановлен" else "Экранный переводчик активен")
            .setContentText(if (paused) "Захват экрана остановлен. Нажмите, чтобы разрешить снова."
                else "Нажмите, чтобы открыть Lenslate")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(R.drawable.ic_notification, "Выключить", stop).build()
    }
    private fun startNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Экранный переводчик", NotificationManager.IMPORTANCE_LOW))
        val notification = notification(paused = false)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION, notification)
    }
    override fun onDestroy() {
        stopping = true
        generation++
        scope.cancel()
        if (screenEventsRegistered) { unregisterReceiver(screenEvents); screenEventsRegistered = false }
        overlay?.close(); overlay = null
        capture?.close(); capture = null
        // Releasing a recognizer waits for the recognition it is running; that wait does not
        // belong on the main thread, and this scope is deliberately not the cancelled one.
        ocr?.let { engines ->
            ocr = null
            CoroutineScope(Dispatchers.IO).launch {
                try { engines.close() } catch (_: Exception) { }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!failed) app.session.value = SessionState()
        super.onDestroy()
    }
    private class CaptureUnavailable : Exception()
    companion object {
        const val ACTION_STOP = "com.adam.app_screentranslate.STOP"
        const val ACTION_RESUME = "com.adam.app_screentranslate.RESUME_CAPTURE"
        const val CHANNEL = "translation_session"
        private const val NOTIFICATION = 1
    }
}
