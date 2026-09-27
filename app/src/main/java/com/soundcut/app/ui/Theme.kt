package com.soundcut.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.soundcut.core.SegmentKind

private val Light = lightColorScheme(
    primary = Color(0xFF1E5A96),
    secondary = Color(0xFFE0663F),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8EC1F2),
    secondary = Color(0xFFFF9A7A),
)

@Composable
fun SoundCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

fun SegmentKind.color(): Color = when (this) {
    SegmentKind.BREATH -> Color(0xFF2F8FD8)
    SegmentKind.TALK -> Color(0xFFD83B3B)
    SegmentKind.RETAKE -> Color(0xFFE89B1C)
}

fun SegmentKind.title(): String = when (this) {
    SegmentKind.BREATH -> "Дыхание"
    SegmentKind.TALK -> "Переговоры"
    SegmentKind.RETAKE -> "Дубль"
}

fun formatTime(sec: Double): String {
    val total = sec.coerceAtLeast(0.0)
    val m = (total / 60).toInt()
    val s = total - m * 60
    return "%d:%04.1f".format(m, s)
}
