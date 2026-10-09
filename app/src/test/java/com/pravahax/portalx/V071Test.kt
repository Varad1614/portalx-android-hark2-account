package com.pravahax.portalx

import com.pravahax.portalx.data.*
import com.pravahax.portalx.data.model.*
import com.pravahax.portalx.net.Fn
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** v0.7.1: typed read models + typed write bodies. */
class V071Test {
    private val shapes = Shapes(emptyMap(), null)
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test fun tasksDecodeFromNormalisedGatewayRows() {
        val raw = j("""[{"id":"7","title":"Ship v0.7.1","status":"in_progress","priority":"high","due_date":"2026-10-12","project_name":"PortalX"},
                         {"id":8,"title":"  ","status":null}]""")
        val tasks = Endpoint.Tasks.decode(shapes.normalize(Fn.Tasks, raw))
        assertEquals(2, tasks.size)
        val t = tasks[0]
        assertEquals(JsonPrimitive(7L), t.id) // numeric strings become numbers, exactly like the web's Number()
        assertEquals("Ship v0.7.1", t.title); assertEquals("in_progress", t.status); assertEquals("high", t.priority)
        assertEquals("2026-10-12", t.dueDate); assertEquals("PortalX", t.projectName); assertFalse(t.done)
        // Blank strings collapse to null, missing status defaults to todo (Shapes), and nothing throws.
        assertNull(tasks[1].title); assertEquals("todo", tasks[1].status)
    }

    @Test fun garbageNeverThrowsAndYieldsEmptyModels() {
        for (e in listOf(Endpoint.Tasks, Endpoint.Directory, Endpoint.Meetings, Endpoint.Notifications, Endpoint.Users, Endpoint.AuditLogs)) {
            assertTrue(e.decode(j("""{"not":"a list"}""")).isEmpty())
            assertTrue(e.decode(j("""[1, "x", null, {"id":1}]""")).size <= 1)
        }
        assertEquals(AttendanceToday.NotStarted, Endpoint.AttendanceToday.decode(JsonNull)) // null = not checked in yet (v0.9.2)
        assertNull(Endpoint.TaskDetail.decode(j("[]")))
        assertNull(Endpoint.Settings.decode(j("42")))
        assertTrue(Endpoint.MyLeave.decode(j("\"x\"")).balances.isEmpty())
        assertTrue(Endpoint.CalendarMonth.decode(JsonNull).leave.isEmpty())
    }

    @Test fun leaveSummaryKeepsBalancesRequestsAndPaidFlag() {
        val raw = j("""{"balances":[{"leave_type_id":3,"name":"Casual","allocated":12,"used":4.5,"paid":false},{"id":"4","name":"Sick","balance_days":6}],
                         "requests":[{"id":11,"leave_type_name":"Casual","start_date":"2026-10-20","end_date":"2026-10-21","days":2,"status":"pending"}]}""")
        val s = Endpoint.MyLeave.decode(shapes.normalize(Fn.MyLeave, raw))
        assertEquals(listOf("Casual", "Sick"), s.balances.map { it.type })
        assertEquals(7.5, s.balances[0].remaining!!, 0.0); assertEquals(false, s.balances[0].paid)
        assertEquals(JsonPrimitive(4L), s.balances[1].typeId); assertEquals(6.0, s.balances[1].remaining!!, 0.0)
        val r = s.requests.single()
        assertEquals("Casual", r.type); assertEquals(2.0, r.days!!, 0.0); assertEquals("pending", r.status)
    }

    @Test fun attendanceTodayReadsSnakeAndCamelCase() {
        val a = AttendanceToday.from(j("""{"check_in":"2026-10-09T03:30:00Z","isOnBreak":true,"total_break_seconds":"900","onLeave":false}"""))!!
        assertEquals("2026-10-09T03:30:00Z", a.checkIn); assertNull(a.checkOut)
        assertTrue(a.onBreak); assertFalse(a.onLeave); assertEquals(900L, a.breakSeconds)
    }

    @Test fun taskDetailCarriesCommentsAndProjectDetailItsMembersAndTasks() {
        val d = Endpoint.TaskDetail.decode(shapes.normalize(Fn.TaskDetail, j("""{"task":{"id":5,"title":"T"},"comments":[{"id":1,"body":"hi","created_at":"2026-10-09T05:00:00Z"}]}""")))!!
        assertEquals("T", d.task.title); assertEquals("hi", d.comments.single().body); assertEquals("Member", d.comments.single().author)
        val p = Endpoint.ProjectDetail.decode(shapes.normalize(Fn.ProjectDetail, j("""{"project":{"id":2,"name":"P","task_count":3},"members":[{"user_id":9,"role":"lead"}],"tasks":[{"id":1,"title":"a"}]}""")))!!
        assertEquals("P", p.project.name); assertEquals(3.0, p.project.taskCount!!, 0.0)
        assertEquals("lead", p.members.single().role); assertEquals("a", p.tasks.single().title)
    }

    @Test fun personSearchMatchesEveryVisibleField() {
        val p = Person.from(j("""{"id":1,"name":"Asha Rao","userId":"PX-104","team":"Platform","designation":"Engineer","email":"asha@x.com","organizationRoles":["Admin",null]}""").jsonObject)
        for (q in listOf("asha", "px-104", "platform", "engineer", "x.com")) assertTrue(q, p.matches(q))
        assertFalse(p.matches("finance"))
        assertEquals(listOf("Admin"), p.organizationRoles)
    }

    @Test fun typedRowKeysStayUniqueWithMissingOrDuplicateIds() {
        val rows = Task.list(j("""[{"id":1},{"id":1},{},{}]"""))
        assertEquals(listOf("t:1", "t:1#2", "t:idx2", "t:idx3"), stableKeysOf(rows, "t"))
    }

    @Test fun createTaskBodyMatchesWhatTheGatewayExpects() {
        val full = CreateTaskRequest("Write docs", "Readme", "high", "2026-10-15", JsonPrimitive(3), JsonPrimitive("u-9"), 42L).toJson().jsonObject
        assertEquals("todo", full["status"]!!.jsonPrimitive.content)
        assertEquals(42L, full["actorUserId"]!!.jsonPrimitive.long)
        assertEquals("u-9", full["assigneeId"]!!.jsonPrimitive.content)
        // No description → the key is left out entirely; unset ids/date are sent as explicit nulls (as before v0.7.1).
        val bare = CreateTaskRequest("T", null, "medium", null, null, null, null).toJson().jsonObject
        assertFalse(bare.containsKey("description"))
        for (k in listOf("dueDate", "projectId", "assigneeId", "actorUserId")) assertEquals(k, JsonNull, bare[k])
    }

    @Test fun decisionBodyEchoesTheIdAsSent() {
        assertEquals(j("""{"id":12,"decision":"approved"}"""), DecisionRequest(JsonPrimitive(12), "approved").toJson())
        assertEquals(j("""{"id":"abc","decision":"rejected"}"""), DecisionRequest(JsonPrimitive("abc"), "rejected").toJson())
    }
}
