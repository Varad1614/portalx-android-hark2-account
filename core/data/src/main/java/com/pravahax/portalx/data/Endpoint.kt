package com.pravahax.portalx.data

import com.pravahax.portalx.data.model.*
import com.pravahax.portalx.net.Fn
import kotlinx.serialization.json.JsonElement

/**
 * v0.7.1: a read route bound to the model it decodes to. Screens ask for an [Endpoint], never a bare [Fn], so the
 * compiler checks that the Tasks screen gets `List<Task>` and the leave screen gets a [LeaveSummary].
 * [decode] runs on the cleaned JSON [Shapes] produced (and Room cached), so it is cheap and never throws.
 */
class Endpoint<out T>(val fn: Fn, val decode: (JsonElement) -> T) {
    companion object {
        val AttendanceToday = Endpoint(Fn.AttendanceToday) { com.pravahax.portalx.data.model.AttendanceToday.from(it) }
        val AttendanceHistory = Endpoint(Fn.AttendanceHistory, AttendanceRecord::list)
        val Corrections = Endpoint(Fn.Corrections, Correction::list)
        val MyLeave = Endpoint(Fn.MyLeave, LeaveSummary::from)
        val PendingLeaveApprovals = Endpoint(Fn.PendingLeaveApprovals, LeaveRequest::list)
        val Tasks = Endpoint(Fn.Tasks, Task::list)
        val TaskDetail = Endpoint(Fn.TaskDetail) { com.pravahax.portalx.data.model.TaskDetail.from(it) }
        val Projects = Endpoint(Fn.Projects, Project::list)
        val ProjectDetail = Endpoint(Fn.ProjectDetail) { com.pravahax.portalx.data.model.ProjectDetail.from(it) }
        val Directory = Endpoint(Fn.Directory, Person::list)
        val Teams = Endpoint(Fn.Teams, Team::list)
        val Announcements = Endpoint(Fn.Announcements, Announcement::published)
        val Meetings = Endpoint(Fn.Meetings, Meeting::list)
        val CalendarMonth = Endpoint(Fn.CalendarMonth) { com.pravahax.portalx.data.model.CalendarMonth.from(it) }
        val Notifications = Endpoint(Fn.Notifications, Notification::list)
        val PerformanceReviews = Endpoint(Fn.PerformanceReviews, Review::list)
        val Documents = Endpoint(Fn.Documents, Document::list)
        val Users = Endpoint(Fn.Users, UserAccount::list)
        val AccessRoles = Endpoint(Fn.AccessRoles, AccessRole::list)
        val AuditLogs = Endpoint(Fn.AuditLogs, AuditEntry::list)
        val Settings = Endpoint(Fn.Settings) { OrgSettings.from(it) }
    }
}
