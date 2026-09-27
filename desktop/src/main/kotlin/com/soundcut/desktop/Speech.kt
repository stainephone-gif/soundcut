package com.soundcut.desktop

import com.soundcut.core.VoskJson
import com.soundcut.core.Word
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Офлайн-распознавание речи. Модель загружается один раз и переиспользуется. */
class Speech {
    private var model: Model? = null
    private var modelPath: String? = null

    fun load(dir: File) {
        if (modelPath == dir.absolutePath && model != null) return
        close()
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(dir.absolutePath)
        modelPath = dir.absolutePath
    }

    suspend fun transcribe(audio16k: ShortArray, onProgress: (Double) -> Unit): List<Word> {
        val m = requireNotNull(model) { "Модель не загружена" }
        val words = ArrayList<Word>()
        Recognizer(m, 16000f).use { rec ->
            rec.setWords(true)
            val chunk = 8000
            val buf = ShortArray(chunk)
            var pos = 0
            while (pos < audio16k.size) {
                currentCoroutineContext().ensureActive()
                val n = minOf(chunk, audio16k.size - pos)
                System.arraycopy(audio16k, pos, buf, 0, n)
                if (rec.acceptWaveForm(buf, n)) words += VoskJson.parseWords(rec.result)
                pos += n
                onProgress(pos.toDouble() / audio16k.size)
            }
            words += VoskJson.parseWords(rec.finalResult)
        }
        return words
    }

    fun close() {
        model?.close()
        model = null
        modelPath = null
    }
}

/** Проигрывание моно 16 кГц через звуковую карту. */
object Sound {
    fun play(samples: ShortArray, isActive: () -> Boolean, onPosition: (Double) -> Unit) {
        val format = AudioFormat(16000f, 16, 1, true, false)
        val line: SourceDataLine = AudioSystem.getSourceDataLine(format)
        line.open(format, 16000 / 5 * 2)
        line.start()
        try {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                bytes[2 * i] = samples[i].toInt().toByte()
                bytes[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
            }
            var pos = 0
            val chunk = 1600 * 2
            while (isActive() && pos < bytes.size) {
                val n = minOf(chunk, bytes.size - pos)
                line.write(bytes, pos, n)
                pos += n
                onPosition(line.framePosition / 16000.0)
            }
            if (isActive()) line.drain()
        } finally {
            line.stop()
            line.flush()
            line.close()
        }
    }
}
