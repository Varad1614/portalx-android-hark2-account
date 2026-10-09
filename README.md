# PortalX Android (v0.7.0)

Fully native Android client for PortalX, talking only to the PortalX Mobile Gateway v1 REST API (bearer token). No WebView, no browser hand-offs. Kotlin + Jetpack Compose (Material 3), minSdk 26, targetSdk 35.

- Build: `./build.sh` → `PortalX.apk`
- Signing: requires `keystore.properties` + `portalx-release.jks` in the repo root. These are deliberately **not** committed. Keep the same key for every release, otherwise updates won't install.
- API notes: see `API_MAP.md`
- Architecture (v0.7): Hilt for DI (`di/AppModule`); Room as the single source of truth for cached reads and the offline outbox (`data/db`), every value AES-GCM sealed with a Keystore key (memory-only when no Keystore); one `ResourceViewModel` per server resource and an `AppViewModel` for auth, so screens survive rotation; WorkManager sends the outbox (`sync/OutboxWorker`).
- Tests: `./gradlew :app:testDebugUnitTest` (Robolectric; Room tests use the bundled JVM SQLite driver so they run on aarch64 hosts too).
- Reports: `PortalX-Audit-v0.2.0.pdf`, `PortalX-Value-Report-v0.3.0.pdf`
