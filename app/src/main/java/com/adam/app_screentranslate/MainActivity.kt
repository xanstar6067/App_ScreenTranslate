package com.adam.app_screentranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.*
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.service.TranslationService
import com.adam.app_screentranslate.ui.HomeScreen
import com.adam.app_screentranslate.ui.theme.App_ScreenTranslateTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as TranslatorApp
    private var refresh by mutableIntStateOf(0)
    private var resumeEnable = false
    private var resumingSession = false
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
        if (resumeEnable) {
            resumeEnable = false
            if (Settings.canDrawOverlays(this)) requestNotificationsThenCapture()
            else app.session.value = SessionState(SessionPhase.ERROR, "Разрешите отображение поверх приложений.")
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
        if (resumeEnable) { resumeEnable = false; requestCapture() }
    }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, TranslationService::class.java)
                    .putExtra("resultCode", result.resultCode).putExtra("projection", result.data))
            } catch (_: Exception) {
                app.session.value = SessionState(SessionPhase.ERROR, "Android не разрешил запуск. Попробуйте включить снова.")
            }
        } else {
            // A running session that only lost its projection stays paused and reachable from the button.
            app.session.value = SessionState(
                if (resumingSession) SessionPhase.PAUSED else SessionPhase.OFF, "Захват экрана не разрешён.",
                if (resumingSession) ControlState.PAUSED else ControlState.READY)
        }
        resumingSession = false
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumeEnable = savedInstanceState?.getBoolean("resumeEnable") ?: false
        enableEdgeToEdge()
        setContent {
            val settings by app.settings.settings.collectAsState()
            val session by app.session.collectAsState()
            val permissions = remember(refresh, session.phase) { readPermissions() }
            App_ScreenTranslateTheme {
                HomeScreen(settings, session, permissions, app.cache,
                    onSettings = app.settings::update, onToggle = { enable ->
                        if (enable) enableTranslator() else {
                            stopService(Intent(this, TranslationService::class.java))
                            app.session.value = SessionState()
                        }
                    },
                    onOverlay = { openOverlayPermission() },
                    onCapture = { if (session.phase != SessionPhase.ACTIVE) enableTranslator() },
                    onNotifications = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }, onRefresh = { refresh++ })
            }
        }
        handleAction(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent)
    }
    override fun onResume() { super.onResume(); refresh++ }
    /** The overlay button and the paused notification ask for a new capture consent directly. */
    private fun handleAction(intent: Intent?) {
        if (intent?.action != TranslationService.ACTION_RESUME) return
        // The consent is single use; the action must not fire again when the activity is recreated.
        intent.action = null
        resumingSession = true
        app.session.value = app.session.value.copy(phase = SessionPhase.STARTING, message = "")
        if (Settings.canDrawOverlays(this)) requestNotificationsThenCapture()
        else { resumeEnable = true; openOverlayPermission() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("resumeEnable", resumeEnable)
        super.onSaveInstanceState(outState)
    }
    private fun enableTranslator() {
        if (app.session.value.phase in listOf(SessionPhase.ACTIVE, SessionPhase.STARTING)) return
        app.session.value = SessionState(SessionPhase.STARTING)
        if (!Settings.canDrawOverlays(this)) { resumeEnable = true; openOverlayPermission() }
        else requestNotificationsThenCapture()
    }
    private fun openOverlayPermission() {
        try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: Exception) {
            resumeEnable = false
            app.session.value = SessionState(SessionPhase.ERROR, "Откройте системные настройки и разрешите отображение поверх приложений.")
        }
    }
    private fun requestNotificationsThenCapture() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            resumeEnable = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestCapture()
    }
    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        projection.launch(intent)
    }
    private fun readPermissions(): PermissionStatus {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return PermissionStatus(Settings.canDrawOverlays(this),
            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled(),
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }
}
data class PermissionStatus(val overlay: Boolean, val notifications: Boolean, val network: Boolean)
