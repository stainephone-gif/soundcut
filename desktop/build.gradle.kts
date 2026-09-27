import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Офлайн-распознавание речи; в jar уже есть библиотеки для Windows, Linux и macOS.
    implementation("com.alphacephei:vosk:0.3.45")
    implementation("net.java.dev.jna:jna:5.13.0")
}

compose.desktop {
    application {
        mainClass = "com.soundcut.desktop.MainKt"
        jvmArgs += listOf("-Xmx4g", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "SoundCut"
            packageVersion = "1.0.0"
            description = "Чистка дикторских записей: дыхание, переговоры, дубли"
            vendor = "SoundCut"
            modules("java.desktop", "java.prefs", "java.net.http", "jdk.unsupported")
            windows {
                menuGroup = "SoundCut"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1c5f0e-6a53-4b0b-9a3e-2f4f0c9b7d11"
            }
        }
    }
}
