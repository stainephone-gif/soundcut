package com.soundcut.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VoskJsonTest {
    @Test
    fun parsesWords() {
        val json = """
            {
              "result" : [{
                  "conf" : 1.000000,
                  "end" : 1.020000,
                  "start" : 0.630000,
                  "word" : "сегодня"
                }, {
                  "conf" : 0.87,
                  "end" : 1.5,
                  "start" : 1.05,
                  "word" : "мы"
                }],
              "text" : "сегодня мы"
            }
        """.trimIndent()
        assertEquals(
            listOf(Word("сегодня", 0.63, 1.02, 1.0), Word("мы", 1.05, 1.5, 0.87)),
            VoskJson.parseWords(json),
        )
        assertEquals(emptyList(), VoskJson.parseWords("{\"text\" : \"\"}"))
    }
}
