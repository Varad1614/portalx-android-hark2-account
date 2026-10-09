package com.pravahax.portalx.net

import android.content.Context
import com.pravahax.portalx.BuildConfig
import com.pravahax.portalx.security.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Every failure surfaced to the UI is a PortalException with a user-friendly message.
 * [code] 401 = session is gone (sign out). [offline] = network unreachable / timed out.
 * [uncertain] = a write may or may not have reached the server (timeout after sending).
 * [errorCode] = the v1 envelope's machine code (INVALID_CREDENTIALS, RATE_LIMITED, ...).
 */
class PortalException(
    message: String,
    val code: Int = 0,
    val offline: Boolean = false,
    val uncertain: Boolean = false,
    val retryable: Boolean = false,
    val errorCode: String? = null,
    val requestId: String? = null,
) : IOException(message)

enum class M { GET, POST, PATCH }

/**
 * PortalX Mobile Gateway v1 routes (https://portal.pravahax.com/api/v1), verified one-for-one against
 * Portalx `src/bff/mobile/router.ts`. `{name}` segments are filled from the request data and removed from
 * the body/query. GatewayContractTest fails the build if any entry drifts from the gateway's route table.
 */
enum class Fn(val m: M, val path: String) {
    // auth
    CurrentUser(M.GET, "auth/me"),
    MyAccess(M.GET, "access-control/my-access"),
    Login(M.POST, "auth/login"),
    Logout(M.POST, "auth/logout"),
    ChangePassword(M.POST, "auth/change-password"),
    RequestPasswordReset(M.POST, "auth/forgot-password"),
    // attendance
    AttendanceToday(M.GET, "attendance/today"),
    AttendanceHistory(M.GET, "attendance/history"),
    CheckIn(M.POST, "attendance/check-in"),
    CheckOut(M.POST, "attendance/check-out"),
    StartBreak(M.POST, "attendance/break/start"),
    EndBreak(M.POST, "attendance/break/end"),
    RequestCorrection(M.POST, "attendance/corrections/request"),
    Corrections(M.GET, "attendance/corrections/pending"),
    DecideCorrection(M.POST, "attendance/corrections/review"),
    // leave & holidays
    MyLeave(M.GET, "leave/summary"),
    PendingLeaveApprovals(M.GET, "leave/pending"),
    ApplyLeave(M.POST, "leave/apply"),
    DecideLeave(M.POST, "leave/review"),
    Holidays(M.GET, "holidays"),
    // work
    Tasks(M.GET, "tasks"),
    TaskDetail(M.GET, "tasks/{taskId}"),
    CreateTask(M.POST, "tasks"),
    UpdateTaskStatus(M.PATCH, "tasks/{taskId}"),
    AddTaskComment(M.POST, "tasks/{taskId}/comments"),
    Projects(M.GET, "projects"),
    ProjectDetail(M.GET, "projects/{projectId}"),
    // people & comms
    Directory(M.GET, "people/directory"),
    MyProfile(M.GET, "people/me/profile"),
    Teams(M.GET, "teams"),
    Announcements(M.GET, "announcements"),
    MarkAnnouncementRead(M.POST, "announcements/{id}/read"),
    Meetings(M.GET, "meetings"),
    CreateMeeting(M.POST, "meetings"),
    CalendarMonth(M.GET, "calendar"),
    Notifications(M.GET, "notifications"),
    MarkNotificationRead(M.POST, "notifications/{id}/read"),
    MarkAllNotificationsRead(M.POST, "notifications/read-all"),
    // performance
    PerformanceReviews(M.GET, "performance/reviews"),
    GiveFeedback(M.POST, "performance/feedback"),
    // documents
    Documents(M.GET, "documents"),
    // administration
    Users(M.GET, "users"),
    AccessRoles(M.GET, "access-control/roles"),
    AuditLogs(M.GET, "audit/logs"),
    Settings(M.GET, "settings"),
    ;
    val get get() = m == M.GET
}

class PortalApi(
    context: Context,
    val baseUrl: String = DEFAULT_BASE_URL,
    private val store: SecureStore = SecureStore(context, "session"),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://portal.pravahax.com"
        private const val TOKEN_KEY = "__token"
        internal const val MAX_BODY_BYTES = 8L * 1024 * 1024
        internal const val MAX_JSON_DEPTH = 256

        internal fun readCapped(body: ResponseBody?): String {
            if (body == null) return ""
            if (body.contentLength() > MAX_BODY_BYTES) throw PortalException("Portal One sent an unexpectedly large response.")
            val src = body.source()
            if (src.request(MAX_BODY_BYTES + 1)) throw PortalException("Portal One sent an unexpectedly large response.")
            return src.buffer.readUtf8()
        }

        internal fun jsonDepth(s: String): Int {
            var depth = 0; var max = 0; var inStr = false; var esc = false
            for (ch in s) {
                if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
                when (ch) { '"' -> inStr = true; '{', '[' -> { depth++; if (depth > max) max = depth }; '}', ']' -> depth-- }
            }
            return max
        }

        internal fun friendly(e: Throwable): PortalException = when (e) {
            is PortalException -> e
            is UnknownHostException, is ConnectException ->
                PortalException("You're offline or Portal One can't be reached. Check your connection.", offline = true)
            is SocketTimeoutException, is InterruptedIOException ->
                PortalException("Portal One is taking too long to respond. Please try again.", offline = true)
            is SSLException -> PortalException("A secure connection to Portal One couldn't be established.", offline = true)
            else -> PortalException("Something went wrong talking to Portal One. Please try again.")
        }

        /** Server messages for codes the UI should word itself. */
        internal fun messageFor(code: String?, fallback: String): String = when (code) {
            "INVALID_CREDENTIALS" -> "Invalid user ID, email or password."
            "RATE_LIMITED", "TOO_MANY_ATTEMPTS" -> "Too many attempts. Please wait 15 minutes and try again."
            "UNAUTHORIZED", "SESSION_EXPIRED" -> "Your session expired. Please sign in again."
            "FORBIDDEN" -> "You don't have permission to do that."
            else -> fallback
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        // Never replay the bearer token (or a selfie) to wherever a redirect points.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Writes must never be replayed silently by OkHttp (double check-in, duplicate leave request). */
    private val writeClient = client.newBuilder().retryOnConnectionFailure(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** `<workspace>.<token>` from login, Keystore-encrypted at rest. The password is never stored. */
    var token: String?
        get() = store.get(TOKEN_KEY)?.ifBlank { null }
        set(v) { if (v.isNullOrBlank()) store.remove(TOKEN_KEY) else store.put(TOKEN_KEY, v) }

    fun clearSession() { token = null }
    fun hasSession() = token != null

    suspend fun call(fn: Fn, data: JsonElement? = null): JsonElement = withContext(Dispatchers.IO) {
        try {
            guarded(fn, data)
        } catch (e: PortalException) {
            // Reads are safe to retry once on a transient failure; writes never are.
            if (fn.get && e.retryable) { delay(600); guarded(fn, data) } else throw e
        }
    }

    private fun guarded(fn: Fn, data: JsonElement?): JsonElement = try {
        execute(fn, data)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PortalException) {
        if (e.code == 401) clearSession()
        throw e
    } catch (e: IOException) {
        val f = friendly(e)
        val uncertain = !fn.get && (e is SocketTimeoutException || e is InterruptedIOException)
        throw PortalException(f.message ?: "", f.code, offline = f.offline, uncertain = uncertain, retryable = e !is UnknownHostException)
    } catch (e: Exception) {
        throw friendly(e)
    }

    /** Builds the URL: fills `{param}` from data, sends the rest as query (GET) or JSON body (writes). */
    internal fun buildRequest(fn: Fn, data: JsonElement?): Request {
        val fields = (data as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        var path = fn.path
        Regex("\\{(\\w+)\\}").findAll(fn.path).forEach { m ->
            val k = m.groupValues[1]
            val v = (fields.remove(k) as? JsonPrimitive)?.contentOrNull
                ?: throw PortalException("Missing $k for this request.")
            path = path.replace(m.value, java.net.URLEncoder.encode(v, "UTF-8"))
        }
        val url = "${baseUrl.trimEnd('/')}/api/v1/$path".toHttpUrl().newBuilder()
        val b = Request.Builder()
            .header("Accept", "application/json")
            .header("User-Agent", "PortalX-Android/${BuildConfig.VERSION_NAME} (Mobile)")
        token?.let { b.header("Authorization", "Bearer $it") }
        return if (fn.get) {
            fields.forEach { (k, v) -> if (v !is JsonNull) url.addQueryParameter(k, (v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }
            b.url(url.build()).get().build()
        } else {
            val body = JsonObject(fields).toString().toRequestBody("application/json".toMediaType())
            b.header("Idempotency-Key", UUID.randomUUID().toString()) // lets the gateway drop accidental duplicates
            b.url(url.build()).method(fn.m.name, body).build()
        }
    }

    private fun execute(fn: Fn, data: JsonElement?): JsonElement {
        val req = buildRequest(fn, data)
        (if (fn.get) client else writeClient).newCall(req).execute().use { resp ->
            if (resp.code in 300..399) throw PortalException("Portal One answered with an unexpected redirect (${resp.code}).", resp.code)
            val body = readCapped(resp.body)
            if (jsonDepth(body) > MAX_JSON_DEPTH) throw PortalException("Unexpected response from Portal One.", resp.code)
            val env = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw when {
                    resp.code == 401 -> PortalException(messageFor("UNAUTHORIZED", ""), 401)
                    resp.code in 500..599 -> PortalException("Portal One is temporarily unavailable (${resp.code}). Please try again shortly.", resp.code, retryable = true)
                    resp.code == 404 -> PortalException("This feature isn't available right now. The app may need an update.", 404)
                    else -> PortalException("Unexpected response from Portal One.", resp.code)
                }
            val ok = (env["success"] as? JsonPrimitive)?.contentOrNull == "true"
            if (ok && resp.isSuccessful) return env["data"] ?: JsonNull
            val err = env["error"] as? JsonObject
            val code = (err?.get("code") as? JsonPrimitive)?.contentOrNull
            val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Request failed (${resp.code})."
            val retry = (err?.get("retryable") as? JsonPrimitive)?.contentOrNull == "true" || resp.code == 429
            val http = if (code == "UNAUTHORIZED" || code == "SESSION_EXPIRED") 401 else resp.code
            // A wrong password is a 401 too, but it must not be treated as "session gone".
            val status = if (fn == Fn.Login && http == 401) 400 else http
            throw PortalException(messageFor(code, msg), status, retryable = retry, errorCode = code,
                requestId = (err?.get("requestId") as? JsonPrimitive)?.contentOrNull)
        }
    }
}
