package com.adam.app_screentranslate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installed app artwork only; loading never reads or saves a captured screen. */
@Composable
internal fun ApplicationIcon(packageName: String) {
    val pm = LocalContext.current.packageManager
    val pixels = with(LocalDensity.current) { 44.dp.roundToPx() }
    var icon by remember(pm, packageName, pixels) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pm, packageName, pixels) {
        icon = withContext(Dispatchers.IO) {
            try {
                // toBitmap also renders adaptive/vector drawables, rather than assuming a BitmapDrawable.
                pm.getApplicationIcon(packageName).toBitmap(pixels, pixels).asImageBitmap()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Muted.copy(alpha = .08f)),
        contentAlignment = Alignment.Center) {
        val bitmap = icon
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(44.dp))
        else Text("?", color = Muted, fontSize = 20.sp)
    }
}
