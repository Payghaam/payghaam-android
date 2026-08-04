plugins {
    // AGP must match the version pinned by consuming apps' root
    // build.gradle.kts (demo-project, sdks/flutter/example) — Gradle's
    // AgpVersionCompatibilityRule forbids mixing AGP versions across
    // projects in the same build graph, and this module joins that graph
    // via includeBuild composite-build substitution.
    id("com.android.library") version "8.9.1" apply false
    // Kotlin is intentionally capped at 2.0.x, NOT bumped to match the
    // Flutter demo's own 2.1.0. Two different constraints collide here:
    // - The Flutter demo pins Kotlin 2.1.0 for itself, but as a strictly
    //   newer compiler it can always read older (2.0.x) metadata, so this
    //   module staying on 2.0.x doesn't break Flutter.
    // - The React Native demo depends on the React Native Gradle Plugin,
    //   which is only compatible with Kotlin ~1.9.x's plugin API (applying
    //   Kotlin 2.1.0 there breaks RNGP: "Found interface
    //   KotlinTopLevelExtension, but class was expected"). RNGP's compiler
    //   CAN read metadata up to format 2.0.0 (one minor ahead), so this
    //   module must not exceed that when compiling itself, or classes here
    //   become unreadable to the RN demo's build ("compiled with an
    //   incompatible version of Kotlin").
    // 2.0.x is the version that satisfies both apps at once.
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
