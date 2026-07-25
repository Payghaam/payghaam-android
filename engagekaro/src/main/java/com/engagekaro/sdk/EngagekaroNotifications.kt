package com.engagekaro.sdk

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
 * Renders EngageKaro pushes and routes taps.
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
internal object EngagekaroNotifications {
    const val KEY_MESSAGE_ID = "ek_message_id"
    const val KEY_URL = "ek_url"
    const val KEY_SOUND = "ek_sound"
    const val KEY_IMAGE = "ek_image"
    const val KEY_OPENED = "ek_opened"

    private const val CHANNEL_ID = "engagekaro_default"
    private const val CHANNEL_NAME = "Notifications"
    private const val IMAGE_TIMEOUT_MS = 5_000

    /** Draws the tray notification for a data-only message. No-op if it has no display fields. */
    fun show(context: Context, message: RemoteMessage) {
        val data = message.data
        val title = message.notification?.title ?: data["title"]
        val body = message.notification?.body ?: data["body"]
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
     * Pulls an EngageKaro payload out of a tap intent, or null if this intent did not
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
