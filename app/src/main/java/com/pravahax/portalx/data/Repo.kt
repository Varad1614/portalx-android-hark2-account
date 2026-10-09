package com.pravahax.portalx.data

import android.content.Context
import com.pravahax.portalx.location.Fix
import com.pravahax.portalx.net.FilePart
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.net.RefreshResult
import com.pravahax.portalx.net.PortalException
import com.pravahax.portalx.security.SecureStore
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

/** Repository with a small encrypted offline cache: last good response per key is kept on disk. */
class Repo(context: Context, val api: PortalApi = PortalApi(context), cacheName: String = "cache") {
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

    companion object { const val CACHE_SCHEMA = 2 }

    fun invalidate(fns: Collection<Fn>) {
        if (fns.isEmpty()) return
        _versions.update { m -> m + fns.associateWith { (m[it] ?: 0) + 1 } }
    }

    fun cached(key: String): JsonElement? = cache.get(key)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

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
        if (r !is JsonNull) { val s = r.toString(); if (s.length < 512_000) cache.put(key, s) }
        return r
    }

    /** Performs a write and marks dependent reads stale. */
    suspend fun act(fn: Fn, data: JsonElement? = null): JsonElement {
        val r = api.call(fn, data)
        invalidate(Invalidation.after(fn))
        return r
    }

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
     * Check-in / check-out as multipart/form-data: `selfie` (JPEG file; required for check-in, optional for check-out),
     * optional `latitude`/`longitude`/`accuracyMeters`, and `deviceId`. Never auto-retried (writeClient).
     * [selfie] is deleted once the upload has finished, whatever the outcome.
     * @return the server's `meta.locationRecorded` (false when absent).
     */
    suspend fun punch(fn: Fn, selfie: File?, fix: Fix?): Boolean {
        require(fn == Fn.CheckIn || fn == Fn.CheckOut) { "punch is only for check-in/out" }
        try {
            if (fn == Fn.CheckIn && selfie == null) throw PortalException("A selfie is required to check in.")
            val data = buildJsonObject {
                fix?.formFields()?.forEach { (k, v) -> put(k, v) }
                put("deviceId", api.deviceId)
            }
            val env = api.callEnvelope(fn, data, listOfNotNull(selfie?.let { FilePart("selfie", it, "image/jpeg", "selfie.jpg") }))
            invalidate(Invalidation.after(fn))
            return env.metaBool("locationRecorded") ?: false
        } finally {
            selfie?.delete()
        }
    }

    /** Rotates the session token when it's close to expiry (see PortalApi.refreshIfNeeded). */
    suspend fun refreshSession(): RefreshResult = api.refreshIfNeeded()

    /** v0.8 (FCM) will call this with the real registration token. Nothing calls it in v0.6. */
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

    private fun clearCache() { cache.clear(); people = emptyMap(); _versions.value = emptyMap() }

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
        finally { api.clearSession(); clearCache() }
    }
}
