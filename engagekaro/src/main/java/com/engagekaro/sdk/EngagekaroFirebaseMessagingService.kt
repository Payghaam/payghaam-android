package com.engagekaro.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM service for EngageKaro push — draws the notification and reports delivery
 * receipts. Merge into your app's AndroidManifest.xml (see README).
 *
 * EngageKaro sends Android pushes data-only, so this runs whether the app is in
 * the foreground, backgrounded, or was killed (FCM starts the process for us).
 */
class EngagekaroFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            if (EngageKaro.isInitialized) EngageKaro.registerPushToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        // Draw first: the receipt is best-effort and must never cost the user the
        // notification (it is skipped entirely when the SDK is uninitialized).
        EngagekaroNotifications.show(this, message)

        val messageId = data[EngagekaroNotifications.KEY_MESSAGE_ID] ?: return
        scope.launch {
            EngageKaro.reportPushReceipt(messageId, "delivered", data)
        }
    }
}
