package com.pravahax.portalx

import com.pravahax.portalx.data.toJson
import com.pravahax.portalx.data.model.*
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.push.Push
import com.pravahax.portalx.push.PushMessage
import com.pravahax.portalx.widget.WidgetSummary
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** v0.8: push payloads, the approvals inbox and the widget summary. */
class V08Test {
    private fun leave(id: Int, start: String, status: String? = "pending") =
        LeaveRequest(JsonPrimitive(id), "$id", "L$id", null, "Casual", start, start, 1.0, null, status, null)
    private fun corr(id: Int, date: String, status: String = "pending") =
        Correction(JsonPrimitive(id), "$id", "C$id", null, date, null, null, null, status)

    @Test fun inboxMergesPendingOldestFirst() {
        val inbox = ApprovalItem.inbox(
            listOf(leave(1, "2026-10-12"), leave(2, "2026-10-01", "approved"), leave(3, "2026-10-05", null)),
            listOf(corr(1, "2026-10-03"), corr(4, "2026-10-02", "rejected"), Correction(JsonPrimitive(9), "9", "C9", null, null, null, null, null, "pending")),
        )
        assertEquals(listOf("corr:1", "leave:3", "leave:1", "corr:9"), inbox.map { it.key })
        assertEquals(stableKeysOf(inbox, "a").size, stableKeysOf(inbox, "a").toSet().size) // leave 1 and correction 1 don't collide
    }

    @Test fun decisionsRouteByKind() {
        val (c, l) = ApprovalItem.inbox(listOf(leave(7, "2026-10-09")), listOf(corr(8, "2026-10-01")))
        assertEquals(Fn.DecideCorrection, c.kind.fn)
        assertEquals(Fn.DecideLeave, l.kind.fn)
        assertEquals(buildJsonObject { put("id", 7); put("decision", "approved") }, l.decision(true)!!.toJson())
        assertEquals("rejected", c.decision(false)!!.decision)
        assertNotEquals(c.actionKey, l.actionKey)
        assertNull(l.copy(id = null).decision(true))
    }

    @Test fun pushPayloadsMapToChannelAndRoute() {
        val a = PushMessage.from(mapOf("type" to "leave_request", "title" to "Leave request", "body" to "Asha · 2 days", "id" to "5"))!!
        assertEquals(PushMessage.Channel.Approvals, a.channel); assertEquals("approvals", a.route); assertEquals("leave_request:5", a.tag)
        assertTrue(Fn.PendingLeaveApprovals in a.stale)
        assertEquals("tasks", PushMessage.from(mapOf("type" to "task_assigned", "body" to "Fix login"))!!.route)
        assertEquals("leave", PushMessage.from(mapOf("type" to "leave_approved", "body" to "Approved"))!!.route)
        val n = PushMessage.from(emptyMap(), "Hello", "World")!!
        assertEquals("notifications", n.route); assertEquals(PushMessage.Channel.General, n.channel); assertEquals("Hello", n.title)
        assertEquals("PortalX", PushMessage.from(mapOf("body" to "x"))!!.title)
        assertNull(PushMessage.from(mapOf("type" to "task_assigned")))
    }

    @Test fun routesAreAllowlisted() {
        assertEquals("meetings", PushMessage.from(mapOf("body" to "x", "route" to " Meetings "))!!.route)
        assertEquals("notifications", PushMessage.from(mapOf("body" to "x", "route" to "password"))!!.route)
        assertNull(Push.safeRoute("users")); assertNull(Push.safeRoute(null)); assertEquals("approvals", Push.safeRoute("approvals"))
    }

    @Test fun widgetSummary() {
        fun t(inT: String? = null, out: String? = null, leave: Boolean = false, brk: Boolean = false) = AttendanceToday(inT, out, leave, brk, 0, null, null)
        assertEquals("Not checked in yet", WidgetSummary.of(t(), 0, 0, day = java.time.LocalDate.of(2026, 10, 9)).headline)
        assertEquals("No open tasks", WidgetSummary.of(t(), 0, 0, day = java.time.LocalDate.of(2026, 10, 9)).detail)
        assertEquals("Checked in at 9:41 AM", WidgetSummary.of(t("2026-10-09T04:11:00Z"), 1, 0, day = java.time.LocalDate.of(2026, 10, 9)).headline)
        assertEquals("1 open task", WidgetSummary.of(t("2026-10-09T04:11:00Z"), 1, 0, day = java.time.LocalDate.of(2026, 10, 9)).detail)
        assertEquals("On a break", WidgetSummary.of(t("2026-10-09T04:11:00Z", brk = true), 0, 0, day = java.time.LocalDate.of(2026, 10, 9)).headline)
        assertTrue(WidgetSummary.of(t("2026-10-09T04:11:00Z", "2026-10-09T12:30:00Z"), 0, 0, day = java.time.LocalDate.of(2026, 10, 9)).headline.startsWith("Checked out at 6:00"))
        assertEquals("On leave today", WidgetSummary.of(t(leave = true), 0, 0, day = java.time.LocalDate.of(2026, 10, 9)).headline)
        val w = WidgetSummary.of(null, 3, 2)
        assertEquals("Open PortalX to sync", w.headline); assertEquals("3 open tasks · 2 to approve", w.detail); assertEquals("approvals", w.route)
        assertEquals("home", WidgetSummary.signedOut.route)
    }
}
