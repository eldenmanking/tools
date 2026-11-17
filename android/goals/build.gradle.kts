// The Android Gradle plugin is only put on the classpath when :app is built, so
// GOALS_CORE_ONLY=1 builds :core where Google's Maven repository can't be reached.
buildscript {
    if (System.getenv("GOALS_CORE_ONLY") != "1") {
        repositories { google() }
        dependencies { classpath("com.android.tools.build:gradle:8.7.3") }
    }
}

plugins {
    kotlin("android") version "2.1.0" apply false
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    kotlin("plugin.compose") version "2.1.0" apply false
}
