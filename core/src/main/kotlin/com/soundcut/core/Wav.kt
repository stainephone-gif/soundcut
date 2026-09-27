package com.soundcut.core

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFormatException(message: String) : IOException(message)

/** Описание WAV-файла. Чанк fmt хранится как есть, чтобы результат был в точно таком же формате. */
class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val dataOffset: Long,
    val frameCount: Long,
    val fmtChunk: ByteArray,
    /** Служебные чанки (bext, LIST, iXML), которые копируются в результат без изменений. */
    val extraChunks: List<Pair<String, ByteArray>>,
) {
    val bytesPerSample: Int get() = bitsPerSample / 8
    val frameSize: Int get() = bytesPerSample * channels
    val durationSeconds: Double get() = frameCount.toDouble() / sampleRate

    companion object {
        private const val FORMAT_PCM = 1
        private const val FORMAT_FLOAT = 3
        private const val FORMAT_EXTENSIBLE = 0xFFFE
        private val KEPT_CHUNKS = setOf("bext", "LIST", "iXML")

        fun read(file: File): WavInfo = RandomAccessFile(file, "r").use { read(it) }

        fun read(raf: RandomAccessFile): WavInfo {
            val fileLength = raf.length()
            val header = ByteArray(12)
            raf.seek(0)
            if (raf.read(header) != 12) throw WavFormatException("Файл слишком короткий")
            val riff = String(header, 0, 4, Charsets.US_ASCII)
            if (riff != "RIFF" && riff != "RF64") throw WavFormatException("Это не WAV-файл")
            if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") throw WavFormatException("Это не WAV-файл")

            var fmt: ByteArray? = null
            var dataOffset = -1L
            var dataSize = -1L
            var ds64DataSize = -1L
            val extra = mutableListOf<Pair<String, ByteArray>>()
            var pos = 12L
            val chunkHeader = ByteArray(8)
            while (pos + 8 <= fileLength) {
                raf.seek(pos)
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val body = pos + 8
                when (id) {
                    "fmt " -> fmt = ByteArray(size.toInt()).also { raf.readFully(it) }
                    "ds64" -> {
                        val b = ByteArray(minOf(size, 28L).toInt()).also { raf.readFully(it) }
                        if (b.size >= 16) ds64DataSize = ByteBuffer.wrap(b, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
                    }
                    "data" -> {
                        dataOffset = body
                        dataSize = when {
                            size == 0xFFFFFFFFL && ds64DataSize > 0 -> ds64DataSize
                            size == 0xFFFFFFFFL || size == 0L || body + size > fileLength -> fileLength - body
                            else -> size
                        }
                        break
                    }
                    else -> if (id in KEPT_CHUNKS && size < 16L * 1024 * 1024) {
                        extra += id to ByteArray(size.toInt()).also { raf.readFully(it) }
                    }
                }
                pos = body + size + (size and 1L)
            }
            if (fmt == null || fmt.size < 16) throw WavFormatException("В файле нет описания формата (fmt)")
            if (dataOffset < 0) throw WavFormatException("В файле нет аудиоданных")

            val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
            var formatTag = bb.getShort(0).toInt() and 0xFFFF
            val channels = bb.getShort(2).toInt() and 0xFFFF
            val sampleRate = bb.getInt(4)
            val bits = bb.getShort(14).toInt() and 0xFFFF
            if (formatTag == FORMAT_EXTENSIBLE && fmt.size >= 26) {
                formatTag = bb.getShort(24).toInt() and 0xFFFF
            }
            val isFloat = when (formatTag) {
                FORMAT_PCM -> false
                FORMAT_FLOAT -> true
                else -> throw WavFormatException("Сжатый WAV не поддерживается (код формата $formatTag). Нужен PCM или float.")
            }
            if (channels < 1) throw WavFormatException("Неверное число каналов")
            if (sampleRate < 8000) throw WavFormatException("Слишком низкая частота дискретизации: $sampleRate Гц")
            val supported = if (isFloat) bits == 32 || bits == 64 else bits in intArrayOf(8, 16, 24, 32)
            if (!supported) throw WavFormatException("Разрядность $bits бит не поддерживается")
            val frameSize = bits / 8 * channels
            return WavInfo(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bits,
                isFloat = isFloat,
                dataOffset = dataOffset,
                frameCount = dataSize / frameSize,
                fmtChunk = fmt,
                extraChunks = extra,
            )
        }
    }
}

/** Перевод байтов отсчёта в число от -1 до 1 и обратно. */
internal object SampleCodec {
    fun decode(buf: ByteArray, off: Int, info: WavInfo): Float {
        return if (info.isFloat) {
            if (info.bitsPerSample == 32) {
                Float.fromBits(readInt32(buf, off))
            } else {
                Double.fromBits(readInt64(buf, off)).toFloat()
            }
        } else when (info.bitsPerSample) {
            8 -> ((buf[off].toInt() and 0xFF) - 128) / 128f
            16 -> ((buf[off].toInt() and 0xFF) or (buf[off + 1].toInt() shl 8)).toShort() / 32768f
            24 -> ((buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8) or
                (buf[off + 2].toInt() shl 16)) / 8388608f
            else -> (readInt32(buf, off) / 2147483648.0).toFloat()
        }
    }

    fun encode(value: Float, buf: ByteArray, off: Int, info: WavInfo) {
        if (info.isFloat) {
            if (info.bitsPerSample == 32) writeInt32(buf, off, value.toBits())
            else writeInt64(buf, off, value.toDouble().toBits())
            return
        }
        val v = value.coerceIn(-1f, 1f)
        when (info.bitsPerSample) {
            8 -> buf[off] = (Math.round(v * 127f) + 128).coerceIn(0, 255).toByte()
            16 -> {
                val s = Math.round(v * 32767f).coerceIn(-32768, 32767)
                buf[off] = s.toByte(); buf[off + 1] = (s shr 8).toByte()
            }
            24 -> {
                val s = Math.round(v * 8388607f).coerceIn(-8388608, 8388607)
                buf[off] = s.toByte(); buf[off + 1] = (s shr 8).toByte(); buf[off + 2] = (s shr 16).toByte()
            }
            else -> writeInt32(buf, off, Math.round(v.toDouble() * 2147483647.0).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun readInt32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or (b[o + 3].toInt() shl 24)

    private fun readInt64(b: ByteArray, o: Int): Long =
        (readInt32(b, o).toLong() and 0xFFFFFFFFL) or (readInt32(b, o + 4).toLong() shl 32)

    private fun writeInt32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }

    private fun writeInt64(b: ByteArray, o: Int, v: Long) {
        writeInt32(b, o, v.toInt()); writeInt32(b, o + 4, (v shr 32).toInt())
    }
}

/** Пишет заголовок WAV для [frameCount] кадров в формате [info]. */
fun writeWavHeader(out: OutputStream, info: WavInfo, frameCount: Long) {
    val dataSize = frameCount * info.frameSize
    val fmt = info.fmtChunk
    val chunks = ArrayList<Pair<String, ByteArray>>()
    chunks += "fmt " to fmt
    if (info.isFloat) {
        // Для float-формата по стандарту нужен чанк fact с числом кадров.
        chunks += "fact" to ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(frameCount.toInt()).array()
    }
    chunks += info.extraChunks
    var riffSize = 4L
    for ((_, body) in chunks) riffSize += 8 + body.size + (body.size and 1)
    riffSize += 8 + dataSize + (dataSize and 1L)
    if (riffSize > 0xFFFFFFFFL) throw IOException("Результат больше 4 ГБ — такой WAV записать нельзя")

    val bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
    bb.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(riffSize.toInt()).put("WAVE".toByteArray(Charsets.US_ASCII))
    out.write(bb.array())
    for ((id, body) in chunks) {
        out.write(chunkHeader(id, body.size.toLong()))
        out.write(body)
        if (body.size and 1 == 1) out.write(0)
    }
    out.write(chunkHeader("data", dataSize))
}

private fun chunkHeader(id: String, size: Long): ByteArray =
    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .put(id.toByteArray(Charsets.US_ASCII)).putInt(size.toInt()).array()
