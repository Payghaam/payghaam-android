plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Publishes to Maven Central via the Central Publisher Portal. Pinned to
    // 0.35.0, NOT the latest release — 0.36.0+ raises the plugin's own minimum
    // required AGP (8.13.0) and Kotlin (2.2.0), which conflicts with this
    // module's own AGP 8.9.1 / Kotlin 2.0.21 cap (see build.gradle.kts at the
    // repo root for why those are capped — React Native's Gradle Plugin breaks
    // above Kotlin ~2.0.x). 0.35.0's own minimums (AGP 8.2.2, Kotlin 1.9.20)
    // fit inside that cap.
    id("com.vanniktech.maven.publish") version "0.35.0"
}

// Stable coordinates so wrapper SDKs (Flutter, React Native) can depend on this
// module via a Gradle composite build (local dev) or Maven Central (published)
// without knowing its local file path.
group = "com.payghaam"
version = "0.1.0"

android {
    namespace = "com.payghaam.sdk"
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
    // `api`, not `implementation` — both the Flutter (payghaam_flutter) and
    // React Native Android wrapper modules import FirebaseMessagingService /
    // RemoteMessage and kotlinx.coroutines types directly in their own Kotlin
    // sources, relying on these being visible transitively through their
    // `implementation "com.payghaam:payghaam"` dependency. `implementation`
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

// Maven Central publishing. Credentials/signing come from env vars in CI (see
// .github/workflows/publish.yml) or ~/.gradle/gradle.properties locally — never
// hardcode them here. The plugin reads ORG_GRADLE_PROJECT_mavenCentralUsername,
// ORG_GRADLE_PROJECT_mavenCentralPassword (a Central Portal user token, from
// central.sonatype.com/account), ORG_GRADLE_PROJECT_signingInMemoryKey,
// ORG_GRADLE_PROJECT_signingInMemoryKeyId, and
// ORG_GRADLE_PROJECT_signingInMemoryKeyPassword automatically — nothing else
// needed here for credentials.
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("com.payghaam", "payghaam", version.toString())

    pom {
        name.set("Payghaam Android SDK")
        description.set("Payghaam Android SDK — direct FCM push, identify, tags, events.")
        url.set("https://github.com/Payghaam/payghaam-android")
        licenses {
            license {
                name.set("MIT")
                url.set("https://github.com/Payghaam/payghaam-android/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("payghaam")
                name.set("Payghaam")
                email.set("dev@payghaam.com")
            }
        }
        scm {
            url.set("https://github.com/Payghaam/payghaam-android")
            connection.set("scm:git:https://github.com/Payghaam/payghaam-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/Payghaam/payghaam-android.git")
        }
    }
}
