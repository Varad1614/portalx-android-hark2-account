package com.pravahax.portalx.now

import com.pravahax.portalx.data.Dates
import com.pravahax.portalx.data.model.AttendanceRecord
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
        /** Attendance history; pass it only when it is fresh (a day corrected on the web must not be prompted). */
        history: List<AttendanceRecord>? = null,
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
            holiday, onLeave, ms, ts, pendingApprovals, canApprove, openDay(history, day))
    }

    /** v0.10.4: the latest of the last few days with a check-in and no check-out (and no correction already on it). */
    fun openDay(history: List<AttendanceRecord>?, today: java.time.LocalDate): java.time.LocalDate? = history.orEmpty().mapNotNull { r ->
        val d = r.date?.let(Dates::day) ?: Dates.instant(r.checkIn)?.toLocalDate() ?: return@mapNotNull null
        val open = !r.checkIn.isNullOrBlank() && r.checkOut.isNullOrBlank() && r.status?.contains("correct", true) != true
        d.takeIf { open && it.isBefore(today) && !it.isBefore(today.minusDays(NowConfig.OPEN_DAY_LOOKBACK)) }
    }.maxOrNull()

    /** Freshness of one cached resource as the UI sees it. */
    fun freshness(hasData: Boolean, loading: Boolean, stale: Boolean, error: String?, updatedAt: Long? = null,
                  nowMs: Long = System.currentTimeMillis()): Freshness = when {
        !hasData -> if (loading) Freshness.Updating else Freshness.Unknown
        stale || error != null -> Freshness.Cached
        loading -> Freshness.Updating
        // v0.10.4: "live" data that sat on screen past the limit is not live any more.
        isOld(updatedAt, nowMs) -> Freshness.Cached
        else -> Freshness.Live
    }

    fun isOld(updatedAt: Long?, nowMs: Long = System.currentTimeMillis()) =
        updatedAt != null && nowMs - updatedAt > NowConfig.maxDataAge.toMillis()
}
