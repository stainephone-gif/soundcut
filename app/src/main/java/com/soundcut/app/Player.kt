package com.soundcut.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Проигрывает моно-звук 16 кГц. [onPosition] получает позицию в секундах от начала буфера. */
class Player(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun play(samples: ShortArray, onPosition: (Double) -> Unit, onDone: () -> Unit) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val rate = 16000
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, rate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                var pos = 0
                val chunk = rate / 20
                while (isActive && pos < samples.size) {
                    val n = minOf(chunk, samples.size - pos)
                    val written = track.write(samples, pos, n)
                    if (written <= 0) break
                    pos += written
                    onPosition(track.playbackHeadPosition.toDouble() / rate)
                }
                // Дожидаемся, пока доиграет буфер.
                while (isActive && track.playbackHeadPosition < samples.size) {
                    onPosition(track.playbackHeadPosition.toDouble() / rate)
                    Thread.sleep(30)
                }
            } finally {
                track.pause()
                track.flush()
                track.release()
                onDone()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
