package com.soundcut.core

/**
 * Разбор ответа распознавателя Vosk:
 * {"result": [{"conf": 1.0, "end": 1.02, "start": 0.63, "word": "привет"}, ...], "text": "..."}
 */
object VoskJson {
    private val objectRe = Regex("\\{[^{}]*\\}")
    private fun num(obj: String, key: String): Double? =
        Regex("\"$key\"\\s*:\\s*(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)").find(obj)?.groupValues?.get(1)?.toDoubleOrNull()

    fun parseWords(json: String): List<Word> {
        val start = json.indexOf("\"result\"")
        if (start < 0) return emptyList()
        val open = json.indexOf('[', start)
        val close = json.indexOf(']', open)
        if (open < 0 || close < 0) return emptyList()
        return objectRe.findAll(json.substring(open, close)).mapNotNull { m ->
            val obj = m.value
            val word = Regex("\"word\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj)?.groupValues?.get(1)
                ?: return@mapNotNull null
            Word(
                text = word,
                start = num(obj, "start") ?: return@mapNotNull null,
                end = num(obj, "end") ?: return@mapNotNull null,
                conf = num(obj, "conf") ?: 1.0,
            )
        }.toList()
    }
}
