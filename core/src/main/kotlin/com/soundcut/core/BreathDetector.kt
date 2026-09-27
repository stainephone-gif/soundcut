package com.soundcut.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Ищет вдохи в моно-сигнале 16 кГц.
 *
 * Вдох — это шум без тона голоса: у него нет основного тона (автокорреляция низкая),
 * спектр «плоский», почти нет энергии на низких частотах, громкость заметно выше тишины,
 * но обычно ниже речи. Там, где распознаватель слышит слова, дыхание не ищется.
 */
class BreathDetector(private val sensitivity: Double = 0.5) {

    private val rate = AnalysisDecoder.RATE
    private val hop = 160      // 10 мс
    private val win = 512      // 32 мс, заодно размер БПФ

    data class FrameFeatures(val db: Double, val flatness: Double, val lowRatio: Double)

    fun detect(
        audio: ShortArray,
        words: List<Word> = emptyList(),
        onProgress: (Double) -> Unit = {},
    ): List<Segment> {
        val frameCount = (audio.size - win) / hop + 1
        if (frameCount <= 10) return emptyList()

        val features = computeFeatures(audio, frameCount, onProgress)
        val dbSorted = features.map { it.db }.sorted()
        val floor = percentile(dbSorted, 0.08)
        val speech = percentile(dbSorted, 0.95)
        if (speech - floor < 12) return emptyList() // запись без выраженной речи/тишины

        // Пороговые значения в зависимости от чувствительности.
        val s = sensitivity.coerceIn(0.0, 1.0)
        val minAboveFloor = 12.0 - 6.0 * s          // 12..6 дБ над тишиной
        val maxBelowSpeech = 2.0 - 4.0 * s          // сильный вдох может быть почти как речь
        val minFlatness = 0.30 - 0.14 * s           // 0.30..0.16
        val maxLowRatio = 0.22 + 0.13 * s           // 0.22..0.35
        val maxVoicing = 0.45 + 0.1 * s

        val wordMask = BooleanArray(frameCount)
        for (w in words) {
            // Границы слов у распознавателя неточные, поэтому слегка сужаем их.
            val a = ((w.start + 0.04) * rate / hop).toInt().coerceAtLeast(0)
            val b = ((w.end - 0.04) * rate / hop).toInt().coerceAtMost(frameCount - 1)
            for (i in a..b) wordMask[i] = true
        }

        val candidate = BooleanArray(frameCount)
        val frame = FloatArray(win)
        for (i in 0 until frameCount) {
            val f = features[i]
            if (wordMask[i]) continue
            if (f.db < floor + minAboveFloor || f.db > speech - maxBelowSpeech) continue
            if (f.flatness < minFlatness || f.lowRatio > maxLowRatio) continue
            val off = i * hop
            for (k in 0 until win) frame[k] = audio[off + k] / 32768f
            if (voicing(frame) > maxVoicing) continue
            candidate[i] = true
        }

        // Склеиваем соседние кадры в отрезки.
        val result = ArrayList<Segment>()
        var i = 0
        while (i < frameCount) {
            if (!candidate[i]) { i++; continue }
            var j = i
            var hits = 0
            var gap = 0
            var last = i
            while (j < frameCount && gap <= 4) {
                if (candidate[j]) { hits++; gap = 0; last = j } else gap++
                j++
            }
            val lenFrames = last - i + 1
            val start = i * hop.toDouble() / rate
            val end = (last * hop + win).toDouble() / rate
            val dur = end - start
            if (dur in 0.12..1.6 && hits >= lenFrames * 0.6) {
                result += Segment(SegmentKind.BREATH, (start - 0.02).coerceAtLeast(0.0), end + 0.02, "вдох")
            }
            i = last + 1
        }
        return trimToWords(mergeClose(result), words)
    }

    private fun computeFeatures(audio: ShortArray, frameCount: Int, onProgress: (Double) -> Unit): Array<FrameFeatures> {
        val window = FloatArray(win) { (0.5 - 0.5 * cos(2 * PI * it / (win - 1))).toFloat() }
        val re = FloatArray(win)
        val im = FloatArray(win)
        val binHz = rate.toDouble() / win
        val lowFrom = (80 / binHz).toInt()
        val lowTo = (400 / binHz).toInt()
        val flatFrom = (400 / binHz).toInt()
        val flatTo = (6000 / binHz).toInt()
        val fullTo = (7800 / binHz).toInt()
        return Array(frameCount) { i ->
            val off = i * hop
            var energy = 0.0
            for (k in 0 until win) {
                val v = audio[off + k] / 32768f
                energy += v * v
                re[k] = v * window[k]
                im[k] = 0f
            }
            val db = 10 * log10(energy / win + 1e-12)
            Fft.transform(re, im)
            var low = 0.0
            var total = 0.0
            var logSum = 0.0
            var linSum = 0.0
            for (b in lowFrom..fullTo) {
                val p = (re[b] * re[b] + im[b] * im[b]).toDouble() + 1e-12
                total += p
                if (b <= lowTo) low += p
                if (b in flatFrom..flatTo) {
                    logSum += ln(p)
                    linSum += p
                }
            }
            val n = flatTo - flatFrom + 1
            val flatness = exp(logSum / n) / (linSum / n)
            if (i % 2000 == 0) onProgress(i.toDouble() / frameCount)
            FrameFeatures(db, flatness, low / total)
        }
    }

    /** Сила основного тона 70–400 Гц по нормированной автокорреляции. */
    private fun voicing(x: FloatArray): Double {
        val minLag = rate / 400
        val maxLag = rate / 70
        var e0 = 0.0
        for (v in x) e0 += v * v
        if (e0 < 1e-9) return 0.0
        var best = 0.0
        for (lag in minLag..maxLag) {
            var num = 0.0
            var e1 = 0.0
            var e2 = 0.0
            for (k in 0 until x.size - lag) {
                num += x[k] * x[k + lag]
                e1 += x[k] * x[k]
                e2 += x[k + lag] * x[k + lag]
            }
            val r = num / sqrt(e1 * e2 + 1e-12)
            if (r > best) best = r
        }
        return best
    }

    private fun mergeClose(list: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in list) {
            val last = out.lastOrNull()
            if (last != null && s.start - last.end < 0.08 && s.end - last.start <= 1.8) {
                out[out.size - 1] = last.copy(end = s.end)
            } else out += s
        }
        return out
    }

    /** Не даём приглушению заходить на слова. */
    private fun trimToWords(list: List<Segment>, words: List<Word>): List<Segment> {
        if (words.isEmpty()) return list
        return list.mapNotNull { seg ->
            var start = seg.start
            var end = seg.end
            for (w in words) {
                if (w.end - 0.03 <= start || w.start + 0.03 >= end) continue
                // Слово перекрывает вдох — обрезаем ту сторону, с которой оно ближе.
                if (w.start + w.end < start + end) start = maxOf(start, w.end - 0.03)
                else end = minOf(end, w.start + 0.03)
            }
            if (end - start >= 0.1) seg.copy(start = start, end = end) else null
        }
    }

    private fun percentile(sorted: List<Double>, p: Double): Double =
        sorted[((sorted.size - 1) * p).toInt()]
}

internal object Fft {
    /** БПФ по основанию 2, на месте. Размер должен быть степенью двойки. */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
