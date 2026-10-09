package com.pravahax.portalx.data.model

import com.pravahax.portalx.data.*
import kotlinx.serialization.json.*

/**
 * v0.7.1: typed read models. Each screen reads one of these instead of poking string keys into a JsonObject,
 * so a renamed field is a compile error in one mapper, not a blank label on some screen.
 *
 * The pipeline is: gateway payload → [Shapes] (one clean camelCase JSON per route; that is what Room caches)
 * → `from()` here at the edge of the UI. The mappers use the same tolerant readers as before (blank → null,
 * numeric strings → numbers, `{ name }` objects → their name), so garbage input still never throws.
 *
 * Ids stay [JsonPrimitive] because the server mixes numeric and string ids and writes must echo them as sent.
 */
interface Keyed { val key: String? }

/** Unique, stable LazyColumn keys for typed rows (see [stableKeys] for the JSON twin). */
fun stableKeysOf(items: List<Keyed>, prefix: String): List<String> {
    val seen = HashMap<String, Int>()
    return items.mapIndexed { i, o ->
        val base = "$prefix:" + (o.key ?: "idx$i")
        val n = seen.merge(base, 1, Int::plus) ?: 1
        if (n == 1) base else "$base#$n"
    }
}

private fun JsonObject.strings(key: String): List<String> = list(key).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
private inline fun <T> JsonElement?.rows(map: (JsonObject) -> T): List<T> = objects().map(map)

// ---------------- people ----------------
data class Person(
    val id: JsonPrimitive?, override val key: String?, val userId: String?, val name: String?, val email: String?,
    val role: String?, val roleLabel: String?, val status: String?, val team: String?, val manager: String?,
    val designation: String?, val phone: String?, val organizationRoles: List<String>,
) : Keyed {
    fun matches(q: String) = listOf(name, userId, team, roleLabel, designation, email).any { (it ?: "").lowercase().contains(q) }
    companion object {
        fun from(o: JsonObject) = Person(
            o.idOf(), o.str("id"), o.str("userId"), o.str("name"), o.str("email"), o.str("role"), o.str("roleLabel"),
            o.str("status"), o.str("team"), o.str("manager"), o.str("designation"), o.str("phone"), o.strings("organizationRoles"),
        )
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

// ---------------- attendance ----------------
data class AttendanceToday(
    val checkIn: String?, val checkOut: String?, val onLeave: Boolean, val onBreak: Boolean,
    val breakSeconds: Long, val leaveType: String?, val lastBreakStart: String?,
) {
    companion object {
        /** No attendance row yet today: the gateway answers `data: null` until the first check-in. */
        val NotStarted = AttendanceToday(null, null, false, false, 0, null, null)

        /**
         * v0.9.2: `data: null` (not checked in yet) is a real answer, "not started", not a load failure. Treating it
         * as unknown hid the Check in button every morning. A row dated another day (stale cache across midnight)
         * also means today hasn't started.
         */
        fun from(e: JsonElement?, day: java.time.LocalDate = java.time.LocalDate.now(AppZone)): AttendanceToday? {
            if (e is JsonNull) return NotStarted
            val t = e.obj() ?: return null
            val date = t.str("attendance_date", "attendanceDate")?.let(::istDate)
            if (date != null && date != day) return NotStarted
            return AttendanceToday(
                t.str("check_in", "checkIn"), t.str("check_out", "checkOut"), t.bool("onLeave", "on_leave"),
                t.bool("is_on_break", "isOnBreak"), (t.num("total_break_seconds") ?: 0.0).toLong(), t.str("leaveType"),
                t.str("last_break_start", "lastBreakStart"),
            )
        }
    }
}

/** "2026-10-09", or a timestamp ("2026-10-08T18:30:00.000Z" = IST midnight) as an IST calendar date; null if unparseable. */
internal fun istDate(v: String): java.time.LocalDate? = runCatching {
    if (v.length <= 10) java.time.LocalDate.parse(v)
    else java.time.OffsetDateTime.parse(v).atZoneSameInstant(AppZone).toLocalDate()
}.getOrNull()

data class AttendanceRecord(val date: String?, val checkIn: String?, val checkOut: String?, val breakSeconds: Long, val status: String?, override val key: String?) : Keyed {
    companion object {
        fun from(r: JsonObject) = AttendanceRecord(
            r.str("attendance_date", "date"), r.str("check_in", "checkIn"), r.str("check_out", "checkOut"),
            (r.num("total_break_seconds") ?: 0.0).toLong(), r.str("status"), r.str("id"),
        )
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

data class Correction(
    val id: JsonPrimitive?, override val key: String?, val name: String?, val memberUserId: String?, val date: String?,
    val checkIn: String?, val checkOut: String?, val note: String?, val status: String,
) : Keyed {
    companion object {
        fun from(c: JsonObject) = Correction(
            c.idOf(), c.str("id"), c.str("name", "full_name", "memberName"), c.str("memberUserId"), c.str("date", "attendance_date"),
            c.str("checkIn"), c.str("checkOut"), c.str("note"), c.str("status") ?: "pending",
        )
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

// ---------------- leave ----------------
data class LeaveBalance(
    val typeId: JsonPrimitive?, override val key: String?, val type: String?, val allocated: Double?, val used: Double?,
    val remaining: Double?, val paid: Boolean?,
) : Keyed {
    companion object {
        fun from(b: JsonObject) = LeaveBalance(
            b.idOf("typeId", "leaveTypeId", "id"), b.str("typeId", "type"), b.str("type"), b.num("allocated"), b.num("used"),
            b.num("remaining"), (b["paid"] as? JsonPrimitive)?.booleanOrNull,
        )
    }
}

data class LeaveRequest(
    val id: JsonPrimitive?, override val key: String?, val name: String?, val memberUserId: String?, val type: String?,
    val startDate: String?, val endDate: String?, val days: Double?, val reason: String?, val status: String?, val reviewer: String?,
) : Keyed {
    companion object {
        fun from(r: JsonObject) = LeaveRequest(
            r.idOf(), r.str("id"), r.str("name"), r.str("memberUserId"), r.str("type"), r.str("startDate"), r.str("endDate"),
            r.num("days"), r.str("reason"), r.str("status"), r.str("reviewer"),
        )
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

data class LeaveSummary(val balances: List<LeaveBalance>, val requests: List<LeaveRequest>) {
    companion object {
        fun from(e: JsonElement?): LeaveSummary {
            val o = e.obj()
            return LeaveSummary(o.list("balances").objects().map(LeaveBalance::from), o.list("requests").objects().map(LeaveRequest::from))
        }
    }
}

// ---------------- work ----------------
data class Task(
    val id: JsonPrimitive?, override val key: String?, val title: String?, val description: String?, val status: String?,
    val priority: String?, val dueDate: String?, val projectName: String?, val assignee: String?, val creator: String?,
) : Keyed {
    val done get() = status == "done"
    companion object {
        fun from(t: JsonObject) = Task(
            t.idOf(), t.str("id"), t.str("title"), t.str("description"), t.str("status"), t.str("priority"),
            t.str("dueDate", "due_date"), t.str("projectName"), t.str("assignee"), t.str("creator", "createdBy"),
        )
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

data class TaskComment(override val key: String?, val author: String?, val body: String?, val at: String?) : Keyed

data class TaskDetail(val task: Task, val comments: List<TaskComment>) {
    companion object {
        fun from(e: JsonElement?): TaskDetail? = e.obj()?.let { d ->
            TaskDetail(Task.from(d), d.list("comments").objects().map { c ->
                TaskComment(c.str("id"), c.str("author", "authorName", "name"), c.str("body"), c.str("at", "createdAt", "created_at"))
            })
        }
    }
}

data class Project(
    val id: JsonPrimitive?, override val key: String?, val name: String?, val code: String?, val description: String?,
    val status: String?, val priority: String?, val startDate: String?, val endDate: String?,
    val memberCount: Double?, val taskCount: Double?, val tasksDone: Double?,
) : Keyed {
    companion object {
        fun from(p: JsonObject) = Project(
            p.idOf(), p.str("id"), p.str("name"), p.str("code"), p.str("description"), p.str("status"), p.str("priority"),
            p.str("startDate"), p.str("endDate"), p.num("memberCount"), p.num("taskCount"), p.num("tasksDone"),
        )
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

data class ProjectMember(override val key: String?, val name: String?, val role: String?) : Keyed

data class ProjectDetail(val project: Project, val members: List<ProjectMember>, val tasks: List<Task>) {
    companion object {
        fun from(e: JsonElement?): ProjectDetail? = e.obj()?.let { d ->
            ProjectDetail(Project.from(d), d.list("members").objects().map { ProjectMember(it.str("id"), it.str("name"), it.str("role")) },
                d.list("tasks").objects().map(Task::from))
        }
    }
}

// ---------------- comms ----------------
data class Announcement(
    val id: JsonPrimitive?, override val key: String?, val title: String?, val body: String?, val author: String?,
    val publishedAt: String?, val teamName: String?, val readByMe: Boolean,
) : Keyed {
    companion object {
        fun from(a: JsonObject) = Announcement(a.idOf(), a.str("id"), a.str("title"), a.str("body"), a.str("author"),
            a.str("publishedAt"), a.str("teamName"), a.bool("readByMe"))
        /** The route returns { published: [...] }; drafts are not shown in the app. */
        fun published(e: JsonElement?) = e.obj().list("published").objects().map(::from)
    }
}

data class Meeting(
    override val key: String?, val title: String?, val description: String?, val startAt: String?, val endAt: String?,
    val location: String?, val link: String?, val organizer: String?,
) : Keyed {
    companion object {
        fun from(m: JsonObject) = Meeting(m.str("id"), m.str("title"), m.str("description"), m.str("startAt"), m.str("endAt"),
            m.str("location"), m.str("link"), m.str("organizer"))
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

data class Notification(
    val id: JsonPrimitive?, override val key: String?, val title: String?, val body: String?, val type: String?,
    val createdAt: String?, val read: Boolean,
) : Keyed {
    companion object {
        fun from(n: JsonObject) = Notification(n.idOf(), n.str("id"), n.str("title"), n.str("body"), n.str("type"), n.str("createdAt"), n.bool("read"))
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

/** calendar route: one month of holidays, meetings, task due dates, project deadlines and approved leave. */
data class CalendarMonth(
    val holidays: List<Dated>, val meetings: List<CalMeeting>, val tasks: List<Dated>,
    val deadlines: List<Dated>, val leave: List<CalLeave>,
) {
    data class Dated(val date: String?, val title: String?)
    data class CalMeeting(val date: String?, val startAt: String?, val title: String?)
    data class CalLeave(val name: String?, val startDate: String?, val endDate: String?, val status: String?)
    companion object {
        fun from(e: JsonElement?): CalendarMonth {
            val d = e.obj()
            return CalendarMonth(
                d.list("holidays").objects().map { Dated(it.str("date"), it.str("name")) },
                d.list("meetings").objects().map { CalMeeting(it.str("date"), it.str("startAt"), it.str("title")) },
                d.list("tasks").objects().map { Dated(it.str("dueDate"), it.str("title")) },
                d.list("projectDeadlines").objects().map { Dated(it.str("endDate"), it.str("name")) },
                d.list("leave").objects().map { CalLeave(it.str("name"), it.str("startDate"), it.str("endDate"), it.str("status")) },
            )
        }
    }
}

// ---------------- workspace / admin ----------------
data class Team(override val key: String?, val name: String?, val description: String?, val lead: String?, val manager: String?, val memberCount: Double?) : Keyed {
    companion object {
        fun from(t: JsonObject) = Team(t.str("id"), t.str("name"), t.str("description"), t.str("lead"), t.str("manager"), t.num("memberCount"))
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

data class Review(
    override val key: String?, val period: String?, val rating: Double?, val strengths: String?, val improvements: String?,
    val goals: String?, val status: String?, val reviewer: String?, val at: String?,
) : Keyed {
    companion object {
        fun from(r: JsonObject) = Review(r.str("id"), r.str("period"), r.num("rating"), r.str("strengths"), r.str("improvements"),
            r.str("goals"), r.str("status"), r.str("reviewer"), r.str("at"))
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

data class Document(
    override val key: String?, val title: String?, val filename: String?, val state: String?, val version: Double?,
    val size: Double?, val owner: String?, val createdAt: String?,
) : Keyed {
    companion object {
        fun from(d: JsonObject) = Document(d.str("id"), d.str("title"), d.str("filename"), d.str("state"), d.num("version"),
            d.num("size"), d.str("owner"), d.str("createdAt"))
        /** The route returns { documents: [...], hasMore }. */
        fun list(e: JsonElement?) = e.obj().list("documents").objects().map(::from)
    }
}

data class UserAccount(
    override val key: String?, val name: String?, val userId: String?, val email: String?, val team: String?,
    val status: String?, val organizationRoles: List<String>, val mustChangePassword: Boolean,
) : Keyed {
    companion object {
        fun from(u: JsonObject) = UserAccount(u.str("id"), u.str("name"), u.str("userId"), u.str("email"), u.str("team"),
            u.str("status"), u.strings("organizationRoles"), u.bool("mustChangePassword"))
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

data class AccessRole(override val key: String?, val name: String?, val description: String?, val system: Boolean, val permissionCount: Int, val assignedCount: Int) : Keyed {
    companion object {
        fun from(r: JsonObject) = AccessRole(r.str("id"), r.str("name"), r.str("description"), r.bool("system"),
            r.list("permissionCodes").ifEmpty { r.list("permissions") }.size, r.list("assignedUserIds").size)
        /** The route returns { roles: [...] }. */
        fun list(e: JsonElement?) = e.obj().list("roles").objects().map(::from)
    }
}

data class AuditEntry(override val key: String?, val action: String?, val actor: String?, val actorUid: String?, val entityType: String?, val entityId: String?, val createdAt: String?) : Keyed {
    companion object {
        fun from(a: JsonObject) = AuditEntry(a.str("id"), a.str("action"), a.str("actor"), a.str("actorUid"), a.str("entityType"), a.str("entityId"), a.str("createdAt"))
        fun list(e: JsonElement?) = e.rows(::from)
    }
}

data class OrgSettings(val name: String?, val legalName: String?, val workspaceSlug: String?, val timezone: String?, val primaryColor: String?, val secondaryColor: String?) {
    companion object {
        fun from(e: JsonElement?): OrgSettings? = e.obj()?.let { s ->
            OrgSettings(s.str("name"), s.str("legalName"), s.str("workspaceSlug"), s.str("timezone"), s.str("primaryColor"), s.str("secondaryColor"))
        }
    }
}

// ---------------- approvals inbox (v0.8) ----------------
/** One item a manager can approve or reject: a pending leave request or attendance correction, in one list. */
data class ApprovalItem(
    val kind: Kind, val id: JsonPrimitive?, override val key: String?, val name: String, val type: String?,
    val startDate: String?, val endDate: String?, val days: Double?, val checkIn: String?, val checkOut: String?, val note: String?,
) : Keyed {
    enum class Kind(val fn: com.pravahax.portalx.net.Fn, val label: String) {
        Leave(com.pravahax.portalx.net.Fn.DecideLeave, "Leave"),
        Correction(com.pravahax.portalx.net.Fn.DecideCorrection, "Correction"),
    }
    /** One in-flight key per item for both decisions, so Approve and Reject can't race each other. */
    val actionKey get() = "${kind.name}-${id?.content ?: key}"
    fun decision(approve: Boolean): com.pravahax.portalx.data.DecisionRequest? =
        id?.let { com.pravahax.portalx.data.DecisionRequest(it, if (approve) "approved" else "rejected") }

    companion object {
        /** Pending items only, oldest first (FIFO), undated last. Keys are prefixed by kind so ids never collide. */
        fun inbox(leave: List<LeaveRequest>, corrections: List<Correction>): List<ApprovalItem> {
            val l = leave.filter { it.status == null || it.status.equals("pending", true) }.map {
                ApprovalItem(Kind.Leave, it.id, it.key?.let { k -> "leave:$k" }, it.name ?: "Member", it.type, it.startDate, it.endDate, it.days, null, null, it.reason)
            }
            val c = corrections.filter { it.status.equals("pending", true) }.map {
                ApprovalItem(Kind.Correction, it.id, it.key?.let { k -> "corr:$k" }, it.name ?: "Member", null, it.date, null, null, it.checkIn, it.checkOut, it.note)
            }
            return (l + c).sortedBy { com.pravahax.portalx.data.Dates.day(it.startDate)?.toEpochDay() ?: Long.MAX_VALUE }
        }
    }
}
