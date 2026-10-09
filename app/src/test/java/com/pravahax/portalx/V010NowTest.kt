package com.pravahax.portalx

import com.pravahax.portalx.data.AppZone
import com.pravahax.portalx.data.model.AttendanceToday
import com.pravahax.portalx.data.model.CalendarMonth
import com.pravahax.portalx.data.model.LeaveRequest
import com.pravahax.portalx.data.model.LeaveSummary
import com.pravahax.portalx.data.model.Meeting
import com.pravahax.portalx.data.model.Task
import com.pravahax.portalx.now.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** v0.10 PortalX NOW: rules, conflicts, freshness gate, snooze, stability and the context builder. Pure JVM. */
class V010NowTest {
    private val fri = LocalDate.of(2026, 10, 9) // a Friday
    private fun at(h: Int, m: Int = 0, d: LocalDate = fri) = ZonedDateTime.of(d.atTime(h, m), AppZone)
    private val notStarted = NowAttendance(null, null, false, null, false)
    private fun checkedIn(h: Int = 9, m: Int = 40) = NowAttendance(at(h, m), null, false, null, false)
    private fun ctx(now: ZonedDateTime, a: NowAttendance? = notStarted, f: Freshness = Freshness.Live, meetings: List<NowMeeting> = emptyList(),
                    tasks: List<NowTask> = emptyList(), approvals: Int = 0, canApprove: Boolean = false, holiday: String? = null, leave: Boolean = false) =
        WorkdayContext(now, ShiftWindow(), a, f, holiday, leave, meetings, tasks, approvals, canApprove)
    private fun pick(c: WorkdayContext, snoozed: Map<String, Long> = emptyMap()) = NowEngine.evaluate(c, snoozedUntil = snoozed).chosen

    // ---- attendance ----
    @Test fun checkInIsDueBeforeShiftAndTimeCriticalAfter() {
        assertEquals("workday.clear", pick(ctx(at(8, 0))).ruleId) // > 60 min early: nothing yet
        pick(ctx(at(9, 0))).let { assertEquals("attendance.check-in-required", it.ruleId); assertEquals(Band.Due, it.band) }
        pick(ctx(at(10, 5))).let { assertEquals(Band.TimeCritical, it.band); assertEquals(NowAction.Open("attendance"), it.action) }
        assertEquals("workday.clear", pick(ctx(at(19, 0))).ruleId) // after shift end: don't nag a late check-in at night
    }

    @Test fun noCheckInNagOnWeekendHolidayOrLeave() {
        val sat = LocalDate.of(2026, 10, 10)
        assertEquals("workday.clear", pick(ctx(at(10, 0, sat))).ruleId)
        assertEquals("workday.holiday", pick(ctx(at(10, 0), holiday = "Dussehra")).ruleId)
        assertEquals("workday.on-leave", pick(ctx(at(10, 0), leave = true)).ruleId)
        assertEquals("workday.on-leave", pick(ctx(at(10, 0), a = notStarted.copy(onLeave = true))).ruleId)
    }

    @Test fun checkOutDueThenForgotten() {
        assertEquals("workday.clear", pick(ctx(at(17, 0), checkedIn())).ruleId)
        pick(ctx(at(18, 40), checkedIn())).let { assertEquals("attendance.check-out-required", it.ruleId); assertEquals(Band.Due, it.band) }
        pick(ctx(at(19, 30), checkedIn())).let { assertEquals("attendance.check-out-forgotten", it.ruleId); assertEquals(Band.TimeCritical, it.band) }
    }

    @Test fun runningBreakBlocksEverything() {
        val onBreak = checkedIn().copy(onBreak = true, breakStartedAt = at(13, 0))
        val c = pick(ctx(at(13, 20), onBreak, meetings = listOf(NowMeeting("m1", "Standup", at(13, 22), at(13, 40)))))
        assertEquals("attendance.break-running", c.ruleId); assertEquals("On break for 20 min", c.detail)
    }

    @Test fun dayCompleteAfterCheckout() {
        val c = pick(ctx(at(19, 0), NowAttendance(at(9, 30), at(18, 45), false, null, false)))
        assertEquals("workday.complete", c.ruleId); assertTrue(c.detail.contains("9h 15m"))
    }

    // ---- conflicts (the important bugs live here) ----
    @Test fun lateCheckInBeatsMeetingInEightMinutesButNotInTwo() {
        val m8 = NowMeeting("m", "Project review", at(10, 8), at(10, 30))
        assertEquals("attendance.check-in-required", pick(ctx(at(10, 0), meetings = listOf(m8))).ruleId)
        assertEquals("meeting.starting-soon", pick(ctx(at(10, 6), meetings = listOf(m8))).ruleId)
    }

    @Test fun liveMeetingBeatsEverythingButABreak() {
        val m = NowMeeting("m", "Sprint planning", at(11, 0), at(12, 0))
        val c = pick(ctx(at(11, 10), checkedIn(), meetings = listOf(m), tasks = listOf(NowTask("t", "Old", fri.minusDays(9), "high", false)), approvals = 3, canApprove = true))
        assertEquals("meeting.current", c.ruleId)
    }

    @Test fun overdueTaskOutranksApprovalsWhichOutrankDueToday() {
        val tasks = listOf(NowTask("a", "Due today", fri, null, false), NowTask("b", "Late report", fri.minusDays(3), null, false), NowTask("c", "Done", fri.minusDays(5), null, true))
        val r = NowEngine.evaluate(ctx(at(12, 0), checkedIn(), tasks = tasks, approvals = 2, canApprove = true))
        assertEquals(listOf("task.overdue", "approval.pending", "task.due-today"), r.ranked.map { it.ruleId })
        assertEquals("Late report", r.chosen.title); assertEquals("Overdue by 3 days", r.chosen.detail)
    }

    @Test fun approvalsNeedThePermission() {
        assertEquals("workday.clear", pick(ctx(at(12, 0), checkedIn(), approvals = 4, canApprove = false)).ruleId)
    }

    @Test fun sameContextSameAnswer() {
        val c = ctx(at(12, 0), checkedIn(), tasks = listOf(NowTask("x", "A", fri, null, false), NowTask("y", "B", fri, null, false)))
        val first = NowEngine.evaluate(c)
        repeat(20) { assertEquals(first, NowEngine.evaluate(c)) }
        assertEquals(first, NowEngine.evaluate(c, Rules.catalogue.reversed())) // rule order never matters
    }

    // ---- freshness ----
    @Test fun staleAttendanceTurnsPunchesIntoRefresh() {
        listOf(Freshness.Cached, Freshness.Unknown).forEach { f ->
            val c = pick(ctx(at(10, 0), f = f))
            assertEquals("attendance.refresh-needed", c.ruleId); assertEquals(NowAction.Refresh, c.action); assertEquals(Band.TimeCritical, c.band)
        }
        assertEquals("attendance.check-in-required", pick(ctx(at(10, 0), f = Freshness.Updating)).ruleId)
        // unknown attendance on a work day asks for a refresh instead of guessing
        assertEquals("attendance.refresh-needed", pick(ctx(at(10, 0), a = null, f = Freshness.Unknown)).ruleId)
        // non-attendance candidates don't need live attendance
        val m = NowMeeting("m", "Sync", at(11, 0), at(11, 30))
        assertEquals("meeting.current", pick(ctx(at(11, 5), checkedIn(), f = Freshness.Cached, meetings = listOf(m))).ruleId)
    }

    // ---- snooze ----
    @Test fun snoozeHidesOnlyThatCandidateUntilItExpires() {
        val c = ctx(at(12, 0), checkedIn(), tasks = listOf(NowTask("b", "Late", fri.minusDays(1), null, false)), approvals = 1, canApprove = true)
        val until = mapOf("task.overdue:b" to at(12, 30).toInstant().toEpochMilli())
        assertEquals("approval.pending", pick(c, until).ruleId)
        assertEquals("task.overdue", pick(c.copy(now = at(12, 31)), until).ruleId)
    }

    // ---- stability ----
    @Test fun stabilityHoldsSameBandForAMinuteButYieldsToHigherBand() {
        val sp = StabilityPolicy(60_000)
        val t = NowTask("b", "Late", fri.minusDays(1), null, false)
        val base = ctx(at(12, 0), checkedIn(), tasks = listOf(t))
        assertEquals("task.overdue", sp.choose(NowEngine.evaluate(base), 0).ruleId)
        // approvals arrive with a higher score in the same band: hold the task for the minute
        val more = base.copy(tasks = listOf(t.copy(due = fri)), pendingApprovals = 9, canApprove = true)
        val withBoth = base.copy(pendingApprovals = 9, canApprove = true, tasks = listOf(t))
        assertEquals("task.overdue", sp.choose(NowEngine.evaluate(withBoth), 10_000).ruleId)
        // a higher band (meeting live) replaces at once
        val live = withBoth.copy(meetings = listOf(NowMeeting("m", "Sync", at(11, 59), at(12, 30))))
        assertEquals("meeting.current", sp.choose(NowEngine.evaluate(live), 20_000).ruleId)
        // an invalid current is dropped at once (task completed)
        val sp2 = StabilityPolicy(60_000)
        sp2.choose(NowEngine.evaluate(base), 0)
        assertEquals("approval.pending", sp2.choose(NowEngine.evaluate(more.copy(tasks = emptyList())), 1_000).ruleId)
        // after the hold, the better same-band candidate wins
        val sp3 = StabilityPolicy(60_000)
        sp3.choose(NowEngine.evaluate(base), 0)
        val overdue1 = NowEngine.evaluate(withBoth.copy(tasks = listOf(t.copy(due = fri.minusDays(1)))))
        assertEquals(overdue1.chosen.key, sp3.choose(overdue1, 61_000).key)
    }

    // ---- context builder ----
    @Test fun contextBuilderNormalizesModels() {
        val now = at(10, 0)
        val ctx = NowContext.build(
            now, AttendanceToday.NotStarted, Freshness.Live,
            meetings = listOf(Meeting("1", "Review", null, "2026-10-09T04:35:00Z", "2026-10-09T05:00:00Z", null, null, null),
                Meeting("2", "Tomorrow", null, "2026-10-10T04:35:00Z", null, null, null, null)),
            tasks = listOf(Task(null, "7", "Ship", null, "todo", "high", "2026-10-07", null, null, null)),
            leave = LeaveSummary(emptyList(), listOf(LeaveRequest(null, "l", null, null, "casual", "2026-10-12", "2026-10-13", 2.0, null, "approved", null))),
            calendar = CalendarMonth(listOf(CalendarMonth.Dated("2026-10-20", "Diwali")), emptyList(), emptyList(), emptyList(), emptyList()),
        )
        assertEquals(1, ctx.meetings.size); assertEquals(at(10, 5), ctx.meetings[0].start)
        assertEquals(LocalDate.of(2026, 10, 7), ctx.tasks[0].due)
        assertFalse(ctx.onApprovedLeaveToday); assertNull(ctx.holidayToday); assertTrue(ctx.isWorkDay)
        assertEquals(notStarted, ctx.attendance)
        assertEquals("attendance.check-in-required", NowEngine.evaluate(ctx).chosen.ruleId) // late check-in (385) beats a meeting in 5 min (380)
        // null today while "live" is not knowledge: Unknown
        assertEquals(Freshness.Unknown, NowContext.build(now, null, Freshness.Live).attendanceFreshness)
        assertEquals(Freshness.Cached, NowContext.freshness(true, false, true, "x"))
        assertEquals(Freshness.Updating, NowContext.freshness(true, true, false, null))
    }
}
