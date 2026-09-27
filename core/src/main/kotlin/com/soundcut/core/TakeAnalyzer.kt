package com.soundcut.core

enum class WordLabel { KEEP, TALK, RETAKE }

/**
 * Решает, какие распознанные слова относятся к чистовому чтению, а какие —
 * к переговорам или неудачным дублям.
 */
object TakeAnalyzer {

    fun analyze(words: List<Word>, script: String?, detectTalk: Boolean, detectRetakes: Boolean): Array<WordLabel> {
        val scriptTokens = script?.let { expandNumbers(TextUtils.tokenize(it)) }.orEmpty()
        val labels = if (scriptTokens.size >= 5) {
            ScriptAligner.label(words, scriptTokens)
        } else {
            WithoutScript.label(words)
        }
        return Array(labels.size) {
            when (labels[it]) {
                WordLabel.TALK -> if (detectTalk) WordLabel.TALK else WordLabel.KEEP
                WordLabel.RETAKE -> if (detectRetakes) WordLabel.RETAKE else WordLabel.KEEP
                WordLabel.KEEP -> WordLabel.KEEP
            }
        }
    }

    /** Цифры в тексте заменяются словами, чтобы они совпадали с распознанной речью. */
    internal fun expandNumbers(tokens: List<String>): List<String> = tokens.flatMap { t ->
        if (t.all { it.isDigit() } && t.length <= 9) RussianNumbers.toWords(t.toLong()) else listOf(t)
    }
}

/**
 * Выравнивание распознанных слов по тексту (динамическое программирование).
 * Помимо обычных совпадений, вставок и пропусков разрешён «прыжок» в другое
 * место текста: прыжок назад означает, что диктор начал фрагмент заново.
 */
internal object ScriptAligner {
    private const val INS = 1.0f
    private const val DEL = 1.0f
    private const val JUMP = 1.5f

    private const val OP_START: Byte = 0
    private const val OP_MATCH: Byte = 1
    private const val OP_INS: Byte = 2
    private const val OP_DEL: Byte = 3
    private const val OP_JUMP: Byte = 4

    fun label(words: List<Word>, script: List<String>): Array<WordLabel> {
        val n = words.size
        val m = script.size
        if (n == 0) return emptyArray()
        val asr = words.map { TextUtils.normalizeWord(it.text) }
        val pos = align(asr, script)

        // Разбиваем на «проходы»: между ними был прыжок по тексту.
        val runStarts = ArrayList<Int>()
        for (i in 0 until n) if (i == 0 || pos.runBreakBefore[i]) runStarts += i
        runStarts += n

        val labels = Array(n) { WordLabel.KEEP }
        val covered = BooleanArray(m)
        // Идём с конца: если место текста потом прочитано ещё раз, ранний проход — брак.
        for (r in runStarts.size - 2 downTo 0) {
            val from = runStarts[r]
            val to = runStarts[r + 1]
            var first = -1
            var last = -1
            for (i in from until to) {
                val p = pos.scriptPos[i]
                if (p >= 0 && pos.exact[i] && covered[p]) {
                    labels[i] = WordLabel.RETAKE
                    if (first < 0) first = i
                    last = i
                }
            }
            if (first >= 0) {
                // Все совпавшие слова после первого бракованного тоже брак → обрезаем проход до конца,
                // вместе с оборванными словами и оговорками.
                val tailIsRetake = (first until to).all { !pos.exact[it] || labels[it] == WordLabel.RETAKE }
                val until = if (tailIsRetake) to - 1 else last
                for (i in first..until) labels[i] = WordLabel.RETAKE
            }
            for (i in from until to) {
                val p = pos.scriptPos[i]
                if (p >= 0 && pos.exact[i]) covered[p] = true
            }
        }

        markTalk(words, labels) { i -> pos.scriptPos[i] < 0 }
        return labels
    }

    /** Группы лишних слов (которых нет в тексте) помечаются как переговоры. */
    fun markTalk(words: List<Word>, labels: Array<WordLabel>, isExtra: (Int) -> Boolean) {
        val n = words.size
        var i = 0
        while (i < n) {
            if (labels[i] != WordLabel.KEEP || !isExtra(i)) { i++; continue }
            var j = i
            while (j + 1 < n && labels[j + 1] == WordLabel.KEEP && isExtra(j + 1)) j++
            val size = j - i + 1
            val pauseBefore = if (i == 0) 10.0 else words[i].start - words[i - 1].end
            val pauseAfter = if (j == n - 1) 10.0 else words[j + 1].start - words[j].end
            val isTalk = when {
                size >= 4 -> true
                size >= 2 -> pauseBefore >= 0.25 || pauseAfter >= 0.25
                else -> pauseBefore >= 0.35 && pauseAfter >= 0.35
            }
            if (isTalk) for (k in i..j) labels[k] = WordLabel.TALK
            i = j + 1
        }
    }

    /**
     * [scriptPos] — какому слову текста соответствует слово записи (-1 — лишнее слово),
     * [exact] — соответствие уверенное, а не просто замена одного слова другим.
     */
    class Alignment(val scriptPos: IntArray, val exact: BooleanArray, val runBreakBefore: BooleanArray)

    fun align(asr: List<String>, script: List<String>): Alignment {
        val n = asr.size
        val m = script.size

        // Словари, чтобы не считать похожесть одних и тех же пар много раз.
        val asrVocab = HashMap<String, Int>()
        val scriptVocab = HashMap<String, Int>()
        val a = IntArray(n) { asrVocab.getOrPut(asr[it]) { asrVocab.size } }
        val s = IntArray(m) { scriptVocab.getOrPut(script[it]) { scriptVocab.size } }
        val asrWords = arrayOfNulls<String>(asrVocab.size).also { arr -> asrVocab.forEach { (k, v) -> arr[v] = k } }
        val scriptWords = arrayOfNulls<String>(scriptVocab.size).also { arr -> scriptVocab.forEach { (k, v) -> arr[v] = k } }
        val vs = scriptWords.size
        val sub = FloatArray(asrWords.size * vs)
        for (x in asrWords.indices) for (y in 0 until vs) {
            sub[x * vs + y] = matchCost(asrWords[x]!!, scriptWords[y]!!)
        }

        val back = ByteArray((n + 1) * (m + 1))
        val jumpFrom = IntArray(n + 1)
        var prev = FloatArray(m + 1)
        var cur = FloatArray(m + 1)

        // Строка 0: можно начать чтение не с начала текста.
        prev[0] = 0f
        back[0] = OP_START
        for (j in 1..m) {
            if (j * DEL <= JUMP) { prev[j] = j * DEL; back[j] = OP_DEL } else { prev[j] = JUMP; back[j] = OP_JUMP }
        }
        jumpFrom[0] = 0

        for (i in 1..n) {
            val row = i * (m + 1)
            val ai = a[i - 1] * vs
            cur[0] = prev[0] + INS
            back[row] = OP_INS
            for (j in 1..m) {
                var best = prev[j - 1] + sub[ai + s[j - 1]]
                var op = OP_MATCH
                val up = prev[j] + INS
                if (up < best) { best = up; op = OP_INS }
                val left = cur[j - 1] + DEL
                if (left < best) { best = left; op = OP_DEL }
                cur[j] = best
                back[row + j] = op
            }
            var bj = 0
            for (j in 1..m) if (cur[j] < cur[bj]) bj = j
            jumpFrom[i] = bj
            val jumpCost = cur[bj] + JUMP
            for (j in 0..m) if (jumpCost < cur[j]) { cur[j] = jumpCost; back[row + j] = OP_JUMP }
            val t = prev; prev = cur; cur = t
        }

        // Конец: можно не дочитать хвост текста.
        var j = 0
        var bestEnd = Float.MAX_VALUE
        for (k in 0..m) {
            val c = prev[k] + minOf((m - k) * DEL, JUMP)
            if (c < bestEnd) { bestEnd = c; j = k }
        }

        val scriptPos = IntArray(n) { -1 }
        val runBreak = BooleanArray(n)
        var i = n
        while (i > 0 || j > 0) {
            when (back[i * (m + 1) + j]) {
                OP_MATCH -> { scriptPos[i - 1] = j - 1; i--; j-- }
                OP_INS -> { i-- }
                OP_DEL -> { j-- }
                OP_JUMP -> {
                    if (i < n) runBreak[i] = true
                    j = jumpFrom[i]
                }
                else -> break
            }
        }
        // Замены (ошибки распознавания) не считаем уверенным прочтением текста.
        val exact = BooleanArray(n) { k -> scriptPos[k] >= 0 && sub[a[k] * vs + s[scriptPos[k]]] < 1f }
        return Alignment(scriptPos, exact, runBreak)
    }

    private fun matchCost(asr: String, script: String): Float {
        val sim = TextUtils.similarity(asr, script)
        return when {
            sim >= 0.99 -> 0f
            sim >= 0.8 -> 0.3f
            TextUtils.isFragmentOf(asr, script) -> 0.5f
            sim >= 0.6 -> 0.7f
            // Чуть дороже вставки: при равенстве лишнее слово лучше считать вставкой, а не заменой.
            else -> 1.05f
        }
    }
}

/** Когда текста нет: ищем повторы («сегодня мы... сегодня мы поговорим») и служебные фразы. */
internal object WithoutScript {
    private val talkWords = setOf(
        "стоп", "дубль", "заново", "сначала", "перезапишем", "перепишем", "переписать",
        "поехали", "пишем", "запись", "пишется", "извините", "простите",
    )

    fun label(words: List<Word>): Array<WordLabel> {
        val n = words.size
        val w = words.map { TextUtils.normalizeWord(it.text) }
        val labels = Array(n) { WordLabel.KEEP }

        fun same(x: String, y: String) = TextUtils.similarity(x, y) >= 0.8 || TextUtils.isFragmentOf(x, y)

        var b = 1
        while (b < n) {
            var bestA = -1
            var bestLen = 0
            for (a in b - 1 downTo maxOf(0, b - 25)) {
                var len = 0
                while (a + len < b && b + len < n && same(w[a + len], w[b + len])) len++
                val span = b - a
                // Ранняя попытка должна почти целиком повторяться в новой.
                val ok = len >= 2 && len >= span * 0.6 && (len >= 3 || span == len)
                if (ok) { bestA = a; bestLen = len }
            }
            if (bestA >= 0) {
                for (k in bestA until b) labels[k] = WordLabel.RETAKE
                // Повтор уже учтён — не ищем дубли внутри новой попытки.
                b += bestLen
            } else {
                b++
            }
        }

        // Служебная фраза вместе со всей репликой вокруг неё (до пауз).
        val talkSeed = BooleanArray(n)
        for (i in 0 until n) {
            if (w[i] in talkWords) talkSeed[i] = true
            if (i + 1 < n && (w[i] == "еще" || w[i] == "ещё") && w[i + 1] == "раз") { talkSeed[i] = true; talkSeed[i + 1] = true }
        }
        for (i in 0 until n) {
            if (!talkSeed[i]) continue
            var s = i
            while (s > 0 && words[s].start - words[s - 1].end < 0.4 && i - s < 6) s--
            var e = i
            while (e + 1 < n && words[e + 1].start - words[e].end < 0.4 && e - i < 6) e++
            for (k in s..e) if (labels[k] == WordLabel.KEEP) labels[k] = WordLabel.TALK
        }
        return labels
    }
}

internal object RussianNumbers {
    private val units = arrayOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val unitsFem = arrayOf("", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val teens = arrayOf(
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val tens = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val hundreds = arrayOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")

    fun toWords(n: Long): List<String> {
        if (n == 0L) return listOf("ноль")
        val out = ArrayList<String>()
        val millions = (n / 1_000_000).toInt()
        val thousands = (n / 1000 % 1000).toInt()
        val rest = (n % 1000).toInt()
        if (millions > 0) { triple(millions, false, out); out += plural(millions, "миллион", "миллиона", "миллионов") }
        if (thousands > 0) { triple(thousands, true, out); out += plural(thousands, "тысяча", "тысячи", "тысяч") }
        if (rest > 0) triple(rest, false, out)
        return out
    }

    private fun triple(n: Int, feminine: Boolean, out: MutableList<String>) {
        if (n / 100 > 0) out += hundreds[n / 100]
        val t = n % 100
        when {
            t in 10..19 -> out += teens[t - 10]
            else -> {
                if (t / 10 > 0) out += tens[t / 10]
                if (t % 10 > 0) out += if (feminine) unitsFem[t % 10] else units[t % 10]
            }
        }
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val t = n % 100
        return when {
            t in 11..14 -> many
            t % 10 == 1 -> one
            t % 10 in 2..4 -> few
            else -> many
        }
    }
}
