package com.pravahax.portalx.data

import com.pravahax.portalx.net.Fn
import kotlinx.serialization.json.*

/**
 * Turns raw Mobile Gateway v1 payloads (Postgres rows, snake_case) into the one clean shape each screen reads.
 * Every mapping below is written against the gateway + service source (router.ts and the *-store.ts SQL).
 * Unknown/garbage input never throws: wrong types collapse to empty lists / missing fields.
 *
 * [people] resolves a numeric user id to a directory entry (already normalised by [person]) so lists that only
 * carry `user_id` can show a name. It is empty when the user lacks `people.read`; then rows fall back to [me].
 */
class Shapes(private val people: Map<Long, JsonObject>, private val me: SessionUser?) {

    fun normalize(fn: Fn, raw: JsonElement): JsonElement = when (fn) {
        Fn.Directory -> JsonArray(raw.objects().map { person(it) })
        Fn.Corrections -> JsonArray(raw.objects().map { correction(it) })
        Fn.MyLeave -> leaveSummary(raw.obj())
        Fn.PendingLeaveApprovals -> JsonArray(raw.objects().map { leaveRequest(it) })
        Fn.Tasks -> JsonArray(raw.objects().map { task(it) })
        Fn.TaskDetail -> taskDetail(raw.obj())
        Fn.Projects -> JsonArray(raw.objects().map { project(it) })
        Fn.ProjectDetail -> projectDetail(raw.obj())
        Fn.Announcements -> announcements(raw)
        Fn.Meetings -> JsonArray(raw.objects().map { meeting(it) })
        Fn.CalendarMonth -> calendar(raw.obj())
        Fn.Holidays -> JsonArray(raw.objects().map { o -> obj { put("id", o.idOf()); put("name", o.str("name")); put("date", day(o.str("holiday_date", "date"))) } })
        Fn.Teams -> JsonArray(raw.objects().map { team(it) })
        Fn.PerformanceReviews -> JsonArray(raw.objects().map { review(it) })
        Fn.Documents -> documents(raw.obj())
        Fn.Notifications -> JsonArray(raw.objects())
        Fn.Users, Fn.AuditLogs -> JsonArray(raw.objects())
        Fn.AccessRoles -> raw.obj() ?: JsonObject(emptyMap())
        Fn.Settings -> raw.obj() ?: JsonNull
        else -> raw
    }

    // ---------------- people ----------------
    fun nameOf(id: String?): String? {
        val n = id?.toDoubleOrNull()?.toLong() ?: return null
        people[n]?.str("name")?.let { return it }
        if (me != null && me.raw.str("id") == id) return me.name
        return null
    }
    private fun externalIdOf(id: String?): String? = id?.toDoubleOrNull()?.toLong()?.let { people[it]?.str("userId") }

    companion object {
        /** people.member_projection row (people/directory) → person. */
        fun person(o: JsonObject): JsonObject = obj {
            put("id", o.idOf("user_id", "id"))
            put("userId", o.str("external_user_id", "userId"))
            put("name", o.str("full_name", "name"))
            put("email", o.str("work_email", "email"))
            put("role", o.str("legacy_role", "role"))
            put("status", o.str("status"))
            put("team", o.str("team_name", "team"))
            put("manager", o.str("manager_name", "manager"))
            put("designation", o.str("designation"))
            put("department", o.str("department"))
            put("phone", o.str("phone"))
            put("isTeamLead", o.bool("is_team_lead", "isTeamLead"))
        }

        /**
         * DATE columns cross two JSON hops (service → gateway → app) as ISO instants of local midnight on the service's
         * clock ("2026-10-08T00:00:00.000Z" on a UTC host, "2026-10-07T18:30:00.000Z" on an IST host). Dates.day maps
         * both to 8 Oct in IST; a plain take(10) would put the second on the previous day.
         */
        fun day(s: String?): String? = s?.let { Dates.day(it)?.toString() ?: it }

        internal inline fun obj(b: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
            val m = LinkedHashMap<String, JsonElement>(); m.b(); return JsonObject(m)
        }
        internal fun MutableMap<String, JsonElement>.put(k: String, v: String?) { if (v != null) this[k] = JsonPrimitive(v) }
        internal fun MutableMap<String, JsonElement>.put(k: String, v: JsonPrimitive?) { if (v != null) this[k] = v }
        internal fun MutableMap<String, JsonElement>.put(k: String, v: Double?) { if (v != null) this[k] = JsonPrimitive(v) }
        internal fun MutableMap<String, JsonElement>.put(k: String, v: Boolean) { this[k] = JsonPrimitive(v) }
        internal fun MutableMap<String, JsonElement>.put(k: String, v: JsonElement) { this[k] = v }
    }

    // ---------------- attendance ----------------
    /** time_management.attendance_correction_requests row. `reason` is free text, so it's shown as the note. */
    private fun correction(o: JsonObject) = obj {
        put("id", o.idOf())
        put("name", nameOf(o.str("user_id")) ?: "Member")
        put("memberUserId", externalIdOf(o.str("user_id")))
        put("date", day(o.str("requested_date")))
        put("checkIn", o.str("check_in")); put("checkOut", o.str("check_out"))
        put("note", o.str("reason"))
        put("status", o.str("status") ?: "pending")
    }

    // ---------------- leave ----------------
    /** { year, balances: leave_types ⟕ leave_balances, requests } from leave/summary. */
    private fun leaveSummary(o: JsonObject?): JsonObject {
        val balances = o.list("balances").objects().map { b ->
            // A type with no balance row yet still carries its default allowance (balance_days).
            val allocated = b.num("allocated") ?: b.num("balance_days") ?: 0.0
            val used = b.num("used") ?: 0.0
            obj {
                put("typeId", b.idOf("leave_type_id", "id"))
                put("type", b.str("name"))
                put("code", b.str("code"))
                put("allocated", allocated); put("used", used); put("remaining", (allocated - used).coerceAtLeast(0.0))
                (b["paid"] as? JsonPrimitive)?.booleanOrNull?.let { put("paid", it) }
            }
        }
        val requests = o.list("requests").objects().map { leaveRequest(it) }
        return obj { put("balances", JsonArray(balances)); put("requests", JsonArray(requests)) }
    }

    private fun leaveRequest(r: JsonObject) = obj {
        put("id", r.idOf())
        put("name", nameOf(r.str("user_id")) ?: if (r["user_id"] == null) me?.name else "Member")
        put("memberUserId", externalIdOf(r.str("user_id")))
        put("type", r.str("leave_type_name") ?: "Leave")
        put("startDate", day(r.str("start_date"))); put("endDate", day(r.str("end_date")))
        put("days", r.num("days"))
        put("reason", r.str("reason"))
        put("status", r.str("status"))
        put("reviewer", nameOf(r.str("reviewed_by")))
    }

    // ---------------- work ----------------
    /** work.tasks row (+ project_name). */
    private fun task(t: JsonObject) = obj {
        put("id", t.idOf())
        put("title", t.str("title"))
        put("description", t.str("description"))
        put("status", t.str("status") ?: "todo")
        put("priority", t.str("priority") ?: "medium")
        put("dueDate", day(t.str("due_date")))
        put("projectId", t.idOf("project_id"))
        put("projectName", t.str("project_name"))
        put("assigneeId", t.idOf("assignee_id"))
        put("assignee", nameOf(t.str("assignee_id")))
        put("creator", nameOf(t.str("reporter_id")))
    }

    /** { task, comments } from tasks/{id}; null when the task is gone. */
    private fun taskDetail(o: JsonObject?): JsonElement {
        val t = o?.get("task").obj() ?: return JsonNull
        val comments = o.list("comments").objects().map { c ->
            obj { put("id", c.idOf()); put("author", nameOf(c.str("user_id")) ?: "Member"); put("body", c.str("body")); put("at", c.str("created_at")) }
        }
        return JsonObject(task(t) + ("comments" to JsonArray(comments)))
    }

    /** work.projects row with member_count / task_count / tasks_done. */
    private fun project(p: JsonObject) = obj {
        put("id", p.idOf())
        put("name", p.str("name")); put("code", p.str("code"))
        put("description", p.str("description"))
        put("status", p.str("status")); put("priority", p.str("priority"))
        put("startDate", day(p.str("start_date"))); put("endDate", day(p.str("end_date")))
        put("memberCount", p.num("member_count")); put("taskCount", p.num("task_count")); put("tasksDone", p.num("tasks_done"))
    }

    private fun projectDetail(o: JsonObject?): JsonElement {
        val p = o?.get("project").obj() ?: return JsonNull
        val members = o.list("members").objects().map { m ->
            obj { put("id", m.idOf("user_id")); put("name", nameOf(m.str("user_id")) ?: "Member"); put("role", m.str("role")) }
        }
        return JsonObject(project(p) + mapOf("members" to JsonArray(members), "tasks" to JsonArray(o.list("tasks").objects().map { task(it) })))
    }

    // ---------------- comms ----------------
    /** { published: announcements.* + read_by_me, drafts } */
    private fun announcements(raw: JsonElement): JsonObject {
        val published = raw.obj().list("published").objects().map { a ->
            obj {
                put("id", a.idOf()); put("title", a.str("title")); put("body", a.str("body"))
                put("author", nameOf(a.str("created_by")))
                put("publishedAt", a.str("published_at"))
                put("readByMe", a.bool("read_by_me", "readByMe"))
            }
        }
        return obj { put("published", JsonArray(published)) }
    }

    /** communications.meetings row (+ participant_count, is_participant). */
    private fun meeting(m: JsonObject) = obj {
        put("id", m.idOf()); put("title", m.str("title")); put("description", m.str("description"))
        put("startAt", m.str("start_at")); put("endAt", m.str("end_at"))
        put("location", m.str("location")); put("link", m.str("meeting_link", "link"))
        put("organizer", nameOf(m.str("created_by")))
    }

    /** calendar is already camelCase; leave rows only carry userId and project deadlines use `deadline`. */
    private fun calendar(o: JsonObject?): JsonObject {
        if (o == null) return JsonObject(emptyMap())
        val leave = o.list("leave").objects().map { l ->
            JsonObject(l + ("name" to JsonPrimitive(nameOf(l.str("userId")) ?: "Someone")))
        }
        val deadlines = o.list("projectDeadlines").objects().mapNotNull { p ->
            p.str("deadline")?.let { JsonObject(p + ("endDate" to JsonPrimitive(it))) }
        }
        return JsonObject(o + mapOf("leave" to JsonArray(leave), "projectDeadlines" to JsonArray(deadlines)))
    }

    private fun team(t: JsonObject) = obj {
        put("id", t.idOf()); put("name", t.str("name")); put("description", t.str("description"))
        put("lead", t.str("lead_name")); put("manager", t.str("manager_name")); put("memberCount", t.num("member_count"))
    }

    /** performance.performance_reviews row. */
    private fun review(r: JsonObject) = obj {
        put("id", r.idOf()); put("period", r.str("period")); put("rating", r.num("overall_rating"))
        put("strengths", r.str("strengths")); put("improvements", r.str("improvements")); put("goals", r.str("goals"))
        put("status", r.str("status")); put("reviewer", nameOf(r.str("reviewer_id"))); put("at", r.str("submitted_at", "created_at"))
    }

    /** documents: { documents: DocumentBffRow[], hasMore, folders, ... } */
    private fun documents(o: JsonObject?): JsonObject {
        val docs = o.list("documents").objects().map { d ->
            obj {
                put("id", d.idOf()); put("title", d.str("title")); put("filename", d.str("filename"))
                put("state", d.str("state")); put("version", d.num("current_version")); put("size", d.num("byte_size"))
                put("owner", nameOf(d.str("owner_id"))); put("createdAt", d.str("created_at"))
            }
        }
        return obj { put("documents", JsonArray(docs)); put("hasMore", o.bool("hasMore")) }
    }
}
