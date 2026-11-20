// GOALS_CORE_ONLY=1 builds only :core, without Google's Maven repository or the Android SDK.
val coreOnly = System.getenv("GOALS_CORE_ONLY") == "1"

pluginManagement {
    repositories {
        if (System.getenv("GOALS_CORE_ONLY") != "1") google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!coreOnly) google()
        mavenCentral()
    }
}

rootProject.name = "goals"

// core: plain Kotlin (status model, gate decision), builds without the Android SDK.
include(":core")
// app: the Android app.
if (!coreOnly) include(":app")
