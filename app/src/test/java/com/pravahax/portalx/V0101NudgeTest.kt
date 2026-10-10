package com.pravahax.portalx

import com.pravahax.portalx.data.AppZone
import com.pravahax.portalx.data.model.AttendanceToday
import com.pravahax.portalx.now.*
import com.pravahax.portalx.push.PushMessage
import com.pravahax.portalx.widget.WidgetSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** v0.10.1: NOW local nudges (once each, never on stale attendance) and the widget's NOW line. */
class V0101NudgeTest {
    private val fri = LocalDate.of(2026, 10, 9)
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(fri.atTime(h, m), AppZone)
    private val inAt940 = NowAttendance(at(9, 40), null, false, null, false)
    private fun ctx(now: ZonedDateTime, a: NowAttendance?, f: Freshness = Freshness.Live, meetings: List<NowMeeting> = emptyList()) =
        WorkdayContext(now, ShiftWindow(), a, f, meetings = meetings)

    @Test fun forgottenCheckOutNudgesOnceAndOnlyOnLiveData() {
        assertTrue(NowNudges.due(ctx(at(18, 45), inAt940), emptySet()).isEmpty()) // inside the 30-min grace
        val n = NowNudges.due(ctx(at(19, 15), inAt940), emptySet()).single()
        assertEquals("2026-10-09|checkout", n.key); assertEquals("attendance", n.route); assertTrue(n.body.contains("6:30 PM"))
        assertTrue(NowNudges.due(ctx(at(19, 30), inAt940), setOf(n.key)).isEmpty())
        assertTrue(NowNudges.due(ctx(at(19, 15), inAt940, Freshness.Cached), emptySet()).isEmpty())
        val out = inAt940.copy(checkOut = at(18, 40))
        assertTrue(NowNudges.due(ctx(at(19, 15), out), emptySet()).isEmpty())
    }

    @Test fun meetingNudgesWithinTenMinutesOnce() {
        val m = NowMeeting("42", "Design review", at(11, 0), at(11, 30))
        assertTrue(NowNudges.due(ctx(at(10, 40), inAt940, meetings = listOf(m)), emptySet()).isEmpty())
        assertTrue(NowNudges.due(ctx(at(10, 48), inAt940, meetings = listOf(m)), emptySet()).isEmpty()) // v0.10.3: same 10-min lead as the card
        val n = NowNudges.due(ctx(at(10, 52), inAt940, meetings = listOf(m)), emptySet()).single()
        assertEquals("2026-10-09|meeting:42", n.key); assertEquals("Starts in 8 min, at 11:00 AM", n.body); assertEquals("meetings", n.route)
        assertTrue(NowNudges.due(ctx(at(10, 55), inAt940, meetings = listOf(m)), setOf(n.key)).isEmpty())
        assertTrue(NowNudges.due(ctx(at(11, 5), inAt940, meetings = listOf(m)), emptySet()).isEmpty()) // started: no late nudge
    }

    @Test fun reminderChannelExistsAndRefreshesAttendance() {
        assertTrue(PushMessage.Channel.Reminders in PushMessage.Channel.entries)
        assertTrue(com.pravahax.portalx.net.Fn.AttendanceToday in PushMessage("t", "b", PushMessage.Channel.Reminders, "attendance", "x").stale)
    }

    @Test fun widgetShowsActionableNowLineAndRoutesToIt() {
        val c = Candidate("approval.pending", "inbox", Band.Due, 52, "Review 2 approvals", "Leave and corrections waiting on you", "", "Review", NowAction.Open("approvals"))
        val today = AttendanceToday("2026-10-09T04:10:00Z", null, false, false, 0, null, null)
        val s = WidgetSummary.of(today, 0, 0, fri, now = c)
        assertEquals("Now: Review 2 approvals · Leave and corrections waiting on you", s.now); assertEquals("approvals", s.route)
        assertNull(WidgetSummary.of(today, 0, 0, fri, now = NowEngine.Clear).now)
        assertNull(WidgetSummary.of(null, 0, 0, fri, now = c).now)
        assertNull(WidgetSummary.of(today, 3, 0, fri).now)
    }
}
