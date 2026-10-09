package com.pravahax.portalx.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.pravahax.portalx.MainActivity
import com.pravahax.portalx.R
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.net.Fn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * v0.8 push alerts over FCM. Firebase is only configured when app/google-services.json is present at build time;
 * without it [available] is false and every call here is a no-op, so the app still builds and runs.
 */
object Push {
    const val EXTRA_ROUTE = "portalx.route"
    /** In-app destinations a notification may open. Anything else is ignored (intents into an exported activity are untrusted). */
    val ROUTES = setOf("home", "approvals", "attendance", "tasks", "leave", "calendar", "notifications", "announcements", "meetings")
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun safeRoute(r: String?): String? = r?.trim()?.lowercase()?.takeIf { it in ROUTES }

    fun available(ctx: Context): Boolean = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    /** Fetches the current FCM token and registers it with the gateway. Call after sign-in; never throws. */
    fun register(ctx: Context, repo: Repo) {
        if (!available(ctx)) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> if (!t.isNullOrBlank()) send(repo, t) }
        }
    }

    internal fun send(repo: Repo, token: String) {
        if (!repo.api.hasSession()) return
        scope.launch { runCatching { repo.registerDeviceToken(token) } }
    }

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(PushMessage.Channel.entries.map { c ->
            NotificationChannel(c.id, c.title, if (c == PushMessage.Channel.General) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH)
        })
    }

    fun show(ctx: Context, m: PushMessage) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ROUTE, m.route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, m.route.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, m.channel.id)
            .setSmallIcon(R.drawable.ic_stat_portalx).setColor(0xFFB8832B.toInt())
            .setContentTitle(m.title).setContentText(m.body).setStyle(NotificationCompat.BigTextStyle().bigText(m.body))
            .setAutoCancel(true).setContentIntent(pi)
            // Lock screen shows only "PortalX" until unlocked (names and dates stay private).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(ctx, m.channel.id).setSmallIcon(R.drawable.ic_stat_portalx).setContentTitle("PortalX").setContentText("New update").build())
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(m.tag, m.tag.hashCode(), n) }
    }
}

/** Pending deep link from a tapped notification, consumed by the signed-in shell. */
object DeepLink {
    val route = MutableStateFlow<String?>(null)
    fun offer(intent: Intent?) {
        Push.safeRoute(intent?.getStringExtra(Push.EXTRA_ROUTE) ?: intent?.getStringExtra("route"))?.let { route.value = it }
    }
    fun consume() { route.value = null }
}

/**
 * A gateway push, read from FCM `data` (preferred: `type`, `title`, `body`, optional `route`, `id`) with the
 * `notification` block as a fallback. Returns null when there is nothing to show.
 */
data class PushMessage(val title: String, val body: String, val channel: Channel, val route: String, val tag: String) {
    enum class Channel(val id: String, val title: String) { Approvals("approvals", "Approvals"), Tasks("tasks", "Tasks"), General("general", "Updates") }

    /** Cached screens to re-read next time they are shown. */
    val stale: List<Fn> get() = when (channel) {
        Channel.Approvals -> listOf(Fn.PendingLeaveApprovals, Fn.Corrections, Fn.MyLeave, Fn.AttendanceToday, Fn.Notifications)
        Channel.Tasks -> listOf(Fn.Tasks, Fn.Notifications)
        Channel.General -> listOf(Fn.Notifications, Fn.Announcements)
    }

    companion object {
        fun from(data: Map<String, String>, notifTitle: String? = null, notifBody: String? = null): PushMessage? {
            val title = data["title"]?.takeIf { it.isNotBlank() } ?: notifTitle?.takeIf { it.isNotBlank() }
            val body = data["body"]?.takeIf { it.isNotBlank() } ?: notifBody?.takeIf { it.isNotBlank() }
            if (title == null && body == null) return null
            val type = data["type"]?.lowercase().orEmpty()
            val channel = when {
                listOf("approval", "leave_request", "correction_request").any { type.startsWith(it) } -> Channel.Approvals
                type.startsWith("task") -> Channel.Tasks
                else -> Channel.General
            }
            val route = Push.safeRoute(data["route"]) ?: when {
                channel == Channel.Approvals -> "approvals"
                channel == Channel.Tasks -> "tasks"
                type.startsWith("leave") -> "leave"
                type.startsWith("attendance") || type.startsWith("correction") -> "attendance"
                type.startsWith("announcement") -> "announcements"
                type.startsWith("meeting") -> "meetings"
                else -> "notifications"
            }
            return PushMessage(title?.take(120) ?: "PortalX", body?.take(500) ?: "", channel, route, data["id"]?.let { "$type:$it" } ?: "$type:${title}:${body}")
        }
    }
}
