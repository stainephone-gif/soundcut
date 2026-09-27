package com.soundcut.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundcut.app.MainViewModel
import com.soundcut.app.Stage
import com.soundcut.app.UiState
import com.soundcut.core.Segment
import com.soundcut.core.SegmentKind
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (state.stage) {
        Stage.SETUP -> SetupScreen(state, vm)
        Stage.PROCESSING -> ProgressScreen(state, onCancel = vm::cancel)
        Stage.REVIEW, Stage.SAVING -> ReviewScreen(state, vm)
    }
}

// ---------------------------------------------------------------- Выбор файлов

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(state: UiState, vm: MainViewModel) {
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickAudio(uri)
    }
    val pickScript = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickScript(uri)
    }
    val o = state.options

    Scaffold(topBar = { TopAppBar(title = { Text("SoundCut") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Приложение приглушает дыхание диктора и вырезает переговоры и неудачные дубли. " +
                    "Всё работает на телефоне, без интернета.",
                style = MaterialTheme.typography.bodyMedium,
            )

            FileCard(
                title = "1. Запись (WAV)",
                fileName = state.audio?.name,
                hint = "Выберите WAV-файл с записью диктора",
                icon = { Icon(Icons.Default.AudioFile, null) },
                onPick = { pickAudio.launch(arrayOf("audio/*")) },
                onClear = null,
            )
            FileCard(
                title = "2. Текст диктора (необязательно)",
                fileName = state.script?.name,
                hint = "Файл .txt или .docx с текстом, который читает диктор. С ним дубли и переговоры " +
                    "находятся намного точнее. Без текста ищутся повторы фраз и служебные слова («стоп», «ещё раз»).",
                icon = { Icon(Icons.Default.Description, null) },
                onPick = {
                    pickScript.launch(
                        arrayOf(
                            "text/plain",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/octet-stream",
                        ),
                    )
                },
                onClear = if (state.script != null) ({ vm.pickScript(null) }) else null,
            )

            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("3. Что искать", style = MaterialTheme.typography.titleMedium)
                    SwitchRow("Дыхание", o.detectBreaths) { v -> vm.updateOptions { it.copy(detectBreaths = v) } }
                    if (o.detectBreaths) {
                        Text("Приглушать на ${o.breathAttenuationDb.roundToInt()} дБ", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = o.breathAttenuationDb.toFloat(),
                            onValueChange = { v -> vm.updateOptions { it.copy(breathAttenuationDb = v.roundToInt().toDouble()) } },
                            valueRange = 6f..40f,
                        )
                        Text(
                            "Чувствительность: " + when {
                                o.breathSensitivity < 0.34 -> "только явное дыхание"
                                o.breathSensitivity < 0.67 -> "средняя"
                                else -> "высокая (больше находок, возможны ошибки)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = o.breathSensitivity.toFloat(),
                            onValueChange = { v -> vm.updateOptions { it.copy(breathSensitivity = v.toDouble()) } },
                        )
                    }
                    SwitchRow("Переговоры", o.detectTalk) { v -> vm.updateOptions { it.copy(detectTalk = v) } }
                    SwitchRow("Повторно прочитанные фрагменты (дубли)", o.detectRetakes) { v ->
                        vm.updateOptions { it.copy(detectRetakes = v) }
                    }
                }
            }

            Button(
                onClick = vm::process,
                enabled = state.audio != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Обработать") }
        }
    }
    state.error?.let { ErrorDialog(it, vm::dismissMessage) }
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
            OutlinedButton(onClick = onPick) { Text(if (fileName == null) "Выбрать файл" else "Выбрать другой") }
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
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.stepText, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("${(state.progress * 100).roundToInt()} %")
            Spacer(Modifier.height(8.dp))
            Text(
                "Распознавание 20-минутной записи на телефоне занимает несколько минут.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onCancel) { Text("Отменить") }
        }
    }
}

// ---------------------------------------------------------------- Проверка

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(state: UiState, vm: MainViewModel) {
    var filter by rememberSaveable { mutableStateOf<SegmentKind?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmBack by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) vm.save(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }
    BackHandler { confirmBack = true }

    val visible = state.segments.withIndex().filter { filter == null || it.value.kind == filter }
    val cutSeconds = state.segments.filter { it.enabled && it.kind.isCut }.sumOf { it.duration }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.audio?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { confirmBack = true }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                Text(
                    "Длительность: ${formatTime(state.durationSec)} → ${formatTime(state.durationSec - cutSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { saveLauncher.launch(vm.suggestedOutputName()) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = state.stage == Stage.REVIEW,
                ) {
                    Icon(Icons.Default.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Сохранить WAV")
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Waveform(
                state = state,
                onTapTime = { t ->
                    val idx = state.segments.indices.minByOrNull { i ->
                        val s = state.segments[i]
                        if (t in s.start..s.end) 0.0 else minOf(kotlin.math.abs(t - s.start), kotlin.math.abs(t - s.end))
                    } ?: return@Waveform
                    filter = null
                    scope.launch { listState.animateScrollToItem(idx) }
                },
                modifier = Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Все ${state.segments.size}") })
                for (k in SegmentKind.entries) {
                    val n = state.segments.count { it.kind == k }
                    if (n > 0) FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text("${k.title()} $n") })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.setAll(filter, true) }) { Text("Отметить все") }
                TextButton(onClick = { vm.setAll(filter, false) }) { Text("Снять все") }
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
            } else LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(visible, key = { _, iv -> iv.index }) { _, iv ->
                    SegmentRow(
                        segment = iv.value,
                        playing = state.playback?.takeIf { it.segmentIndex == iv.index },
                        onToggle = { vm.toggle(iv.index) },
                        onPlay = { processed -> vm.play(iv.index, processed) },
                        onStop = vm::stopPlayback,
                    )
                    HorizontalDivider()
                }
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
            confirmButton = { TextButton(onClick = { confirmBack = false; vm.backToSetup() }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { confirmBack = false }) { Text("Нет") } },
        )
    }
    state.error?.let { ErrorDialog(it, vm::dismissMessage) }
}

@Composable
private fun SegmentRow(
    segment: Segment,
    playing: com.soundcut.app.Playback?,
    onToggle: () -> Unit,
    onPlay: (processed: Boolean) -> Unit,
    onStop: () -> Unit,
) {
    val color = segment.kind.color()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = segment.enabled, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    segment.kind.title() + if (segment.kind == SegmentKind.BREATH) " — приглушить" else " — вырезать",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (segment.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                "${formatTime(segment.start)} – ${formatTime(segment.end)}  (${"%.1f".format(segment.duration)} с)",
                style = MaterialTheme.typography.bodySmall,
            )
            if (segment.kind != SegmentKind.BREATH && segment.text.isNotBlank()) {
                Text(
                    "«${segment.text}»",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (playing != null) {
            IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Стоп") }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                PlayButton("Было") { onPlay(false) }
                PlayButton("Будет") { onPlay(true) }
            }
        }
    }
}

@Composable
private fun PlayButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
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
            val c = seg.kind.color()
            if (seg.enabled) {
                drawRect(c.copy(alpha = 0.35f), Offset(x0, 0f), Size(x1 - x0, h))
            } else {
                drawRect(Color.Gray.copy(alpha = 0.5f), Offset(x0, 0f), Size(x1 - x0, h), style = Stroke(1f))
            }
        }
        val step = w / wave.size
        for (i in wave.indices) {
            val a = wave[i] * h / 2
            drawLine(waveColor, Offset(i * step, h / 2 - a), Offset(i * step, h / 2 + a), strokeWidth = maxOf(1f, step))
        }
        state.playback?.let { p ->
            val t = if (p.processed) null else p.windowStart + p.position
            if (t != null) {
                val x = (t / duration * w).toFloat()
                drawLine(playColor, Offset(x, 0f), Offset(x, h), strokeWidth = 3f)
            }
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
