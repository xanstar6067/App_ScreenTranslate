package com.adam.app_screentranslate.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LenslateColors = darkColorScheme(
    primary = Color(0xFF67E8C4), onPrimary = Color(0xFF0E1B27),
    primaryContainer = Color(0xFF235448), onPrimaryContainer = Color(0xFFDCFBEF),
    secondary = Color(0xFFA4C5E8), background = Color(0xFF0E1B27),
    surface = Color(0xFF172733), onSurface = Color(0xFFF1F6FA),
    surfaceVariant = Color(0xFF243845), onSurfaceVariant = Color(0xFFB2C3CE),
    outline = Color(0xFF4D6574)
)
@Composable fun App_ScreenTranslateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LenslateColors, typography = Typography, content = content)
}
