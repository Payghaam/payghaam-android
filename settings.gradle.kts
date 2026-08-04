pluginManagement {
    // Included builds resolve plugins (e.g. com.android.library, declared in
    // build.gradle.kts) from THIS settings file's repositories — they do not
    // inherit the consuming app's pluginManagement block. Without this,
    // `includeBuild("../../../sdks/android")` from demo-project fails with
    // "Plugin [id: 'com.android.library' ...] was not found", since the
    // default (no pluginManagement) resolution only checks Gradle Plugin
    // Portal, and AGP is only published to google().
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Separate from pluginManagement.repositories above: this covers regular
// dependency resolution (firebase-messaging, kotlinx-coroutines-android,
// etc.) for modules in this build. Same reasoning — an included build
// doesn't inherit the consuming app's `allprojects { repositories {...} }`.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "engagekaro-android"
include(":engagekaro")
