package com.pravahax.portalx

import com.pravahax.portalx.data.AppZone
import com.pravahax.portalx.data.model.AttendanceRecord
import com.pravahax.portalx.data.model.AttendanceToday
import com.pravahax.portalx.now.*
import com.pravahax.portalx.push.Push
import com.pravahax.portalx.widget.WidgetSummary
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** v0.10.4: correction prompt for an earlier day left open, and data-age awareness. */
class V0104Test {
    private val mon = LocalDate.of(2026, 10, 12)
    private fun at(h: Int, m: Int = 0, d: LocalDate = mon) = ZonedDateTime.of(d.atTime(h, m), AppZone)
    private fun rec(d: String, inT: String?, out: String?, status: String? = "present") = AttendanceRecord(d, inT, out, 0, status, d)
    private val fridayOpen = rec("2026-10-09", "2026-10-09T09:35:00+05:30", null)
    private val thursdayDone = rec("2026-10-08", "2026-10-08T09:30:00+05:30", "2026-10-08T18:40:00+05:30")
    private fun ctx(now: ZonedDateTime, history: List<AttendanceRecord>?, inAt: ZonedDateTime? = null) = NowContext.build(
        now, AttendanceToday(inAt?.toOffsetDateTime()?.toString(), null, false, false, 0, null, null), Freshness.Live, history = history)

    @After fun tearDown() = NowSnoozes.clear()

    @Test fun findsTheLatestOpenDayInTheLookback() {
        assertEquals(LocalDate.of(2026, 10, 9), NowContext.openDay(listOf(thursdayDone, fridayOpen), mon))
        assertNull(NowContext.openDay(listOf(rec("2026-10-08", "2026-10-08T09:30:00+05:30", null)), mon.plusDays(2))) // older than 3 days
        assertNull(NowContext.openDay(listOf(rec("2026-10-12", "2026-10-12T09:30:00+05:30", null)), mon)) // today is not "open", it's running
        assertNull(NowContext.openDay(listOf(rec("2026-10-09", "2026-10-09T09:35:00+05:30", null, "correction_pending")), mon))
        assertNull(NowContext.openDay(null, mon))
    }

    @Test fun cardOffersAPrefilledCorrection() {
        val c = NowEngine.evaluate(ctx(at(11, 0), listOf(fridayOpen), inAt = at(9, 30))).chosen
        assertEquals("attendance.previous-day-open", c.ruleId)
        assertEquals("Check-out missing for Fri, 9 Oct", c.title)
        assertEquals(NowAction.Open("attendance?correct=2026-10-09&reason=forgot_check_out"), c.action)
        assertEquals("attendance?correct=2026-10-09&reason=forgot_check_out", Push.safeRoute((c.action as NowAction.Open).route))
        // check-in still comes first during the shift
        assertEquals("attendance.check-in-required", NowEngine.evaluate(ctx(at(10, 0), listOf(fridayOpen))).chosen.ruleId)
    }

    @Test fun submittingTheCorrectionClearsThePrompt() {
        val nowMs = at(11, 0).toInstant().toEpochMilli()
        NowSnoozes.correctionRequested(LocalDate.of(2026, 10, 9), mon, AppZone, nowMs)
        val r = NowEngine.evaluate(ctx(at(11, 0), listOf(fridayOpen), inAt = at(9, 30)), snoozedUntil = NowSnoozes.snapshot())
        assertNotEquals("attendance.previous-day-open", r.chosen.ruleId)
        // missed check-in for today: the request hides it until midnight
        NowSnoozes.correctionRequested(mon, mon, AppZone, at(19, 0).toInstant().toEpochMilli())
        assertTrue(NowSnoozes.isSnoozed("attendance.check-in-missed:today", at(23, 59).toInstant().toEpochMilli()))
        assertFalse(NowSnoozes.isSnoozed("attendance.check-in-missed:today", at(0, 1, mon.plusDays(1)).toInstant().toEpochMilli()))
        assertEquals("attendance?correct=2026-10-12&reason=forgot_check_in",
            (NowEngine.evaluate(ctx(at(19, 0), null)).chosen.action as NowAction.Open).route)
    }

    @Test fun oneMorningNudgePerOpenDay() {
        assertTrue(NowNudges.due(ctx(at(8, 0), listOf(fridayOpen)), emptySet()).none { "openday" in it.key }) // not before 8:30
        val n = NowNudges.due(ctx(at(8, 30), listOf(fridayOpen)), emptySet()).single { "openday" in it.key }
        assertEquals("2026-10-12|openday:2026-10-09", n.key); assertEquals("attendance?correct=2026-10-09&reason=forgot_check_out", n.route)
        assertTrue(NowNudges.due(ctx(at(9, 0), listOf(fridayOpen)), setOf(n.key)).none { "openday" in it.key })
    }

    @Test fun oldDataIsNotLive() {
        val now = at(12, 0).toInstant().toEpochMilli()
        assertEquals(Freshness.Live, NowContext.freshness(true, false, false, null, now - 29 * 60_000, now))
        assertEquals(Freshness.Cached, NowContext.freshness(true, false, false, null, now - 31 * 60_000, now))
        assertEquals(Freshness.Live, NowContext.freshness(true, false, false, null, null, now))
        // old attendance can't drive a punch: the card asks for a refresh instead
        val ctx = NowContext.build(at(10, 0), AttendanceToday(null, null, false, false, 0, null, null),
            NowContext.freshness(true, false, false, null, now - 3 * 3_600_000, now))
        assertEquals("attendance.refresh-needed", NowEngine.evaluate(ctx).chosen.ruleId)
    }

    @Test fun widgetSaysHowOldItsDataIs() {
        val t = AttendanceToday("2026-10-12T09:31:00+05:30", null, false, false, 0, null, null)
        val nowMs = at(12, 0).toInstant().toEpochMilli()
        assertEquals("No open tasks · as of 9:40 AM", WidgetSummary.of(t, 0, 0, day = mon, asOf = at(9, 40).toInstant().toEpochMilli(), nowMs = nowMs).detail)
        assertEquals("No open tasks", WidgetSummary.of(t, 0, 0, day = mon, asOf = at(11, 50).toInstant().toEpochMilli(), nowMs = nowMs).detail)
        assertNull(Push.safeRoute("attendance?correct=2026-10-09&reason=x;drop"))
        assertNull(Push.safeRoute("users?correct=2026-10-09&reason=forgot_check_out"))
    }
}
