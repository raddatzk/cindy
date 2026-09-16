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

rootProject.name = "Cindy"

// Pure Kotlin: signal pipeline, calibration, workout rules, readiness. No
// Android imports, so everything in it runs as plain JVM unit tests.
include(":core")
// The Android app: camera, MediaPipe, Health Connect, audio, Compose UI.
include(":app")
