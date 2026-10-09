package com.pravahax.portalx

import com.pravahax.portalx.net.Fn
import java.time.LocalDate

/**
 * Responses exactly as the PortalX Mobile Gateway v1 sends them: `data` payloads copied from router.ts and the
 * column lists of each service's SQL (snake_case Postgres rows, timestamps as ISO instants, DATE columns as
 * UTC-midnight instants, numeric columns as strings like Postgres `numeric`). No web/serverFn shapes.
 */
object GatewayFixtures {
    /** permissions of an Organization Owner as returned by access-control/my-access. */
    val ownerPerms = listOf(
        "people.read", "people.manage", "teams.read", "attendance.read", "attendance.manage", "leave.read", "leave.approve",
        "tasks.read", "tasks.manage", "projects.read", "projects.manage", "comms.read", "comms.manage", "performance.read",
        "documents.read", "audit.read", "organization.members.read", "organization.roles.manage", "organization.settings.manage",
    )

    private fun ts(d: LocalDate, hhmm: String): String {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        // IST wall time → UTC instant, the way node-pg/JSON.stringify emits timestamptz.
        return java.time.ZonedDateTime.of(d.year, d.monthValue, d.dayOfMonth, h, m, 0, 0, java.time.ZoneId.of("Asia/Kolkata"))
            .toInstant().toString().replace("Z", ".000Z")
    }
    private fun date(d: LocalDate) = "${d}T00:00:00.000Z"

    fun real(fn: Fn?, today: LocalDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")), perms: List<String> = ownerPerms): String {
        val d = { n: Long -> today.plusDays(n) }
        return when (fn) {
            Fn.Login -> """{"token":"${"a".repeat(64)}","compositeToken":"pravahax.${"a".repeat(64)}","expiresInSeconds":604800,"workspace":"pravahax",
                "user":{"id":2,"userId":"PSE-00002","fullName":"varad","workEmail":"varad@pravahax.com","role":"super_admin","mustChangePassword":false,"teamId":null,"isTeamLead":false,"status":"active"}}"""
            Fn.CurrentUser -> """{"user":{"id":2,"userId":"PSE-00002","fullName":"varad","workEmail":"varad@pravahax.com","role":"super_admin","mustChangePassword":false,"teamId":null,"isTeamLead":false,"status":"active"},"workspace":"pravahax","organizationId":1}"""
            Fn.MyAccess -> """{"organizationId":1,"permissions":[${perms.joinToString(",") { "\"$it\"" }}]}"""
            Fn.AttendanceToday -> """{"id":41,"organization_id":"1","user_id":"2","attendance_date":"${date(today)}","check_in":"${ts(today, "11:15")}","check_out":null,
                "status":"present","selfie_url":"data:image/jpeg;base64,AAA","source":"web","is_on_break":false,"last_break_start":null,"total_break_seconds":0,"note":null,
                "created_at":"${ts(today, "11:15")}","updated_at":"${ts(today, "11:15")}"}"""
            Fn.AttendanceHistory -> (0L..4L).joinToString(",", "[", "]") { i ->
                """{"id":${40 - i},"user_id":"2","attendance_date":"${date(d(-i))}","check_in":"${ts(d(-i), "09:30")}","check_out":${if (i == 0L) "null" else "\"${ts(d(-i), "18:15")}\""},"status":"present","is_on_break":false,"total_break_seconds":${1800 + i * 60}}"""
            }
            Fn.Corrections -> """[{"id":"11","user_id":"7","attendance_id":"38","requested_date":"${date(d(-2))}","check_in":"${ts(d(-2), "09:00")}","check_out":null,
                "reason":"Forgot check out: left for a client visit","status":"pending","reviewed_by":null,"reviewed_at":null,"created_at":"${ts(d(-1), "10:00")}","updated_at":"${ts(d(-1), "10:00")}","organization_id":"1"}]"""
            Fn.MyLeave -> """{"year":${today.year},"balances":[
                {"leave_type_id":1,"name":"Casual Leave","code":"CL","paid":true,"balance_days":"12","allocated":"12.00","used":"5.50"},
                {"leave_type_id":2,"name":"Sick Leave","code":"SL","paid":true,"balance_days":"8","allocated":null,"used":null},
                {"leave_type_id":3,"name":"Unpaid","code":"UL","paid":false,"balance_days":"0","allocated":"0","used":"1"}],
                "requests":[{"id":"5","leave_type_id":"1","leave_type_name":"Casual Leave","start_date":"${date(d(9))}","end_date":"${date(d(10))}","days":"2.0","reason":"Family function","status":"pending","reviewed_by":null,"reviewed_at":null,"created_at":"${ts(today, "09:00")}"},
                {"id":"4","leave_type_id":"2","leave_type_name":"Sick Leave","start_date":"${date(d(-20))}","end_date":"${date(d(-20))}","days":"1.0","reason":"Fever","status":"approved","reviewed_by":"7","reviewed_at":"${ts(d(-21), "18:00")}","created_at":"${ts(d(-21), "08:00")}"}]}"""
            Fn.PendingLeaveApprovals -> """[{"id":"7","user_id":"9","leave_type_id":"2","leave_type_name":"Sick Leave","start_date":"${date(today)}","end_date":"${date(d(1))}","days":"2.0","reason":"Fever, doctor advised rest","status":"pending","reviewed_by":null,"reviewed_at":null,"created_at":"${ts(today, "08:10")}","updated_at":"${ts(today, "08:10")}","organization_id":"1"}]"""
            Fn.Holidays -> """[{"id":"1","name":"Dussehra","holiday_date":"${date(d(5))}","is_recurring":false,"organization_id":"1"}]"""
            Fn.Tasks -> """[{"id":"1","organization_id":"1","project_id":"3","title":"L6","description":null,"status":"todo","priority":"medium","assignee_id":"2","reporter_id":"2","due_date":null,"created_at":"${ts(d(-3), "10:00")}","project_name":"PortalX"},
                {"id":"2","organization_id":"1","project_id":null,"title":"Finalise Q3 payroll inputs","description":"Collect attendance and leave","status":"in_progress","priority":"high","assignee_id":"2","reporter_id":"7","due_date":"${date(d(-1))}","project_name":null},
                {"id":"3","organization_id":"1","project_id":"3","title":"Release notes","status":"done","priority":"low","assignee_id":"2","reporter_id":"2","due_date":"${date(d(2))}","project_name":"PortalX"}]"""
            Fn.TaskDetail -> """{"task":{"id":"1","title":"L6","description":"Ship level six","status":"todo","priority":"medium","assignee_id":"2","reporter_id":"7","due_date":"${date(d(3))}","project_name":"PortalX"},
                "comments":[{"id":"1","task_id":"1","user_id":"7","body":"Please pick this up","created_at":"${ts(today, "10:00")}"}]}"""
            Fn.Projects -> """[{"id":"3","organization_id":"1","name":"PortalX","code":"PX","description":"Employee portal","status":"active","priority":"high","start_date":"${date(d(-60))}","end_date":"${date(d(30))}","member_count":"4","task_count":"10","tasks_done":"6"},
                {"id":"4","name":"Old site","code":null,"status":"completed","priority":"low","member_count":"1","task_count":"0","tasks_done":"0"}]"""
            Fn.ProjectDetail -> """{"project":{"id":"3","name":"PortalX","code":"PX","status":"active","priority":"high","start_date":"${date(d(-60))}","end_date":"${date(d(30))}"},
                "members":[{"id":"1","project_id":"3","user_id":"7","role":"lead","created_at":"${ts(d(-60), "10:00")}"}],"tasks":[{"id":"1","title":"L6","status":"todo","priority":"medium","assignee_id":"2"}]}"""
            Fn.Directory -> """[{"user_id":"2","external_user_id":"PSE-00002","full_name":"varad","work_email":"varad@pravahax.com","legacy_role":"super_admin","status":"active","organization_id":"1","team_id":null,"manager_id":null,"mentor_id":null,"is_team_lead":false,"department":null,"designation":"Founder","avatar":null,"city":"Nashik","state":"MH","team_name":null,"manager_name":null},
                {"user_id":"7","external_user_id":"PSE-00007","full_name":"Asha Rao","work_email":"asha@pravahax.com","legacy_role":"member","status":"active","team_id":"1","is_team_lead":true,"designation":"HR Manager","team_name":"People","manager_name":"varad"},
                {"user_id":"9","external_user_id":"PSE-00009","full_name":"Rohan Mehta","work_email":"rohan@pravahax.com","legacy_role":"member","status":"active","team_id":"2","is_team_lead":false,"designation":"Android Engineer","team_name":"Engineering","manager_name":"Asha Rao"}]"""
            Fn.MyProfile -> """{"id":"1","user_id":"2","phone":"+91 98765 43210","designation":"Founder","external_user_id":"PSE-00002","full_name":"varad","work_email":"varad@pravahax.com","team_name":null}"""
            Fn.Teams -> """[{"id":"1","name":"People","description":"HR & admin","lead_id":"7","manager_id":"2","organization_id":"1","lead_name":"Asha Rao","manager_name":"varad","member_count":2}]"""
            Fn.Announcements -> """{"published":[{"id":"1","title":"Office closed for Dussehra","body":"The office will remain closed on Tuesday.","created_by":"7","target_role":null,"published_at":"${ts(d(-1), "10:30")}","target_team_id":null,"organization_id":"1","read_by_me":false},
                {"id":"2","title":"New leave policy","body":"Casual leave now accrues monthly.","created_by":"2","published_at":"${ts(d(-6), "16:00")}","read_by_me":true}],"drafts":[]}"""
            Fn.Meetings -> """[{"id":"1","title":"Weekly leadership sync","description":"Agenda in doc","start_at":"${ts(d(1), "10:00")}","end_at":"${ts(d(1), "10:45")}","location":"Board room","meeting_link":"https://meet.google.com/abc-defg-hij","created_by":"2","organization_id":"1","participant_count":3,"is_participant":true},
                {"id":"2","title":"Old","start_at":"${ts(d(-30), "10:00")}","end_at":null,"meeting_link":"javascript:alert(1)","created_by":"7","participant_count":1,"is_participant":true}]"""
            Fn.CalendarMonth -> """{"year":${today.year},"month":${today.monthValue},"holidays":[{"id":1,"name":"Dussehra","date":"${d(5)}"}],
                "leave":[{"id":7,"userId":9,"startDate":"$today","endDate":"${d(1)}","status":"approved","leaveType":"Sick Leave"},{"id":5,"userId":2,"startDate":"${d(9)}","endDate":"${d(10)}","status":"pending","leaveType":"Casual Leave"}],
                "tasks":[{"id":2,"title":"Finalise Q3 payroll inputs","dueDate":"${d(-1)}","priority":"high","status":"in_progress"}],
                "projectDeadlines":[{"id":3,"name":"PortalX","deadline":"${d(30)}"},{"id":4,"name":"No date","deadline":null}],
                "meetings":[{"id":1,"title":"Weekly leadership sync","startAt":"${ts(d(1), "10:00")}","date":"${d(1)}"}]}"""
            Fn.Notifications -> """[{"id":1,"title":"Leave approved","body":"Your sick leave was approved.","type":"leave","read":false,"url":null,"createdAt":"${ts(today, "09:00")}"},{"id":2,"title":"Welcome","body":"","type":"system","read":true,"url":null,"createdAt":"${ts(d(-9), "09:00")}"}]"""
            Fn.PerformanceReviews -> """[{"id":"1","reviewee_id":"2","reviewer_id":"7","period":"2026 H1","overall_rating":"4.5","strengths":"Ownership","improvements":"Delegation","goals":"Ship v1","status":"submitted","submitted_at":"${ts(d(-40), "12:00")}","created_at":"${ts(d(-41), "12:00")}"}]"""
            Fn.Documents -> """{"documents":[{"id":"8d3c3f8e-1f2a-4b5c-9d6e-7f8a9b0c1d2e","title":"Leave policy","folder_id":null,"owner_id":7,"recipient_id":null,"reviewer_id":null,"requires_approval":false,"current_version":2,"trashed_at":null,"filename":"leave-policy.pdf","source":"upload","state":"published","byte_size":248331,"created_at":"${ts(d(-10), "11:00")}","created_by":7,"processing_status":null}],"hasMore":false,"folders":[],"templates":[],"members":[],"permissions":["documents.read"]}"""
            Fn.Users -> """[{"id":2,"userId":"PSE-00002","name":"varad","email":"varad@pravahax.com","role":"super_admin","organizationRoles":["Organization Owner"],"status":"active","mustChangePassword":false,"isTeamLead":false,"team":null,"manager":null,"createdAt":"${ts(d(-90), "10:00")}"},
                {"id":9,"userId":"PSE-00009","name":"Rohan Mehta","email":"rohan@pravahax.com","role":"member","organizationRoles":["Employee"],"status":"active","mustChangePassword":true,"isTeamLead":false,"team":"Engineering","manager":"Asha Rao","createdAt":"${ts(d(-5), "10:00")}"}]"""
            Fn.AccessRoles -> """{"permissions":[{"code":"people.read"}],"members":[{"userId":2,"roleNames":["Organization Owner"]}],"roles":[{"id":1,"name":"Organization Owner","description":"Full access","system":true,"permissionCodes":["people.read","people.manage"],"assignedUserIds":[2]},{"id":2,"name":"Employee","description":null,"system":false,"permissionCodes":["tasks.read"],"assignedUserIds":[7,9]}]}"""
            Fn.AuditLogs -> """[{"id":1,"action":"attendance.checkin.mobile","actor":"varad","actorUid":"PSE-00002","entityType":"attendance","entityId":"41","details":null,"ip":"mobile-api","createdAt":"${ts(today, "11:15")}"}]"""
            Fn.Settings -> """{"id":1,"name":"PravahaX","legalName":"PravahaX Pvt Ltd","workspaceSlug":"pravahax","timezone":"Asia/Kolkata","primaryColor":"#4f46e5","secondaryColor":"#06b6d4","logoUrl":""}"""
            Fn.AuthRefresh -> """{"token":"${"b".repeat(64)}","compositeToken":"pravahax.${"b".repeat(64)}","expiresInSeconds":604800,"workspace":"pravahax",
                "user":{"id":2,"userId":"PSE-00002","fullName":"varad","workEmail":"varad@pravahax.com","role":"super_admin","mustChangePassword":false,"teamId":null,"isTeamLead":false,"status":"active"}}"""
            Fn.DeviceToken, Fn.DeviceTokenRemove -> """{"ok":true}"""
            Fn.Logout, Fn.ChangePassword, Fn.RequestPasswordReset -> """{"message":"ok"}"""
            Fn.CheckIn, Fn.CheckOut, Fn.StartBreak, Fn.EndBreak, Fn.RequestCorrection -> """{"id":41}"""
            Fn.DecideCorrection, Fn.DecideLeave -> """{"ok":true}"""
            Fn.ApplyLeave -> """{"ok":true,"id":6,"days":1}"""
            Fn.CreateTask -> """{"ok":true,"taskId":9}"""
            Fn.UpdateTaskStatus -> """{"ok":true,"task":{"id":"1","status":"done"}}"""
            Fn.AddTaskComment -> """{"ok":true,"commentId":2}"""
            Fn.MarkAnnouncementRead, Fn.MarkNotificationRead, Fn.MarkAllNotificationsRead -> """{"ok":true}"""
            Fn.GiveFeedback -> """{"ok":true,"feedback":{"id":"1"}}"""
            Fn.CreateMeeting -> """{"id":3}"""
            null -> "null"
        }
    }
}
