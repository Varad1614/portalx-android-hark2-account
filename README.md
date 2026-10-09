# PortalX Android (v0.8.0)

Fully native Android client for PortalX, talking only to the PortalX Mobile Gateway v1 REST API (bearer token). No WebView, no browser hand-offs. Kotlin + Jetpack Compose (Material 3), minSdk 26, targetSdk 35.

- Build: `./build.sh` → `PortalX.apk`
- Signing: requires `keystore.properties` + `portalx-release.jks` in the repo root. These are deliberately **not** committed. Keep the same key for every release, otherwise updates won't install.
- API notes: see `API_MAP.md`
- Architecture (v0.7): Hilt for DI (`di/AppModule`); Room as the single source of truth for cached reads and the offline outbox (`data/db`), every value AES-GCM sealed with a Keystore key (memory-only when no Keystore); one `ResourceViewModel` per server resource and an `AppViewModel` for auth, so screens survive rotation; WorkManager sends the outbox (`sync/OutboxWorker`).
- Modules (v0.7.1):

  | Module | Holds |
  |---|---|
  | `:core:net` | `PortalApi` (routes in `Fn`), connectivity, `SecureStore`, `Sealer` |
  | `:core:data` | `Repo`, `Shapes` (gateway JSON → one clean shape), typed models (`data/model`), `Endpoint` (route ↔ model), typed request bodies, Room, outbox worker, location |
  | `:core:ui` | Theme, shared components and controls, `ResourceViewModel`, `AppViewModel`, fonts and logos |
  | `:feature:auth` | Sign-in |
  | `:feature:today` | Home and attendance (selfie capture) |
  | `:feature:work` | Tasks, leave and the approvals inbox |
  | `:feature:workspace` | More, people, calendar, announcements, meetings, admin screens |
  | `:app` | Activity shell, navigation, Hilt wiring, push (`push/`), home-screen widget (`widget/`), integration tests |

  Features depend only on `:core:ui` (never on each other). Screens read typed models: `rememberResource(Endpoint.Tasks)` gives `List<Task>`, so a renamed field is a compile error rather than a blank label.
- v0.8:
  - **Push alerts (FCM).** Drop your Firebase project's `google-services.json` into `app/` (git-ignored) and rebuild; without it push is simply off. After sign-in the app registers its token with `POST notifications/device-token` and asks for the Android 13+ notification permission. The gateway should send **data** messages: `type` (`leave_request`, `correction_request`, `approval_*`, `task_*`, `leave_*`, `attendance_*`, `announcement_*`, `meeting_*`), `title`, `body`, optional `id` and `route`. Tapping opens the matching screen (routes are allowlisted). Lock-screen content stays hidden.
  - **Approvals inbox.** Pending leave and attendance corrections in one list, oldest first, with Approve/Reject. Reached from Home and More.
  - **Home-screen widget.** "PortalX Today": attendance status, open tasks and approvals waiting. Reads only the on-device cache, never the network; shows nothing personal when signed out.
- Tests: `./gradlew :app:testDebugUnitTest` (Robolectric; Room tests use the bundled JVM SQLite driver so they run on aarch64 hosts too).
- Reports: `PortalX-Audit-v0.2.0.pdf`, `PortalX-Value-Report-v0.3.0.pdf`

## v0.9.0 — polish

- **Liveness check-in selfie.** On devices with a front camera and Google Play services, check-in/out opens an in-app camera (CameraX + ML Kit face detection, model delivered by Play services) that asks for two random challenges (blink, head turn, smile), one face only, then captures the selfie. Otherwise it falls back to the system camera. The punch sends `liveness=passed|unavailable` and `livenessChallenges` (e.g. `blink,turn`); the gateway decides the policy. Camera permission is now required for selfies.
- **Insights.** Attendance → History → Insights (also More → My insights): days present, streak, average check-in and day length, on-time rate, a 14-day hours chart and leave used. Computed on-device.
- **Search.** Search icon on Home: one offline search across tasks, people, projects, meetings, announcements and documents the user can see.
- **Certificate pinning** for `portal.pravahax.com` in `network_security_config.xml`: CA SPKI pins (ISRG Root YE/X2/X1, backups GTS Root R1/R4), expiring 2027-10-01 as a failsafe. Refresh pins and the date in a release before then, or if the host changes CA.
- **Baseline Profile** (`app/src/main/baseline-prof.txt`) + ProfileInstaller for faster cold start, including sideloaded installs.
