import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    // Screenshot tests rendered on the JVM: ./gradlew :app:verifyPaparazziDebug
    id("app.cash.paparazzi")
}

android {
    namespace = "goals.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "goals.app"
        minSdk = 33 // the target tablet runs Android 13
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    // Foundation only, no Material: no ripples or animated components to ghost on e-ink.
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
