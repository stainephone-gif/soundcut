package com.soundcut.core

/** Слово, распознанное в записи. Время в секундах от начала файла. */
data class Word(
    val text: String,
    val start: Double,
    val end: Double,
    val conf: Double = 1.0,
)

enum class SegmentKind {
    /** Дыхание — приглушается. */
    BREATH,

    /** Посторонние переговоры (не по тексту) — вырезаются. */
    TALK,

    /** Неудачный дубль, который потом был перечитан, — вырезается. */
    RETAKE;

    val isCut: Boolean get() = this != BREATH
}

/** Найденный фрагмент. [enabled] — применять ли правку (пользователь может снять отметку). */
data class Segment(
    val kind: SegmentKind,
    val start: Double,
    val end: Double,
    val text: String = "",
    val enabled: Boolean = true,
) {
    val duration: Double get() = end - start
}

data class ProcessingOptions(
    /** Насколько приглушать дыхание, дБ. */
    val breathAttenuationDb: Double = 18.0,
    /** Чувствительность поиска дыхания: 0 — только явное, 1 — максимально. */
    val breathSensitivity: Double = 0.5,
    val detectBreaths: Boolean = true,
    val detectTalk: Boolean = true,
    val detectRetakes: Boolean = true,
)
