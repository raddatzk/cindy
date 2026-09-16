plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Upload signing for Google Play (Play App Signing re-signs with Google's key). Everything comes
// from the environment, so the same build signs locally and in CI and no secret is ever in the
// repository. Without CINDY_UPLOAD_KEYSTORE the release build stays unsigned, which is all the CI
// build job needs. Blank counts as unset: GitHub Actions passes a missing secret as "".
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val uploadKeystore: String? = env("CINDY_UPLOAD_KEYSTORE")

// Only the major number is maintained here. The release workflow passes
// <major>.<commits on main> as the version name (the same version the iOS app gets for the same
// commit) and 10 × run number + attempt as the version code; local builds are simply 1.0 (1).
val majorVersion = 1

android {
    namespace = "me.raddatz.cindy"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.raddatz.cindy"
        minSdk = 29
        targetSdk = 36
        // Play rejects an upload whose versionCode is not higher than every earlier one.
        versionCode = env("CINDY_VERSION_CODE")?.toInt() ?: 1
        versionName = env("CINDY_VERSION_NAME") ?: "$majorVersion.0"
    }

    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                val password = checkNotNull(env("CINDY_UPLOAD_STORE_PASSWORD")) {
                    "CINDY_UPLOAD_KEYSTORE is set, but CINDY_UPLOAD_STORE_PASSWORD is not."
                }
                storePassword = password
                keyAlias = env("CINDY_UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = env("CINDY_UPLOAD_KEY_PASSWORD") ?: password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "de")
        generateLocaleConfig = true
        // MediaPipe memory-maps its models from the APK; compressed assets cannot be mapped.
        noCompress += listOf("tflite", "task")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mediapipe.tasks.vision) {
        // MediaPipe's usage logging uploads through Google's data-transport library (and its
        // Firebase encoders). Cindy stays offline: the library is left out and the few classes the
        // logger links against are inert stand-ins (src/main/kotlin/com/google/android/datatransport).
        exclude(group = "com.google.android.datatransport")
        exclude(group = "com.google.firebase")
    }
    implementation(libs.health.connect)
    implementation(libs.work.runtime)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
