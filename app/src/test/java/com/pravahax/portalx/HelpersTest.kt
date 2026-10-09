package com.pravahax.portalx

import com.pravahax.portalx.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HelpersTest {
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun idOfKeepsServerTypeAndNeverFallsBackToZero() {
        assertEquals(JsonPrimitive(42L), o("""{"id":42}""").idOf())
        assertEquals(JsonPrimitive(42L), o("""{"id":"42"}""").idOf())        // numeric string -> number like the web's Number()
        assertEquals(JsonPrimitive("a1b2-uuid"), o("""{"id":"a1b2-uuid"}""").idOf())
        assertNull(o("""{"id":null}""").idOf())
        assertNull(o("""{"name":"x"}""").idOf())
        assertEquals(JsonPrimitive(3L), o("""{"typeId":3}""").idOf("typeId", "id"))
    }

    @Test fun stableKeysAreUniqueEvenWithMissingOrDuplicateIds() {
        val items = listOf(o("""{"id":1}"""), o("""{"id":1}"""), o("{}"), o("{}"))
        val keys = stableKeys(items, "t")
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun tolerantAccessors() {
        val x = o("""{"a":"","b":"7.5","c":true,"d":{"name":"Asha"},"e":"Infinity"}""")
        assertNull(x.str("a"))
        assertEquals("7.5", x.str("a", "b"))
        assertEquals(7.5, x.num("b")!!, 0.0)
        assertTrue(x.bool("c"))
        assertEquals("Asha", x.str("d"))
        assertNull(x.num("e"))
        assertEquals("1", fmtNum(1.0)); assertEquals("1.5", fmtNum(1.5)); assertEquals("0", fmtNum(null))
    }

    @Test fun sessionUserRoles() {
        // Before my-access answers: legacy role fallback. After: permission codes are the only truth.
        val owner = SessionUser(o("""{"fullName":"Varad K","userId":"PSE-00002","role":"super_admin"}"""))
        assertTrue(owner.isAdmin); assertTrue(owner.canApproveLeave); assertEquals("Varad", owner.firstName)
        val ownerNoPerm = SessionUser(o("""{"role":"super_admin","permissions":[]}"""))
        assertFalse(ownerNoPerm.canApproveLeave)
        val member = SessionUser(o("""{"name":"  ","role":"member","permissions":["leave.approve"]}"""))
        assertFalse(member.isAdmin); assertTrue(member.canApproveLeave); assertFalse(member.canManageTasks)
        assertFalse(member.mustChangePassword)
        assertTrue(SessionUser(o("""{"must_change_password":true}""")).mustChangePassword)
    }

    @Test fun invalidationCoversWrites() {
        assertTrue(Invalidation.after(com.pravahax.portalx.net.Fn.CheckIn).contains(com.pravahax.portalx.net.Fn.AttendanceToday))
        assertTrue(Invalidation.after(com.pravahax.portalx.net.Fn.DecideLeave).contains(com.pravahax.portalx.net.Fn.PendingLeaveApprovals))
        assertTrue(Invalidation.after(com.pravahax.portalx.net.Fn.Tasks).isEmpty())
    }
}

class DatesAndValidationTest {
    private val today = LocalDate.of(2026, 10, 7)

    @Test fun dayFieldsLandOnTheIstCalendarDay() {
        assertEquals(LocalDate.of(2026, 10, 7), Dates.day("2026-10-07"))
        assertEquals(LocalDate.of(2026, 10, 7), Dates.day("2026-10-07T00:00:00.000Z"))   // midnight UTC
        assertEquals(LocalDate.of(2026, 10, 7), Dates.day("2026-10-06T18:30:00.000Z"))   // IST midnight (v0.1.x showed 6 Oct)
        assertNull(Dates.day("")); assertNull(Dates.day("not a date"))
    }

    @Test fun timesAreShownInIst() {
        assertEquals("9:00 AM", Dates.time("2026-10-07T03:30:00Z"))
        assertEquals("9:00 AM", Dates.time("2026-10-07 09:00:00"))  // naive timestamps treated as IST
        assertEquals("—", Dates.time(null))
    }

    @Test fun overdue() {
        assertTrue(Dates.isOverdue("2026-10-06", false, today))
        assertFalse(Dates.isOverdue("2026-10-06T18:30:00Z", false, today)) // that's 7 Oct IST: due today, not overdue
        assertFalse(Dates.isOverdue("2026-10-01", true, today))
    }

    @Test fun duration() {
        assertEquals("0m", Dates.duration(0)); assertEquals("< 1m", Dates.duration(30)); assertEquals("1h 5m", Dates.duration(3900))
    }

    @Test fun leaveRules() {
        val d = { s: String -> LocalDate.parse(s) }
        assertEquals("End date can't be before the start date.", Validate.leave(1, d("2026-10-10"), d("2026-10-09"), false, "x", null, today).error)
        assertEquals("A half day must start and end on the same date.", Validate.leave(1, d("2026-10-10"), d("2026-10-11"), true, "x", null, today).error)
        assertEquals("Add a short reason.", Validate.leave(1, d("2026-10-10"), d("2026-10-10"), false, " ", null, today).error)
        assertEquals("Choose a leave type.", Validate.leave(null, d("2026-10-10"), d("2026-10-10"), false, "x", null, today).error)
        val ok = Validate.leave(1, d("2026-10-10"), d("2026-10-12"), false, "Family", 2.0, today)
        assertNull(ok.error); assertEquals(3.0, ok.days, 0.0); assertNotNull(ok.warning)
        assertEquals(0.5, Validate.leave(1, d("2026-10-10"), d("2026-10-10"), true, "x", 5.0, today).days, 0.0)
        assertNotNull(Validate.leave(1, d("2027-11-10"), d("2027-11-10"), false, "x", null, today).error)
    }

    @Test fun otherValidation() {
        assertNotNull(Validate.correction(LocalDate.of(2026, 10, 8), "", today))
        assertNull(Validate.correction(today, "", today))
        assertNotNull(Validate.newPassword("old", "short", "short"))
        assertNotNull(Validate.newPassword("old", "abcdefghijkl", "abcdefghijkX"))
        assertNull(Validate.newPassword("old", "abcdefghijkl", "abcdefghijkl"))
        assertNotNull(Validate.login("Bad WS!", "PSE-1", "pw"))
        assertNull(Validate.login("pravahax", "PSE-00002", "pw"))
        assertNull(Validate.safeWebUrl("javascript:alert(1)"))
        assertNull(Validate.safeWebUrl("intent://x#Intent;end"))
        assertEquals("https://meet.google.com/abc", Validate.safeWebUrl("https://meet.google.com/abc"))
    }

    @Test fun dueLabelsAreRelativeInIst() {
        val t = LocalDate.of(2026, 10, 8)
        assertEquals("Due today", Dates.dueLabel("2026-10-08", false, t))
        assertEquals("Due tomorrow", Dates.dueLabel("2026-10-09", false, t))
        assertEquals("Overdue · 7 Oct", Dates.dueLabel("2026-10-07", false, t))
        assertEquals("Due 7 Oct", Dates.dueLabel("2026-10-07", true, t))
    }

    @Test fun weekWorkedSumsOnlyThisIstWeekMinusBreaks() {
        val rows = Json.parseToJsonElement("""[
            {"attendance_date":"2026-10-06","check_in":"2026-10-06T03:30:00Z","check_out":"2026-10-06T12:30:00Z","total_break_seconds":1800},
            {"attendance_date":"2026-10-07","check_in":"2026-10-07T03:30:00Z","check_out":null},
            {"attendance_date":"2026-10-02","check_in":"2026-10-02T03:30:00Z","check_out":"2026-10-02T12:30:00Z"}
        ]""").jsonArray.map { com.pravahax.portalx.data.model.AttendanceRecord.from(it.jsonObject) }
        // Thu 8 Oct 2026 → week starts Mon 5 Oct: only the 6th counts (9h − 30m).
        assertEquals(8 * 3600L + 1800L, com.pravahax.portalx.ui.weekWorkedSeconds(rows, LocalDate.of(2026, 10, 8)))
    }
}
