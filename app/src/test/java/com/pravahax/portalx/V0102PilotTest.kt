package com.pravahax.portalx

import com.pravahax.portalx.data.AppZone
import com.pravahax.portalx.data.model.AttendanceToday
import com.pravahax.portalx.now.*
import com.pravahax.portalx.widget.WidgetSummary
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

/** v0.10.2: NOW pilot instrumentation (on-device event log + metrics). */
class V0102PilotTest {
    private val fri = LocalDate.of(2026, 10, 9)
    private fun ms(d: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(d.atTime(h, m), AppZone).toInstant().toEpochMilli()
    private fun z(d: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(d.atTime(h, m), AppZone)

    @Before fun setUp() = NowPilot.reset()
    @After fun tearDown() = NowPilot.reset()

    @Test fun shownCountsOncePerDayAndTapRateIsDistinct() {
        repeat(5) { NowPilot.record("shown", "attendance.check-in:today", "home", ms(fri, 9, 40 + it)) }
        NowPilot.record("shown", "task.overdue:7", "home", ms(fri, 10))
        NowPilot.record("tapped", "attendance.check-in:today", "home", ms(fri, 9, 50))
        NowPilot.record("tapped", "attendance.check-in:today", "home", ms(fri, 9, 51)) // double tap: one key-day
        NowPilot.record("snoozed", "task.overdue:7", "home", ms(fri, 10, 1))
        val m = NowPilot.metrics(NowPilot.snapshot(), fri.plusDays(1))
        assertEquals(2, m.shown); assertEquals(1, m.tapped); assertEquals(0.5, m.tapRate, 1e-9); assertEquals(1, m.snoozed)
    }

    @Test fun missedCheckOutUsesLastLiveStateOfFinishedDays() {
        val thu = fri.minusDays(1)
        NowPilot.record("att", "in", "live", ms(thu, 9, 35)); NowPilot.record("att", "out", "live", ms(thu, 18, 40))
        NowPilot.record("att", "in", "live", ms(fri, 9, 35))
        NowPilot.record("nudge", "$fri|checkout", "notification", ms(fri, 19, 5))
        // today (Saturday) shows "in" on Friday: a miss. Friday counted only once it's over.
        val m = NowPilot.metrics(NowPilot.snapshot(), fri.plusDays(1))
        assertEquals(2, m.checkedInDays); assertEquals(1, m.missedCheckOutDays); assertEquals(1, m.checkOutNudges); assertEquals(0, m.checkOutAfterNudge)
        NowPilot.record("att", "out", "live", ms(fri, 19, 20))
        val m2 = NowPilot.metrics(NowPilot.snapshot(), fri.plusDays(1))
        assertEquals(0, m2.missedCheckOutDays); assertEquals(1, m2.checkOutAfterNudge)
        assertEquals(1, NowPilot.metrics(NowPilot.snapshot(), fri).checkedInDays) // Friday still in progress
    }

    @Test fun attendanceIsRecordedOnlyFromLiveData() {
        val a = NowAttendance(z(fri, 9, 40), null, false, null, false)
        NowPilot.attendance(WorkdayContext(z(fri, 19), ShiftWindow(), a, Freshness.Cached))
        assertTrue(NowPilot.snapshot().isEmpty())
        NowPilot.attendance(WorkdayContext(z(fri, 19), ShiftWindow(), a, Freshness.Live))
        assertEquals("in", NowPilot.snapshot().single().key)
    }

    @Test fun persistsAndReloadsFromFile() {
        val f = File.createTempFile("pilot", ".tsv"); f.delete()
        NowPilot.init(f)
        NowPilot.record("shown", "meeting.starting-soon:42", "widget", ms(fri, 10, 50))
        NowPilot.record("nudge-open", "$fri|meeting:42", "notification", ms(fri, 10, 52))
        NowPilot.reset(); NowPilot.init(f)
        assertEquals(2, NowPilot.snapshot().size)
        NowPilot.record("shown", "meeting.starting-soon:42", "widget", ms(fri, 11)) // still deduped after reload
        assertEquals(2, NowPilot.snapshot().size)
        val csv = NowPilot.csv(NowPilot.snapshot())
        assertTrue(csv.startsWith("time_ms,day,type,key,source\n")); assertTrue(csv.contains("nudge-open"))
        assertTrue(NowPilot.metrics(NowPilot.snapshot(), fri).report("0.10.2").contains("Nudges sent: 0 · opened: 1"))
        f.delete()
    }

    @Test fun widgetCarriesTheNowKeyOnlyWhenActionable() {
        val today = AttendanceToday.NotStarted
        val c = Candidate("attendance.check-in", "today", Band.TimeCritical, 80, "Check in", "Shift started at 9:30 AM", "", "Check in", NowAction.Open("attendance"))
        val s = WidgetSummary.of(today, 0, 0, day = LocalDate.now(AppZone), now = c)
        assertEquals("attendance.check-in:today", s.nowKey)
        assertNull(WidgetSummary.of(today, 0, 0, day = LocalDate.now(AppZone), now = c.copy(action = NowAction.None)).nowKey)
    }
}
