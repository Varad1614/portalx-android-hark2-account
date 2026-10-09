# PortalX Android ↔ Mobile Gateway v1

Base: `https://portal.pravahax.com/api/v1` · `Authorization: Bearer <workspace>.<token>` · `X-Device-Id: <uuid>` on every request (random UUID generated once per install, kept in SecureStore, survives sign-out) · envelope `{success,data,meta}` / `{success:false,error:{code,message}}`.

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
| AuthRefresh | POST | `auth/refresh` | signed in (returns login shape; old token revoked) |
| AttendanceToday | GET | `attendance/today` | attendance.read |
| AttendanceHistory | GET | `attendance/history` | attendance.read |
| CheckIn | POST (JSON) | `attendance/check-in` | attendance.read |
| CheckOut | POST (JSON) | `attendance/check-out` | attendance.read |
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
| DeviceToken | POST | `notifications/device-token` | signed in |
| DeviceTokenRemove | POST | `notifications/device-token/remove` | signed in |
| PerformanceReviews | GET | `performance/reviews` | performance.read |
| GiveFeedback | POST | `performance/feedback` | performance.read |
| Documents | GET | `documents` | documents.read |
| Users | GET | `users` | people.read + organization.members.read |
| AccessRoles | GET | `access-control/roles` | organization.roles.manage |
| AuditLogs | GET | `audit/logs` | audit.read |
| Settings | GET | `settings` | organization.settings.manage |

## v0.6 contract details
- **Check-in / check-out** (JSON, since v0.9.3; the live gateway rejected v0.6 multipart with "Invalid JSON request payload"): `selfie` as a JPEG data URL (`data:image/jpeg;base64,...`, like the web; required for check-in, optional for check-out), optional numbers `latitude`, `longitude`, `accuracyMeters`, and `deviceId`. On 400/422 the app retries once with `selfie` alone. Response is the usual envelope plus `meta.locationRecorded` (boolean); the app shows "Location recorded" / "Checked in without location" from it (absent = false). Sent on the no-auto-retry write client; never replayed on redirect.
  - Selfie: ≤ 1280 px long edge, EXIF orientation applied to the pixels, JPEG q80, all EXIF/XMP/COM segments stripped. Temp files are deleted after the upload finishes.
  - Location: best-effort only, never blocks check-in. Platform `LocationManager` (no Play Services): `getCurrentLocation` on API 30+, recent (≤ 10 min) last-known fix on 26–29 or as fallback; 5 s budget. No geofencing.
- **auth/refresh** (POST, bearer): `{token, compositeToken, expiresInSeconds, workspace, user}`. Login stores `now + expiresInSeconds` in SecureStore. On app start and every resume, if < 48 h is left (or the expiry is unknown, i.e. a pre-v0.6 session), the app refreshes once (single-flight Mutex; skipped while a write is in flight; requests that start during the swap wait and go out with the new token). 401 → clean sign-out; any other failure keeps the current session.
- **notifications/device-token** `{token, platform:"android", deviceId}` — plumbing only; nothing registers a token until FCM lands (v0.8).
- **notifications/device-token/remove** `{deviceId}` — sent on sign-out, then `auth/logout` (both carry `X-Device-Id`).

## v0.7 offline outbox
- Queueable writes: `leave/apply`, `PATCH tasks/{taskId}` (status), `tasks/{taskId}/comments`. Bodies are typed (`data/Requests.kt`).
- Sent at once when possible. Queued **only** when the request definitely never left the device (DNS, connect or TLS failure). A timeout after sending is never queued (it may have landed).
- Each queued write gets one `Idempotency-Key` (UUID) at creation; every attempt, including a manual "Send again", reuses it. **Gateway ask:** honour `Idempotency-Key` on these three routes (today it is only known to be honoured for documents).
- WorkManager sends the queue when a network is available (exponential backoff from 30 s). 5xx/429 retried up to 5 times; other 4xx mark the item failed; a timeout marks it "unconfirmed" and it is never resent automatically. The banner under the top bar offers Send again / Discard.
- Check-in/out and breaks stay online-only: the server stamps them with its own clock.
- Sign-out (or a 401) clears the outbox with the rest of the session's data.

## Gateway fixes required (Portalx patch)
- `POST /performance/feedback`: gateway sent giverId/revieweeId/content/score; performance-api wants fromUserId/toUserId/body/rating → always 400.
- `POST /tasks`, `POST /projects`: gateway sent creatorId; work-api requires actorUserId (+status, memberIds) → always 400.
- `GET /calendar`: leave rows had no leave_type_name (always "Leave").

## Known server behaviour
- Server "today" is IST-correct as of the v0.6 gateway (previously the server's local clock, which put 00:00–05:30 IST check-ins on the previous day).
- No dashboard stats route exists; Home's overview is composed from directory, leave/pending, corrections/pending, projects and calendar.
