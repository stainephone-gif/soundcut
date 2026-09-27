package com.soundcut.desktop

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipFile

/** Модели распознавания русской речи Vosk, которые можно скачать из программы. */
enum class ModelChoice(val id: String, val title: String, val details: String) {
    SMALL(
        "vosk-model-small-ru-0.22",
        "Быстрая (45 МБ)",
        "Работает на любом компьютере, распознаёт хуже.",
    ),
    LARGE(
        "vosk-model-ru-0.42",
        "Точная (1,8 ГБ)",
        "Заметно точнее, но нужно 8–16 ГБ оперативной памяти, распознаёт медленнее.",
    );

    val url: String get() = "https://alphacephei.com/vosk/models/$id.zip"
}

object ModelStore {
    /**
     * Папка для моделей. Библиотека распознавания на Windows не понимает русские буквы в пути,
     * поэтому, если в имени пользователя есть кириллица, модели кладутся в C:\ProgramData.
     */
    val root: File by lazy {
        val home = File(System.getProperty("user.home"), ".soundcut/models")
        val programData = System.getenv("ProgramData")
        if (isAsciiPath(home) || programData == null) home else File(programData, "SoundCut/models")
    }

    fun isAsciiPath(f: File): Boolean = f.absolutePath.all { it.code < 128 }

    /** Папка модели: сама [dir] или единственная вложенная папка (если выбрали распакованный архив). */
    fun resolveModelDir(dir: File): File? {
        fun isModel(d: File) = File(d, "am").isDirectory || File(d, "conf/model.conf").isFile
        if (isModel(dir)) return dir
        return dir.listFiles()?.filter { it.isDirectory }?.firstOrNull { isModel(it) }
    }

    fun installed(): List<File> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { resolveModelDir(it) }.orEmpty()

    fun find(choice: ModelChoice): File? = resolveModelDir(File(root, choice.id))

    suspend fun download(choice: ModelChoice, onProgress: (Double) -> Unit): File {
        root.mkdirs()
        val zip = File(root, "${choice.id}.zip.part")
        val conn = URI(choice.url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        try {
            if (conn.responseCode != 200) error("Сервер ответил ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toDouble() / total * 0.9)
                    }
                }
            }
        } catch (e: Throwable) {
            zip.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        val dir = unzip(zip, File(root, choice.id)) { onProgress(0.9 + it * 0.1) }
        zip.delete()
        return dir
    }

    /** Распаковывает архив модели, выбранный пользователем или скачанный. */
    suspend fun installFromZip(zip: File, onProgress: (Double) -> Unit): File {
        root.mkdirs()
        return unzip(zip, File(root, zip.nameWithoutExtension.removeSuffix(".zip"))) { onProgress(it) }
    }

    private suspend fun unzip(zip: File, target: File, onProgress: (Double) -> Unit): File {
        target.deleteRecursively()
        target.mkdirs()
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            entries.forEachIndexed { i, e ->
                currentCoroutineContext().ensureActive()
                val out = File(target, e.name)
                if (!out.canonicalPath.startsWith(target.canonicalPath)) return@forEachIndexed
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile.mkdirs()
                    zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                onProgress((i + 1).toDouble() / entries.size)
            }
        }
        return resolveModelDir(target) ?: run {
            target.deleteRecursively()
            error("В архиве нет модели Vosk")
        }
    }
}
