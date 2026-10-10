package com.pravahax.portalx.now

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.pravahax.portalx.push.Push
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/**
 * v0.10.3: one exact alarm for the next NOW moment ([NowSchedule.next]): meeting minus 10 min, shift start,
 * shift start + 15, shift end + 30, break marks, snooze expiry, midnight. Each run re-arms the next one, so
 * reminders land on the minute even in Doze. Without the exact-alarm permission it falls back to an inexact
 * idle-allowed alarm, and the 15-minute worker stays as a safety net either way.
 */
object NowAlarms {
    const val ACTION_FIRE = "com.pravahax.portalx.NOW_ALARM"
    const val ACTION_SNOOZE = "com.pravahax.portalx.NOW_SNOOZE"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_TAG = "tag"

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || runCatching { ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true }.getOrDefault(false)

    private fun firePi(ctx: Context) = PendingIntent.getBroadcast(ctx, 7101,
        Intent(ctx, NowAlarmReceiver::class.java).setAction(ACTION_FIRE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun arm(ctx: Context, at: ZonedDateTime) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val ms = at.toInstant().toEpochMilli()
        runCatching {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, firePi(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, firePi(ctx))
        }
    }

    fun cancel(ctx: Context) { runCatching { ctx.getSystemService(AlarmManager::class.java)?.cancel(firePi(ctx)) } }

    /** Run a NOW pass now (app start, boot, time change, sign-in) so the next alarm is armed. */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        Push.scope.launch { runCatching { NowNudgeWorker.runOnce(app) } }
    }

    fun snoozeIntent(ctx: Context, key: String, tag: String): PendingIntent = PendingIntent.getBroadcast(ctx, tag.hashCode(),
        Intent(ctx, NowAlarmReceiver::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_KEY, key).putExtra(EXTRA_TAG, tag),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    internal fun handle(ctx: Context, intent: Intent, done: () -> Unit) {
        val app = ctx.applicationContext
        if (intent.action == ACTION_SNOOZE) {
            val key = intent.getStringExtra(EXTRA_KEY); val tag = intent.getStringExtra(EXTRA_TAG)
            if (key != null) { NowSnoozes.snooze(key); NowPilot.record("snoozed", key, "notification") }
            if (tag != null) runCatching { NotificationManagerCompat.from(app).cancel(tag, tag.hashCode()) }
        }
        Push.scope.launch {
            // A receiver gets ~10 s. Live fetches are bounded; if we run out, the worker finishes the job.
            if (withTimeoutOrNull(8_000) { NowNudgeWorker.runOnce(app) } == null)
                runCatching {
                    androidx.work.WorkManager.getInstance(app).enqueueUniqueWork("portalx-now-once", androidx.work.ExistingWorkPolicy.REPLACE,
                        androidx.work.OneTimeWorkRequestBuilder<NowNudgeWorker>().build())
                }
            done()
        }
    }
}

/** Exact NOW alarms, notification "Not now", and re-arming after boot, app update or a clock/time-zone change. */
class NowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        NowAlarms.handle(context, intent) { pending.finish() }
    }
}

/** System events that wipe or shift alarms: re-run NOW and arm the next one. Exported only for protected broadcasts. */
class NowBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        NowAlarms.handle(context, Intent(NowAlarms.ACTION_FIRE)) { pending.finish() }
    }
}
