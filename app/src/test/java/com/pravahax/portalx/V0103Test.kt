package com.pravahax.portalx

import com.pravahax.portalx.data.AppZone
import com.pravahax.portalx.data.model.AttendanceToday
import com.pravahax.portalx.now.*
import com.pravahax.portalx.widget.WidgetSummary
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** v0.10.3: NOW made punctual and foolproof. Includes a whole simulated workday driven only by the alarm schedule. */
class V0103Test {
    private val fri = LocalDate.of(2026, 10, 9)
    private val sat = fri.plusDays(1)
    private fun at(h: Int, m: Int = 0, d: LocalDate = fri) = ZonedDateTime.of(d.atTime(h, m), AppZone)
    private val notStarted = NowAttendance(null, null, false, null, false)
    private fun ctx(now: ZonedDateTime, a: NowAttendance? = notStarted, meetings: List<NowMeeting> = emptyList(), f: Freshness = Freshness.Live) =
        WorkdayContext(now, ShiftWindow(), a, f, meetings = meetings)
    private fun pick(c: WorkdayContext) = NowEngine.evaluate(c).chosen

    @After fun tearDown() = NowSnoozes.clear()

    /** The user's example: a 10:00 meeting. Every reminder must land on its minute when only the alarms wake us. */
    @Test fun wholeDaySimulationFiresEachReminderOnTheMinute() {
        val m = NowMeeting("42", "Design review", at(10, 0), at(10, 30))
        var att = notStarted
        var now = at(0, 1)
        val sent = mutableSetOf<String>()
        val fired = linkedMapOf<String, LocalTime>()
        var wakes = 0 // counted for debugging only
        while (now.toLocalDate() == fri) {
            // what the user does: checks in at 9:55, starts a long break at 13:07, ends it 14:10, forgets to check out.
            // The app only learns of a punch at its next wake: an alarm, or the 15-min safety-net worker.
            if (now >= at(9, 55) && att.checkIn == null) att = att.copy(checkIn = at(9, 55))
            if (now >= at(13, 7) && now < at(14, 10) && !att.onBreak) att = att.copy(onBreak = true, breakStartedAt = at(13, 7))
            if (now >= at(14, 10) && att.onBreak) att = att.copy(onBreak = false, breakStartedAt = null)
            val c = ctx(now, att, listOf(m))
            NowNudges.due(c, sent).forEach { n -> sent += n.key; fired[n.key.substringAfter('|')] = now.toLocalTime() }
            val poll = now.withMinute(now.minute / 15 * 15).withSecond(0).plusMinutes(15)
            now = minOf(NowSchedule.next(c), poll); wakes++
        }
        assertEquals(LocalTime.of(9, 30), fired["checkin"])
        assertEquals(LocalTime.of(9, 45), fired["checkin2"])
        assertEquals(LocalTime.of(9, 50), fired["meeting:42"])
        assertEquals(LocalTime.of(13, 52), fired.entries.single { it.key.startsWith("break45") }.value)
        assertEquals(LocalTime.of(14, 7), fired.entries.single { it.key.startsWith("break60") }.value)
        assertEquals(LocalTime.of(19, 0), fired["checkout"])
        assertEquals(6, fired.size) // nothing else, nothing twice
        assertEquals(sat, now.toLocalDate()); assertFalse(now.isAfter(at(0, 1, sat))) // a wake right after midnight rolls the widget over
        assertTrue(at(0, 1, sat) in NowSchedule.moments(ctx(at(23, 0), att, listOf(m))))
    }

    @Test fun meetingBeatsCheckInOnlyInTheLastThreeMinutes() {
        val m = NowMeeting("42", "Design review", at(10, 0), at(10, 30))
        pick(ctx(at(9, 50), meetings = listOf(m))).let {
            assertEquals("attendance.check-in-required", it.ruleId)
            assertEquals("Your shift started at 9:30 AM · then Design review at 10:00 AM", it.detail)
        }
        pick(ctx(at(9, 57), meetings = listOf(m))).let {
            assertEquals("meeting.starting-soon", it.ruleId)
            assertEquals("Starts in 3 min, at 10:00 AM · check in right after", it.detail)
            assertEquals("Starts at 10:00 AM", it.glance)
        }
        assertEquals("meeting.current", pick(ctx(at(10, 0), meetings = listOf(m))).ruleId)
        // checked in: the meeting shows from 9:50 with no check-in note
        pick(ctx(at(9, 50), NowAttendance(at(9, 31), null, false, null, false), listOf(m))).let {
            assertEquals("meeting.starting-soon", it.ruleId); assertEquals("Starts in 10 min, at 10:00 AM", it.detail)
        }
    }

    @Test fun missedCheckInNeverGoesSilent() {
        pick(ctx(at(18, 45))).let {
            assertEquals("attendance.check-in-missed", it.ruleId); assertEquals("Request correction", it.actionLabel)
            assertEquals("Request a correction for 9 Oct", it.detail)
        }
        assertEquals("attendance.refresh-needed", pick(ctx(at(18, 45), f = Freshness.Cached)).ruleId) // never from stale data
        assertNotEquals("attendance.check-in-missed", pick(WorkdayContext(at(18, 45, sat), ShiftWindow(), notStarted, Freshness.Live)).ruleId)
    }

    @Test fun punchNudgesNeedLiveDataAndRespectSnooze() {
        assertTrue(NowNudges.due(ctx(at(9, 30), f = Freshness.Cached), emptySet()).isEmpty())
        val n = NowNudges.due(ctx(at(9, 30)), emptySet()).single()
        assertEquals("attendance.check-in-required:today", n.snoozeKey)
        assertTrue(NowNudges.due(ctx(at(9, 45)), setOf(n.key)) { it == n.snoozeKey }.isEmpty()) // "Not now" holds the repeat
        assertTrue(NowNudges.due(WorkdayContext(at(9, 30), ShiftWindow(), notStarted, Freshness.Live, holidayToday = "Diwali"), emptySet()).isEmpty())
    }

    @Test fun snoozesSurviveARestart() {
        val f = File.createTempFile("snz", ".tsv")
        NowSnoozes.init(f, 1_000)
        NowSnoozes.snooze("task.overdue:7", 1_000)
        NowSnoozes.clear(); NowSnoozes.init(f, 2_000)
        assertTrue(NowSnoozes.isSnoozed("task.overdue:7", 2_000))
        NowSnoozes.clear(); NowSnoozes.init(f, 1_000 + NowConfig.snooze.toMillis() + 1)
        assertFalse(NowSnoozes.isSnoozed("task.overdue:7", 1_000 + NowConfig.snooze.toMillis() + 1))
        f.delete()
    }

    @Test fun scheduleIncludesSnoozeExpiryAndSkipsShiftOnWeekends() {
        val c = ctx(at(11, 0), NowAttendance(at(9, 31), null, false, null, false))
        assertEquals(at(18, 30), NowSchedule.next(c))
        val wake = at(11, 30).toInstant().toEpochMilli()
        assertEquals(at(11, 30), NowSchedule.next(c, mapOf("x" to wake)))
        assertEquals(at(0, 1, sat.plusDays(1)), NowSchedule.next(WorkdayContext(at(8, 0, sat), ShiftWindow(), notStarted, Freshness.Live)))
    }

    @Test fun widgetUsesClockTimesNotCountdowns() {
        val c = pick(ctx(at(9, 52), NowAttendance(at(9, 31), null, false, null, false), listOf(NowMeeting("42", "Design review", at(10, 0), at(10, 30)))))
        val today = AttendanceToday("2026-10-09T09:31:00+05:30", null, false, false, 0, null, null)
        val s = WidgetSummary.of(today, 0, 0, day = fri, now = c)
        assertEquals("Now: Design review · Starts at 10:00 AM", s.now); assertEquals("meetings", s.route)
    }
}
