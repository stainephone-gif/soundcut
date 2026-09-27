import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.soundcut.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soundcut.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подписываем отладочным ключом, чтобы релизную сборку можно было сразу поставить на телефон.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Файлы модели читаются один раз при первом запуске — сжатие только замедлит копирование.
        noCompress += listOf("mdl", "fst", "int", "conf", "mat", "ie", "txt", "dubm", "stats")
    }
}

dependencies {
    implementation(project(":core"))

    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// ---------------------------------------------------------------------------
// Модель распознавания русской речи Vosk (~45 МБ) скачивается один раз при сборке
// и кладётся внутрь APK, поэтому приложение работает полностью без интернета.
// Если интернета при сборке нет, положите zip-файл модели в папку .vosk-cache/
// в корне проекта (скачать: https://alphacephei.com/vosk/models).
// ---------------------------------------------------------------------------
val voskModelName = "vosk-model-small-ru-0.22"

abstract class PrepareVoskModel : DefaultTask() {
    @get:Input
    abstract val modelName: Property<String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val name = modelName.get()
        val zip = cacheDir.get().asFile.resolve("$name.zip")
        if (!zip.exists()) {
            zip.parentFile.mkdirs()
            val url = "https://alphacephei.com/vosk/models/$name.zip"
            logger.lifecycle("Скачиваю модель распознавания речи: $url")
            val tmp = File(zip.path + ".part")
            try {
                URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } catch (e: Exception) {
                tmp.delete()
                throw GradleException(
                    "Не удалось скачать модель $url. Скачайте её вручную и положите в ${zip.parentFile}",
                    e,
                )
            }
            tmp.renameTo(zip)
        }

        val target = outputDir.get().asFile.resolve("model-ru")
        target.deleteRecursively()
        target.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                // Убираем верхнюю папку архива (vosk-model-small-ru-0.22/...).
                val rel = entry.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val file = target.resolve(rel)
                if (!file.canonicalPath.startsWith(target.canonicalPath)) continue
                if (entry.isDirectory) file.mkdirs() else {
                    file.parentFile.mkdirs()
                    file.outputStream().use { zin.copyTo(it) }
                }
            }
        }
        target.resolve("version.txt").writeText(name)
    }
}

val prepareVoskModel = tasks.register<PrepareVoskModel>("prepareVoskModel") {
    modelName.set(voskModelName)
    cacheDir.set(rootProject.layout.projectDirectory.dir(".vosk-cache"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareVoskModel, PrepareVoskModel::outputDir)
    }
}
