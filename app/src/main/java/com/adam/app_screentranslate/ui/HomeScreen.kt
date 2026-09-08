package com.adam.app_screentranslate.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adam.app_screentranslate.*
import com.adam.app_screentranslate.R
import com.adam.app_screentranslate.data.*
import com.adam.app_screentranslate.model.*
import kotlinx.coroutines.launch

private val Mint = Color(0xFF67E8C4)
private val Ink = Color(0xFF0E1B27)
private val Muted = Color(0xFF93A6B6)
private val Panel = Color(0xFF172733)

@Composable
fun HomeScreen(
    settings: AppSettings, session: SessionState, permissions: PermissionStatus, cache: TranslationCache,
    onSettings: (AppSettings) -> Unit, onToggle: (Boolean) -> Unit,
    onOverlay: () -> Unit, onCapture: () -> Unit, onNotifications: () -> Unit, onRefresh: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chooser by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf(CacheStats()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tab, session.control) { stats = cache.stats() }
    Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_brand), contentDescription = null,
                    tint = Color.Unspecified, modifier = Modifier.size(42.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Lenslate", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Понимайте любой экран", fontSize = 12.sp, color = Muted)
                }
                Spacer(Modifier.weight(1f))
                Text("MVP 0.2", color = Mint, fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Mint.copy(alpha = .1f)).padding(8.dp))
            }
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(5.dp)) {
                listOf("Перевод", "Настройки").forEachIndexed { i, title ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (tab == i) Mint else Color.Transparent).clickable { tab = i }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text(title, color = if (tab == i) Ink else Muted, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            if (tab == 0) {
                DemoCard()
                Spacer(Modifier.height(20.dp))
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Экранный переводчик", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Spacer(Modifier.height(6.dp))
                            val status = when(session.phase) {
                                SessionPhase.OFF -> "Выключен"
                                SessionPhase.STARTING -> "Включается…"
                                SessionPhase.ACTIVE -> when(session.control) {
                                    ControlState.READY -> "Активен · готов к переводу"
                                    ControlState.PROCESSING -> "Распознаём и переводим…"
                                    ControlState.TRANSLATED -> "Перевод на экране"
                                }
                                SessionPhase.ERROR -> "Не удалось включить"
                            }
                            Text(status, color = if (session.phase == SessionPhase.ACTIVE) Mint else Muted, fontSize = 13.sp)
                        }
                        Switch(checked = session.phase in listOf(SessionPhase.ACTIVE, SessionPhase.STARTING),
                            enabled = session.phase != SessionPhase.STARTING, onCheckedChange = onToggle)
                    }
                    if (session.message.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(session.message, color = Color(0xFFFFD39B), fontSize = 12.sp)
                    }
                }
                Heading("ЯЗЫКИ И ПЕРЕВОД")
                Section {
                    ChoiceRow("Язык оригинала", Languages.sources[settings.source] ?: settings.source) { chooser = "source" }
                    HorizontalDivider(color = Color.White.copy(alpha = .07f), modifier = Modifier.padding(vertical = 8.dp))
                    ChoiceRow("Переводить на", Languages.targets[settings.target] ?: settings.target) { chooser = "target" }
                    Spacer(Modifier.height(16.dp))
                    Text("Сервис перевода", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Options(ProviderMode.entries, settings.provider, { it.label }) { onSettings(settings.copy(provider = it)) }
                    Spacer(Modifier.height(8.dp))
                    Text(if (settings.provider == ProviderMode.AUTO) "Сначала Google, при ошибке — Yandex" else "Перевод через ${settings.provider.label}", fontSize = 11.sp, color = Muted)
                }
                Heading("РАЗРЕШЕНИЯ И СОСТОЯНИЕ")
                Section {
                    PermissionRow("Поверх приложений", permissions.overlay, "Разрешить", onOverlay)
                    PermissionRow("Захват экрана", session.phase == SessionPhase.ACTIVE, "Разрешить", onCapture)
                    PermissionRow("Уведомления", permissions.notifications, "Разрешить", onNotifications)
                    PermissionRow("OCR-модели в приложении", true)
                    PermissionRow("Интернет", permissions.network)
                    TextButton(onClick = { onRefresh(); scope.launch { stats = cache.stats() } }, contentPadding = PaddingValues(0.dp)) {
                        Text("Проверить снова", color = Mint)
                    }
                    Text("Для точного размещения перевода выберите весь экран в системном запросе.", color = Muted, fontSize = 11.sp)
                }
                Heading("КАК ПОЛЬЗОВАТЬСЯ")
                Section {
                    Guide("01", "Включите переводчик", "Выдайте разрешения и откройте игру или приложение.")
                    Guide("02", "Нажмите плавающую кнопку", "Перевод появится поверх текста. Управление остаётся доступным.")
                    Guide("03", "Нажмите ещё раз для очистки", "Перетаскивайте кнопку. Удерживайте, чтобы открыть настройки.")
                }
            } else {
                Text("Подстройте под себя", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Для игр, диалогов и всего между ними", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Heading("РАСПОЗНАВАНИЕ")
                Section {
                    Text("Объединение строк OCR", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    Options(MergeMode.entries, settings.merge, { it.label }) { onSettings(settings.copy(merge = it)) }
                    Spacer(Modifier.height(12.dp))
                    Text("Нормальное объединение сохраняет абзацы. Осторожное подходит для меню, агрессивное — для длинных диалогов.",
                        color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(if (settings.source == "auto") "Локальные модели: Latin + Japanese + Korean + Русский"
                        else "Модели выбираются по языку оригинала. Latin остаётся доступным для имён и интерфейса.",
                        color = Mint, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Проверка распознавания", fontWeight = FontWeight.Medium)
                            Text("Показать исходный OCR без перевода и сети", color = Muted, fontSize = 11.sp)
                        }
                        Switch(checked = settings.ocrPreview, onCheckedChange = { onSettings(settings.copy(ocrPreview = it)) })
                    }
                }
                Heading("ПЕРЕВОД ПОВЕРХ ЭКРАНА")
                Section {
                    ChoiceRow("Стиль фона", settings.background.label) { chooser = "background" }
                    Spacer(Modifier.height(16.dp))
                    SliderSetting("Плотность фона", settings.opacity, .6f..1f) { onSettings(settings.copy(opacity = it)) }
                    Text("На Android 12+ итоговая непрозрачность ограничена системой для передачи касаний.", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(16.dp))
                    SliderSetting("Масштаб текста", settings.textScale, .8f..1.3f) { onSettings(settings.copy(textScale = it)) }
                    Spacer(Modifier.height(12.dp))
                    Text("Размер плавающей кнопки", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Options(ButtonSize.entries, settings.buttonSize, { it.label }) { onSettings(settings.copy(buttonSize = it)) }
                    Spacer(Modifier.height(18.dp))
                    SliderSetting("Непрозрачность кнопки", settings.buttonOpacity, .2f..1f) { onSettings(settings.copy(buttonOpacity = it)) }
                    Text("20 % — почти прозрачная, 100 % — непрозрачная", color = Muted, fontSize = 11.sp)
                }
                Heading("ЛОКАЛЬНЫЙ КЭШ")
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Сохранять переводы", fontWeight = FontWeight.Medium)
                            Text("Знакомый текст — без запроса в сеть", color = Muted, fontSize = 11.sp)
                        }
                        Switch(checked = settings.cacheEnabled, onCheckedChange = { onSettings(settings.copy(cacheEnabled = it)) })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("${stats.entries} записей  ·  ≈ ${stats.bytes / 1024} КБ текста", color = Mint, fontSize = 14.sp)
                    TextButton(onClick = { scope.launch { cache.clear(); stats = cache.stats() } }, enabled = stats.entries > 0) {
                        Text("Очистить кэш")
                    }
                }
                Heading("ПРИВАТНОСТЬ")
                Section {
                    Text("Ваш экран остаётся на устройстве", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Изображение обрабатывается локально, не сохраняется и не отправляется в сеть. Google или Yandex получают только распознанный текст. Кэш хранится на этом устройстве.",
                        color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Веб-переводчики неофициальные: их доступность и протокол могут меняться.", color = Muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("Локальное распознавание  ·  Перевод по нажатию", fontSize = 10.sp, color = Muted)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    chooser?.let { kind ->
        val title = when(kind) { "source" -> "Язык оригинала"; "target" -> "Переводить на"; else -> "Стиль фона" }
        val choices = when(kind) {
            "source" -> Languages.sources
            "target" -> Languages.targets
            else -> BackgroundStyle.entries.associate { it.name to it.label }
        }
        AlertDialog(onDismissRequest = { chooser = null }, title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { (key, label) ->
                        Text(label, Modifier.fillMaxWidth().clickable {
                            onSettings(when(kind) {
                                "source" -> settings.copy(source = key)
                                "target" -> settings.copy(target = key)
                                else -> settings.copy(background = BackgroundStyle.valueOf(key))
                            })
                            chooser = null
                        }.padding(vertical = 15.dp))
                    }
                }
            }, confirmButton = { TextButton(onClick = { chooser = null }) { Text("Закрыть") } })
    }
}
@Composable private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(18.dp), content = content)
}
@Composable private fun Heading(text: String) {
    Text(text, fontSize = 10.sp, letterSpacing = 1.6.sp, color = Muted, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 25.dp, bottom = 12.dp, start = 2.dp))
}
@Composable private fun ChoiceRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        Text("⌄", color = Mint, fontSize = 24.sp)
    }
}
@Composable private fun <T> Options(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (selected == item) Mint else Ink)
                .clickable { onSelect(item) }.padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(label(item), color = if (selected == item) Ink else Muted, fontSize = 11.sp,
                    fontWeight = if (selected == item) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
@Composable private fun SliderSetting(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row { Text(title, Modifier.weight(1f)); Text("${(value*100).toInt()} %", color = Mint) }
    Slider(value = value, onValueChange = onChange, valueRange = range)
}
@Composable private fun PermissionRow(title: String, granted: Boolean, action: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 43.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (granted) "✓" else "!", color = if (granted) Mint else Color(0xFFFFD39B), modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            if (!granted && action == null) Text("Недоступен", color = Muted, fontSize = 10.sp)
        }
        if (!granted && action != null) TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(action, fontSize = 11.sp, color = Mint)
        }
    }
}
@Composable private fun Guide(number: String, title: String, subtitle: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Text(number, color = Mint, fontSize = 12.sp, modifier = Modifier.width(31.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
@Composable private fun DemoCard() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF235448), Color(0xFF1B343A)))).padding(22.dp)) {
        Text("МЕНЬШЕ ГРАНИЦ. БОЛЬШЕ СМЫСЛА.", color = Mint, fontSize = 9.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(12.dp))
        Text("Ваша игра.\nНа вашем языке.", fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MISSION COMPLETE", color = Color(0xFF9BBEB4), fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Text("Задание выполнено", fontSize = 15.sp, color = Ink, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFE4F8ED)).padding(9.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Mint), contentAlignment = Alignment.Center) {
                Text("A⇄", fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp)
            }
        }
    }
}
