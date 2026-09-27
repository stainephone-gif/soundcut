pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SoundCut"
include(":core", ":desktop")

// Android-приложение собирается, только если установлен Android SDK (Android Studio).
// Для сборки одной ПК-версии в IntelliJ IDEA или из командной строки его не нужно.
if (!providers.gradleProperty("desktopOnly").isPresent) {
    include(":app")
}
