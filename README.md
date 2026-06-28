# EngageKaro Android SDK

Native Kotlin SDK for Android — FCM push, user identity, tags, and events.

## Install

Add the module to your project (local path or Maven when published):

```kotlin
// settings.gradle.kts
includeBuild("../sdks/android") // or publish to Maven

// app/build.gradle.kts
dependencies {
    implementation(project(":engagekaro"))
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
}
```

Apply Google services and add `google-services.json` (see [Flutter Android setup](../flutter/ANDROID_SETUP.md) — same FCM requirements).

## Quick start

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EngageKaro.initialize(
            this,
            EngageKaroConfig(
                appId = "YOUR_PROJECT_ID",
                apiKey = "ek_client_...",
                baseUrl = "https://api.yourhost.com",
            ),
        )
    }
}

// Activity — after user signs in:
lifecycleScope.launch {
    EngageKaro.login("user-123")
    if (Build.VERSION.SDK_INT >= 33) {
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    } else {
        EngageKaro.onPushPermissionResult(true)
    }
    EngageKaro.trackEvent("app_open")
}
```

## API

| Method | Description |
|--------|-------------|
| `initialize(context, config)` | Required once at app start |
| `login(externalId, identityHash?)` | Identify user |
| `logout()` | Clear identity |
| `trackEvent(name, properties?)` | Track custom event |
| `addTag` / `addTags` | Update user tags |
| `addEmail` / `addSms` | Register channel subscriptions |
| `registerPushToken(token)` | Manual FCM token registration |
| `onPushPermissionResult(granted)` | After runtime permission prompt |

## Manifest

The SDK ships `EngagekaroFirebaseMessagingService`. If your app already has an FCM service, forward `onNewToken` / data messages to `EngageKaro` instead of declaring a second service.

Upload the FCM **service account JSON** in the dashboard (Channels → Android · FCM).
