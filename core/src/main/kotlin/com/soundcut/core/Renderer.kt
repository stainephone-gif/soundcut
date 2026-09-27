package com.soundcut.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.pow

/**
 * Правки в отсчётах: вырезаемые интервалы (с короткими затуханиями по краям)
 * и приглушаемые интервалы (с плавным входом и выходом).
 */
class GainPlan(
    segments: List<Segment>,
    private val sampleRate: Int,
    val totalFrames: Long,
    attenuationDb: Double,
) {
    private class Region(val start: Long, val end: Long, val cut: Boolean, val ramp: Long) {
        val affectFrom: Long get() = if (cut) start - ramp else start
        val affectTo: Long get() = if (cut) end + ramp else end
    }

    private val atten = 10.0.pow(-attenuationDb / 20).toFloat()
    private val regions: List<Region>
    val keptFrames: Long

    init {
        val active = segments.filter { it.enabled }
        val cutRamp = (0.008 * sampleRate).toLong()
        val breathRamp = (0.02 * sampleRate).toLong()
        val cuts = mergeIntervals(active.filter { it.kind.isCut }.map { toFrames(it) })
        val breaths = active.filter { !it.kind.isCut }.map { toFrames(it) }
            .filter { (s, e) -> e > s }
        keptFrames = totalFrames - cuts.sumOf { it.second - it.first }
        regions = (cuts.map { Region(it.first, it.second, true, cutRamp) } +
            breaths.map { (s, e) -> Region(s, e, false, minOf(breathRamp, (e - s) / 2)) })
            .sortedBy { it.affectFrom }
    }

    private fun toFrames(s: Segment): Pair<Long, Long> =
        Pair(
            (s.start * sampleRate).toLong().coerceIn(0, totalFrames),
            (s.end * sampleRate).toLong().coerceIn(0, totalFrames),
        )

    private fun mergeIntervals(list: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val sorted = list.filter { it.second > it.first }.sortedBy { it.first }
        val out = ArrayList<Pair<Long, Long>>()
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && p.first <= last.second) out[out.size - 1] = last.first to maxOf(last.second, p.second)
            else out += p
        }
        return out
    }

    private var cursor = 0

    /**
     * Заполняет [gain] (множитель громкости) и [skip] (кадр вырезан) для кадров [from, from+len).
     * Кадры нужно запрашивать по возрастанию.
     */
    fun fill(from: Long, len: Int, gain: FloatArray, skip: BooleanArray) {
        java.util.Arrays.fill(gain, 0, len, 1f)
        java.util.Arrays.fill(skip, 0, len, false)
        val to = from + len
        // Области отсортированы по началу; уже закончившиеся в начале списка больше не нужны.
        while (cursor < regions.size && regions[cursor].affectTo <= from) cursor++
        var k = cursor
        while (k < regions.size && regions[k].affectFrom < to) {
            val r = regions[k]
            val a = maxOf(r.affectFrom, from)
            val b = minOf(r.affectTo, to)
            for (f in a until b) {
                val idx = (f - from).toInt()
                if (r.cut) {
                    when {
                        f < r.start -> gain[idx] *= (r.start - f).toFloat() / r.ramp
                        f < r.end -> skip[idx] = true
                        else -> gain[idx] *= (f - r.end + 1).toFloat() / r.ramp
                    }
                } else {
                    val g = when {
                        r.ramp > 0 && f < r.start + r.ramp -> 1f + (atten - 1f) * (f - r.start + 1).toFloat() / r.ramp
                        r.ramp > 0 && f >= r.end - r.ramp -> 1f + (atten - 1f) * (r.end - f).toFloat() / r.ramp
                        else -> atten
                    }
                    gain[idx] *= g
                }
            }
            k++
        }
    }

}

object Renderer {
    /** Записывает обработанный WAV в [output] в том же формате, что и исходный. */
    fun render(
        source: File,
        info: WavInfo,
        segments: List<Segment>,
        attenuationDb: Double,
        output: OutputStream,
        onProgress: (Double) -> Unit = {},
    ) {
        val plan = GainPlan(segments, info.sampleRate, info.frameCount, attenuationDb)
        val out = BufferedOutputStream(output, 1 shl 16)
        writeWavHeader(out, info, plan.keptFrames)
        val chunk = 32768
        val inBuf = ByteArray(chunk * info.frameSize)
        val outBuf = ByteArray(chunk * info.frameSize)
        val gain = FloatArray(chunk)
        val skip = BooleanArray(chunk)
        val fs = info.frameSize
        val bps = info.bytesPerSample
        RandomAccessFile(source, "r").use { raf ->
            raf.seek(info.dataOffset)
            var frame = 0L
            while (frame < info.frameCount) {
                val n = minOf(chunk.toLong(), info.frameCount - frame).toInt()
                raf.readFully(inBuf, 0, n * fs)
                plan.fill(frame, n, gain, skip)
                var o = 0
                for (f in 0 until n) {
                    if (skip[f]) continue
                    val src = f * fs
                    val g = gain[f]
                    if (g == 1f) {
                        System.arraycopy(inBuf, src, outBuf, o, fs)
                    } else {
                        for (c in 0 until info.channels) {
                            val v = SampleCodec.decode(inBuf, src + c * bps, info)
                            SampleCodec.encode(v * g, outBuf, o + c * bps, info)
                        }
                    }
                    o += fs
                }
                out.write(outBuf, 0, o)
                frame += n
                onProgress(frame.toDouble() / info.frameCount)
            }
        }
        if ((plan.keptFrames * fs) and 1L == 1L) out.write(0)
        out.flush()
    }

    /** Применяет правки к фрагменту моно-звука 16 кГц — для прослушивания «как будет». */
    fun preview(audio: ShortArray, segments: List<Segment>, attenuationDb: Double, from: Int, to: Int): ShortArray {
        val plan = GainPlan(segments, AnalysisDecoder.RATE, audio.size.toLong(), attenuationDb)
        val len = to - from
        val gain = FloatArray(len)
        val skip = BooleanArray(len)
        plan.fill(from.toLong(), len, gain, skip)
        val out = ShortArray(len)
        var o = 0
        for (i in 0 until len) {
            if (skip[i]) continue
            out[o++] = (audio[from + i] * gain[i]).toInt().toShort()
        }
        return out.copyOf(o)
    }
}
