package com.soundcut.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Переводит WAV любого формата в моно 16 кГц / 16 бит — это нужно распознавателю речи
 * и детектору дыхания. Исходный файл при этом не меняется.
 */
object AnalysisDecoder {
    const val RATE = 16000

    fun decode(file: File, info: WavInfo, onProgress: (Double) -> Unit = {}): ShortArray {
        val ratio = info.sampleRate.toDouble() / RATE
        val outCount = (info.frameCount / ratio).toLong()
        require(outCount < Int.MAX_VALUE) { "Запись слишком длинная" }
        val out = ShortArray(outCount.toInt())

        // Фильтр от наложения спектра: два звена Баттерворта 2-го порядка.
        val cutoff = minOf(7000.0, info.sampleRate * 0.45)
        val needFilter = info.sampleRate != RATE
        val f1 = Biquad.lowPass(cutoff, info.sampleRate.toDouble(), 0.5412)
        val f2 = Biquad.lowPass(cutoff, info.sampleRate.toDouble(), 1.3066)

        val framesPerChunk = 65536
        val buf = ByteArray(framesPerChunk * info.frameSize)
        var outIndex = 0
        var prev = 0f
        var srcIndex = 0L // индекс текущего входного отсчёта
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset)
            var remaining = info.frameCount
            while (remaining > 0 && outIndex < out.size) {
                val n = minOf(remaining, framesPerChunk.toLong()).toInt()
                raf.readFully(buf, 0, n * info.frameSize)
                for (f in 0 until n) {
                    var sum = 0f
                    val base = f * info.frameSize
                    for (c in 0 until info.channels) {
                        sum += SampleCodec.decode(buf, base + c * info.bytesPerSample, info)
                    }
                    var x = sum / info.channels
                    if (needFilter) x = f2.process(f1.process(x))
                    // Выходные отсчёты, попадающие между srcIndex-1 и srcIndex.
                    while (outIndex < out.size) {
                        val pos = outIndex * ratio
                        if (pos > srcIndex) break
                        val frac = (pos - (srcIndex - 1)).toFloat()
                        val y = if (srcIndex == 0L) x else prev + (x - prev) * frac
                        out[outIndex++] = (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    prev = x
                    srcIndex++
                }
                remaining -= n
                onProgress(1.0 - remaining.toDouble() / info.frameCount)
            }
        }
        return if (outIndex == out.size) out else out.copyOf(outIndex)
    }
}

internal class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y.toFloat()
    }

    companion object {
        fun lowPass(freq: Double, rate: Double, q: Double): Biquad {
            val w = 2 * PI * freq / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                (1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0,
                -2 * c / a0, (1 - alpha) / a0,
            )
        }
    }
}

internal fun rms(x: ShortArray, from: Int, to: Int): Double {
    var s = 0.0
    for (i in from until to) {
        val v = x[i] / 32768.0
        s += v * v
    }
    return sqrt(s / maxOf(1, to - from))
}
