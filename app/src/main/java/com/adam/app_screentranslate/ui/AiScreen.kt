package com.adam.app_screentranslate.ui

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
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
import com.adam.app_screentranslate.translation.ai.AiConnection
import com.adam.app_screentranslate.translation.ai.AiEngine
import com.adam.app_screentranslate.translation.ai.AiEngines
import com.adam.app_screentranslate.translation.ai.AiHttpException
import com.adam.app_screentranslate.translation.ai.AiPricing
import com.adam.app_screentranslate.translation.ai.AiPrompts
import com.adam.app_screentranslate.translation.ai.AiReasoning
import com.adam.app_screentranslate.translation.ai.ModelSearch
import com.adam.app_screentranslate.translation.ai.PriceTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State behind the AI tab. Network work and the Keystore both belong off the main thread, and the
 * revealed token is held only until the user hides it again.
 *
 * The key section works on one provider at a time — [keyProvider] — which is deliberately not the
 * provider that translates: a key is set up once per provider, and the two roles may well be on
 * two different ones.
 */
class AiPanel(private val config: AiConfigManager, private val scope: CoroutineScope) {
    // Engines are kept for the session: each remembers what its models negotiated.
    private val engines = mutableMapOf<AiProvider, AiEngine>()
    private var running: Job? = null

    /** Shared with the games tab, so research and the connection check learn about the same model. */
    fun engine(provider: AiProvider): AiEngine = engines.getOrPut(provider) { AiEngines.create(provider) }

    val settings get() = config.settings
    val models get() = config.models
    val tokens get() = config.tokens

    /** Whose key the key section is editing. Starts on the provider that translates. */
    var keyProvider by mutableStateOf(config.settings.value.provider)
        private set

    /**
     * Which model the connection check probes. It is a diagnostic of its own, not a role: the check
     * used to silently take whichever role happened to use this provider, which made a green report
     * say nothing about the model the user was actually looking at. Session state — checking a model
     * is not choosing it, and nothing here is written to the settings.
     */
    var probeModel by mutableStateOf(defaultProbe(config.settings.value.provider))
        private set

    fun selectProbeModel(model: String) { probeModel = model }

    /** What this provider is set to work with, translation first, or the last model it was set to. */
    private fun defaultProbe(provider: AiProvider): String {
        val settings = config.settings.value
        AiRole.entries.forEach { role ->
            if (settings.providerFor(role) == provider && settings.modelFor(role).isNotBlank())
                return settings.modelFor(role)
        }
        return AiRole.entries.firstNotNullOfOrNull { config.remembered(it, provider).ifBlank { null } }.orEmpty()
    }

    /**
     * The reasoning level the probe sends. A model that a role already uses is probed exactly as
     * that role will use it; anything else is probed at the level the screen would use.
     */
    fun probeEffort(): AiEffort {
        val settings = config.settings.value
        val role = AiRole.entries.firstOrNull {
            settings.providerFor(it) == keyProvider && settings.modelFor(it) == probeModel && probeModel.isNotBlank()
        }
        return role?.let { settings.effortFor(it) } ?: AiEffort.MINIMAL
    }

    /** Which role, if any, the probed model belongs to — so the screen can say so. */
    fun probeRole(): AiRole? {
        val settings = config.settings.value
        if (probeModel.isBlank()) return null
        return AiRole.entries.firstOrNull {
            settings.providerFor(it) == keyProvider && settings.modelFor(it) == probeModel
        }
    }

    var revealed by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var report by mutableStateOf<List<AiCheckLine>>(emptyList())
        private set

    fun update(value: AiSettings) = config.update(value)

    /** A revealed key and a report belong to the provider they came from, and stay behind with it. */
    fun selectKeyProvider(provider: AiProvider) {
        keyProvider = provider
        probeModel = defaultProbe(provider)
        revealed = null
        report = emptyList()
    }

    fun reveal() {
        val provider = keyProvider
        scope.launch { revealed = withContext(Dispatchers.IO) { config.token(provider) } }
    }

    fun hide() { revealed = null }

    fun save(token: String) {
        val provider = keyProvider
        scope.launch {
            val saved = withContext(Dispatchers.IO) { config.saveToken(provider, token) }
            revealed = null
            report = listOf(if (saved) AiCheckLine(true, "Ключ ${provider.label} сохранён")
            else AiCheckLine(false, "Не удалось зашифровать ключ: хранилище ключей недоступно"))
        }
    }

    fun clear() {
        val provider = keyProvider
        config.clearProvider(provider)
        // The models this key could reach are gone; an id left aimed at them would only mislead.
        probeModel = defaultProbe(provider)
        revealed = null
        report = listOf(AiCheckLine(true, "Ключ и список моделей ${provider.label} удалены"))
    }

    fun copy(): String = config.token(keyProvider)

    fun refreshModels() = start {
        val engine = engine(keyProvider)
        val listed = engine.models(config.token(engine.provider))
        val fetched = engine.usable(listed)
        val hidden = listed.distinctBy { it.id }.size - fetched.size
        config.saveModels(engine.provider, fetched)
        val chosen = AiRole.entries.map { it to config.settings.value.modelFor(it) }
            .filter { (role, model) -> model.isNotBlank() && config.settings.value.providerFor(role) == engine.provider }
        report = buildList {
            add(AiCheckLine(fetched.isNotEmpty(), "Текстовых моделей: ${fetched.size}"))
            // Without this a model missing because of our own filter looks exactly like a model
            // the provider never sent.
            if (hidden > 0) add(AiCheckLine(true, "Скрыто как нетекстовые: $hidden"))
            chosen.filter { (_, model) -> fetched.none { it.id == model } }.forEach { (_, model) ->
                add(AiCheckLine(false, "Выбранная модель $model больше недоступна. Выберите другую."))
            }
        }
    }

    fun check() = start {
        val engine = engine(keyProvider)
        val (lines, fetched) = AiConnection.check(engine, config.token(engine.provider), probeModel, probeEffort())
        if (fetched.isNotEmpty()) config.saveModels(engine.provider, fetched)
        report = lines
    }

    private fun start(action: suspend () -> Unit) {
        if (running?.isActive == true) return
        busy = true
        report = emptyList()
        running = scope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: CancellationException) { throw e }
            catch (e: AiHttpException) { report = listOf(AiCheckLine(false, "${e.status}: ${e.reason}")) }
            // The class name alone: an exception message can carry the text that was being sent.
            catch (e: Exception) { report = listOf(AiCheckLine(false, "Сбой: ${e.javaClass.simpleName}")) }
            finally { busy = false }
        }
    }
}

@Composable
fun AiTab(panel: AiPanel, app: AppSettings, onApp: (AppSettings) -> Unit) {
    val ai by panel.settings.collectAsState()
    val models by panel.models.collectAsState()
    val tokens by panel.tokens.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf<AiRole?>(null) }
    var probing by remember { mutableStateOf(false) }
    val editing = panel.keyProvider
    val probeList = models[editing].orEmpty()
    LaunchedEffect(panel.revealed) { panel.revealed?.let { draft = it; show = true } }

    Text("ИИ-перевод", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text("Grok, Gemini или OpenRouter вместо веб-переводчика", color = Muted, fontSize = 13.sp,
        modifier = Modifier.padding(top = 6.dp))

    Heading("РЕЖИМ ПЕРЕВОДА")
    Section {
        Options(TranslationMode.entries, app.mode, { it.label }) { onApp(app.copy(mode = it)) }
        Spacer(Modifier.height(10.dp))
        Text(if (app.mode == TranslationMode.AI)
            "Экран уходит в ${ai.provider.label} пакетами по 8 блоков. При отказе включается резервный переводчик."
        else "Перевод идёт через Google или Yandex. Настройки ниже не используются.",
            color = Muted, fontSize = 11.sp)
    }

    Heading("КЛЮЧИ ПРОВАЙДЕРОВ")
    Section {
        Options(AiProvider.entries, editing, { it.short }) {
            draft = ""; show = false; panel.selectKeyProvider(it)
        }
        Spacer(Modifier.height(10.dp))
        Text(when (editing) {
            AiProvider.XAI -> "Ключ из консоли xAI. Все текущие модели — reasoning; глубина размышлений задаётся ниже."
            AiProvider.GEMINI -> "Ключ из Google AI Studio. Модели Flash отвечают заметно быстрее Grok."
            AiProvider.OPENROUTER -> "Ключ с openrouter.ai: один счёт на модели всех вендоров сразу. Модель называется «вендор/модель», например openai/gpt-5 или anthropic/claude-sonnet-4.5."
        }, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
            label = { Text("API-ключ ${editing.short}") },
            modifier = Modifier.fillMaxWidth(),
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
                Text("Очистить", color = Warn, fontSize = 12.sp)
            }
        }
        Button(onClick = { panel.save(draft); show = false; draft = "" }, enabled = draft.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
            Text("Сохранить ключ")
        }
        Spacer(Modifier.height(10.dp))
        Text(if (editing in tokens) "Ключ ${editing.label} сохранён и зашифрован Android Keystore"
        else "Ключ ${editing.label} не задан.", color = if (editing in tokens) Mint else Warn, fontSize = 11.sp)

        Spacer(Modifier.height(12.dp))
        Text("Ключ нужен каждому провайдеру, которого вы выбрали ниже: перевод экрана и заполнение глоссария могут работать на разных.",
            color = Muted, fontSize = 11.sp)
    }

    Heading("ПРОВЕРКА ПОДКЛЮЧЕНИЯ")
    Section {
        Text("Провайдер: ${editing.label}", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        ChoiceRow("Проверяемая модель", panel.probeModel.ifBlank { "Не выбрана" }) {
            if (probeList.isNotEmpty() || panel.probeModel.isNotBlank()) probing = true
        }
        Spacer(Modifier.height(6.dp))
        Text(when (panel.probeRole()) {
            AiRole.TRANSLATE -> "Этой моделью переводится экран. Рассуждение в пробе — как у неё: ${panel.probeEffort().label}."
            AiRole.RESEARCH -> "Этой моделью заполняется глоссарий. Рассуждение в пробе — как у неё: ${panel.probeEffort().label}."
            null -> "Модель не назначена ни одной роли — проба уйдёт с минимальным рассуждением."
        }, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { panel.check() }, enabled = editing in tokens && !panel.busy, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                Text("Проверить", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { panel.refreshModels() }, enabled = editing in tokens && !panel.busy,
                modifier = Modifier.weight(1f)) {
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
                    Text(line.text, fontSize = 12.sp, color = if (line.ok) Color.White else Warn)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Проверка отправляет настоящий пробный перевод из двух блоков — выбор модели здесь ничего не меняет в настройках ниже.",
            color = Muted, fontSize = 11.sp)
    }

    Heading("МОДЕЛЬ ДЛЯ ПЕРЕВОДА ЭКРАНА")
    Section {
        ModelChoice(panel, ai, AiRole.TRANSLATE, models, tokens) { picking = AiRole.TRANSLATE }
        Spacer(Modifier.height(16.dp))
        Text("Рассуждение при переводе", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        EffortPicker(ai.provider, ai.model, ai.effort) { panel.update(ai.copy(effort = it)) }
        if (ai.effort != AiEffort.MINIMAL && AiReasoning.levels(ai.provider, ai.model).isNotEmpty())
            Text("Экран будет появляться заметно дольше. Для перевода обычно хватает минимальной.",
                color = Warn, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }

    Heading("МОДЕЛЬ ДЛЯ ЗАПОЛНЕНИЯ ГЛОССАРИЯ")
    Section {
        ModelChoice(panel, ai, AiRole.RESEARCH, models, tokens) { picking = AiRole.RESEARCH }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Веб-поиск", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("Искать официальную локализацию и вики игры", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = ai.researchSearch, onCheckedChange = { panel.update(ai.copy(researchSearch = it)) })
        }
        Spacer(Modifier.height(16.dp))
        Text("Степень рассуждения", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        EffortPicker(ai.researchProvider, ai.researchModel, ai.researchEffort) {
            panel.update(ai.copy(researchEffort = it))
        }
        Spacer(Modifier.height(10.dp))
        Text("Эта модель работает только на странице «Заполнить профиль с ИИ» во вкладке «Игры»: один долгий запрос по вашей команде, а не на каждом экране. Но это самый дорогой запрос в приложении: с веб-поиском модель делает несколько поисковых вызовов и читает найденные страницы целиком.",
            color = Muted, fontSize = 11.sp)
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
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Контекст игры", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("Название, заметки и найденные на экране термины глоссария", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = ai.context, onCheckedChange = { panel.update(ai.copy(context = it)) })
        }
        Spacer(Modifier.height(8.dp))
        Text(when {
            !app.gameDetection -> "Определение игры выключено на вкладке «Игры» — контекст не передаётся."
            ai.context -> "Работает для игр с включённым профилем."
            else -> "Выключено: модель получает только текст экрана и языки."
        }, color = Muted, fontSize = 11.sp)
    }

    Heading("СИСТЕМНЫЙ ПРОМПТ")
    Section {
        Picker(AiPrompts.builtIn, AiPrompts.byId(ai.prompt), { it.title }, { it.description }) {
            panel.update(ai.copy(prompt = it.id))
        }
    }

    Heading("ПРИВАТНОСТЬ ИИ-РЕЖИМА")
    Section {
        Text("Что уходит в ${ai.provider.label}", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Снимок экрана не покидает устройство и в ИИ-режиме — отправляется только распознанный текст, номера блоков и языки. Но текста уходит больше, чем обычному переводчику: весь распознанный экран пакетами и системный промпт.",
            color = Muted, fontSize = 13.sp)
        if (ai.context && app.gameDetection) {
            Spacer(Modifier.height(8.dp))
            Text("С контекстом игры к этому добавляются её название и package, ваши заметки о ней и те термины глоссария, что найдены на экране.",
                color = Muted, fontSize = 13.sp)
        }
        if (ai.researchProvider != ai.provider) {
            Spacer(Modifier.height(8.dp))
            Text("Заполнение глоссария обращается к другому провайдеру — ${ai.researchProvider.label} — и только когда вы сами его запускаете.",
                color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text("Ключи хранятся зашифрованными на этом устройстве, по одному на провайдера, и не попадают в резервные копии Android.",
            color = Muted, fontSize = 11.sp)
    }

    picking?.let { role ->
        ModelDialog(models[ai.providerFor(role)].orEmpty(), ai.modelFor(role),
            screen = role == AiRole.TRANSLATE,
            onPick = { panel.update(ai.withModel(role, it)); picking = null }) { picking = null }
    }

    // Choosing here aims the check and nothing else: no role's model changes.
    if (probing) ModelDialog(probeList, panel.probeModel, screen = false,
        onPick = { panel.selectProbeModel(it); probing = false }) { probing = false }
}

/** Provider and model of one role, with the one line that says whether it can actually be used. */
@Composable
private fun ModelChoice(panel: AiPanel, ai: AiSettings, role: AiRole,
                        models: Map<AiProvider, List<AiModelInfo>>, tokens: Set<AiProvider>,
                        onPick: () -> Unit) {
    val provider = ai.providerFor(role)
    val model = ai.modelFor(role)
    val list = models[provider].orEmpty()
    Options(AiProvider.entries, provider, { it.short }) { panel.update(ai.withProvider(role, it)) }
    Spacer(Modifier.height(10.dp))
    ChoiceRow("Модель", model.ifBlank { "Не выбрана" }) { if (list.isNotEmpty()) onPick() }
    Spacer(Modifier.height(6.dp))
    val available = list.any { it.id == model }
    Text(when {
        provider !in tokens -> "Ключ ${provider.label} не задан — сохраните его выше."
        list.isEmpty() -> "Список моделей пуст. Выберите ${provider.short} выше и нажмите «Обновить модели»."
        model.isBlank() -> "Выберите модель из списка."
        available -> "✓ доступна"
        else -> "! модель больше недоступна. Выберите другую — сама она не сменится."
    }, color = when {
        available -> Mint
        provider !in tokens -> Warn
        list.isEmpty() || model.isBlank() -> Muted
        else -> Warn
    }, fontSize = 12.sp)
    // What the chosen model costs, where the choice was made rather than only in the picker.
    val chosen = list.firstOrNull { it.id == model }
    val tier = chosen?.let { AiPricing.tier(it) }
    chosen?.let { AiPricing.summary(it, role == AiRole.TRANSLATE) }?.let {
        Text(it, color = if (tier == PriceTier.DANGEROUS) Warn else Muted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
    if (tier == PriceTier.DANGEROUS || tier == PriceTier.EXPENSIVE)
        Text(if (role == AiRole.TRANSLATE) "Столько стоит примерно каждое нажатие плавающей кнопки."
            else "Заполнение глоссария — запрос намного длиннее экрана, а с веб-поиском и страницы из поиска тоже оплачиваются.",
            color = Warn, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}

/**
 * The model list — the same for every provider, because a list of four and a list of four hundred
 * are the same problem once one of them is OpenRouter's. Search is always there rather than
 * appearing past some size, so the tab works the same way whichever provider is chosen.
 *
 * A model dear enough to matter is confirmed rather than just chosen: one tap of the floating
 * button then costs real money, and the price list is the only place that says so.
 */
@Composable
private fun ModelDialog(models: List<AiModelInfo>, selected: String, screen: Boolean,
                        onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf<AiModelInfo?>(null) }
    val matches = ModelSearch.apply(models, query)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Модель") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Поиск среди ${models.size}") },
                    placeholder = { Text("gem, claude sonnet, gpt…", fontSize = 13.sp) },
                    trailingIcon = {
                        if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕", color = Muted) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, focusedLabelColor = Mint))
                Spacer(Modifier.height(10.dp))
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    // A model can exist before the provider's listing admits it. What is typed here
                    // goes into the request as it stands; the provider is the one that decides.
                    val typed = query.trim().takeIf {
                        ModelSearch.looksLikeModelId(it) && matches.none { model -> model.id == it }
                    }
                    typed?.let { id ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(id) }.padding(vertical = 10.dp, horizontal = 2.dp)) {
                            Text("Использовать «$id»", fontSize = 15.sp, color = Mint)
                            Text("Модели нет в списке — id уйдёт в запрос как есть. Для только что вышедших.",
                                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = .07f))
                    }
                    matches.forEach { model ->
                        ModelRow(model, model.id == selected, screen) {
                            if (AiPricing.warns(model)) confirming = model else onPick(model.id)
                        }
                    }
                    if (matches.isEmpty() && typed == null) Text(
                        if (models.isEmpty()) "Список пуст. Нажмите «Обновить модели»." else "Ничего не найдено. Введите точный id, чтобы взять модель вручную.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })

    confirming?.let { model ->
        AlertDialog(onDismissRequest = { confirming = null },
            title = { Text("Очень дорогая модель") },
            text = {
                Column {
                    Text(model.id, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    AiPricing.summary(model, screen)?.let { Text(it, color = Warn, fontSize = 13.sp) }
                    Spacer(Modifier.height(10.dp))
                    Text(if (screen) "Столько будет стоить примерно каждое нажатие плавающей кнопки. С включённым рассуждением — заметно больше: размышления оплачиваются как ответ."
                        else "Заполнение глоссария — один длинный запрос на игру. С веб-поиском и высокой степенью рассуждения он стоит как десятки экранов.",
                        color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { onPick(model.id); confirming = null }) { Text("Всё равно выбрать", color = Warn) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Отмена") } })
    }
}

/** One row of the list: what the model is called, how much context it has and what it costs. */
@Composable
private fun ModelRow(model: AiModelInfo, chosen: Boolean, screen: Boolean, onClick: () -> Unit) {
    val tier = AiPricing.tier(model)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
        .padding(vertical = 10.dp, horizontal = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.id, fontSize = 15.sp, color = if (chosen) Mint else Color.White,
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f, fill = false))
            if (tier != PriceTier.UNKNOWN && tier != PriceTier.MODERATE) {
                Spacer(Modifier.width(8.dp))
                PriceBadge(tier)
            }
        }
        AiPricing.summary(model, screen)?.let {
            Text(it, color = if (tier == PriceTier.DANGEROUS) Warn else Muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
        model.maxPromptLength?.let {
            Text("контекст до $it токенов", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PriceBadge(tier: PriceTier) {
    val color = when (tier) {
        PriceTier.FREE, PriceTier.CHEAP -> Mint
        PriceTier.DANGEROUS -> Color(0xFFFF9B9B)
        PriceTier.EXPENSIVE -> Warn
        else -> Muted
    }
    Text(tier.label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .12f))
            .padding(horizontal = 7.dp, vertical = 3.dp))
}

/**
 * A key on the clipboard is still a secret: the sensitive flag keeps Android 13+ from putting it in
 * the paste preview and the clipboard history. Older systems ignore the extra, so no branch.
 */
private fun tokenClip(value: String): ClipEntry {
    val clip = ClipData.newPlainText("API key", value)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    return ClipEntry(clip)
}

@Composable
internal fun <T> Picker(items: List<T>, selected: T, label: (T) -> String,
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
