package com.pravahax.portalx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pravahax.portalx.data.*
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.security.SecureStore
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The gateway's own route table (generated from router.ts into test resources). */
object GatewayRoutes {
    val routes: List<Pair<String, Regex>> by lazy {
        val text = GatewayRoutes::class.java.classLoader!!.getResource("gateway-routes.txt")!!.readText()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { l -> l.substringBefore('\t') to Regex(l.substringAfter('\t')) }
    }
    fun matches(method: String, path: String) = routes.any { (m, r) -> m == method && r.matches(path) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class GatewayContractTest {
    private val samples = mapOf("taskId" to "7", "projectId" to "3", "id" to "11")

    /** Every Fn, with its path params filled, must be a route the gateway actually serves with that method. */
    @Test fun everyFnIsARealGatewayRoute() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val api = PortalApi(ctx, "https://portal.pravahax.com", SecureStore(ctx, "contract"))
        val bad = Fn.entries.filterNot { fn ->
            val data = buildJsonObject { Regex("\\{(\\w+)\\}").findAll(fn.path).forEach { put(it.groupValues[1], samples.getValue(it.groupValues[1])) } }
            val req = api.buildRequest(fn, data)
            GatewayRoutes.matches(req.method, req.url.encodedPath)
        }
        assertEquals("Fn entries the gateway would answer with 404 NOT_FOUND", emptyList<Fn>(), bad)
    }

    /** Required body fields per write, from router.ts readJson<> + MISSING_FIELDS checks. */
    @Test fun writesSendTheFieldsTheGatewayRequires() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val api = PortalApi(ctx, "https://portal.pravahax.com", SecureStore(ctx, "contract2"))
        fun body(fn: Fn, d: JsonObject) = Json.parseToJsonElement(okio.Buffer().also { api.buildRequest(fn, d).body!!.writeTo(it) }.readUtf8()).jsonObject
        val leave = body(Fn.ApplyLeave, buildJsonObject { put("leaveTypeId", 1); put("startDate", "2026-10-10"); put("endDate", "2026-10-10"); put("halfDay", false); put("reason", "x") })
        assertTrue(leave.keys.containsAll(listOf("leaveTypeId", "startDate", "endDate", "reason")))
        val review = body(Fn.DecideLeave, buildJsonObject { put("id", 7); put("decision", "approved") })
        assertEquals(setOf("id", "decision"), review.keys)
        val comment = body(Fn.AddTaskComment, buildJsonObject { put("taskId", 7); put("body", "hi") })
        assertEquals(setOf("body"), comment.keys) // taskId went into the path
        val reset = body(Fn.RequestPasswordReset, buildJsonObject { put("workspace", "pravahax"); put("identifier", "PSE-00002") })
        assertEquals(setOf("workspace", "identifier"), reset.keys)
    }

    @Test fun correctionReasonIsOneFreeTextField() {
        assertEquals("Forgot check out", com.pravahax.portalx.ui.correctionReason("forgot_check_out", " "))
        assertEquals("Late arrival: train delayed", com.pravahax.portalx.ui.correctionReason("late_arrival", "train delayed"))
    }
}

class ShapesTest {
    private val directory = Shapes(emptyMap(), null).normalize(Fn.Directory, Json.parseToJsonElement(GatewayFixtures.real(Fn.Directory)))
    private val people = directory.objects().associateBy { it.str("id")!!.toLong() }
    private val me = SessionUser(Json.parseToJsonElement(GatewayFixtures.real(Fn.Login)).jsonObject["user"]!!.jsonObject)
    private val shapes = Shapes(people, me)
    private fun n(fn: Fn) = shapes.normalize(fn, Json.parseToJsonElement(GatewayFixtures.real(fn, java.time.LocalDate.of(2026, 10, 8))))

    @Test fun directoryMapsMemberProjection() {
        val asha = people.getValue(7)
        assertEquals("Asha Rao", asha.str("name")); assertEquals("PSE-00007", asha.str("userId")); assertEquals("People", asha.str("team"))
        assertEquals("asha@pravahax.com", asha.str("email")); assertTrue(asha.bool("isTeamLead"))
    }

    @Test fun leaveBalancesUseDefaultAllowanceWhenNoBalanceRow() {
        val b = n(Fn.MyLeave).obj().list("balances").objects()
        assertEquals(listOf("Casual Leave", "Sick Leave", "Unpaid"), b.map { it.str("type") })
        assertEquals(6.5, b[0].num("remaining")!!, 0.0)
        assertEquals(8.0, b[1].num("allocated")!!, 0.0); assertEquals(8.0, b[1].num("remaining")!!, 0.0) // null row → balance_days
        assertEquals(0.0, b[2].num("remaining")!!, 0.0) // never negative
        assertEquals("1", b[0].str("typeId"))
        val r = n(Fn.MyLeave).obj().list("requests").objects()
        assertEquals("2026-10-17", r[0].str("startDate")); assertEquals("Asha Rao", r[1].str("reviewer"))
    }

    @Test fun pendingRowsGetNames() {
        val l = n(Fn.PendingLeaveApprovals).objects().single()
        assertEquals("Rohan Mehta", l.str("name")); assertEquals("Sick Leave", l.str("type")); assertEquals(2.0, l.num("days")!!, 0.0)
        val c = n(Fn.Corrections).objects().single()
        assertEquals("Asha Rao", c.str("name")); assertEquals("PSE-00007", c.str("memberUserId")); assertEquals("2026-10-06", c.str("date"))
        assertNotNull(c.str("note"))
    }

    @Test fun tasksAndDetail() {
        val t = n(Fn.Tasks).objects()
        assertEquals("L6", t[0].str("title")); assertEquals("PortalX", t[0].str("projectName")); assertEquals("varad", t[0].str("assignee"))
        assertNull(t[0].str("dueDate")); assertEquals("2026-10-07", t[1].str("dueDate"))
        val d = n(Fn.TaskDetail).obj()!!
        assertEquals("Asha Rao", d.str("creator")); assertEquals("Asha Rao", d.list("comments").objects().single().str("author"))
    }

    @Test fun calendarDeadlinesAndLeaveNames() {
        val c = n(Fn.CalendarMonth).obj()!!
        assertEquals(listOf("Rohan Mehta", "varad"), c.list("leave").objects().map { it.str("name") })
        val dl = c.list("projectDeadlines").objects()
        assertEquals(1, dl.size); assertEquals("2026-11-07", dl[0].str("endDate"))
    }

    @Test fun commsShapes() {
        val a = n(Fn.Announcements).obj().list("published").objects()
        assertEquals("Asha Rao", a[0].str("author")); assertFalse(a[0].bool("readByMe")); assertTrue(a[1].bool("readByMe"))
        val m = n(Fn.Meetings).objects()
        assertEquals("https://meet.google.com/abc-defg-hij", m[0].str("link")); assertEquals("varad", m[0].str("organizer"))
    }

    @Test fun istServerDatesDoNotShiftADay() {
        // A DATE serialised by a service running in IST arrives as the previous day 18:30Z.
        assertEquals("2026-10-08", Shapes.day("2026-10-07T18:30:00.000Z"))
        assertEquals("2026-10-08", Shapes.day("2026-10-08T00:00:00.000Z"))
        assertEquals("2026-10-08", Shapes.day("2026-10-08"))
    }

    @Test fun permissionsDriveCapabilities() {
        val owner = SessionUser(JsonObject(me.raw + ("permissions" to JsonArray(GatewayFixtures.ownerPerms.map { JsonPrimitive(it) }))))
        assertTrue(owner.canApproveLeave && owner.canApproveCorrections && owner.canManageTasks && owner.canReadPeople)
        val member = SessionUser(JsonObject(me.raw + mapOf("role" to JsonPrimitive("member"), "permissions" to JsonArray(listOf(JsonPrimitive("tasks.read"))))))
        assertFalse(member.canApproveLeave || member.canReadPeople || member.seesStats)
    }

    @Test fun garbageNeverThrows() {
        for (fn in Fn.entries) for (junk in listOf("null", "1", "\"x\"", "[]", "{}", "[null,{\"id\":{}}]", "{\"task\":5,\"balances\":\"x\",\"leave\":{}}"))
            shapes.normalize(fn, Json.parseToJsonElement(junk))
    }
}
