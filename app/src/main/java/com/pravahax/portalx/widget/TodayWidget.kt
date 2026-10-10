package com.pravahax.portalx.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pravahax.portalx.MainActivity
import com.pravahax.portalx.R
import com.pravahax.portalx.data.Dates
import com.pravahax.portalx.data.Endpoint
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.data.RepoHost
import com.pravahax.portalx.data.model.ApprovalItem
import com.pravahax.portalx.data.model.AttendanceToday
import com.pravahax.portalx.data.model.Correction
import com.pravahax.portalx.data.model.LeaveRequest
import com.pravahax.portalx.data.model.Task
import com.pravahax.portalx.push.Push
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * v0.8 home-screen widget: today's attendance and what's waiting (open tasks, approvals).
 * Reads only the sealed on-device cache — it never calls the network and shows nothing personal when signed out.
 * Refreshed when the app goes to the background, when a push arrives, and by the system every 30 minutes.
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val done = goAsync()
        Push.scope.launch { try { render(context, manager, ids) } finally { done.finish() } }
    }

    companion object {
        fun refresh(context: Context) {
            val ctx = context.applicationContext
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(ctx, TodayWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) Push.scope.launch { render(ctx, manager, ids) }
        }

        private suspend fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val s = try { (ctx.applicationContext as? RepoHost)?.repo?.let { summary(it) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                ?: WidgetSummary.signedOut
            val open = Intent(ctx, MainActivity::class.java).putExtra(Push.EXTRA_ROUTE, s.route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            s.nowKey?.let { open.putExtra(Push.EXTRA_SRC, "widget:$it"); com.pravahax.portalx.now.NowPilot.record("shown", it, "widget") }
            val pi = PendingIntent.getActivity(ctx, 7001, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val views = RemoteViews(ctx.packageName, R.layout.widget_today).apply {
                setTextViewText(R.id.widget_headline, s.headline)
                setTextViewText(R.id.widget_detail, s.detail)
                // v0.10.1: the NOW line, when there is something to do.
                setTextViewText(R.id.widget_now, s.now ?: "")
                setViewVisibility(R.id.widget_now, if (s.now != null) android.view.View.VISIBLE else android.view.View.GONE)
                setOnClickPendingIntent(R.id.widget_root, pi)
            }
            manager.updateAppWidget(ids, views)
        }

        private suspend fun summary(repo: Repo): WidgetSummary {
            val user = repo.cachedMe()
            if (!repo.api.hasSession() || user == null) return WidgetSummary.signedOut
            val today = AttendanceToday.from(repo.cachedResponse(Endpoint.AttendanceToday.fn.name))
            val tasks = Task.list(repo.cachedResponse(Endpoint.Tasks.fn.name))
            val approvals = ApprovalItem.inbox(
                if (user.canApproveLeave) LeaveRequest.list(repo.cachedResponse(Endpoint.PendingLeaveApprovals.fn.name)) else emptyList(),
                if (user.canApproveCorrections) Correction.list(repo.cachedResponse(Endpoint.Corrections.fn.name)) else emptyList(),
            ).size
            // NOW from the same cache. Cache-only, so marked Updating: the tap opens the app, which loads live before any punch.
            val now = runCatching {
                com.pravahax.portalx.now.NowEngine.evaluate(com.pravahax.portalx.now.NowContext.build(
                    java.time.ZonedDateTime.now(com.pravahax.portalx.data.AppZone), today,
                    if (today == null) com.pravahax.portalx.now.Freshness.Unknown else com.pravahax.portalx.now.Freshness.Updating,
                    com.pravahax.portalx.data.model.Meeting.list(repo.cachedResponse(Endpoint.Meetings.fn.name)), tasks,
                    repo.cachedResponse(Endpoint.MyLeave.fn.name)?.let { com.pravahax.portalx.data.model.LeaveSummary.from(it) },
                    repo.cachedResponse("cal-${java.time.YearMonth.from(Dates.today())}")?.let { com.pravahax.portalx.data.model.CalendarMonth.from(it) },
                    approvals, user.canApproveLeave || user.canApproveCorrections,
                    history = repo.cachedAt(Endpoint.AttendanceHistory.fn.name)?.takeIf { !com.pravahax.portalx.now.NowContext.isOld(it) }
                        ?.let { com.pravahax.portalx.data.model.AttendanceRecord.list(repo.cachedResponse(Endpoint.AttendanceHistory.fn.name)) },
                ), snoozedUntil = com.pravahax.portalx.now.NowSnoozes.snapshot()).chosen
            }.getOrNull()
            return WidgetSummary.of(today, tasks.count { !it.done }, approvals, now = now,
                asOf = repo.cachedAt(Endpoint.AttendanceToday.fn.name))
        }
    }
}

/** What the widget says. Pure, so it is unit-tested without a launcher. */
data class WidgetSummary(val headline: String, val detail: String, val route: String, val now: String? = null, val nowKey: String? = null) {
    companion object {
        val signedOut = WidgetSummary("Sign in to PortalX", "Your day at a glance", "home")

        fun of(today: AttendanceToday?, openTasks: Int, approvals: Int, day: java.time.LocalDate = Dates.today(),
               now: com.pravahax.portalx.now.Candidate? = null, asOf: Long? = null, nowMs: Long = System.currentTimeMillis()): WidgetSummary {
            // v0.9.1: the cache has no date of its own; a punch from an earlier day means the cache is stale (after midnight).
            val stale = listOfNotNull(today?.checkIn, today?.checkOut).any { Dates.instant(it)?.toLocalDate()?.let { d -> d != day } == true }
            val headline = when {
                today == null || stale -> "Open PortalX to sync"
                today.onLeave -> "On leave today"
                today.checkOut != null -> "Checked out at ${Dates.time(today.checkOut)}"
                today.onBreak -> "On a break"
                today.checkIn != null -> "Checked in at ${Dates.time(today.checkIn)}"
                else -> "Not checked in yet"
            }
            val parts = buildList {
                add(if (openTasks == 0) "No open tasks" else "$openTasks open task${if (openTasks == 1) "" else "s"}")
                if (approvals > 0) add("$approvals to approve")
                // v0.10.4: the widget only reads the cache; say when it's old so nobody trusts a stale "Checked in".
                if (today != null && !stale && com.pravahax.portalx.now.NowContext.isOld(asOf, nowMs))
                    add("as of " + java.time.Instant.ofEpochMilli(asOf!!).atZone(com.pravahax.portalx.data.AppZone).toLocalTime()
                        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)))
            }
            val fallback = if (approvals > 0) "approvals" else "attendance"
            // Only an actionable NOW candidate earns the line (and the tap target); "nothing urgent" stays quiet.
            val act = (now?.action as? com.pravahax.portalx.now.NowAction.Open)?.route?.let(Push::safeRoute)
            val line = now?.takeIf { !stale && today != null && it.action != com.pravahax.portalx.now.NowAction.None }
                ?.let { c -> val d = c.glance ?: c.detail; "Now: " + c.title + if (d.isNotBlank()) " · $d" else "" }
            return WidgetSummary(headline, parts.joinToString(" · "), if (line != null && act != null) act else fallback, line,
                nowKey = if (line != null && act != null) now?.key else null)
        }
    }
}
