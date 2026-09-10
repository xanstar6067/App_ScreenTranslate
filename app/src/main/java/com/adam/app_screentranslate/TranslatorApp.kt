package com.adam.app_screentranslate
import android.app.Application
import com.adam.app_screentranslate.data.AiConfigManager
import com.adam.app_screentranslate.data.SettingsManager
import com.adam.app_screentranslate.data.TranslationCache
import com.adam.app_screentranslate.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow

class TranslatorApp : Application() {
    val settings by lazy { SettingsManager(this) }
    val ai by lazy { AiConfigManager(this) }
    val cache by lazy { TranslationCache(this) }
    val session = MutableStateFlow(SessionState())
}
