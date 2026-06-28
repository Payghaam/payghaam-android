package com.engagekaro.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM service for EngageKaro push — reports foreground/data delivery receipts.
 * Merge into your app's AndroidManifest.xml (see README).
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
        val messageId = data["ek_message_id"] ?: return
        scope.launch {
            EngageKaro.reportPushReceipt(messageId, "delivered", data)
        }
    }
}
