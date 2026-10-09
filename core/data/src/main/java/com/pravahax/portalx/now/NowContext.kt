package com.pravahax.portalx.now

import com.pravahax.portalx.data.Dates
import com.pravahax.portalx.data.model.AttendanceToday
import com.pravahax.portalx.data.model.CalendarMonth
import com.pravahax.portalx.data.model.LeaveSummary
import com.pravahax.portalx.data.model.Meeting
import com.pravahax.portalx.data.model.Task
import java.time.ZonedDateTime

/**
 * Normalizes PortalX's typed read models into a [WorkdayContext]. This is the only place that knows payload
 * quirks (string timestamps, missing ends, leave ranges); rules stay pure.
 */
object NowContext {
    fun build(
        now: ZonedDateTime,
        today: AttendanceToday?,
        todayFreshness: Freshness,
        meetings: List<Meeting> = emptyList(),
        tasks: List<Task> = emptyList(),
        leave: LeaveSummary? = null,
        calendar: CalendarMonth? = null,
        pendingApprovals: Int = 0,
        canApprove: Boolean = false,
        shift: ShiftWindow = NowConfig.shift,
    ): WorkdayContext {
        val day = now.toLocalDate()
        val att = today?.let {
            NowAttendance(Dates.instant(it.checkIn), Dates.instant(it.checkOut), it.onBreak, Dates.instant(it.lastBreakStart), it.onLeave)
        }
        val holiday = calendar?.holidays?.firstOrNull { Dates.day(it.date) == day }?.let { it.title ?: "Holiday" }
        val onLeave = leave?.requests.orEmpty().any { r ->
            val s = Dates.day(r.startDate); val e = Dates.day(r.endDate) ?: s
            r.status.equals("approved", true) && s != null && e != null && !day.isBefore(s) && !day.isAfter(e)
        }
        val ms = meetings.mapIndexedNotNull { i, m ->
            val s = Dates.instant(m.startAt) ?: return@mapIndexedNotNull null
            if (s.toLocalDate() != day) return@mapIndexedNotNull null
            NowMeeting(m.key ?: "m$i", m.title?.takeIf { it.isNotBlank() } ?: "Meeting", s, Dates.instant(m.endAt))
        }
        val ts = tasks.mapIndexed { i, t ->
            NowTask(t.key ?: "t$i", t.title?.takeIf { it.isNotBlank() } ?: "Task", Dates.day(t.dueDate), t.priority, t.done || t.status.equals("completed", true))
        }
        return WorkdayContext(now, shift, att, if (today == null && todayFreshness == Freshness.Live) Freshness.Unknown else todayFreshness,
            holiday, onLeave, ms, ts, pendingApprovals, canApprove)
    }

    /** Freshness of one cached resource as the UI sees it. */
    fun freshness(hasData: Boolean, loading: Boolean, stale: Boolean, error: String?): Freshness = when {
        !hasData -> if (loading) Freshness.Updating else Freshness.Unknown
        stale || error != null -> Freshness.Cached
        loading -> Freshness.Updating
        else -> Freshness.Live
    }
}
