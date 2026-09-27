package com.soundcut.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTest {

    private fun writeWav(file: File, rate: Int, channels: Int, bits: Int, samples: FloatArray) {
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
            .array()
        val frames = samples.size / channels
        val info = WavInfo(rate, channels, bits, false, 0, frames.toLong(), fmt, emptyList())
        val out = ByteArrayOutputStream()
        writeWavHeader(out, info, frames.toLong())
        val buf = ByteArray(samples.size * bits / 8)
        for (i in samples.indices) SampleCodec.encode(samples[i], buf, i * bits / 8, info)
        out.write(buf)
        file.writeBytes(out.toByteArray())
    }

    /** Голосоподобный сигнал: основной тон с гармониками. */
    private fun voice(rate: Int, seconds: Double, f0: Double = 140.0): FloatArray =
        FloatArray((rate * seconds).toInt()) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            for (h in 1..12) v += sin(2 * PI * f0 * h * t) / h
            (0.3 * v).toFloat()
        }

    /** Шумный «вдох»: высокочастотный шум. */
    private fun breath(rate: Int, seconds: Double, rnd: Random): FloatArray {
        var prev = 0f
        return FloatArray((rate * seconds).toInt()) {
            val w = rnd.nextFloat() * 2 - 1
            val hp = w - prev // простейший ФВЧ
            prev = w
            0.05f * hp
        }
    }

    private fun silence(rate: Int, seconds: Double, rnd: Random) =
        FloatArray((rate * seconds).toInt()) { (rnd.nextFloat() * 2 - 1) * 0.0005f }

    @Test
    fun wavRoundTripAndRenderCuts() {
        val dir = createTempDir()
        val src = File(dir, "in.wav")
        val rate = 48000
        val mono = FloatArray(rate * 2) { i -> sin(2 * PI * 440 * i / rate).toFloat() * 0.5f }
        val stereo = FloatArray(mono.size * 2) { mono[it / 2] }
        writeWav(src, rate, 2, 24, stereo)

        val info = WavInfo.read(src)
        assertEquals(48000, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(24, info.bitsPerSample)
        assertEquals(rate * 2L, info.frameCount)

        val segs = listOf(Segment(SegmentKind.TALK, 0.5, 1.0), Segment(SegmentKind.BREATH, 1.2, 1.4))
        val out = ByteArrayOutputStream()
        Renderer.render(src, info, segs, 20.0, out)
        val dst = File(dir, "out.wav").apply { writeBytes(out.toByteArray()) }
        val info2 = WavInfo.read(dst)
        assertEquals(rate * 3L / 2, info2.frameCount)
        assertEquals(24, info2.bitsPerSample)

        // Середина вдоха (1.3 с в исходнике → 0.8 с в результате) приглушена в 10 раз.
        val bytes = dst.readBytes()
        fun sampleAt(frame: Int) = SampleCodec.decode(bytes, (info2.dataOffset + frame.toLong() * info2.frameSize).toInt(), info2)
        var peakBefore = 0f
        var peakMid = 0f
        for (f in 0 until 480) peakBefore = maxOf(peakBefore, abs(sampleAt((0.2 * rate).toInt() + f)))
        for (f in 0 until 480) peakMid = maxOf(peakMid, abs(sampleAt((0.8 * rate).toInt() + f)))
        assertTrue(abs(peakMid / peakBefore - 0.1f) < 0.01f, "peakMid=$peakMid peakBefore=$peakBefore")

        // Нетронутые участки скопированы бит в бит.
        val srcBytes = src.readBytes()
        val o1 = (info.dataOffset + 1000L * info.frameSize).toInt()
        val o2 = (info2.dataOffset + 1000L * info2.frameSize).toInt()
        assertTrue(srcBytes.copyOfRange(o1, o1 + 600).contentEquals(bytes.copyOfRange(o2, o2 + 600)))
        dir.deleteRecursively()
    }

    @Test
    fun breathBetweenPhrasesIsDetected() {
        val rate = 44100
        val rnd = Random(1)
        val parts = listOf(
            silence(rate, 0.5, rnd), voice(rate, 2.0), silence(rate, 0.2, rnd),
            breath(rate, 0.45, rnd), silence(rate, 0.2, rnd), voice(rate, 2.0, 180.0),
            silence(rate, 0.3, rnd), breath(rate, 0.35, rnd), silence(rate, 0.3, rnd),
            voice(rate, 1.5), silence(rate, 0.5, rnd),
        )
        val all = parts.fold(FloatArray(0)) { acc, p -> acc + p }
        val dir = createTempDir()
        val src = File(dir, "b.wav")
        writeWav(src, rate, 1, 16, all)
        val info = WavInfo.read(src)
        val a16 = AnalysisDecoder.decode(src, info)
        assertEquals((all.size * 16000.0 / rate).toInt(), a16.size, 2.0)

        val found = BreathDetector(0.5).detect(a16)
        // Ожидаемые вдохи: 2.7–3.15 и 5.65–6.0 с.
        val expected = listOf(2.7 to 3.15, 5.65 to 6.0)
        assertEquals(2, found.size, found.toString())
        for ((e, f) in expected.zip(found)) {
            assertTrue(f.start in e.first - 0.1..e.first + 0.1 && f.end in e.second - 0.1..e.second + 0.1, "$e vs $f")
        }
        dir.deleteRecursively()
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Double) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    @Suppress("DEPRECATION")
    private fun createTempDir(): File = kotlin.io.createTempDir("soundcut")
}
