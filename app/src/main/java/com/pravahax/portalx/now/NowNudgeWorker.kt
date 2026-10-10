package com.pravahax.portalx.now

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pravahax.portalx.data.AppZone
import com.pravahax.portalx.data.Endpoint
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.data.RepoHost
import com.pravahax.portalx.data.model.*
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.push.Push
import com.pravahax.portalx.push.PushMessage
import com.pravahax.portalx.widget.TodayWidget
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * v0.10.1: every ~15 minutes, rebuild the NOW context, post the time-critical nudges ([NowNudges]) once each,
 * and refresh the widget. Attendance is fetched live; if that fails, no punch nudge is sent.
 */
class NowNudgeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as? RepoHost)?.repo ?: return Result.success()
        try {
            if (!repo.api.hasSession()) return Result.success()
            val ctx = context(repo, ZonedDateTime.now(AppZone)) ?: return Result.success()
            NowPilot.attendance(ctx)
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val day = ctx.today.toString()
            val sent = prefs.getStringSet(KEY, emptySet()).orEmpty().filter { it.startsWith("$day|") }.toMutableSet()
            NowNudges.due(ctx, sent).forEach { n ->
                Push.show(applicationContext, PushMessage(n.title, n.body, PushMessage.Channel.Reminders, n.route, "now:${n.key}"))
                sent += n.key
                NowPilot.nudge(n.key)
            }
            prefs.edit().putStringSet(KEY, sent).apply() // only today's keys survive: the set never grows
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        TodayWidget.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "portalx-now"
        private const val PREFS = "portalx_now"
        private const val KEY = "sent"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<NowNudgeWorker>(15, TimeUnit.MINUTES).build()
            runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req) }
        }

        fun cancel(context: Context) { runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) } }

        /** Live attendance + meetings when reachable, the rest from cache. Null when signed out. */
        suspend fun context(repo: Repo, now: ZonedDateTime): WorkdayContext? {
            val me = repo.cachedMe() ?: return null
            val live = runCatching { AttendanceToday.from(repo.load(Fn.AttendanceToday), now.toLocalDate()) }.getOrNull()
            val today = live ?: AttendanceToday.from(repo.cachedResponse(Endpoint.AttendanceToday.fn.name), now.toLocalDate())
            val fresh = when { live != null -> Freshness.Live; today != null -> Freshness.Cached; else -> Freshness.Unknown }
            val meetings = Meeting.list(runCatching { repo.load(Fn.Meetings) }.getOrNull() ?: repo.cachedResponse(Endpoint.Meetings.fn.name))
            val ym = java.time.YearMonth.from(now)
            val approvals = ApprovalItem.inbox(
                if (me.canApproveLeave) LeaveRequest.list(repo.cachedResponse(Endpoint.PendingLeaveApprovals.fn.name)) else emptyList(),
                if (me.canApproveCorrections) Correction.list(repo.cachedResponse(Endpoint.Corrections.fn.name)) else emptyList(),
            ).size
            return NowContext.build(
                now, today, fresh, meetings,
                Task.list(repo.cachedResponse(Endpoint.Tasks.fn.name)),
                repo.cachedResponse(Endpoint.MyLeave.fn.name)?.let { LeaveSummary.from(it) },
                repo.cachedResponse("cal-$ym")?.let { CalendarMonth.from(it) },
                approvals, me.canApproveLeave || me.canApproveCorrections,
            )
        }
    }
}
