package com.adam.app_screentranslate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.translation.ai.AiReasoning
import com.adam.app_screentranslate.translation.ai.AiResearchProtocol
import com.adam.app_screentranslate.translation.ai.GameResearch
import com.adam.app_screentranslate.translation.ai.ResearchProposal
import com.adam.app_screentranslate.translation.ai.TermBasis
import com.adam.app_screentranslate.translation.ai.TermStatus
import kotlinx.coroutines.delay

private val Sky = Color(0xFFA4C5E8)
private val Hero = Brush.linearGradient(listOf(Color(0xFF235448), Color(0xFF1B343A)))

/** How the proposed notes meet the player's own. */
private enum class NotesMode(val label: String) { APPEND("Дописать"), REPLACE("Заменить"), KEEP("Не менять") }

/** The entry point on a game's profile. It also shows a research that is running or waiting. */
@Composable
internal fun ResearchCard(state: ResearchState?, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Hero).clickable(onClick = onOpen).padding(20.dp)) {
        Text("✦ ИИ-ПОМОЩНИК", color = Mint, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(when (state) {
            is ResearchState.Running -> "Собираю профиль…"
            is ResearchState.Done -> "Профиль собран — проверьте"
            is ResearchState.Failed -> "Не получилось собрать профиль"
            null -> "Заполнить профиль с ИИ"
        }, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(when (state) {
            is ResearchState.Running -> "Модель ищет сведения об игре. Можно вернуться позже — результат дождётся."
            is ResearchState.Done -> "Предложено записей: ${state.proposals.count { it.status != TermStatus.SAME }}. Ничего не сохранено, пока вы не выберете."
            is ResearchState.Failed -> state.message
            null -> "Найдёт персонажей, фракции, локации и термины, предложит заметки для переводчика. Вы выбираете, что сохранить."
        }, color = Color(0xFFC9DDD6), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        if (state is ResearchState.Running) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint, trackColor = Ink.copy(alpha = .4f))
        else Text(when (state) {
            is ResearchState.Done -> "Открыть результат  ›"
            is ResearchState.Failed -> "Попробовать снова  ›"
            else -> "Начать  ›"
        }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Mint).padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

@Composable
internal fun ResearchPage(panel: GamesPanel, game: GameProfile, app: AppSettings, onBack: () -> Unit) {
    val state = panel.research?.takeIf { it.pkg == game.packageName }
    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← К профилю", color = Mint) }
    Text("ИИ-заполнение", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text(game.title, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(18.dp))
    when (state) {
        is ResearchState.Running -> ResearchRunning(panel, state)
        is ResearchState.Done -> ResearchReview(panel, game, state, onBack)
        else -> ResearchSetup(panel, game, app, (state as? ResearchState.Failed)?.message)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResearchSetup(panel: GamesPanel, game: GameProfile, app: AppSettings, failure: String?) {
    val ai by panel.aiSettings.collectAsState()
    val hasToken by panel.hasToken.collectAsState()
    var kinds by remember { mutableStateOf(TermKind.entries.toSet()) }
    var notes by rememberSaveable { mutableStateOf(true) }
    var limit by rememberSaveable { mutableIntStateOf(GameResearch.LIMITS[1]) }
    var focus by rememberSaveable { mutableStateOf("") }
    val ready = hasToken && ai.model.isNotBlank()

    if (failure != null) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Warn.copy(alpha = .1f))
            .border(1.dp, Warn.copy(alpha = .35f), RoundedCornerShape(16.dp)).padding(14.dp)) {
            Text("Прошлая попытка не удалась", color = Warn, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(failure, color = Color.White, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
    }

    Heading("ЧТО ЗАПОЛНИТЬ")
    Section {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Заметки", notes) { notes = !notes }
            TermKind.entries.forEach { kind ->
                Chip(kind.label, kind in kinds) { kinds = if (kind in kinds) kinds - kind else kinds + kind }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Сколько записей глоссария", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Options(GameResearch.LIMITS, limit, { "до $it" }) { limit = it }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = focus, onValueChange = { focus = it.take(300) }, minLines = 2, maxLines = 4,
            label = { Text("Уточнение (необязательно)") },
            placeholder = { Text("Например: персонажи 3-й главы, предметы крафта, как героиня обращается к командиру") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, focusedLabelColor = Mint))
        Spacer(Modifier.height(6.dp))
        Text("Уже записанные термины модель пропустит и предложит новые.", color = Muted, fontSize = 11.sp)
    }

    Heading("МОДЕЛЬ")
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ai.provider.label, color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(ai.model.ifBlank { "Модель не выбрана" }, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = if (ai.model.isBlank()) Warn else Color.White)
            }
            Text("вкладка «ИИ»", color = Muted, fontSize = 10.sp)
        }
        if (!ready) {
            Spacer(Modifier.height(8.dp))
            Text(if (!hasToken) "Сохраните ключ ${ai.provider.label} на вкладке «ИИ»." else "Выберите модель на вкладке «ИИ».",
                color = Warn, fontSize = 12.sp)
        }
        HorizontalDivider(color = Color.White.copy(alpha = .07f), modifier = Modifier.padding(vertical = 14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Веб-поиск", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(if (ai.provider == AiProvider.GEMINI) "Поиск Google внутри Gemini" else "Поиск xAI внутри Grok",
                    color = Muted, fontSize = 11.sp)
            }
            Switch(checked = ai.researchSearch, onCheckedChange = { panel.updateAi(ai.copy(researchSearch = it)) })
        }
        Spacer(Modifier.height(6.dp))
        Text(when {
            !ai.researchSearch -> "Модель ответит по собственным знаниям. Быстрее, но о новых и редких играх она может не знать."
            AiReasoning.searchable(ai.provider, ai.model) ->
                "Найдёт официальную локализацию, вики и страницы магазина и покажет источники. Дольше и дороже обычного запроса."
            else -> "Эта модель, похоже, не умеет искать. Попытка будет, а при отказе ответ придёт без поиска."
        }, color = Muted, fontSize = 11.sp)

        Spacer(Modifier.height(16.dp))
        Text("Степень рассуждения", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        EffortPicker(ai.provider, ai.model, ai.researchEffort) { panel.updateAi(ai.copy(researchEffort = it)) }
    }

    Heading("ЧТО УЙДЁТ В ${ai.provider.label.uppercase()}")
    Section {
        Text("Название и package игры, ваши заметки о ней, языки и список уже записанных терминов — только сами термины, без переводов. Снимок экрана и распознанный текст не отправляются.",
            color = Muted, fontSize = 12.sp)
    }

    Spacer(Modifier.height(20.dp))
    Button(onClick = { panel.startResearch(game, app, kinds, notes, limit, focus) },
        enabled = ready && (notes || kinds.isNotEmpty()), modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
        Text("✦  Заполнить профиль", fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text("Ничего не сохраняется само: сначала вы увидите предложения и выберете нужные.", color = Muted, fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
}

/**
 * Levels for the chosen model. A model that has nothing to adjust says so instead of showing
 * switches that would do nothing.
 */
@Composable
internal fun EffortPicker(provider: AiProvider, model: String, selected: AiEffort, onSelect: (AiEffort) -> Unit) {
    val levels = AiReasoning.levels(provider, model)
    if (levels.isEmpty()) {
        Text("Эта модель не поддерживает настройку рассуждения.", color = Muted, fontSize = 12.sp)
        return
    }
    // A level the model lacks shows as the nearest one it has; the client makes the same step.
    val shown = if (selected in levels) selected else levels.minBy { kotlin.math.abs(it.ordinal - selected.ordinal) }
    Options(levels, shown, { it.label }) { onSelect(it) }
    Spacer(Modifier.height(6.dp))
    Text(shown.hint, color = Muted, fontSize = 11.sp)
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text((if (on) "✓  " else "") + label, color = if (on) Ink else Muted, fontSize = 13.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Mint else Ink)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
private fun ResearchRunning(panel: GamesPanel, state: ResearchState.Running) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.started) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val seconds = ((now - state.started) / 1000).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Hero).padding(22.dp)) {
        Text("✦ РАБОТАЕТ", color = Mint, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint, trackColor = Ink.copy(alpha = .4f))
        Spacer(Modifier.height(16.dp))
        if (state.search) Step("Ищет игру, её вики и официальную локализацию")
        Step("Отбирает имена и термины, которые чаще встречаются на экране")
        Step("Сверяет переводы и пишет заметки для переводчика")
        Spacer(Modifier.height(10.dp))
        Text(if (state.search) "С поиском и высокой степенью рассуждения это может занять пару минут."
            else "Обычно это меньше минуты.", color = Color(0xFFC9DDD6), fontSize = 11.sp)
    }
    Spacer(Modifier.height(14.dp))
    OutlinedButton(onClick = { panel.cancelResearch() }, modifier = Modifier.fillMaxWidth()) {
        Text("Отменить", color = Warn)
    }
    Spacer(Modifier.height(8.dp))
    Text("Можно уйти с этой страницы — результат будет ждать на профиле игры.", color = Muted, fontSize = 11.sp)
}

@Composable
private fun Step(text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("•", color = Mint, modifier = Modifier.width(18.dp))
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun ResearchReview(panel: GamesPanel, game: GameProfile, state: ResearchState.Done, onBack: () -> Unit) {
    val result = state.result
    val offered = state.proposals.withIndex().filter { it.value.status != TermStatus.SAME }
    val same = state.proposals.filter { it.status == TermStatus.SAME }
    // New entries start ticked; one that would overwrite the player's own rendering never does.
    val chosen = remember(state) { mutableStateMapOf<Int, Boolean>().apply { offered.forEach { put(it.index, it.value.status == TermStatus.NEW) } } }
    val edits = remember(state) { mutableStateMapOf<Int, GlossaryEntry>() }
    var editing by remember(state) { mutableStateOf<Int?>(null) }
    var notesMode by remember(state) { mutableStateOf(if (game.notes.isBlank()) NotesMode.REPLACE else NotesMode.APPEND) }
    var notes by remember(state) { mutableStateOf(result.notes) }
    val proposeTitle = result.title.isNotBlank() && !result.title.equals(game.title, ignoreCase = true)
    var useTitle by remember(state) { mutableStateOf(proposeTitle && game.customName.isBlank()) }
    var saving by remember(state) { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    val picked = offered.count { chosen[it.index] == true }
    val notesChange = result.notes.isNotBlank() && notesMode != NotesMode.KEEP && notes.isNotBlank()

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Hero).padding(20.dp)) {
        Text(if (result.found) "✦ ГОТОВО" else "✦ ИГРА НЕ ОПОЗНАНА", color = if (result.found) Mint else Warn,
            fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (result.found) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(offered.count { it.value.status == TermStatus.NEW }, "новых", Modifier.weight(1f))
            Stat(offered.count { it.value.status == TermStatus.CONFLICT }, "иначе, чем у вас", Modifier.weight(1f))
            Stat(same.size, "уже есть", Modifier.weight(1f))
        } else Text("Модель не смогла уверенно определить игру и не стала гадать. Задайте «Своё название» в профиле — точное название из магазина — и попробуйте снова.",
            color = Color.White, fontSize = 13.sp)
        if (result.remarks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            result.remarks.forEach { Text(it, color = Color(0xFFC9DDD6), fontSize = 11.sp) }
        }
    }

    if (proposeTitle) {
        Heading("НАЗВАНИЕ")
        Section {
            Row(Modifier.fillMaxWidth().clickable { useTitle = !useTitle }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useTitle, onCheckedChange = { useTitle = it })
                Column(Modifier.weight(1f)) {
                    Text("Назвать «${result.title}»", fontSize = 14.sp)
                    Text("Сейчас: ${game.title}", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }

    if (result.notes.isNotBlank()) {
        Heading("ЗАМЕТКИ ДЛЯ ИИ")
        Section {
            Options(NotesMode.entries, notesMode, { it.label }) { notesMode = it }
            Spacer(Modifier.height(12.dp))
            if (notesMode != NotesMode.KEEP) {
                OutlinedTextField(value = notes, onValueChange = { notes = it }, minLines = 4, maxLines = 12,
                    label = { Text("Предложение — можно править") }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, focusedLabelColor = Mint))
                if (notesMode == NotesMode.APPEND && game.notes.isNotBlank())
                    Text("Будет добавлено после ваших заметок.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                if (notesMode == NotesMode.REPLACE && game.notes.isNotBlank())
                    Text("Ваши текущие заметки будут заменены.", color = Warn, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            } else Text("Заметки останутся как есть.", color = Muted, fontSize = 12.sp)
        }
    }

    if (offered.isNotEmpty() || same.isNotEmpty()) {
        Heading("ГЛОССАРИЙ")
        Section {
            if (offered.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Выбрано $picked из ${offered.size}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { offered.forEach { chosen[it.index] = true } }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Все", color = Mint, fontSize = 12.sp)
                }
                TextButton(onClick = { offered.forEach { chosen[it.index] = false } }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Ничего", color = Mint, fontSize = 12.sp)
                }
            }
            TermKind.entries.forEach { kind ->
                val items = offered.filter { (edits[it.index] ?: it.value.term.entry).kind == kind }
                if (items.isNotEmpty()) {
                    Text(kind.label.uppercase(), fontSize = 10.sp, letterSpacing = 1.2.sp, color = Muted,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                    items.forEach { (index, proposal) ->
                        ProposalRow(proposal, edits[index], chosen[index] == true,
                            onToggle = { chosen[index] = chosen[index] != true }, onEdit = { editing = index })
                    }
                }
            }
            if (same.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Уже в глоссарии с тем же переводом: " + same.joinToString { it.term.entry.term },
                    color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("Нажмите на запись, чтобы поправить перевод или тип.", color = Muted, fontSize = 11.sp)
        }
    }

    if (result.sources.isNotEmpty()) {
        Heading("ИСТОЧНИКИ")
        Section {
            result.sources.take(12).forEach { source ->
                Row(Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(source.url) } }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(AiResearchProtocol.host(source.url), color = Muted, fontSize = 10.sp, maxLines = 1)
                    }
                    Text("↗", color = Mint, fontSize = 15.sp)
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    val total = picked + (if (notesChange) 1 else 0) + (if (useTitle) 1 else 0)
    Button(onClick = {
        saving = true
        val entries = offered.filter { chosen[it.index] == true }.map { (index, proposal) ->
            val entry = edits[index] ?: proposal.term.entry
            // Overwriting keeps the existing row; everything else is added.
            entry.copy(id = if (proposal.status == TermStatus.CONFLICT) proposal.existing?.id ?: 0 else 0)
        }
        val newNotes = if (!notesChange) null else when (notesMode) {
            NotesMode.APPEND -> GameResearch.appendNotes(game.notes, notes)
            else -> notes.trim()
        }
        panel.applyResearch(game.packageName, entries, newNotes, if (useTitle) result.title else null) { onBack() }
    }, enabled = total > 0 && !saving, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
        Text(if (total > 0) "Сохранить выбранное · $picked" + (if (notesChange || useTitle) " + профиль" else "") else "Нечего сохранять",
            fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { panel.dismissResearch() }, modifier = Modifier.fillMaxWidth()) {
        Text("Новый запрос", color = Mint)
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { panel.dismissResearch(); onBack() }, modifier = Modifier.fillMaxWidth()) {
        Text("Отбросить всё", color = Muted)
    }

    editing?.let { index ->
        val proposal = state.proposals[index]
        EntryDialog(edits[index] ?: proposal.term.entry,
            onSave = { changed, _ -> edits[index] = changed; chosen[index] = true; editing = null },
            onDelete = null,
            onDismiss = { editing = null })
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Ink.copy(alpha = .35f)).padding(vertical = 12.dp, horizontal = 10.dp)) {
        Text(value.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 11.sp, color = Color(0xFFC9DDD6), maxLines = 2)
    }
}

@Composable
private fun ProposalRow(proposal: ResearchProposal, edited: GlossaryEntry?, checked: Boolean,
                        onToggle: () -> Unit, onEdit: () -> Unit) {
    val entry = edited ?: proposal.term.entry
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onEdit).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.term, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                Text("  →  ", color = Muted, fontSize = 13.sp)
                Text(if (entry.keep) "не переводить" else entry.translation, fontSize = 14.sp,
                    color = if (entry.keep) Muted else Mint, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(proposal.term.basis)
                if (edited != null) Text("  изменено", color = Sky, fontSize = 10.sp)
            }
            if (proposal.term.comment.isNotBlank())
                Text(proposal.term.comment, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            if (proposal.status == TermStatus.CONFLICT) proposal.existing?.let {
                Text("У вас: ${if (it.keep) "не переводить" else it.translation}" + (if (checked) " — будет заменено" else ""),
                    color = Warn, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Badge(basis: TermBasis) {
    val color = when (basis) {
        TermBasis.OFFICIAL -> Mint
        TermBasis.COMMUNITY -> Sky
        TermBasis.SUGGESTED -> Muted
    }
    Text(basis.label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .12f)).padding(horizontal = 7.dp, vertical = 3.dp))
}
