package com.adam.app_screentranslate.ui

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adam.app_screentranslate.data.AiConfigManager
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.translation.ai.AiCheckLine
import com.adam.app_screentranslate.translation.ai.AiPrompts
import com.adam.app_screentranslate.translation.ai.XaiClient
import com.adam.app_screentranslate.translation.ai.XaiException
import com.adam.app_screentranslate.translation.ai.XaiModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State behind the AI tab. Network work and the Keystore both belong off the main thread, and the
 * revealed token is held only until the user hides it again.
 */
class AiPanel(private val config: AiConfigManager, private val scope: CoroutineScope) {
    private val client = XaiClient()
    private var running: Job? = null

    val settings get() = config.settings
    val models get() = config.models
    val hasToken get() = config.hasToken

    var revealed by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var report by mutableStateOf<List<AiCheckLine>>(emptyList())
        private set

    fun update(value: AiSettings) = config.update(value)

    fun reveal() {
        scope.launch { revealed = withContext(Dispatchers.IO) { config.token() } }
    }

    fun hide() { revealed = null }

    fun save(token: String) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { config.saveToken(token) }
            revealed = null
            report = listOf(if (saved) AiCheckLine(true, "Токен сохранён")
            else AiCheckLine(false, "Не удалось зашифровать токен: хранилище ключей недоступно"))
        }
    }

    fun clear() {
        config.clearAll()
        revealed = null
        report = listOf(AiCheckLine(true, "Токен и список моделей удалены"))
    }

    fun copy(): String = config.token()

    fun refreshModels() = start {
        val fetched = XaiModels.textTranslationModels(client.models(config.token()))
        config.saveModels(fetched)
        val current = config.settings.value.model
        report = buildList {
            add(AiCheckLine(fetched.isNotEmpty(), "Текстовых моделей: ${fetched.size}"))
            if (current.isNotBlank() && fetched.none { it.id == current })
                add(AiCheckLine(false, "Выбранная модель $current больше недоступна. Выберите другую."))
        }
    }

    fun check() = start {
        val (lines, fetched) = client.check(config.token(), config.settings.value.model)
        if (fetched.isNotEmpty()) config.saveModels(fetched)
        report = lines
    }

    private fun start(action: suspend () -> Unit) {
        if (running?.isActive == true) return
        busy = true
        report = emptyList()
        running = scope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: CancellationException) { throw e }
            catch (e: XaiException) { report = listOf(AiCheckLine(false, e.reason)) }
            catch (_: Exception) { report = listOf(AiCheckLine(false, "Запрос не удался. Проверьте сеть и токен.")) }
            finally { busy = false }
        }
    }
}

@Composable
fun AiTab(panel: AiPanel, app: AppSettings, onApp: (AppSettings) -> Unit) {
    val ai by panel.settings.collectAsState()
    val models by panel.models.collectAsState()
    val hasToken by panel.hasToken.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(panel.revealed) { panel.revealed?.let { draft = it; show = true } }

    Text("ИИ-перевод", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text("xAI Grok вместо веб-переводчика", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))

    Heading("РЕЖИМ ПЕРЕВОДА")
    Section {
        Options(TranslationMode.entries, app.mode, { it.label }) { onApp(app.copy(mode = it)) }
        Spacer(Modifier.height(10.dp))
        Text(if (app.mode == TranslationMode.AI)
            "Экран уходит в xAI пакетами по 8 блоков. При отказе включается резервный переводчик."
        else "Перевод идёт через Google или Yandex. Настройки ниже не используются.",
            color = Muted, fontSize = 11.sp)
    }

    Heading("API TOKEN XAI")
    Section {
        OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
            label = { Text("API token") }, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, focusedLabelColor = Mint))
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { if (show) { show = false; draft = ""; panel.hide() } else panel.reveal() },
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (show) "Скрыть" else "Показать", color = Mint, fontSize = 12.sp)
            }
            TextButton(onClick = { scope.launch { clipboard.setClipEntry(tokenClip(draft.ifBlank { panel.copy() })) } },
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Скопировать", color = Mint, fontSize = 12.sp)
            }
            TextButton(onClick = { panel.clear(); draft = ""; show = false },
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Очистить", color = Color(0xFFFFD39B), fontSize = 12.sp)
            }
        }
        Button(onClick = { panel.save(draft); show = false; draft = "" }, enabled = draft.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
            Text("Сохранить токен")
        }
        Spacer(Modifier.height(10.dp))
        Text(if (hasToken) "Токен сохранён и зашифрован ключом Android Keystore"
        else "Токен не задан. Без него ИИ-режим не работает.",
            color = if (hasToken) Mint else Color(0xFFFFD39B), fontSize = 11.sp)
    }

    Heading("ПОДКЛЮЧЕНИЕ")
    Section {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { panel.check() }, enabled = hasToken && !panel.busy, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                Text("Проверить", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { panel.refreshModels() }, enabled = hasToken && !panel.busy, modifier = Modifier.weight(1f)) {
                Text("Обновить модели", color = Mint, fontSize = 13.sp)
            }
        }
        if (panel.busy) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Mint)
        }
        if (panel.report.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            panel.report.forEach { line ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(if (line.ok) "✓" else "✗", color = if (line.ok) Mint else Color(0xFFFF9B9B),
                        modifier = Modifier.width(22.dp), fontSize = 13.sp)
                    Text(line.text, fontSize = 12.sp, color = if (line.ok) Color.White else Color(0xFFFFD39B))
                }
            }
        }
    }

    Heading("АКТИВНАЯ МОДЕЛЬ")
    Section {
        ChoiceRow("Модель", ai.model.ifBlank { "Не выбрана" }) { if (models.isNotEmpty()) picking = true }
        Spacer(Modifier.height(6.dp))
        val available = models.any { it.id == ai.model }
        Text(when {
            models.isEmpty() -> "Список пуст. Нажмите «Обновить модели»."
            ai.model.isBlank() -> "Выберите модель из списка."
            available -> "✓ доступна"
            else -> "! модель больше недоступна. Выберите другую — сама она не сменится."
        }, color = when {
            available -> Mint
            models.isEmpty() || ai.model.isBlank() -> Muted
            else -> Color(0xFFFFD39B)
        }, fontSize = 12.sp)
    }

    Heading("ПОВЕДЕНИЕ")
    Section {
        Text("Резервный переводчик при недоступности ИИ", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Picker(AiFallback.entries, ai.fallback, { it.label }) { panel.update(ai.copy(fallback = it)) }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Восстанавливать разорванный текст", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("Склеивать слова и реплики, разбитые распознаванием", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = ai.repair, onCheckedChange = { panel.update(ai.copy(repair = it)) })
        }
        Spacer(Modifier.height(8.dp))
        Text(if (ai.repair) "Экран переводится целиком, поэтому кэш не используется."
        else "Каждый блок переводится отдельно и попадает в кэш.", color = Muted, fontSize = 11.sp)
    }

    Heading("СИСТЕМНЫЙ ПРОМПТ")
    Section {
        Picker(AiPrompts.builtIn, AiPrompts.byId(ai.prompt), { it.title }, { it.description }) {
            panel.update(ai.copy(prompt = it.id))
        }
    }

    Heading("ПРИВАТНОСТЬ ИИ-РЕЖИМА")
    Section {
        Text("Что уходит в xAI", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Снимок экрана не покидает устройство и в ИИ-режиме — отправляется только распознанный текст, номера блоков и языки. Но текста уходит больше, чем обычному переводчику: весь распознанный экран пакетами и системный промпт.",
            color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Text("Токен хранится зашифрованным на этом устройстве и не попадает в резервные копии Android.",
            color = Muted, fontSize = 11.sp)
    }

    if (picking) AlertDialog(onDismissRequest = { picking = false }, title = { Text("Активная модель") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                models.forEach { model ->
                    Column(Modifier.fillMaxWidth().clickable {
                        panel.update(ai.copy(model = model.id)); picking = false
                    }.padding(vertical = 12.dp)) {
                        Text(model.id, fontSize = 15.sp)
                        model.maxPromptLength?.let {
                            Text("контекст до $it токенов", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picking = false }) { Text("Закрыть") } })
}

/**
 * A key on the clipboard is still a secret: the sensitive flag keeps Android 13+ from putting it in
 * the paste preview and the clipboard history. Older systems ignore the extra, so no branch.
 */
private fun tokenClip(value: String): ClipEntry {
    val clip = ClipData.newPlainText("xAI token", value)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    return ClipEntry(clip)
}

@Composable
private fun <T> Picker(items: List<T>, selected: T, label: (T) -> String,
                       hint: (T) -> String? = { null }, onSelect: (T) -> Unit) {
    items.forEach { item ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(item) }
            .padding(vertical = 9.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected == item) "●" else "○", color = if (selected == item) Mint else Muted,
                modifier = Modifier.width(26.dp), fontSize = 13.sp)
            Column(Modifier.weight(1f)) {
                Text(label(item), fontSize = 14.sp, color = if (selected == item) Color.White else Muted)
                hint(item)?.let { Text(it, color = Muted, fontSize = 11.sp) }
            }
        }
    }
}
