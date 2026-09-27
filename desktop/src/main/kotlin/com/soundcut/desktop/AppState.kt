package com.soundcut.desktop

import com.soundcut.core.AnalysisDecoder
import com.soundcut.core.Analyzer
import com.soundcut.core.ProcessingOptions
import com.soundcut.core.Renderer
import com.soundcut.core.Segment
import com.soundcut.core.SegmentKind
import com.soundcut.core.TextUtils
import com.soundcut.core.WavInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.prefs.Preferences
import kotlin.math.abs

enum class Stage { SETUP, PROCESSING, REVIEW, SAVING }

data class Playback(val segmentIndex: Int, val processed: Boolean, val windowStart: Double, val position: Double)

data class UiState(
    val stage: Stage = Stage.SETUP,
    val audio: File? = null,
    val script: File? = null,
    val options: ProcessingOptions = ProcessingOptions(),
    val modelDir: File? = null,
    val modelBusy: String? = null,
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

class AppState(private val scope: CoroutineScope) {
    private val prefs = Preferences.userRoot().node("soundcut")
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state

    private val speech = Speech()
    private var job: Job? = null
    private var playJob: Job? = null
    private var playToken = 0
    private var wavInfo: WavInfo? = null
    private var audio16k = ShortArray(0)

    private fun initialState(): UiState {
        val saved = prefs.get("modelDir", null)?.let { File(it) }?.let { ModelStore.resolveModelDir(it) }
        val model = saved ?: ModelStore.find(ModelChoice.LARGE) ?: ModelStore.find(ModelChoice.SMALL)
            ?: ModelStore.installed().firstOrNull()
        return UiState(
            modelDir = model,
            options = ProcessingOptions(
                breathAttenuationDb = prefs.getDouble("attenuation", 18.0),
                breathSensitivity = prefs.getDouble("sensitivity", 0.5),
                detectBreaths = prefs.getBoolean("breaths", true),
                detectTalk = prefs.getBoolean("talk", true),
                detectRetakes = prefs.getBoolean("retakes", true),
            ),
        )
    }

    // ----- Выбор файлов и настроек -----

    fun pickAudio(file: File) = _state.update { it.copy(audio = file, error = null) }
    fun pickScript(file: File?) = _state.update { it.copy(script = file, error = null) }

    fun updateOptions(transform: (ProcessingOptions) -> ProcessingOptions) {
        _state.update { it.copy(options = transform(it.options)) }
        val o = _state.value.options
        prefs.putDouble("attenuation", o.breathAttenuationDb)
        prefs.putDouble("sensitivity", o.breathSensitivity)
        prefs.putBoolean("breaths", o.detectBreaths)
        prefs.putBoolean("talk", o.detectTalk)
        prefs.putBoolean("retakes", o.detectRetakes)
    }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    // ----- Модель распознавания -----

    fun downloadModel(choice: ModelChoice) = modelJob("Скачивание модели «${choice.title}»") {
        ModelStore.download(choice) { p -> progress(p) }
    }

    /** Пользователь указал папку с моделью или zip-архив. */
    fun useModel(path: File) = modelJob("Установка модели") {
        if (path.isFile && path.name.endsWith(".zip", ignoreCase = true)) {
            ModelStore.installFromZip(path) { p -> progress(p) }
        } else {
            val dir = ModelStore.resolveModelDir(path) ?: error("В папке «${path.name}» нет модели Vosk")
            if (!ModelStore.isAsciiPath(dir)) {
                error("В пути к модели есть русские буквы или другие не латинские символы — библиотека распознавания " +
                    "такой путь не откроет. Выберите zip-архив модели: программа сама установит её в ${ModelStore.root}")
            }
            dir
        }
    }

    private fun modelJob(title: String, block: suspend () -> File) {
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(modelBusy = title, progress = 0f, error = null) }
            try {
                val dir = withContext(Dispatchers.IO) { block() }
                prefs.put("modelDir", dir.absolutePath)
                _state.update { it.copy(modelDir = dir, message = "Модель установлена: ${dir.name}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(error = "Не удалось установить модель: ${e.message}\n\nМожно скачать архив модели в браузере " +
                        "(https://alphacephei.com/vosk/models) и указать его кнопкой «Указать папку или архив».")
                }
            } finally {
                _state.update { it.copy(modelBusy = null) }
            }
        }
    }

    // ----- Обработка -----

    fun process() {
        val s = _state.value
        val audio = s.audio ?: return
        val modelDir = s.modelDir ?: return
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(stage = Stage.PROCESSING, error = null, progress = 0f) }
            try {
                withContext(Dispatchers.Default) { runPipeline(audio, s.script, s.options, modelDir) }
                _state.update { it.copy(stage = Stage.REVIEW) }
            } catch (e: CancellationException) {
                _state.update { it.copy(stage = Stage.SETUP) }
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(stage = Stage.SETUP, error = "Не хватило памяти") }
            } catch (e: Throwable) {
                _state.update { it.copy(stage = Stage.SETUP, error = e.message ?: e.toString()) }
            }
        }
    }

    private suspend fun runPipeline(audio: File, script: File?, options: ProcessingOptions, modelDir: File) {
        val info = WavInfo.read(audio)
        wavInfo = info
        if (info.durationSeconds > 3 * 3600) error("Запись длиннее 3 часов — слишком длинная")
        val scriptText = script?.let { TextUtils.readScript(it.readBytes(), it.name) }

        step("Подготовка звука")
        audio16k = AnalysisDecoder.decode(audio, info) { progress(it) }

        step("Загрузка модели распознавания (быстрая — несколько секунд, точная — 1–3 минуты)")
        _state.update { it.copy(progress = -1f) } // ход загрузки неизвестен
        try {
            speech.load(modelDir)
        } catch (e: Throwable) {
            throw IllegalStateException(
                "Не удалось загрузить модель «${modelDir.name}». Если это точная модель, возможно, не хватает " +
                    "оперативной памяти — попробуйте быструю. (${e.message})",
                e,
            )
        }

        step("Распознавание речи")
        val words = speech.transcribe(audio16k) { progress(it) }

        step("Поиск дыхания, переговоров и дублей")
        val segments = Analyzer.analyze(audio16k, words, scriptText, options) { progress(it) }

        _state.update {
            it.copy(
                segments = segments,
                durationSec = info.durationSeconds,
                waveform = waveform(audio16k, 3000),
                usedScript = scriptText != null,
            )
        }
    }

    private fun step(text: String) = _state.update { it.copy(stepText = text, progress = 0f) }

    private fun progress(p: Double) {
        val v = p.toFloat().coerceIn(0f, 1f)
        if (abs(v - _state.value.progress) >= 0.003f || v == 1f) _state.update { it.copy(progress = v) }
    }

    fun cancel() {
        job?.cancel()
    }

    // ----- Проверка -----

    fun toggle(index: Int) = _state.update { st ->
        st.copy(segments = st.segments.mapIndexed { i, s -> if (i == index) s.copy(enabled = !s.enabled) else s })
    }

    fun setAll(kind: SegmentKind?, enabled: Boolean) = _state.update { st ->
        st.copy(segments = st.segments.map { if (kind == null || it.kind == kind) it.copy(enabled = enabled) else it })
    }

    fun play(index: Int, processed: Boolean) {
        val st = _state.value
        val seg = st.segments.getOrNull(index) ?: return
        stopPlayback()
        val rate = AnalysisDecoder.RATE
        val from = ((seg.start - 1.5) * rate).toInt().coerceIn(0, audio16k.size)
        val to = ((seg.end + 1.5) * rate).toInt().coerceIn(from, audio16k.size)
        val samples = if (processed) {
            Renderer.preview(audio16k, st.segments, st.options.breathAttenuationDb, from, to)
        } else {
            audio16k.copyOfRange(from, to)
        }
        val token = ++playToken
        _state.update { it.copy(playback = Playback(index, processed, from.toDouble() / rate, 0.0)) }
        playJob = scope.launch(Dispatchers.IO) {
            try {
                Sound.play(samples, isActive = { isActive && token == playToken }) { pos ->
                    if (token == playToken) _state.update { it.copy(playback = it.playback?.copy(position = pos)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Не удалось воспроизвести звук: ${e.message}") }
            } finally {
                if (token == playToken) _state.update { it.copy(playback = null) }
            }
        }
    }

    fun stopPlayback() {
        playToken++
        playJob?.cancel()
        playJob = null
        _state.update { it.copy(playback = null) }
    }

    fun backToSetup() {
        stopPlayback()
        _state.update { it.copy(stage = Stage.SETUP, segments = emptyList()) }
    }

    fun suggestedOutput(): File? {
        val a = _state.value.audio ?: return null
        return File(a.parentFile, a.nameWithoutExtension + "_clean.wav")
    }

    fun save(target: File) {
        val source = _state.value.audio ?: return
        val info = wavInfo ?: return
        if (target.absoluteFile == source.absoluteFile) {
            _state.update { it.copy(error = "Нельзя сохранять поверх исходного файла — выберите другое имя") }
            return
        }
        stopPlayback()
        scope.launch {
            _state.update { it.copy(stage = Stage.SAVING, progress = 0f) }
            try {
                withContext(Dispatchers.IO) {
                    target.outputStream().use {
                        Renderer.render(source, info, _state.value.segments, _state.value.options.breathAttenuationDb, it) { p ->
                            progress(p)
                        }
                    }
                }
                _state.update { it.copy(stage = Stage.REVIEW, message = "Сохранено: ${target.absolutePath}") }
            } catch (e: Throwable) {
                _state.update { it.copy(stage = Stage.REVIEW, error = "Ошибка сохранения: ${e.message}") }
            }
        }
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

    fun dispose() {
        stopPlayback()
        job?.cancel()
        speech.close()
    }
}
