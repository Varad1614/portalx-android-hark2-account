package com.pravahax.portalx.data

import android.content.Context
import com.pravahax.portalx.location.Fix
import com.pravahax.portalx.net.FilePart
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.net.RefreshResult
import com.pravahax.portalx.net.PortalException
import com.pravahax.portalx.data.db.CacheEntry
import com.pravahax.portalx.data.db.OutboxItem
import com.pravahax.portalx.data.db.PortalDb
import com.pravahax.portalx.data.db.CacheDao
import com.pravahax.portalx.data.db.OutboxDao
import com.pravahax.portalx.data.db.MemoryCacheDao
import com.pravahax.portalx.data.db.MemoryOutboxDao
import com.pravahax.portalx.security.KeystoreSealer
import com.pravahax.portalx.security.Sealer
import com.pravahax.portalx.security.SecureStore
import com.pravahax.portalx.sync.OutboxWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.io.File

// ---------- tolerant JSON helpers: the web sends a mix of camelCase and snake_case ----------
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
fun JsonElement?.objects(): List<JsonObject> = arr().mapNotNull { it as? JsonObject }
@JvmName("listObjects") fun List<JsonElement>.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }
fun JsonObject?.str(vararg keys: String): String? {
    if (this == null) return null
    for (k in keys) {
        val v = this[k]
        if (v is JsonPrimitive && v !is JsonNull && v.content.isNotBlank()) return v.content
        if (v is JsonObject) v.str("name", "label", "title")?.let { return it } // e.g. { assignee: { name } }
    }
    return null
}
fun JsonObject?.num(vararg keys: String): Double? = str(*keys)?.toDoubleOrNull()?.takeIf { it.isFinite() }
fun JsonObject?.int(vararg keys: String): Int = num(*keys)?.toInt() ?: 0
fun JsonObject?.bool(vararg keys: String): Boolean = str(*keys)?.let { it.equals("true", true) || it == "1" } ?: false
fun JsonObject?.list(key: String): List<JsonElement> = this?.get(key).arr()

/** Number formatting without trailing ".0" (1.0 -> "1", 1.5 -> "1.5"). */
fun fmtNum(d: Double?): String = when {
    d == null -> "0"
    d == Math.floor(d) && !d.isInfinite() -> d.toLong().toString()
    else -> "%.1f".format(java.util.Locale.US, d)
}

/**
 * The record id exactly as the server sent it (number or string). v0.1.x coerced ids to Long and fell back
 * to 0, which would send `id: 0` for string/UUID ids. Returns null when there is no usable id.
 */
fun JsonObject?.idOf(vararg keys: String = arrayOf("id")): JsonPrimitive? {
    if (this == null) return null
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        if (v is JsonNull || v.content.isBlank()) continue
        if (v.isString) v.content.toLongOrNull()?.let { return JsonPrimitive(it) } // numeric strings -> numbers, like the web's Number()
        return v
    }
    return null
}

/** Unique, stable LazyColumn keys even when ids are missing or duplicated (duplicate keys crash Compose). */
fun stableKeys(items: List<JsonObject>, prefix: String): List<String> {
    val seen = HashMap<String, Int>()
    return items.mapIndexed { i, o ->
        val base = "$prefix:" + (o.str("id") ?: "idx$i")
        val n = seen.merge(base, 1, Int::plus) ?: 1
        if (n == 1) base else "$base#$n"
    }
}

data class SessionUser(val raw: JsonObject) {
    val id get() = raw.str("id") ?: ""
    val name get() = raw.str("fullName", "name", "full_name") ?: "User"
    val firstName get() = name.trim().split(" ").firstOrNull { it.isNotBlank() } ?: name
    val userId get() = raw.str("userId", "user_id") ?: ""
    val email get() = raw.str("workEmail", "email", "work_email") ?: ""
    val orgRoles get() = (raw["organizationRoles"] ?: raw["organization_roles"]).arr().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    /** Effective permission codes from access-control/my-access; null until the gateway has answered once. */
    val permissions: List<String>? get() = (raw["permissions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val role get() = raw.str("role") ?: "member"
    val roleLabel get() = when (role) {
        "super_admin" -> "Super Admin"; "member" -> "Employee"; "team_lead" -> "Team Lead"
        else -> role.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    val mustChangePassword get() = raw.bool("mustChangePassword", "must_change_password")
    val isAdmin get() = role == "super_admin"
    val isTeamLead get() = raw.bool("isTeamLead", "is_team_lead") || role == "team_lead"
    /**
     * The gateway's services authorise purely on organisation permission codes, so those are the truth.
     * Before my-access has answered (first launch offline), fall back to the legacy role so the UI isn't empty;
     * the server still enforces every call.
     */
    fun can(p: String): Boolean = permissions?.contains(p) ?: (isAdmin || (role == "manager" && !p.startsWith("organization.")))
    val canApproveLeave get() = can("leave.approve")
    val canApproveCorrections get() = can("attendance.manage")
    val canManageTasks get() = can("tasks.manage")
    val canReadPeople get() = can("people.read")
    val seesStats get() = canReadPeople || canApproveLeave || canApproveCorrections || can("projects.read")
}

/** Which cached reads each write makes stale, so screens refresh themselves after an action. */
object Invalidation {
    private val attendance = listOf(Fn.AttendanceToday, Fn.AttendanceHistory)
    fun after(fn: Fn): List<Fn> = when (fn) {
        Fn.CheckIn, Fn.CheckOut, Fn.StartBreak, Fn.EndBreak -> attendance
        Fn.RequestCorrection, Fn.DecideCorrection -> attendance + Fn.Corrections
        Fn.ApplyLeave, Fn.DecideLeave -> listOf(Fn.MyLeave, Fn.PendingLeaveApprovals, Fn.CalendarMonth)
        Fn.CreateTask, Fn.UpdateTaskStatus, Fn.AddTaskComment -> listOf(Fn.Tasks, Fn.TaskDetail, Fn.CalendarMonth)
        Fn.MarkAnnouncementRead -> listOf(Fn.Announcements)
        Fn.CreateMeeting -> listOf(Fn.Meetings, Fn.CalendarMonth)
        Fn.MarkNotificationRead, Fn.MarkAllNotificationsRead -> listOf(Fn.Notifications)
        Fn.GiveFeedback -> listOf(Fn.PerformanceReviews)
        else -> emptyList()
    }
}

/** Writes the outbox may hold while offline (v0.7). */
object Outbox {
    /**
     * Only writes whose meaning doesn't depend on *when* the server receives them. Check-in/out and breaks stay
     * online-only: the server stamps them with its own clock, so sending one an hour late would record the wrong time.
     */
    val QUEUEABLE = setOf(Fn.ApplyLeave, Fn.UpdateTaskStatus, Fn.AddTaskComment)
    /** Server errors (5xx / 429) are retried this many times before the item is shown as failed. */
    const val MAX_ATTEMPTS = 5
    fun label(fn: String): String = when (fn) {
        Fn.ApplyLeave.name -> "Leave request"
        Fn.UpdateTaskStatus.name -> "Task status change"
        Fn.AddTaskComment.name -> "Task comment"
        else -> "Change"
    }
}

/** Implemented by the Application so app-level components (the outbox worker) can reach the one [Repo]. */
interface RepoHost { val repo: Repo }

/** Outcome of [Repo.submit]: sent now, or saved in the outbox to send when the device is back online. */
enum class Submit { Sent, Queued }

/**
 * Repository. v0.7: Room is the single source of truth for cached reads (screens observe [observe]; [load]
 * refreshes it) and holds the offline outbox. Every value in Room is sealed with AES-GCM ([Sealer]); when the
 * Keystore is unavailable the cache and outbox live in memory only, so nothing is ever written in clear.
 */
class Repo(
    context: Context,
    val api: PortalApi = PortalApi(context),
    cacheName: String = "cache",
    private val sealer: Sealer? = KeystoreSealer.createOrNull(),
    db: PortalDb? = if (sealer != null) PortalDb.open(context, cacheName) else null,
    private val scheduleOutbox: () -> Unit = { OutboxWorker.schedule(context) },
) {
    private val cacheDao: CacheDao = db?.cache() ?: MemoryCacheDao()
    private val outboxDao: OutboxDao = db?.outbox() ?: MemoryOutboxDao()
    private val cache = SecureStore(context, cacheName)
    private val prefs = context.getSharedPreferences("portal_prefs", Context.MODE_PRIVATE) // non-sensitive: workspace, last user id

    private val _versions = MutableStateFlow<Map<Fn, Int>>(emptyMap())
    /** Bumped per Fn when data it returns becomes stale. Resources observe this to refetch. */
    val versions: StateFlow<Map<Fn, Int>> = _versions.asStateFlow()

    init {
        // Drop the v0.1.x plaintext response cache (it held directory/profile data in clear).
        context.getSharedPreferences("portal_cache", Context.MODE_PRIVATE).let { legacy ->
            legacy.getString("workspace", null)?.let { prefs.edit().putString("workspace", it).apply() }
            legacy.getString("lastUserId", null)?.let { prefs.edit().putString("lastUserId", it).apply() }
            legacy.getString("me", null)?.let { cache.put("me", it) }
            if (legacy.all.isNotEmpty()) legacy.edit().clear().apply()
        }
        // v0.4.0 cached raw/guessed shapes; the screens now read Shapes output. Drop old responses once (keep "me").
        if (prefs.getInt("cacheSchema", 0) < CACHE_SCHEMA) {
            val me = cache.get("me")
            cache.clear(); me?.let { cache.put("me", it) }
            prefs.edit().putInt("cacheSchema", CACHE_SCHEMA).apply()
        }
    }

    companion object { const val CACHE_SCHEMA = 3 } // 3: responses moved from SecureStore to Room

    fun invalidate(fns: Collection<Fn>) {
        if (fns.isEmpty()) return
        _versions.update { m -> m + fns.associateWith { (m[it] ?: 0) + 1 } }
    }

    fun cached(key: String): JsonElement? = cache.get(key)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    private fun seal(s: String) = sealer?.seal(s) ?: s // no sealer => in-memory database, never on disk
    private fun unseal(s: String): String? = runCatching { sealer?.open(s) ?: s }.getOrNull()
    private fun parse(sealed: String): JsonElement? = unseal(sealed)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    /** The cached response for [key] as it changes (Room is the source of truth; [load] writes to it). */
    fun observe(key: String): Flow<JsonElement?> = cacheDao.observe(key).map { e -> e?.let { parse(it.value) } }.distinctUntilChanged()

    suspend fun cachedResponse(key: String): JsonElement? = cacheDao.get(key)?.let { parse(it.value) }

    /** Directory index (user id → person) used to put names on rows that only carry ids. */
    @Volatile private var people: Map<Long, JsonObject> = emptyMap()
    private val peopleLock = kotlinx.coroutines.sync.Mutex()
    private val needsPeople = setOf(Fn.Corrections, Fn.MyLeave, Fn.PendingLeaveApprovals, Fn.Tasks, Fn.TaskDetail, Fn.ProjectDetail,
        Fn.Announcements, Fn.Meetings, Fn.CalendarMonth, Fn.PerformanceReviews, Fn.Documents)

    private fun indexPeople(list: JsonElement) {
        people = list.objects().mapNotNull { p -> p.str("id")?.toLongOrNull()?.let { it to p } }.toMap()
    }

    /** Loads the directory once per session when the user may read it. Never throws: names are a nicety. */
    private suspend fun ensurePeople() {
        if (people.isNotEmpty() || cachedMe()?.canReadPeople != true) return
        peopleLock.lock()
        try {
            if (people.isNotEmpty()) return
            val raw = api.call(Fn.Directory)
            indexPeople(Shapes(emptyMap(), null).normalize(Fn.Directory, raw))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { peopleLock.unlock() }
    }

    suspend fun load(fn: Fn, data: JsonElement? = null, key: String = fn.name): JsonElement {
        if (fn in needsPeople) ensurePeople()
        val r = Shapes(people, cachedMe()).normalize(fn, api.call(fn, data))
        if (fn == Fn.Directory) indexPeople(r)
        // Cache only reasonably sized responses; never cache null results over good data.
        if (r !is JsonNull) { val s = r.toString(); if (s.length < 512_000) cacheDao.put(CacheEntry(key, seal(s), System.currentTimeMillis())) }
        return r
    }

    /** Performs a write and marks dependent reads stale. */
    suspend fun act(fn: Fn, data: JsonElement? = null): JsonElement {
        val r = api.call(fn, data)
        invalidate(Invalidation.after(fn))
        return r
    }

    /**
     * A write that may be queued (see [Outbox.QUEUEABLE]). Sent at once when possible; if the request definitely
     * never left the device (no network, DNS, connect or TLS failure) it is saved to the outbox and sent by
     * WorkManager later with the same Idempotency-Key. A timeout after sending is *not* queued: it may have
     * reached the server, so the caller shows the usual "couldn't confirm" flow instead of risking a duplicate.
     */
    suspend fun submit(fn: Fn, body: JsonElement): Submit {
        require(fn in Outbox.QUEUEABLE) { "${fn.name} can't be queued" }
        val key = UUID.randomUUID().toString()
        try {
            api.call(fn, body, key)
            invalidate(Invalidation.after(fn))
            return Submit.Sent
        } catch (e: PortalException) {
            if (!e.offline || e.uncertain) throw e
            outboxDao.insert(OutboxItem(fn = fn.name, payload = seal(body.toString()), idempotencyKey = key, createdAt = System.currentTimeMillis()))
            scheduleOutbox()
            return Submit.Queued
        }
    }

    /** Queued writes (pending, failed and unconfirmed), oldest first, for the sync banner. */
    val outbox: Flow<List<OutboxItem>> get() = outboxDao.observeAll()

    private val drainLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Sends pending outbox items in order. Returns true when WorkManager should retry later (offline, server busy,
     * session being renewed). Server refusals become [OutboxItem.STATE_FAILED]; a timeout after sending becomes
     * [OutboxItem.STATE_UNCONFIRMED] and is never resent automatically.
     */
    suspend fun drainOutbox(): Boolean = drainLock.withLock {
        if (!api.hasSession()) return@withLock false
        for (item in outboxDao.pending()) {
            val fn = Fn.entries.firstOrNull { it.name == item.fn }?.takeIf { it in Outbox.QUEUEABLE }
            val body = parse(item.payload)
            if (fn == null || body == null) {
                outboxDao.update(item.copy(state = OutboxItem.STATE_FAILED, lastError = "This change couldn't be read back. Please make it again."))
                continue
            }
            try {
                api.call(fn, body, item.idempotencyKey)
                outboxDao.delete(item.id)
                invalidate(Invalidation.after(fn))
            } catch (e: CancellationException) { throw e
            } catch (e: PortalException) {
                val n = item.attempts + 1
                when {
                    e.code == 401 -> return@withLock true
                    e.uncertain -> outboxDao.update(item.copy(attempts = n, state = OutboxItem.STATE_UNCONFIRMED,
                        lastError = "Sent, but Portal One didn't confirm it. Check before sending it again."))
                    e.offline -> return@withLock true
                    (e.retryable || e.code in 500..599) && n < Outbox.MAX_ATTEMPTS -> {
                        outboxDao.update(item.copy(attempts = n, lastError = e.message)); return@withLock true
                    }
                    else -> outboxDao.update(item.copy(attempts = n, state = OutboxItem.STATE_FAILED, lastError = e.message))
                }
            }
        }
        false
    }

    /** Puts a failed/unconfirmed item back in the queue (same Idempotency-Key) and wakes the sender. */
    suspend fun resend(id: Long) {
        val i = outboxDao.get(id) ?: return
        outboxDao.update(i.copy(state = OutboxItem.STATE_PENDING, attempts = 0, lastError = null))
        scheduleOutbox()
    }

    suspend fun discard(id: Long) = outboxDao.delete(id)

    suspend fun login(workspace: String, userId: String, password: String): SessionUser {
        api.clearSession()
        clearCache()
        val ws = workspace.trim().lowercase(); val uid = userId.trim()
        val r = api.call(Fn.Login, buildJsonObject { put("workspace", ws); put("identifier", uid); put("password", password) }).obj()
        val token = r.str("compositeToken") ?: r.str("token")?.let { "$ws.$it" }
        val u = r?.get("user").obj()
        if (token == null || u == null) throw PortalException("Sign-in didn't complete. Please try again.")
        api.startSession(token, r.num("expiresInSeconds")?.toLong())
        prefs.edit().putString("workspace", ws).putString("lastUserId", uid).apply()
        acceptUser(withAccess(u))
        return cachedMe() ?: SessionUser(u)
    }

    /**
     * Check-in / check-out as JSON, the way the web app sends it: `{"selfie": "data:image/jpeg;base64,…"}` plus the
     * optional `latitude`/`longitude`/`accuracyMeters` and `deviceId`. v0.9.3: the live gateway only parses JSON bodies;
     * the v0.6 multipart form was answered with "Invalid JSON request payload". If the gateway rejects the extra
     * fields (400/422: nothing was recorded), the punch is retried once with the selfie alone, exactly as the web posts it.
     * Liveness rides the `X-PortalX-Liveness` header. Never auto-retried otherwise (writeClient).
     * [selfie] is deleted once the upload has finished, whatever the outcome.
     * @return the server's `meta.locationRecorded` (false when absent).
     */
    suspend fun punch(fn: Fn, selfie: File?, fix: Fix?, liveness: LivenessResult? = null): Boolean {
        require(fn == Fn.CheckIn || fn == Fn.CheckOut) { "punch is only for check-in/out" }
        try {
            if (fn == Fn.CheckIn && selfie == null) throw PortalException("A selfie is required to check in.")
            val dataUrl = selfie?.let { "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(it.readBytes()) }
            val full = buildJsonObject {
                dataUrl?.let { put("selfie", it) }
                fix?.formFields()?.forEach { (k, v) -> v.toDoubleOrNull()?.let { d -> put(k, d) } }
                put("deviceId", api.deviceId)
            }
            val headers = if (selfie != null && liveness != null) mapOf(liveness.header()) else emptyMap()
            val env = try {
                api.callEnvelope(fn, full, headers = headers)
            } catch (e: PortalException) {
                if (e.code != 400 && e.code != 422) throw e
                api.callEnvelope(fn, buildJsonObject { dataUrl?.let { put("selfie", it) } }, headers = headers)
            }
            invalidate(Invalidation.after(fn))
            return env.metaBool("locationRecorded") ?: false
        } finally {
            selfie?.delete()
        }
    }

    /** Rotates the session token when it's close to expiry (see PortalApi.refreshIfNeeded). */
    suspend fun refreshSession(): RefreshResult = api.refreshIfNeeded()

    /** v0.8: called with the FCM registration token after sign-in and whenever Firebase rotates it. */
    suspend fun registerDeviceToken(token: String) {
        api.call(Fn.DeviceToken, buildJsonObject { put("token", token); put("platform", "android"); put("deviceId", api.deviceId) })
    }

    /** Adds the effective permission codes (access-control/my-access) to a user object; keeps the old ones on failure. */
    private suspend fun withAccess(u: JsonObject): JsonObject = try {
        val perms = api.call(Fn.MyAccess).obj()?.get("permissions") as? JsonArray
        if (perms != null) JsonObject(u + ("permissions" to perms)) else u
    } catch (e: CancellationException) { throw e } catch (e: PortalException) {
        if (e.code == 401) throw e
        cachedMe()?.raw?.get("permissions")?.let { JsonObject(u + ("permissions" to it)) } ?: u
    }

    /** Stores a user object returned by the server (login, me, change password). */
    fun acceptUser(u: JsonObject) {
        cache.put("me", u.toString())
    }

    suspend fun me(): SessionUser? {
        val d = api.call(Fn.CurrentUser).obj() ?: return null
        val r = withAccess(d["user"].obj() ?: d)
        acceptUser(r)
        return SessionUser(r)
    }

    fun cachedMe(): SessionUser? = cached("me")?.obj()?.let { SessionUser(it) }
    fun lastWorkspace() = prefs.getString("workspace", "") ?: ""
    fun lastUserId() = prefs.getString("lastUserId", "") ?: ""

    /** Wipes cached responses and the outbox (queued writes belong to the session that made them). */
    private suspend fun clearCache() {
        cache.clear(); people = emptyMap(); _versions.value = emptyMap()
        cacheDao.clear(); outboxDao.clear()
    }

    /**
     * Best-effort server logout: unregister this device's push token, then delete the session row (both send
     * X-Device-Id). Then wipe the token, expiry and every cached response. Never throws. The device id is kept.
     */
    suspend fun logout() {
        try {
            if (api.hasSession()) {
                try { api.call(Fn.DeviceTokenRemove, buildJsonObject { put("deviceId", api.deviceId) }) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                if (api.hasSession()) api.call(Fn.Logout)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        finally { api.clearSession(); kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { clearCache() } }
    }
}
