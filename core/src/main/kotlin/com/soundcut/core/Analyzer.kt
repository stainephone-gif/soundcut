package com.soundcut.core

/** Всё, что делается после распознавания речи: дыхание, переговоры, дубли. */
object Analyzer {
    fun analyze(
        audio16k: ShortArray,
        words: List<Word>,
        script: String?,
        options: ProcessingOptions,
        onProgress: (Double) -> Unit = {},
    ): List<Segment> {
        val total = audio16k.size.toDouble() / AnalysisDecoder.RATE
        val labels = TakeAnalyzer.analyze(words, script, options.detectTalk, options.detectRetakes)
        val breaths = if (options.detectBreaths) {
            BreathDetector(options.breathSensitivity).detect(audio16k, words, onProgress)
        } else emptyList()
        onProgress(1.0)
        return EditPlanner.plan(words, labels, breaths, audio16k, total)
    }
}
