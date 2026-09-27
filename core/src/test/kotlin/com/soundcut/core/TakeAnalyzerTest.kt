package com.soundcut.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TakeAnalyzerTest {

    /** Слова с паузой [pauseAfter] после каждого, если не указано иное (пауза перед словом через '|'). */
    private fun words(spec: String): List<Word> {
        val out = ArrayList<Word>()
        var t = 0.5
        for (tok in spec.split(" ").filter { it.isNotEmpty() }) {
            if (tok == "|") { t += 0.8; continue }
            out += Word(tok, t, t + 0.3)
            t += 0.35
        }
        return out
    }

    private fun labelsOf(ws: List<Word>, script: String?): String =
        TakeAnalyzer.analyze(ws, script, detectTalk = true, detectRetakes = true)
            .joinToString("") { when (it) { WordLabel.KEEP -> "."; WordLabel.TALK -> "T"; WordLabel.RETAKE -> "R" } }

    private val script = "Сегодня мы поговорим о новых технологиях в медицине. " +
        "Учёные создали прибор, который измеряет давление без манжеты."

    @Test
    fun cleanReadingKeepsEverything() {
        val ws = words("сегодня мы поговорим о новых технологиях в медицине | учёные создали прибор который измеряет давление без манжеты")
        assertEquals(".".repeat(ws.size), labelsOf(ws, script))
    }

    @Test
    fun retakeIsRemovedAndLastTakeKept() {
        val ws = words("сегодня мы поговорим о новых техно | сегодня мы поговорим о новых технологиях в медицине")
        assertEquals("RRRRRR" + "........", labelsOf(ws, script))
    }

    @Test
    fun talkBetweenTakesIsRemoved() {
        val ws = words(
            "сегодня мы поговорим о новых технологиях в медицине | так стоп давай ещё раз | " +
                "учёные создали прибор который измеряет давление без манжеты",
        )
        assertEquals("........" + "TTTTT" + "........", labelsOf(ws, script))
    }

    @Test
    fun retakeWithTalkInside() {
        val ws = words(
            "сегодня мы поговорим о новых технологиях в медицине | учёные создали прибор котор | ой | ещё раз | " +
                "учёные создали прибор который измеряет давление без манжеты",
        )
        val l = labelsOf(ws, script)
        assertEquals("........", l.substring(0, 8))
        // Неудачный дубль и реплики между дублями — всё вырезается.
        assertEquals(false, l.substring(8, 15).contains('.'), l)
        assertEquals("........", l.substring(15))
    }

    @Test
    fun numbersInScriptMatchSpokenWords() {
        val s = "В 2025 году продажи выросли на 15 процентов."
        val ws = words("в две тысячи двадцать пятом году продажи выросли на пятнадцать процентов")
        assertEquals(".".repeat(ws.size), labelsOf(ws, s))
    }

    @Test
    fun withoutScriptRepeatIsFound() {
        val ws = words("сегодня мы поговорим о но | сегодня мы поговорим о новых технологиях")
        assertEquals("RRRRR" + "......", labelsOf(ws, null))
    }

    @Test
    fun withoutScriptTalkKeywords() {
        val ws = words("сегодня хорошая погода | стоп ещё раз | сегодня отличная погода")
        assertEquals("..." + "TTT" + "...", labelsOf(ws, null))
    }

    @Test
    fun russianNumbers() {
        assertEquals("две тысячи двадцать пять", RussianNumbers.toWords(2025).joinToString(" "))
        assertEquals("сто одиннадцать", RussianNumbers.toWords(111).joinToString(" "))
        assertEquals("одна тысяча", RussianNumbers.toWords(1000).joinToString(" "))
    }
}
