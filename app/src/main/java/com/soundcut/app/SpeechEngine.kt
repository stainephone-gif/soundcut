package com.soundcut.app

import android.content.Context
import com.soundcut.core.Word
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Офлайн-распознавание русской речи (Vosk) с отметками времени слов. */
class SpeechEngine(private val context: Context) {
    private var model: Model? = null

    /** Копирует модель из APK во внутреннюю память (только при первом запуске) и загружает её. */
    suspend fun ensureModel(onProgress: (Double) -> Unit) {
        if (model != null) return
        val dir = File(context.filesDir, MODEL_DIR)
        val assetVersion = context.assets.open("$MODEL_DIR/version.txt").use { it.readBytes().decodeToString() }
        val installed = File(dir, "version.txt").takeIf { it.exists() }?.readText()
        if (installed != assetVersion) {
            dir.deleteRecursively()
            val files = listAssets(MODEL_DIR)
            files.forEachIndexed { i, path ->
                currentCoroutineContext().ensureActive()
                val out = File(context.filesDir, path)
                out.parentFile?.mkdirs()
                context.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } }
                onProgress((i + 1).toDouble() / files.size)
            }
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(dir.absolutePath)
    }

    private fun listAssets(path: String): List<String> {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) return listOf(path)
        // version.txt копируем последним: его наличие означает, что копирование завершено.
        return children.sortedBy { it == "version.txt" }.flatMap { listAssets("$path/$it") }
    }

    suspend fun transcribe(audio16k: ShortArray, onProgress: (Double) -> Unit): List<Word> {
        val m = requireNotNull(model) { "Модель не загружена" }
        val words = ArrayList<Word>()
        Recognizer(m, 16000f).use { rec ->
            rec.setWords(true)
            val chunk = 8000
            var pos = 0
            while (pos < audio16k.size) {
                currentCoroutineContext().ensureActive()
                val n = minOf(chunk, audio16k.size - pos)
                val buf = if (pos == 0 && n == audio16k.size) audio16k else audio16k.copyOfRange(pos, pos + n)
                if (rec.acceptWaveForm(buf, n)) parse(rec.result, words)
                pos += n
                onProgress(pos.toDouble() / audio16k.size)
            }
            parse(rec.finalResult, words)
        }
        return words
    }

    private fun parse(json: String, out: MutableList<Word>) {
        val arr = JSONObject(json).optJSONArray("result") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Word(
                text = o.getString("word"),
                start = o.getDouble("start"),
                end = o.getDouble("end"),
                conf = o.optDouble("conf", 1.0),
            )
        }
    }

    fun close() {
        model?.close()
        model = null
    }

    companion object {
        private const val MODEL_DIR = "model-ru"
    }
}
