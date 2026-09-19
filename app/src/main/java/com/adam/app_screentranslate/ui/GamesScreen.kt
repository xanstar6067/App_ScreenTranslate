package com.adam.app_screentranslate.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adam.app_screentranslate.data.AiConfigManager
import com.adam.app_screentranslate.data.GameStore
import com.adam.app_screentranslate.game.ForegroundApp
import com.adam.app_screentranslate.game.InstalledApp
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.translation.ai.AiEngine
import com.adam.app_screentranslate.translation.ai.AiFormatException
import com.adam.app_screentranslate.translation.ai.AiHttpException
import com.adam.app_screentranslate.translation.ai.AiPrompts
import com.adam.app_screentranslate.translation.ai.AiTranslator
import com.adam.app_screentranslate.translation.ai.GameResearch
import com.adam.app_screentranslate.translation.ai.GameResearcher
import com.adam.app_screentranslate.translation.ai.ResearchProposal
import com.adam.app_screentranslate.translation.ai.ResearchRequest
import com.adam.app_screentranslate.translation.ai.ResearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date

internal val Warn = Color(0xFFFFD39B)

/** The AI fill of one game's profile. It belongs to the panel, so leaving the tab does not lose it. */
sealed interface ResearchState {
    val pkg: String
    data class Running(override val pkg: String, val started: Long, val search: Boolean) : ResearchState
    data class Failed(override val pkg: String, val message: String) : ResearchState
    data class Done(override val pkg: String, val result: ResearchResult, val proposals: List<ResearchProposal>) : ResearchState
}

/**
 * State behind the games tab. Writes run in the activity's scope so that leaving the tab mid-write
 * does not cancel them; what is on screen is reloaded after each one through [revision].
 */
class GamesPanel(private val store: GameStore, private val context: Context, private val scope: CoroutineScope,
                 private val ai: AiConfigManager, private val engine: (AiProvider) -> AiEngine) {
    val games get() = store.games
    val terms get() = store.terms
    val aiSettings get() = ai.settings
    val hasToken get() = ai.hasToken
    val models get() = ai.models

    /** One research at a time: it can run for minutes and costs the user's own quota. */
    var research by mutableStateOf<ResearchState?>(null)
        private set
    private var researchJob: Job? = null

    /** Bumped after every glossary write so an open game reloads its entries. */
    var revision by mutableIntStateOf(0)
        private set
    /** Launchable apps for the add dialog; null while they are being read. */
    var installed by mutableStateOf<List<InstalledApp>?>(null)
        private set

    fun refresh() { scope.launch { store.refresh() } }
    suspend fun glossary(pkg: String) = store.glossary(pkg)
    fun update(profile: GameProfile) { scope.launch { store.update(profile) } }
    fun delete(pkg: String) { scope.launch { store.delete(pkg) } }

    fun loadInstalled() {
        installed = null
        scope.launch { installed = withContext(Dispatchers.IO) { ForegroundApp.launchable(context) } }
    }

    fun add(pkg: String, label: String, then: () -> Unit) {
        scope.launch { store.add(pkg, label); then() }
    }

    fun saveEntry(pkg: String, entry: GlossaryEntry, done: (Boolean) -> Unit) {
        scope.launch {
            val saved = store.save(pkg, entry)
            if (saved) revision++
            done(saved)
        }
    }

    fun removeEntry(id: Long) { scope.launch { store.remove(id); revision++ } }

    fun updateAi(value: AiSettings) = ai.update(value)

    fun startResearch(game: GameProfile, app: AppSettings, kinds: Set<TermKind>, notes: Boolean, limit: Int, focus: String) {
        if (researchJob?.isActive == true) return
        val settings = ai.settings.value
        research = ResearchState.Running(game.packageName, System.currentTimeMillis(), settings.researchSearch)
        researchJob = scope.launch {
            research = try {
                val known = store.glossary(game.packageName)
                val languages = app.forGame(game)
                val source = if (languages.source == "auto") "the game's original language, as shown on screen"
                    else AiTranslator.language(languages.source)
                val request = ResearchRequest(game, known, source, AiTranslator.language(languages.target),
                    kinds, notes, limit, focus)
                val result = withContext(Dispatchers.IO) {
                    GameResearcher(engine(settings.provider)).research(request, settings, ai.token())
                }
                ResearchState.Done(game.packageName, result, GameResearch.compare(result.terms, known))
            } catch (e: CancellationException) { research = null; throw e }
            catch (e: AiHttpException) { ResearchState.Failed(game.packageName, if (e.status > 0) "${e.status}: ${e.reason}" else e.reason) }
            catch (e: AiFormatException) { ResearchState.Failed(game.packageName, "Модель ответила не по формату: ${e.reason}") }
            catch (_: IOException) { ResearchState.Failed(game.packageName, "Сеть недоступна или сервер не ответил вовремя.") }
            // The class name alone: an exception message can carry what was being sent.
            catch (e: Exception) { ResearchState.Failed(game.packageName, "Сбой: ${e.javaClass.simpleName}") }
        }
    }

    fun cancelResearch() { researchJob?.cancel(); research = null }

    fun dismissResearch() { if (researchJob?.isActive != true) research = null }

    /**
     * Writes what the user kept. [notes] and [title] are null when they chose to leave them as
     * they are. The profile is re-read from the list, so an edit made meanwhile is not undone.
     */
    fun applyResearch(pkg: String, entries: List<GlossaryEntry>, notes: String?, title: String?, done: (Int) -> Unit) {
        scope.launch {
            val written = store.saveAll(pkg, entries)
            val current = store.games.value.firstOrNull { it.packageName == pkg }
            if (current != null && (notes != null || title != null))
                store.update(current.copy(notes = notes ?: current.notes, customName = title ?: current.customName))
            revision++
            research = null
            done(written)
        }
    }
}

@Composable
fun GamesTab(panel: GamesPanel, settings: AppSettings, onSettings: (AppSettings) -> Unit,
             usageAccess: Boolean, onUsageAccess: () -> Unit) {
    val games by panel.games.collectAsState()
    val terms by panel.terms.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var researching by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { panel.refresh() }
    val open = games.firstOrNull { it.packageName == selected }
    if (open != null && researching) ResearchPage(panel, open, settings) { researching = false }
    else if (open != null) GameDetail(panel, open, onResearch = { researching = true }) { selected = null }
    else GameList(panel, games, terms, settings, onSettings, usageAccess, onUsageAccess) { selected = it }
}

@Composable
private fun GameList(panel: GamesPanel, games: List<GameProfile>, terms: Map<String, Int>,
                     settings: AppSettings, onSettings: (AppSettings) -> Unit,
                     usageAccess: Boolean, onUsageAccess: () -> Unit, onOpen: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }

    Text("Игры", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text("Название, глоссарий и заметки для ИИ-перевода", color = Muted, fontSize = 13.sp,
        modifier = Modifier.padding(top = 6.dp))

    Heading("ОПРЕДЕЛЕНИЕ ИГРЫ")
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Определять игру", fontWeight = FontWeight.Medium)
                Text("Узнавать приложение на экране в момент перевода", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = settings.gameDetection, onCheckedChange = {
                onSettings(settings.copy(gameDetection = it))
                // Switching on without access would do nothing; take the user straight to it.
                if (it && !usageAccess) onUsageAccess()
            })
        }
        Spacer(Modifier.height(10.dp))
        if (usageAccess) Text("✓ Доступ к статистике использования выдан", color = Mint, fontSize = 12.sp)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Нужен доступ к статистике использования", color = Warn, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onUsageAccess, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("Выдать", color = Mint, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Lenslate смотрит только, какое приложение открыто в момент нажатия кнопки. Историю использования не сохраняет. Пока переключатель выключен, статистика не читается совсем.",
            color = Muted, fontSize = 11.sp)
    }

    Heading("ИГРЫ")
    Section {
        if (games.isEmpty()) Text("Пока пусто. Включите определение и переведите экран в игре — профиль появится сам. Или добавьте игру вручную.",
            color = Muted, fontSize = 13.sp)
        games.forEachIndexed { index, game ->
            if (index > 0) HorizontalDivider(color = Color.White.copy(alpha = .07f))
            GameRow(game, terms[game.packageName] ?: 0) { onOpen(game.packageName) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { adding = true; panel.loadInstalled() }, modifier = Modifier.fillMaxWidth()) {
            Text("Добавить игру", color = Mint)
        }
    }

    Heading("КАК ЭТО РАБОТАЕТ")
    Section {
        Text("Язык оригинала и перевода из профиля действуют в любом режиме.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text("Название, заметки и глоссарий уходят только в ИИ-режиме и только при включённом «Контексте игры» на вкладке ИИ.",
            color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text("Из глоссария в запрос попадают только термины, найденные на текущем экране.", color = Muted, fontSize = 12.sp)
    }

    if (adding) AddGameDialog(panel, games, onDismiss = { adding = false }) { pkg -> adding = false; onOpen(pkg) }
}

@Composable
private fun GameRow(game: GameProfile, terms: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(game.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(game.packageName, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(3.dp))
            Text(buildString {
                append(if (game.enabled) game.origin.label else "Отключена")
                append("  ·  в глоссарии: ").append(terms)
            }, color = if (game.enabled) Muted else Warn, fontSize = 11.sp)
        }
        Text("›", color = Mint, fontSize = 22.sp)
    }
}

@Composable
private fun GameDetail(panel: GamesPanel, game: GameProfile, onResearch: () -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable(game.packageName) { mutableStateOf(game.customName) }
    var notes by rememberSaveable(game.packageName) { mutableStateOf(game.notes) }
    var glossary by remember(game.packageName) { mutableStateOf<List<GlossaryEntry>>(emptyList()) }
    var editing by remember { mutableStateOf<GlossaryEntry?>(null) }
    var chooser by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(game.packageName, panel.revision) { glossary = panel.glossary(game.packageName) }

    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← Все игры", color = Mint) }
    Text(game.title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text(game.packageName, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(18.dp))
    ResearchCard(panel.research?.takeIf { it.pkg == game.packageName }, onResearch)

    Heading("ПРОФИЛЬ")
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Учитывать эту игру", fontWeight = FontWeight.Medium)
                Text(if (game.enabled) "Язык, стиль и контекст применяются при переводе"
                    else "Отключена: экран переводится так, будто профиля нет", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = game.enabled, onCheckedChange = { panel.update(game.copy(enabled = it)) })
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Своё название") }, placeholder = { Text(game.label.ifBlank { game.packageName }) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, focusedLabelColor = Mint))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = notes, onValueChange = { notes = it }, minLines = 3, maxLines = 8,
            label = { Text("Заметки для ИИ") }, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Сеттинг, тон, кто к кому как обращается. Уходят в запрос как есть.") },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, focusedLabelColor = Mint))
        Spacer(Modifier.height(6.dp))
        Button(onClick = { panel.update(game.copy(customName = name.trim(), notes = notes.trim())) },
            enabled = name.trim() != game.customName || notes.trim() != game.notes, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
            Text("Сохранить")
        }
        Spacer(Modifier.height(10.dp))
        Text(buildString {
            append(game.origin.label).append(' ').append(date(game.firstSeen))
            if (game.lastUsed > 0) append("  ·  последний перевод ").append(date(game.lastUsed))
        }, color = Muted, fontSize = 11.sp)
    }

    Heading("ЯЗЫКИ И СТИЛЬ")
    Section {
        ChoiceRow("Язык оригинала", game.source?.let { Languages.sources[it] ?: it } ?: "Как в общих настройках") { chooser = "source" }
        HorizontalDivider(color = Color.White.copy(alpha = .07f), modifier = Modifier.padding(vertical = 8.dp))
        ChoiceRow("Переводить на", game.target?.let { Languages.targets[it] ?: it } ?: "Как в общих настройках") { chooser = "target" }
        Spacer(Modifier.height(14.dp))
        Text("Стиль ИИ-перевода", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Picker(listOf<String?>(null) + AiPrompts.builtIn.map { it.id }, game.prompt,
            { id -> id?.let { AiPrompts.byId(it).title } ?: "Как на вкладке ИИ" },
            { id -> id?.let { AiPrompts.byId(it).description } }) { panel.update(game.copy(prompt = it)) }
    }

    Heading("ГЛОССАРИЙ")
    Section {
        Text("Термины переводятся строго так, как указано здесь. В запрос уходят только те, что найдены на экране.",
            color = Muted, fontSize = 12.sp)
        TermKind.entries.forEach { kind ->
            val items = glossary.filter { it.kind == kind }
            if (items.isNotEmpty()) {
                Text(kind.label.uppercase(), fontSize = 10.sp, letterSpacing = 1.2.sp, color = Muted,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                items.forEach { entry ->
                    Row(Modifier.fillMaxWidth().clickable { editing = entry }.padding(vertical = 8.dp)) {
                        Text(entry.term, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(if (entry.keep) "не переводить" else entry.translation, fontSize = 14.sp,
                            color = if (entry.keep) Muted else Mint)
                    }
                }
            }
        }
        if (glossary.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Пусто. Например: Rapture → Рапчер, NIKKE → не переводить. Или заполните его с ИИ — кнопка вверху.",
                color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { editing = GlossaryEntry("", "") }, modifier = Modifier.fillMaxWidth()) {
            Text("Добавить запись", color = Mint)
        }
    }

    Heading("УДАЛЕНИЕ")
    Section {
        TextButton(onClick = { deleting = true }, contentPadding = PaddingValues(0.dp)) {
            Text("Удалить игру и её глоссарий", color = Warn)
        }
        Text("Пока определение включено, игра появится снова при следующем переводе в ней. Чтобы Lenslate её не учитывал, выключите «Учитывать эту игру».",
            color = Muted, fontSize = 11.sp)
    }

    chooser?.let { kind ->
        val languages = if (kind == "source") Languages.sources else Languages.targets
        val choices: Map<String?, String> = mapOf<String?, String>(null to "Как в общих настройках") + languages
        AlertDialog(onDismissRequest = { chooser = null },
            title = { Text(if (kind == "source") "Язык оригинала" else "Переводить на") },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { (key, label) ->
                        Text(label, Modifier.fillMaxWidth().clickable {
                            panel.update(if (kind == "source") game.copy(source = key) else game.copy(target = key))
                            chooser = null
                        }.padding(vertical = 15.dp))
                    }
                }
            }, confirmButton = { TextButton(onClick = { chooser = null }) { Text("Закрыть") } })
    }

    editing?.let { entry ->
        EntryDialog(entry,
            onSave = { changed, rejected -> panel.saveEntry(game.packageName, changed) { ok -> if (ok) editing = null else rejected() } },
            onDelete = if (entry.id != 0L) ({ panel.removeEntry(entry.id); editing = null }) else null,
            onDismiss = { editing = null })
    }

    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text("Удалить ${game.title}?") },
        text = { Text("Профиль и весь глоссарий этой игры будут удалены с устройства.") },
        confirmButton = { TextButton(onClick = { deleting = false; panel.delete(game.packageName); onBack() }) {
            Text("Удалить", color = Warn)
        } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } })
}

@Composable
internal fun EntryDialog(entry: GlossaryEntry, onSave: (GlossaryEntry, () -> Unit) -> Unit,
                        onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var term by remember { mutableStateOf(entry.term) }
    var translation by remember { mutableStateOf(entry.translation) }
    var kind by remember { mutableStateOf(entry.kind) }
    var keep by remember { mutableStateOf(entry.keep) }
    var duplicate by remember { mutableStateOf(false) }
    val valid = term.isNotBlank() && (keep || translation.isNotBlank())
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (entry.id == 0L) "Новая запись" else "Запись глоссария") },
        text = {
            Column {
                OutlinedTextField(value = term, onValueChange = { term = it; duplicate = false }, singleLine = true,
                    label = { Text("Оригинал") }, isError = duplicate, modifier = Modifier.fillMaxWidth(),
                    supportingText = if (duplicate) ({ Text("Такой термин уже есть") }) else null)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = if (keep) term else translation, onValueChange = { translation = it },
                    singleLine = true, enabled = !keep, label = { Text("Перевод") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keep, onCheckedChange = { keep = it })
                    Text("Не переводить", fontSize = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
                Options(TermKind.entries, kind, { it.single }) { kind = it }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(entry.copy(term = term.trim(), translation = if (keep) term.trim() else translation.trim(),
                    kind = kind, keep = keep)) { duplicate = true }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить", color = Warn) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        })
}

@Composable
private fun AddGameDialog(panel: GamesPanel, known: List<GameProfile>, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val installed = panel.installed
    val existing = known.map { it.packageName }.toSet()
    val q = query.trim()
    val matches = installed.orEmpty().filter {
        it.packageName !in existing && (q.isEmpty() || it.label.contains(q, true) || it.packageName.contains(q, true))
    }
    // A package typed by hand, for a game that is not installed yet or has no launcher icon.
    val typed = q.takeIf { looksLikePackage(it) && it !in existing && matches.none { app -> app.packageName == it } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить игру") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Название или package") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (installed == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
                    typed?.let { pkg ->
                        Text("Добавить «$pkg»", color = Mint, modifier = Modifier.fillMaxWidth()
                            .clickable { panel.add(pkg, "") { onAdded(pkg) } }.padding(vertical = 12.dp))
                    }
                    matches.forEach { app ->
                        Column(Modifier.fillMaxWidth().clickable { panel.add(app.packageName, app.label) { onAdded(app.packageName) } }
                            .padding(vertical = 10.dp)) {
                            Text(app.label, fontSize = 15.sp)
                            Text(app.packageName, color = Muted, fontSize = 11.sp)
                        }
                    }
                    if (installed != null && matches.isEmpty() && typed == null)
                        Text("Ничего не найдено", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

private fun looksLikePackage(text: String) =
    text.contains('.') && text.first().isLetter() && text.none { it.isWhitespace() }

private fun date(millis: Long): String =
    if (millis <= 0) "—" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
