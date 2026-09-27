package com.soundcut.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundcut.core.AnalysisDecoder
import com.soundcut.core.Analyzer
import com.soundcut.core.ProcessingOptions
import com.soundcut.core.Renderer
import com.soundcut.core.Segment
import com.soundcut.core.SegmentKind
import com.soundcut.core.TextUtils
import com.soundcut.core.WavInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

enum class Stage { SETUP, PROCESSING, REVIEW, SAVING }

data class PickedFile(val uri: Uri, val name: String)

data class Playback(val segmentIndex: Int, val processed: Boolean, val windowStart: Double, val position: Double)

data class UiState(
    val stage: Stage = Stage.SETUP,
    val audio: PickedFile? = null,
    val script: PickedFile? = null,
    val options: ProcessingOptions = ProcessingOptions(),
    val stepText: String = "",
    val progress: Float = 0f,
    val error: String? = null,
    val message: String? = null,
    val durationSec: Double = 0.0,
    val waveform: FloatArray = FloatArray(0),
    val segments: List<Segment> = emptyList(),
    val usedScript: Boolean = false,
    val playback: Playback? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val speech = SpeechEngine(app)
    private val player = Player(viewModelScope)
    private var playToken = 0

    private var job: Job? = null
    private var inputFile: File? = null
    private var wavInfo: WavInfo? = null
    private var audio16k: ShortArray = ShortArray(0)

    fun pickAudio(uri: Uri) {
        _state.update { it.copy(audio = PickedFile(uri, displayName(uri)), error = null) }
    }

    fun pickScript(uri: Uri?) {
        _state.update { it.copy(script = uri?.let { u -> PickedFile(u, displayName(u)) }, error = null) }
    }

    fun updateOptions(transform: (ProcessingOptions) -> ProcessingOptions) {
        _state.update { it.copy(options = transform(it.options)) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    fun process() {
        val s = _state.value
        val audio = s.audio ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(stage = Stage.PROCESSING, error = null, progress = 0f) }
            try {
                withContext(Dispatchers.Default) { runPipeline(audio, s.script, s.options) }
                _state.update { it.copy(stage = Stage.REVIEW) }
            } catch (e: CancellationException) {
                _state.update { it.copy(stage = Stage.SETUP) }
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(stage = Stage.SETUP, error = e.message ?: e.toString()) }
            }
        }
    }

    private suspend fun runPipeline(audio: PickedFile, script: PickedFile?, options: ProcessingOptions) {
        val ctx = getApplication<Application>()

        step("Копирование файла")
        val file = File(ctx.cacheDir, "input.wav")
        val total = querySize(audio.uri)
        ctx.contentResolver.openInputStream(audio.uri).use { input ->
            requireNotNull(input) { "Не удалось открыть файл" }
            file.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress(done.toDouble() / total)
                }
            }
        }
        inputFile = file
        val info = WavInfo.read(file)
        wavInfo = info
        if (info.durationSeconds > 3 * 3600) error("Запись длиннее 3 часов — слишком длинная")

        val scriptText = script?.let { sf ->
            val bytes = ctx.contentResolver.openInputStream(sf.uri).use { requireNotNull(it).readBytes() }
            TextUtils.readScript(bytes, sf.name)
        }

        step("Подготовка звука")
        audio16k = AnalysisDecoder.decode(file, info) { progress(it) }

        step("Подготовка модели (только при первом запуске)")
        speech.ensureModel { progress(it) }

        step("Распознавание речи")
        val words = speech.transcribe(audio16k) { progress(it) }

        step("Поиск дыхания, переговоров и дублей")
        val segments = Analyzer.analyze(audio16k, words, scriptText, options) { progress(it) }

        _state.update {
            it.copy(
                segments = segments,
                durationSec = info.durationSeconds,
                waveform = waveform(audio16k, 1500),
                usedScript = scriptText != null,
            )
        }
    }

    private fun step(text: String) = _state.update { it.copy(stepText = text, progress = 0f) }

    private fun progress(p: Double) {
        val v = p.toFloat().coerceIn(0f, 1f)
        if (abs(v - _state.value.progress) >= 0.005f || v == 1f) _state.update { it.copy(progress = v) }
    }

    fun cancel() {
        job?.cancel()
    }

    // ----- Проверка -----

    fun toggle(index: Int) {
        _state.update { st ->
            st.copy(segments = st.segments.mapIndexed { i, s -> if (i == index) s.copy(enabled = !s.enabled) else s })
        }
    }

    fun setAll(kind: SegmentKind?, enabled: Boolean) {
        _state.update { st ->
            st.copy(segments = st.segments.map { if (kind == null || it.kind == kind) it.copy(enabled = enabled) else it })
        }
    }

    fun play(index: Int, processed: Boolean) {
        val seg = _state.value.segments.getOrNull(index) ?: return
        val rate = AnalysisDecoder.RATE
        val from = ((seg.start - 1.5) * rate).toInt().coerceIn(0, audio16k.size)
        val to = ((seg.end + 1.5) * rate).toInt().coerceIn(from, audio16k.size)
        val samples = if (processed) {
            Renderer.preview(audio16k, _state.value.segments, _state.value.options.breathAttenuationDb, from, to)
        } else {
            audio16k.copyOfRange(from, to)
        }
        val token = ++playToken
        val windowStart = from.toDouble() / rate
        _state.update { it.copy(playback = Playback(index, processed, windowStart, 0.0)) }
        player.play(
            samples,
            onPosition = { pos ->
                if (token == playToken) _state.update { it.copy(playback = it.playback?.copy(position = pos)) }
            },
            onDone = { if (token == playToken) _state.update { it.copy(playback = null) } },
        )
    }

    fun stopPlayback() {
        playToken++
        player.stop()
        _state.update { it.copy(playback = null) }
    }

    fun backToSetup() {
        stopPlayback()
        _state.update { it.copy(stage = Stage.SETUP, segments = emptyList()) }
    }

    fun suggestedOutputName(): String {
        val name = _state.value.audio?.name ?: "record.wav"
        return name.substringBeforeLast('.') + "_clean.wav"
    }

    fun save(target: Uri) {
        val file = inputFile ?: return
        val info = wavInfo ?: return
        stopPlayback()
        viewModelScope.launch {
            _state.update { it.copy(stage = Stage.SAVING, progress = 0f, stepText = "Сохранение") }
            try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(target, "wt")
                    requireNotNull(out) { "Не удалось создать файл" }.use {
                        Renderer.render(file, info, _state.value.segments, _state.value.options.breathAttenuationDb, it) { p ->
                            progress(p)
                        }
                    }
                }
                _state.update { it.copy(stage = Stage.REVIEW, message = "Готово: ${displayName(target)}") }
            } catch (e: Throwable) {
                _state.update { it.copy(stage = Stage.REVIEW, error = "Ошибка сохранения: ${e.message}") }
            }
        }
    }

    // ----- Вспомогательное -----

    private fun displayName(uri: Uri): String {
        val cr = getApplication<Application>().contentResolver
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun querySize(uri: Uri): Long {
        val cr = getApplication<Application>().contentResolver
        return runCatching {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun waveform(audio: ShortArray, buckets: Int): FloatArray {
        if (audio.isEmpty()) return FloatArray(0)
        val out = FloatArray(buckets)
        val per = maxOf(1, audio.size / buckets)
        for (b in 0 until buckets) {
            var peak = 0
            val from = b * per
            val to = minOf(audio.size, from + per)
            for (i in from until to) peak = maxOf(peak, abs(audio[i].toInt()))
            out[b] = peak / 32768f
        }
        return out
    }

    override fun onCleared() {
        player.stop()
        speech.close()
        inputFile?.delete()
    }
}
