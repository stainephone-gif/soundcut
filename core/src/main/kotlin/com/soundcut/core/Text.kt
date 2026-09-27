package com.soundcut.core

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

object TextUtils {
    private val nonLetters = Regex("[^\\p{L}\\p{N}]+")

    /** Нижний регистр, ё→е, без знаков препинания. */
    fun normalizeWord(w: String): String =
        w.lowercase().replace('ё', 'е').replace(nonLetters, "")

    fun tokenize(text: String): List<String> =
        text.split(Regex("\\s+|[-–—]"))
            .map { normalizeWord(it) }
            .filter { it.isNotEmpty() }

    /** Похожесть двух нормализованных слов от 0 до 1. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        // Русские окончания распознаются неуверенно — общий корень считаем почти совпадением.
        val common = a.commonPrefixWith(b).length
        if (common >= 4 && common >= minOf(a.length, b.length) - 2) return 0.85
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / maxOf(a.length, b.length)
    }

    /** Слово [partial] похоже на оборванное начало слова [full] («техно-» → «технологии»). */
    fun isFragmentOf(partial: String, full: String): Boolean =
        partial.length in 2 until full.length && full.startsWith(partial)

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Читает текст из .txt (UTF-8 или Windows-1251) или .docx. */
    fun readScript(bytes: ByteArray, fileName: String): String {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (fileName.lowercase().endsWith(".docx") || isZip) {
            return readDocx(bytes)
        }
        return decodeText(bytes)
    }

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    private fun readDocx(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    return xml
                        .replace(Regex("</w:p>"), "\n")
                        .replace(Regex("<w:tab/>|<w:br/>"), " ")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&apos;", "'")
                        .replace("&amp;", "&")
                }
            }
        }
        throw java.io.IOException("Не удалось прочитать текст из .docx")
    }
}
