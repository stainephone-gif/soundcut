package com.soundcut.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soundcut.core.Segment
import com.soundcut.core.SegmentKind
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.io.File
import javax.swing.JFileChooser
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun App(app: AppState, window: ComposeWindow) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF8EC1F2), secondary = Color(0xFFFF9A7A))
    } else {
        lightColorScheme(primary = Color(0xFF1E5A96), secondary = Color(0xFFE0663F))
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            val state by app.state.collectAsState()
            when (state.stage) {
                Stage.SETUP -> SetupScreen(state, app, window)
                Stage.PROCESSING -> ProgressScreen(state, onCancel = app::cancel)
                Stage.REVIEW, Stage.SAVING -> ReviewScreen(state, app, window)
            }
            state.error?.let { ErrorDialog(it, app::dismissMessage) }
        }
    }
}

// ---------------------------------------------------------------- Диалоги выбора файлов

private fun openFile(window: ComposeWindow, title: String, vararg extensions: String): File? {
    val dialog = FileDialog(window, title, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
    // На Windows фильтр задаётся маской в поле имени файла.
    dialog.file = extensions.joinToString(";") { "*.$it" }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

private fun saveFile(window: ComposeWindow, suggested: File?): File? {
    val dialog = FileDialog(window, "Сохранить результат", FileDialog.SAVE)
    if (suggested != null) {
        dialog.directory = suggested.parent
        dialog.file = suggested.name
    }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val withExt = if (name.endsWith(".wav", ignoreCase = true)) name else "$name.wav"
    return File(dialog.directory, withExt)
}

private fun chooseModel(window: ComposeWindow): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Папка с моделью Vosk или zip-архив модели"
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
    }
    return if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

// ---------------------------------------------------------------- Выбор файлов

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(state: UiState, app: AppState, window: ComposeWindow) {
    val o = state.options
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); app.dismissMessage() }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("SoundCut") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val scroll = rememberScrollState()
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 760.dp)
                    .fillMaxHeight()
                    .verticalScroll(scroll)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Программа приглушает дыхание диктора и вырезает переговоры и неудачные дубли. " +
                        "Работает без интернета. Файлы можно перетащить прямо в окно.",
                    style = MaterialTheme.typography.bodyLarge,
                )

                ModelCard(state, app, window)

                FileCard(
                    title = "1. Запись (WAV)",
                    fileName = state.audio?.absolutePath,
                    hint = "Выберите WAV-файл с записью диктора",
                    icon = { Icon(Icons.Default.AudioFile, null) },
                    onPick = { openFile(window, "Запись диктора", "wav", "wave")?.let(app::pickAudio) },
                    onClear = null,
                )
                FileCard(
                    title = "2. Текст диктора (необязательно)",
                    fileName = state.script?.absolutePath,
                    hint = "Файл .txt или .docx с текстом, который читает диктор. С ним дубли и переговоры " +
                        "находятся намного точнее. Без текста ищутся повторы фраз и служебные слова («стоп», «ещё раз»).",
                    icon = { Icon(Icons.Default.Description, null) },
                    onPick = { openFile(window, "Текст диктора", "txt", "docx")?.let(app::pickScript) },
                    onClear = if (state.script != null) ({ app.pickScript(null) }) else null,
                )

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("3. Что искать", style = MaterialTheme.typography.titleMedium)
                        SwitchRow("Дыхание", o.detectBreaths) { v -> app.updateOptions { it.copy(detectBreaths = v) } }
                        if (o.detectBreaths) {
                            Text("Приглушать на ${o.breathAttenuationDb.roundToInt()} дБ")
                            Slider(
                                value = o.breathAttenuationDb.toFloat(),
                                onValueChange = { v -> app.updateOptions { it.copy(breathAttenuationDb = v.roundToInt().toDouble()) } },
                                valueRange = 6f..40f,
                            )
                            Text(
                                "Чувствительность: " + when {
                                    o.breathSensitivity < 0.34 -> "только явное дыхание"
                                    o.breathSensitivity < 0.67 -> "средняя"
                                    else -> "высокая (больше находок, возможны ошибки)"
                                },
                            )
                            Slider(
                                value = o.breathSensitivity.toFloat(),
                                onValueChange = { v -> app.updateOptions { it.copy(breathSensitivity = v.toDouble()) } },
                            )
                        }
                        SwitchRow("Переговоры", o.detectTalk) { v -> app.updateOptions { it.copy(detectTalk = v) } }
                        SwitchRow("Повторно прочитанные фрагменты (дубли)", o.detectRetakes) { v ->
                            app.updateOptions { it.copy(detectRetakes = v) }
                        }
                    }
                }

                Button(
                    onClick = app::process,
                    enabled = state.audio != null && state.modelDir != null && state.modelBusy == null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Обработать") }
                if (state.modelDir == null) {
                    Text("Сначала установите модель распознавания речи (вверху).", color = MaterialTheme.colorScheme.error)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ModelCard(state: UiState, app: AppState, window: ComposeWindow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, null)
                Spacer(Modifier.width(8.dp))
                Text("Модель распознавания речи", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            val busy = state.modelBusy
            when {
                busy != null -> {
                    Text(busy)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = app::cancel) { Text("Отменить") }
                }
                else -> {
                    val current = state.modelDir
                    Text(
                        if (current != null) "Используется: ${current.name}" else "Модель не установлена",
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Скачивается один раз (нужен интернет), дальше всё работает офлайн. " +
                            "Точная модель заметно лучше распознаёт речь, а значит, точнее находит дубли и переговоры.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in ModelChoice.entries) {
                            val installed = ModelStore.find(c)
                            if (installed != null && installed != current) {
                                OutlinedButton(onClick = { app.useModel(installed) }) { Text("Использовать: ${c.title}") }
                            } else if (installed == null) {
                                OutlinedButton(onClick = { app.downloadModel(c) }) { Text("Скачать: ${c.title}") }
                            }
                        }
                        TextButton(onClick = { chooseModel(window)?.let(app::useModel) }) { Text("Указать папку или архив…") }
                    }
                    for (c in ModelChoice.entries) {
                        Text("${c.title}: ${c.details}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileCard(
    title: String,
    fileName: String?,
    hint: String,
    icon: @Composable () -> Unit,
    onPick: () -> Unit,
    onClear: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (fileName == null) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon()
                    Spacer(Modifier.width(8.dp))
                    Text(fileName, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (onClear != null) IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Убрать") }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPick) { Text(if (fileName == null) "Выбрать файл…" else "Выбрать другой…") }
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- Обработка

@Composable
private fun ProgressScreen(state: UiState, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.stepText, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            if (state.progress < 0f) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("${(state.progress * 100).roundToInt()} %")
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onCancel) { Text("Отменить") }
        }
    }
}

// ---------------------------------------------------------------- Проверка

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(state: UiState, app: AppState, window: ComposeWindow) {
    var filter by remember { mutableStateOf<SegmentKind?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmBack by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); app.dismissMessage() }
    }

    val visible = state.segments.withIndex().filter { filter == null || it.value.kind == filter }
    val cutSeconds = state.segments.filter { it.enabled && it.kind.isCut }.sumOf { it.duration }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.audio?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { confirmBack = true }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    Text(
                        "Длительность: ${formatTime(state.durationSec)} → ${formatTime(state.durationSec - cutSeconds)}",
                        modifier = Modifier.padding(end = 16.dp),
                    )
                    Button(
                        onClick = { saveFile(window, app.suggestedOutput())?.let(app::save) },
                        enabled = state.stage == Stage.REVIEW,
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Сохранить WAV")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Waveform(
                state = state,
                onTapTime = { t ->
                    val idx = state.segments.indices.minByOrNull { i ->
                        val s = state.segments[i]
                        if (t in s.start..s.end) 0.0 else minOf(abs(t - s.start), abs(t - s.end))
                    } ?: return@Waveform
                    filter = null
                    scope.launch { listState.animateScrollToItem(idx) }
                },
                modifier = Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Все ${state.segments.size}") })
                for (k in SegmentKind.entries) {
                    val n = state.segments.count { it.kind == k }
                    if (n > 0) FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text("${k.title()} $n") })
                }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { app.setAll(filter, true) }) { Text("Отметить все") }
                TextButton(onClick = { app.setAll(filter, false) }) { Text("Снять все") }
            }
            if (!state.usedScript) {
                Text(
                    "Текст диктора не загружен — дубли и переговоры найдены по повторам и служебным словам.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            HorizontalDivider()
            if (state.segments.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("Ничего не найдено") }
            } else Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                    itemsIndexed(visible, key = { _, iv -> iv.index }) { _, iv ->
                        SegmentRow(
                            segment = iv.value,
                            playing = state.playback?.segmentIndex == iv.index,
                            onToggle = { app.toggle(iv.index) },
                            onPlay = { processed -> app.play(iv.index, processed) },
                            onStop = app::stopPlayback,
                        )
                        HorizontalDivider()
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }

    if (state.stage == Stage.SAVING) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Сохранение") },
            text = { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()) },
        )
    }
    if (confirmBack) {
        AlertDialog(
            onDismissRequest = { confirmBack = false },
            title = { Text("Вернуться к выбору файла?") },
            text = { Text("Результаты проверки будут потеряны.") },
            confirmButton = { TextButton(onClick = { confirmBack = false; app.backToSetup() }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { confirmBack = false }) { Text("Нет") } },
        )
    }
}

@Composable
private fun SegmentRow(
    segment: Segment,
    playing: Boolean,
    onToggle: () -> Unit,
    onPlay: (processed: Boolean) -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = segment.enabled, onCheckedChange = { onToggle() })
        Box(Modifier.size(10.dp).background(segment.kind.color(), CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(210.dp)) {
            Text(
                segment.kind.title() + if (segment.kind == SegmentKind.BREATH) " — приглушить" else " — вырезать",
                style = MaterialTheme.typography.labelLarge,
                color = if (segment.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
            Text(
                "${formatTime(segment.start)} – ${formatTime(segment.end)}  (${"%.1f".format(segment.duration)} с)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            if (segment.kind != SegmentKind.BREATH && segment.text.isNotBlank()) "«${segment.text}»" else "",
            Modifier.weight(1f).padding(horizontal = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (playing) {
            IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Стоп") }
        } else {
            PlayButton("Было") { onPlay(false) }
            PlayButton("Будет") { onPlay(true) }
        }
    }
}

@Composable
private fun PlayButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
        Text(label)
    }
}

@Composable
private fun Waveform(state: UiState, onTapTime: (Double) -> Unit, modifier: Modifier) {
    val wave = state.waveform
    val duration = state.durationSec
    val waveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val playColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier.pointerInput(duration) {
            detectTapGestures { pos -> if (duration > 0) onTapTime(pos.x / size.width * duration) }
        },
    ) {
        if (wave.isEmpty() || duration <= 0) return@Canvas
        val w = size.width
        val h = size.height
        for (seg in state.segments) {
            val x0 = (seg.start / duration * w).toFloat()
            val x1 = maxOf(x0 + 1.5f, (seg.end / duration * w).toFloat())
            if (seg.enabled) {
                drawRect(seg.kind.color().copy(alpha = 0.35f), Offset(x0, 0f), Size(x1 - x0, h))
            } else {
                drawRect(Color.Gray.copy(alpha = 0.5f), Offset(x0, 0f), Size(x1 - x0, h), style = Stroke(1f))
            }
        }
        val step = w / wave.size
        for (i in wave.indices) {
            val a = wave[i] * h / 2
            drawLine(waveColor, Offset(i * step, h / 2 - a), Offset(i * step, h / 2 + a), strokeWidth = maxOf(1f, step))
        }
        state.playback?.takeIf { !it.processed }?.let { p ->
            val x = ((p.windowStart + p.position) / duration * w).toFloat()
            drawLine(playColor, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
        }
    }
}

@Composable
private fun ErrorDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ошибка") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

private fun SegmentKind.color(): Color = when (this) {
    SegmentKind.BREATH -> Color(0xFF2F8FD8)
    SegmentKind.TALK -> Color(0xFFD83B3B)
    SegmentKind.RETAKE -> Color(0xFFE89B1C)
}

private fun SegmentKind.title(): String = when (this) {
    SegmentKind.BREATH -> "Дыхание"
    SegmentKind.TALK -> "Переговоры"
    SegmentKind.RETAKE -> "Дубль"
}

private fun formatTime(sec: Double): String {
    val total = sec.coerceAtLeast(0.0)
    val m = (total / 60).toInt()
    return "%d:%04.1f".format(m, total - m * 60)
}
