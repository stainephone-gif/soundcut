package com.soundcut.core

/** Собирает найденные фрагменты в единый список правок. */
object EditPlanner {

    fun plan(
        words: List<Word>,
        labels: Array<WordLabel>,
        breaths: List<Segment>,
        audio16k: ShortArray,
        totalSeconds: Double,
    ): List<Segment> {
        val cuts = cutSegments(words, labels, audio16k, totalSeconds)
        val keptBreaths = breaths.filter { b ->
            val mid = (b.start + b.end) / 2
            cuts.none { mid in it.start..it.end }
        }
        return (cuts + keptBreaths).sortedBy { it.start }
    }

    private fun cutSegments(
        words: List<Word>,
        labels: Array<WordLabel>,
        audio: ShortArray,
        total: Double,
    ): List<Segment> {
        val out = ArrayList<Segment>()
        val n = words.size
        var i = 0
        while (i < n) {
            if (labels[i] == WordLabel.KEEP) { i++; continue }
            var j = i
            while (j + 1 < n && labels[j + 1] == labels[i]) j++
            val kind = if (labels[i] == WordLabel.TALK) SegmentKind.TALK else SegmentKind.RETAKE

            val start = when {
                i == 0 -> 0.0
                labels[i - 1] != WordLabel.KEEP -> (words[i - 1].end + words[i].start) / 2
                else -> {
                    val prevEnd = words[i - 1].end
                    val gap = words[i].start - prevEnd
                    snap(audio, prevEnd + minOf(gap / 2, 0.3), prevEnd, words[i].start)
                }
            }
            val end = when {
                j == n - 1 -> total
                labels[j + 1] != WordLabel.KEEP -> (words[j].end + words[j + 1].start) / 2
                else -> {
                    val nextStart = words[j + 1].start
                    val gap = nextStart - words[j].end
                    snap(audio, nextStart - minOf(gap / 2, 0.3), words[j].end, nextStart)
                }
            }
            if (end > start) {
                out += Segment(kind, start, end, words.subList(i, j + 1).joinToString(" ") { it.text })
            }
            i = j + 1
        }
        return out
    }

    /** Сдвигает точку склейки в самое тихое место рядом (±50 мс), не выходя за [lo, hi]. */
    internal fun snap(audio: ShortArray, t: Double, lo: Double, hi: Double): Double {
        val rate = AnalysisDecoder.RATE
        val from = maxOf(t - 0.05, lo)
        val to = minOf(t + 0.05, hi)
        if (to - from < 0.02) return t
        val win = rate / 100 // 10 мс
        var best = t
        var bestRms = Double.MAX_VALUE
        var x = from
        while (x + 0.01 <= to) {
            val a = (x * rate).toInt().coerceIn(0, audio.size)
            val b = (a + win).coerceAtMost(audio.size)
            if (b > a) {
                val r = rms(audio, a, b)
                if (r < bestRms) { bestRms = r; best = x + 0.005 }
            }
            x += 0.005
        }
        return best
    }
}
