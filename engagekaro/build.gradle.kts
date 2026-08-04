plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Stable coordinates so wrapper SDKs (Flutter, React Native) can depend on this
// module via a Gradle composite build without knowing its local file path — see
// sdk-native-wrapper-design.md.
group = "com.engagekaro"
version = "0.1.0"

android {
    namespace = "com.engagekaro.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // `api`, not `implementation` — both the Flutter (engagekaro_flutter) and
    // React Native Android wrapper modules import FirebaseMessagingService /
    // RemoteMessage and kotlinx.coroutines types directly in their own Kotlin
    // sources, relying on these being visible transitively through their
    // `implementation "com.engagekaro:engagekaro"` dependency. `implementation`
    // here would hide them from those consumers ("Unresolved reference
    // 'google'"/'FirebaseMessaging'` etc. at compile time).
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("com.google.firebase:firebase-messaging:24.0.0")
    // NOTE: do NOT exclude com.google.android.gms:play-services-stats here. It
    // was excluded briefly to dodge an androidx.legacy resolution failure that
    // was actually caused by a missing `android.useAndroidX=true` (now set in
    // gradle.properties). play-services-stats provides
    // com.google.android.gms.stats.WakeLock, which Firebase Messaging's legacy
    // background wake path (FirebaseInstanceIdReceiver -> ServiceStarter ->
    // WakeLockHolder) needs at RUNTIME to wake the app for a data-only push
    // when backgrounded/killed — excluding it compiles fine but breaks exactly
    // the delivery path this SDK relies on (NoClassDefFoundError at runtime).
}
