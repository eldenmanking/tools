import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

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
        // The Apps Script /exec URL and token, from local.properties (kept out of git). Without
        // them the app shows sample data.
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
        }
        fun quoted(key: String) = "\"" + local.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "GATE_URL", quoted("gate.url"))
        buildConfigField("String", "GATE_TOKEN", quoted("gate.token"))
    }

    buildTypes {
        release {
            // R8 strips unused code (most of Compose) and resources: a smaller APK and less code
            // mapped into memory, which matters on the e-ink tablet.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded personal app: sign release builds with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
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
