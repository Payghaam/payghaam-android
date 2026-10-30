package com.payghaam.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

/**
 * Renders Payghaam pushes and routes taps.
 *
 * Android pushes are sent data-only (see PushFcmStrategy) so this builder runs in
 * every app state rather than letting FCM draw the tray notification when the app
 * is backgrounded. That is what makes the deep link attachable at all.
 *
 * Tap routing is deliberately two-stage: the PendingIntent always opens the app's
 * launcher activity carrying the payload as extras, and the decision of what to do
 * with [KEY_URL] is made once the app is running. Baking an ACTION_VIEW intent into
 * the PendingIntent would be simpler but wrong — when FCM revives a killed process
 * just to deliver the message, the host app has not registered its handler yet, so
 * we would auto-open every time and rob the app of its routing.
 */
internal object PayghaamNotifications {
    const val KEY_MESSAGE_ID = "ek_message_id"
    const val KEY_URL = "ek_url"
    const val KEY_SOUND = "ek_sound"
    const val KEY_IMAGE = "ek_image"
    const val KEY_OPENED = "ek_opened"

    // Live Updates (Android 16+). See live-activities-design.md §4.3.
    const val KEY_LIVE_EVENT = "ek_live_event"
    const val KEY_LIVE_ID = "ek_live_id"
    const val KEY_LIVE_PROGRESS = "ek_live_progress"
    const val KEY_LIVE_SEGMENTS = "ek_live_segments"
    const val KEY_LIVE_POINTS = "ek_live_points"
    // `ek_live_state` is deliberately not read here. It carries the customer's
    // own free-form state, which only their app can interpret — the SDK renders
    // title/body/progress and nothing else. It still reaches the app: tapIntent
    // forwards every data key as an intent extra.

    private const val CHANNEL_ID = "payghaam_default"
    private const val CHANNEL_NAME = "Notifications"
    private const val IMAGE_TIMEOUT_MS = 5_000

    /** API level that introduced Notification.ProgressStyle / promoted ongoing. */
    private const val SDK_LIVE_UPDATES = 36

    /** Draws the tray notification for a data-only message. No-op if it has no display fields. */
    fun show(context: Context, message: RemoteMessage) {
        val data = message.data
        val title = message.notification?.title ?: data["title"]
        val body = message.notification?.body ?: data["body"]

        // A live update ends by cancelling its notification, which has no display
        // fields of its own — so handle it before the title/body guard below.
        val liveId = data[KEY_LIVE_ID]
        if (liveId != null) {
            showLiveUpdate(context, data, liveId, title, body)
            return
        }

        if (title == null && body == null) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = soundUri(context, data[KEY_SOUND])
        val channelId = ensureChannel(context, nm, sound, data[KEY_SOUND])

        val id = notificationId(message)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).also { if (sound != null) it.setSound(sound) }
        }

        builder.setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentIntent(tapIntent(context, data, id))

        // Rich image. Safe to fetch inline: onMessageReceived already runs off the
        // main thread, and the download is tightly bounded.
        bigPicture(data[KEY_IMAGE])?.let { bitmap ->
            builder.setLargeIcon(bitmap)
            builder.setStyle(Notification.BigPictureStyle().bigPicture(bitmap))
        }

        nm.notify(id, builder.build())
    }

    /**
     * Renders a Live Update — the Android counterpart to an iOS Live Activity.
     *
     * Unlike iOS, where APNs updates a system-managed activity directly, Android
     * has no remote update mechanism: the server sends an ordinary data message
     * and *we* re-post the notification. So "update" is just a repost under the
     * same tag, and "end" is a cancel.
     *
     * [liveId] is the notification tag, which is what makes an update replace the
     * existing notification rather than stack a second one.
     *
     * Degrades in two steps rather than failing: below API 36 this is a plain
     * ongoing notification (no promotion, no segmented bar), and below API 26 it
     * loses the channel too. A user on Android 14 sees a working, if plainer,
     * progress notification.
     */
    private fun showLiveUpdate(
        context: Context,
        data: Map<String, String>,
        liveId: String,
        title: String?,
        body: String?,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = liveId.hashCode()

        if (data[KEY_LIVE_EVENT] == "end") {
            nm.cancel(liveId, id)
            return
        }

        // Promotion requires a contentTitle; without one the OS silently declines
        // to promote, so there is nothing useful to post.
        if (title == null) return

        val channelId = ensureChannel(context, nm, null, null)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setContentTitle(title)
            .setContentText(body)
            // Ongoing is a hard requirement for promotion, and correct anyway:
            // a journey in progress should not be swipe-dismissible.
            .setOngoing(true)
            .setAutoCancel(false)
            // Alert once, then update silently. The default channel is
            // IMPORTANCE_HIGH, so without this every progress tick would buzz —
            // a tracker refreshing every 30s would be unusable. The first post
            // still gets the user's attention; the rest just redraw.
            .setOnlyAlertOnce(true)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentIntent(tapIntent(context, data, id))

        if (Build.VERSION.SDK_INT >= SDK_LIVE_UPDATES) {
            applyProgressStyle(builder, data)
        } else {
            // Pre-36 fallback: the platform's flat progress bar. Loses segments
            // and milestone points, keeps the sense of progress.
            data[KEY_LIVE_PROGRESS]?.toIntOrNull()?.let { builder.setProgress(100, it, false) }
        }

        nm.notify(liveId, id, builder.build())
    }

    /**
     * Applies the API 36 ProgressStyle: segmented bar, milestone points, and the
     * promotion request that lifts the notification into the status bar chip.
     *
     * Reflection is deliberate. The SDK compiles against 36 but ships to apps
     * still on older build tools, and a direct reference would hard-fail their
     * build; this keeps the whole feature optional at the call site. It is also
     * confined to this one function, so the rest of the file stays type-checked.
     */
    private fun applyProgressStyle(builder: Notification.Builder, data: Map<String, String>) {
        try {
            val styleClass = Class.forName("android.app.Notification\$ProgressStyle")
            val style = styleClass.getDeclaredConstructor().newInstance()

            data[KEY_LIVE_PROGRESS]?.toIntOrNull()?.let { progress ->
                styleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                    .invoke(style, progress)
            }

            buildSegments(data[KEY_LIVE_SEGMENTS])?.let { segments ->
                styleClass.getMethod("setProgressSegments", List::class.java)
                    .invoke(style, segments)
            }

            buildPoints(data[KEY_LIVE_POINTS])?.let { points ->
                styleClass.getMethod("setProgressPoints", List::class.java)
                    .invoke(style, points)
            }

            builder.setStyle(style as Notification.Style)
            // The actual "make this a Live Update" request. Without it the
            // notification posts as an ordinary ongoing one.
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
        } catch (_: Exception) {
            // Any reflection miss (an OEM build, an API rename) falls back to the
            // flat bar rather than dropping the notification entirely.
            data[KEY_LIVE_PROGRESS]?.toIntOrNull()?.let { builder.setProgress(100, it, false) }
        }
    }

    /** Parses `ek_live_segments` into ProgressStyle.Segment instances (`length` + colour). */
    private fun buildSegments(json: String?): List<Any>? =
        buildParts(json, "android.app.Notification\$ProgressStyle\$Segment", "length", 1)

    /** Parses `ek_live_points` into ProgressStyle.Point instances (`at` + colour). */
    private fun buildPoints(json: String?): List<Any>? =
        buildParts(json, "android.app.Notification\$ProgressStyle\$Point", "at", 0)

    /**
     * Segment and Point have the same shape — one int in the constructor plus an
     * optional colour — so they share a builder rather than duplicating the
     * reflection twice.
     */
    private fun buildParts(
        json: String?,
        className: String,
        intField: String,
        intDefault: Int,
    ): List<Any>? {
        if (json.isNullOrBlank()) return null
        return try {
            val cls = Class.forName(className)
            val ctor = cls.getDeclaredConstructor(Int::class.javaPrimitiveType)
            val setColor = cls.getMethod("setColor", Int::class.javaPrimitiveType)
            val arr = org.json.JSONArray(json)
            val out = ArrayList<Any>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val part = ctor.newInstance(o.optInt(intField, intDefault))
                parseColor(o.opt("color"))?.let { setColor.invoke(part, it) }
                out.add(part)
            }
            out.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Accepts either an ARGB int or a "#rrggbb" string, matching the wire contract. */
    private fun parseColor(value: Any?): Int? = when (value) {
        is Int -> value
        is String -> try {
            android.graphics.Color.parseColor(value)
        } catch (_: IllegalArgumentException) {
            null
        }
        else -> null
    }

    /** Downloads the notification image, or null if it can't be had quickly. */
    private fun bigPicture(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = IMAGE_TIMEOUT_MS
                readTimeout = IMAGE_TIMEOUT_MS
                doInput = true
            }
            try {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            // An unreachable or malformed image must never cost the notification.
            null
        }
    }

    /**
     * Resolves a `res/raw` resource name to a playable URI, or null when the app
     * ships no such file — a bad URI would silence the notification entirely, so an
     * unresolvable name falls back to the system default rather than nothing.
     */
    private fun soundUri(context: Context, name: String?): Uri? {
        if (name.isNullOrBlank()) return null
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        if (resId == 0) return null
        return Uri.parse("android.resource://${context.packageName}/$resId")
    }

    /**
     * Returns the channel to post on, creating it if needed.
     *
     * On Android 8+ the sound is a property of the *channel*, not the notification,
     * and a channel's sound is immutable after creation — so a custom sound means
     * posting to a channel dedicated to that sound.
     */
    private fun ensureChannel(
        context: Context,
        nm: NotificationManager,
        sound: Uri?,
        soundName: String?,
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CHANNEL_ID

        val channelId =
            if (sound == null || soundName.isNullOrBlank()) CHANNEL_ID
            else "${CHANNEL_ID}_sound_$soundName"
        if (nm.getNotificationChannel(channelId) != null) return channelId

        val name = if (channelId == CHANNEL_ID) CHANNEL_NAME else "$CHANNEL_NAME ($soundName)"
        val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH)
        if (sound != null) {
            channel.setSound(
                sound,
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build(),
            )
        }
        nm.createNotificationChannel(channel)
        return channelId
    }

    private fun tapIntent(context: Context, data: Map<String, String>, id: Int): PendingIntent? {
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(KEY_OPENED, true)
                for ((k, v) in data) putExtra(k, v)
            } ?: return null

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getActivity(context, id, launch, flags)
    }

    private fun notificationId(message: RemoteMessage): Int =
        (message.data[KEY_MESSAGE_ID] ?: message.messageId ?: System.currentTimeMillis().toString())
            .hashCode()

    /**
     * Pulls an Payghaam payload out of a tap intent, or null if this intent did not
     * come from one of our notifications. Clears the marker so a config change or a
     * resume does not re-dispatch the same tap.
     */
    fun payloadFrom(intent: Intent?): Map<String, String>? {
        if (intent == null || !intent.getBooleanExtra(KEY_OPENED, false)) return null
        val extras = intent.extras ?: return null
        val payload = HashMap<String, String>()
        for (key in extras.keySet()) {
            if (key == KEY_OPENED) continue
            extras.getString(key)?.let { payload[key] = it }
        }
        intent.removeExtra(KEY_OPENED)
        return payload
    }

    /** Opens [url] in whatever app claims it. Returns false if nothing can handle it. */
    fun openUrl(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
