# PortalX Android ↔ Mobile Gateway v1

Base: `https://portal.pravahax.com/api/v1` · `Authorization: Bearer <workspace>.<token>` · envelope `{success,data,meta}` / `{success:false,error:{code,message}}`.

Every route below is verified against Portalx `src/bff/mobile/router.ts`; `GatewayContractTest` fails the build if one drifts (route table in `app/src/test/resources/gateway-routes.txt`).

Raw service rows (snake_case) are mapped to screen shapes in `data/Shapes.kt`. Permissions come from `access-control/my-access`; screens and calls are gated on the code each service's `authorize()` checks.

| Fn | Method | Path | Server permission |
|---|---|---|---|
| CurrentUser | GET | `auth/me` | signed in |
| MyAccess | GET | `access-control/my-access` | signed in |
| Login | POST | `auth/login` | public |
| Logout | POST | `auth/logout` | signed in |
| ChangePassword | POST | `auth/change-password` | signed in |
| RequestPasswordReset | POST | `auth/forgot-password` | public |
| AttendanceToday | GET | `attendance/today` | attendance.read |
| AttendanceHistory | GET | `attendance/history` | attendance.read |
| CheckIn | POST | `attendance/check-in` | attendance.read |
| CheckOut | POST | `attendance/check-out` | attendance.read |
| StartBreak | POST | `attendance/break/start` | attendance.read |
| EndBreak | POST | `attendance/break/end` | attendance.read |
| RequestCorrection | POST | `attendance/corrections/request` | attendance.read |
| Corrections | GET | `attendance/corrections/pending` | attendance.manage |
| DecideCorrection | POST | `attendance/corrections/review` | attendance.manage |
| MyLeave | GET | `leave/summary` | leave.read |
| PendingLeaveApprovals | GET | `leave/pending` | leave.approve |
| ApplyLeave | POST | `leave/apply` | leave.read |
| DecideLeave | POST | `leave/review` | leave.approve |
| Holidays | GET | `holidays` | comms.read |
| Tasks | GET | `tasks` | tasks.read |
| TaskDetail | GET | `tasks/{taskId}` | tasks.read |
| CreateTask | POST | `tasks` | tasks.manage |
| UpdateTaskStatus | PATCH | `tasks/{taskId}` | tasks.manage |
| AddTaskComment | POST | `tasks/{taskId}/comments` | tasks.manage |
| Projects | GET | `projects` | projects.read |
| ProjectDetail | GET | `projects/{projectId}` | projects.read |
| Directory | GET | `people/directory` | people.read |
| MyProfile | GET | `people/me/profile` | signed in |
| Teams | GET | `teams` | teams.read |
| Announcements | GET | `announcements` | comms.read |
| MarkAnnouncementRead | POST | `announcements/{id}/read` | signed in |
| Meetings | GET | `meetings` | comms.read |
| CreateMeeting | POST | `meetings` | comms.manage |
| CalendarMonth | GET | `calendar` | comms.read |
| Notifications | GET | `notifications` | signed in |
| MarkNotificationRead | POST | `notifications/{id}/read` | signed in |
| MarkAllNotificationsRead | POST | `notifications/read-all` | signed in |
| PerformanceReviews | GET | `performance/reviews` | performance.read |
| GiveFeedback | POST | `performance/feedback` | performance.read |
| Documents | GET | `documents` | documents.read |
| Users | GET | `users` | people.read + organization.members.read |
| AccessRoles | GET | `access-control/roles` | organization.roles.manage |
| AuditLogs | GET | `audit/logs` | audit.read |
| Settings | GET | `settings` | organization.settings.manage |

## Gateway fixes required (Portalx patch)
- `POST /performance/feedback`: gateway sent giverId/revieweeId/content/score; performance-api wants fromUserId/toUserId/body/rating → always 400.
- `POST /tasks`, `POST /projects`: gateway sent creatorId; work-api requires actorUserId (+status, memberIds) → always 400.
- `GET /calendar`: leave rows had no leave_type_name (always "Leave").

## Known server behaviour
- `today` is the server's clock (`toLocaleDateString` with no TZ). Set `TZ=Asia/Kolkata` on the web service or check-ins between 00:00 and 05:30 IST land on the previous day (web has the same issue).
- No dashboard stats route exists; Home's overview is composed from directory, leave/pending, corrections/pending, projects and calendar.
