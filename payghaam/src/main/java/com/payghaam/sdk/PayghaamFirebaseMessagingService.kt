package com.payghaam.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM service for Payghaam push — draws the notification and reports delivery
 * receipts. Merge into your app's AndroidManifest.xml (see README).
 *
 * Payghaam sends Android pushes data-only, so this runs whether the app is in
 * the foreground, backgrounded, or was killed (FCM starts the process for us).
 */
class PayghaamFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            if (Payghaam.isInitialized) Payghaam.registerPushToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Draws the notification and reports `delivered` — see Payghaam.handleRemoteMessage.
        Payghaam.handleRemoteMessage(this, message)
    }
}
