package com.adam.app_screentranslate.data

import android.content.Context
import com.adam.app_screentranslate.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private inline fun <reified T : Enum<T>> enum(key: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == prefs.getString(key, null) } ?: fallback
    private val mutable = MutableStateFlow(AppSettings(
        target = prefs.getString("target", "ru") ?: "ru", source = prefs.getString("source", "auto") ?: "auto",
        provider = enum("provider", ProviderMode.AUTO), merge = enum("merge", MergeMode.NORMAL),
        background = enum("background", BackgroundStyle.AUTO), opacity = prefs.getFloat("opacity", .9f).coerceIn(.6f, 1f),
        textScale = prefs.getFloat("textScale", 1f).coerceIn(.8f, 1.3f), buttonSize = enum("buttonSize", ButtonSize.MEDIUM),
        cacheEnabled = prefs.getBoolean("cache", true), buttonOpacity = prefs.getFloat("buttonOpacity", 1f).coerceIn(.2f, 1f),
        ocrPreview = prefs.getBoolean("ocrPreview", false)))
    val settings = mutable.asStateFlow()
    fun update(value: AppSettings) {
        prefs.edit().putString("target", value.target).putString("source", value.source)
            .putString("provider", value.provider.name).putString("merge", value.merge.name)
            .putString("background", value.background.name).putFloat("opacity", value.opacity)
            .putFloat("textScale", value.textScale).putString("buttonSize", value.buttonSize.name)
            .putBoolean("cache", value.cacheEnabled).putFloat("buttonOpacity", value.buttonOpacity.coerceIn(.2f, 1f))
            .putBoolean("ocrPreview", value.ocrPreview).apply()
        mutable.value = value
    }
    fun position(landscape: Boolean) = Pair(prefs.getFloat("x_$landscape", .92f), prefs.getFloat("y_$landscape", .35f))
    fun savePosition(landscape: Boolean, x: Float, y: Float) {
        prefs.edit().putFloat("x_$landscape", x.coerceIn(0f, 1f)).putFloat("y_$landscape", y.coerceIn(0f, 1f)).apply()
    }
}
