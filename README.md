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
| `onNotificationOpened` | Handler for notification taps |
| `handleNotificationIntent(intent)` | Forward from `onNewIntent` (singleTop activities) |

## Handling taps and deep links

A campaign's **Deep link URL** arrives as `ek_url`, and anything you pass as `data`
on `POST /api/notifications` arrives alongside it:

```kotlin
EngageKaro.onNotificationOpened = { payload ->
    // payload["ek_url"]   → "myapp://offers/summer"
    // payload["targetId"] → your own data key
    payload["targetId"]?.let { openOffer(it) }
}
```

If you register **no** handler, the SDK opens `ek_url` itself with `ACTION_VIEW`.
Registering one suppresses that, so routing — including the deep link — is yours.

If your launcher activity is `singleTop` or `singleTask`, Android delivers taps through
`onNewIntent`, which does not update `getIntent()`. Forward it:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    EngageKaro.handleNotificationIntent(intent)
}
```

Reserved payload keys: `ek_message_id`, `ek_url`, `ek_image`, `ek_sound`, `ek_opened`,
`title`, `body`.

## Manifest

The SDK ships `EngagekaroFirebaseMessagingService`, which draws the notification and
attaches the tap intent. EngageKaro sends Android pushes **data-only** so this runs in
every app state — declaring it is required, or nothing is displayed.

If your app already has an FCM service, forward `onNewToken` / data messages to
`EngageKaro` instead of declaring a second service.

Upload the FCM **service account JSON** in the dashboard (Channels → Android · FCM).
