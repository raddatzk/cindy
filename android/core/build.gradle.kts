plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

// The CSV fixtures are shared with the iOS tests, so both detectors are held
// to the same rep counts on the same recordings.
sourceSets {
    test {
        resources.srcDir("../../shared/fixtures")
    }
}
