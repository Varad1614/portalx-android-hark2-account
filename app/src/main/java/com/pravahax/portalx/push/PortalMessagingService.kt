package com.pravahax.portalx.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pravahax.portalx.data.RepoHost
import com.pravahax.portalx.widget.TodayWidget

/** v0.8: receives FCM token rotations and gateway alerts. Nothing is shown when signed out. */
class PortalMessagingService : FirebaseMessagingService() {
    private val repo get() = (application as? RepoHost)?.repo

    override fun onNewToken(token: String) { repo?.let { Push.send(it, token) } }

    override fun onMessageReceived(message: RemoteMessage) {
        val r = repo ?: return
        if (!r.api.hasSession()) return
        val m = PushMessage.from(message.data, message.notification?.title, message.notification?.body) ?: return
        r.invalidate(m.stale)
        Push.show(this, m)
        TodayWidget.refresh(this)
    }
}
