package com.pravahax.portalx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pravahax.portalx.data.ApplyLeaveRequest
import com.pravahax.portalx.data.Outbox
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.data.Submit
import com.pravahax.portalx.data.TaskCommentRequest
import com.pravahax.portalx.data.TaskStatusRequest
import com.pravahax.portalx.data.db.OutboxItem
import com.pravahax.portalx.data.db.PortalDb
import com.pravahax.portalx.data.toJson
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.net.PortalException
import com.pravahax.portalx.security.AesGcmSealer
import com.pravahax.portalx.security.SecureStore
import com.pravahax.portalx.ui.Auth
import com.pravahax.portalx.ui.AppViewModel
import com.pravahax.portalx.ui.ResourceViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLooper
import javax.crypto.KeyGenerator
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

private fun ok(data: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":$data}""")
private fun fail(code: Int, err: String, msg: String) =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody("""{"success":false,"error":{"code":"$err","message":"$msg"}}""")

/** Waits for main-thread (viewModelScope) work and IO to settle. */
private fun settle(until: () -> Boolean) {
    val end = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); if (until()) return; Thread.sleep(20) }
    ShadowLooper.idleMainLooper()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class V07Test {
    private lateinit var server: MockWebServer
    private lateinit var api: PortalApi
    private lateinit var repo: Repo
    private lateinit var db: PortalDb
    private var port = 0
    private var scheduled = 0
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val sealer = AesGcmSealer(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    @Before fun setUp() {
        server = MockWebServer(); server.start(); port = server.port
        val tag = System.nanoTime()
        api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "v07-$tag"))
        db = PortalDb.open(ctx, "v07-$tag", driver = BundledSQLiteDriver(), inMemory = true)
        repo = Repo(ctx, api, "cache-v07-$tag", sealer = sealer, db = db, scheduleOutbox = { scheduled++ })
        api.startSession("pravahax.abc", 30L * 24 * 3600)
    }
    @After fun tearDown() { runCatching { server.shutdown() }; db.close() }

    private fun goOffline() { server.shutdown() }
    private fun comeBackOnline() { server = MockWebServer(); server.start(port) }
    private val leave = ApplyLeaveRequest(JsonPrimitive(3), "2026-10-12", "2026-10-13", false, "Family function in Pune")

    @Test fun typedRequestsKeepTheGatewayFieldNames() {
        assertEquals(setOf("leaveTypeId", "startDate", "endDate", "halfDay", "reason"), leave.toJson().jsonObject.keys)
        assertEquals("""{"taskId":"t-9","status":"done"}""", TaskStatusRequest(JsonPrimitive("t-9"), "done").toJson().toString())
        assertEquals("""{"taskId":7,"body":"hi"}""", TaskCommentRequest(JsonPrimitive(7), "hi").toJson().toString())
    }

    @Test fun onlineSubmitSendsAtOnce() = runBlocking {
        server.enqueue(ok("""{"id":1}"""))
        assertEquals(Submit.Sent, repo.submit(Fn.ApplyLeave, leave.toJson()))
        assertEquals("/api/v1/leave/apply", server.takeRequest().path)
        assertTrue(repo.outbox.first().isEmpty()); assertEquals(0, scheduled)
    }

    @Test fun offlineWriteIsQueuedSealedAndSentLaterWithTheSameIdempotencyKey() = runBlocking {
        goOffline()
        assertEquals(Submit.Queued, repo.submit(Fn.ApplyLeave, leave.toJson()))
        assertEquals(1, scheduled)
        val item = db.outbox().pending().single()
        assertFalse("payload must be sealed at rest", item.payload.contains("Pune"))

        comeBackOnline()
        server.enqueue(ok("""{"id":1}"""))
        assertFalse(repo.drainOutbox())
        val req = server.takeRequest()
        assertEquals("/api/v1/leave/apply", req.path)
        assertEquals(item.idempotencyKey, req.getHeader("Idempotency-Key"))
        assertTrue(req.body.readUtf8().contains("Family function in Pune"))
        assertTrue(repo.outbox.first().isEmpty())
    }

    @Test fun stillOfflineMeansRetryLater() = runBlocking {
        goOffline()
        repo.submit(Fn.UpdateTaskStatus, TaskStatusRequest(JsonPrimitive(5), "done").toJson())
        assertTrue(repo.drainOutbox())
        assertEquals(OutboxItem.STATE_PENDING, db.outbox().pending().single().state)
    }

    @Test fun serverRefusalFailsTheItemAndResendReusesTheKey() = runBlocking {
        goOffline()
        repo.submit(Fn.AddTaskComment, TaskCommentRequest(JsonPrimitive(5), "hi").toJson())
        val key = db.outbox().pending().single().idempotencyKey
        comeBackOnline()
        server.enqueue(fail(400, "VALIDATION_ERROR", "Task is closed."))
        assertFalse(repo.drainOutbox())
        val failed = repo.outbox.first().single()
        assertEquals(OutboxItem.STATE_FAILED, failed.state); assertEquals("Task is closed.", failed.lastError)

        repo.resend(failed.id)
        server.takeRequest()
        server.enqueue(ok("{}"))
        assertFalse(repo.drainOutbox())
        assertEquals(key, server.takeRequest().getHeader("Idempotency-Key"))
        assertTrue(repo.outbox.first().isEmpty())
    }

    @Test fun serverErrorsRetryThenGiveUp() = runBlocking {
        goOffline()
        repo.submit(Fn.ApplyLeave, leave.toJson())
        comeBackOnline()
        repeat(Outbox.MAX_ATTEMPTS * 2) { server.enqueue(fail(503, "UNAVAILABLE", "Down")) }
        repeat(Outbox.MAX_ATTEMPTS - 1) { assertTrue(repo.drainOutbox()) }
        assertFalse(repo.drainOutbox())
        assertEquals(OutboxItem.STATE_FAILED, repo.outbox.first().single().state)
    }

    @Test fun timeSensitiveWritesAreNeverQueued() = runBlocking {
        goOffline()
        for (fn in listOf(Fn.StartBreak, Fn.EndBreak, Fn.CheckIn, Fn.DecideLeave))
            assertTrue(runCatching { repo.submit(fn, buildJsonObject { }) }.exceptionOrNull() is IllegalArgumentException)
        // And a normal act() offline still fails loudly instead of pretending.
        assertTrue(runCatching { repo.act(Fn.StartBreak) }.exceptionOrNull() is PortalException)
    }

    @Test fun signOutClearsCacheAndOutbox() = runBlocking {
        goOffline()
        repo.submit(Fn.ApplyLeave, leave.toJson())
        comeBackOnline()
        server.enqueue(ok("""[{"id":1,"title":"T"}]"""))
        repo.load(Fn.Tasks)
        server.enqueue(ok("{}")); server.enqueue(ok("{}")) // device-token/remove, auth/logout
        repo.logout()
        assertNull(repo.cachedResponse("Tasks"))
        assertTrue(repo.outbox.first().isEmpty())
    }

    @Test fun cachedResponsesAreSealedInRoom() = runBlocking {
        server.enqueue(ok("""[{"id":1,"title":"Secret roadmap"}]"""))
        repo.load(Fn.Tasks)
        val raw = db.cache().get("Tasks")!!.value
        assertFalse(raw.contains("Secret roadmap"))
        assertTrue(repo.cachedResponse("Tasks").toString().contains("Secret roadmap"))
    }

    @Test fun resourceViewModelLoadsThenServesCacheOffline() {
        server.enqueue(ok("""[{"id":1,"title":"Ship v0.7"}]"""))
        val vm = ResourceViewModel(repo, Fn.Tasks, null, "Tasks")
        vm.start(true)
        settle { vm.state.value.data != null && !vm.state.value.loading }
        assertTrue(vm.state.value.data.toString().contains("Ship v0.7"))
        assertNull(vm.state.value.error)

        goOffline()
        val again = ResourceViewModel(repo, Fn.Tasks, null, "Tasks") // e.g. after process death
        again.start(true)
        settle { again.state.value.stale && !again.state.value.loading }
        assertTrue("Room serves the last good data", again.state.value.data.toString().contains("Ship v0.7"))
        assertTrue(again.state.value.stale)
        assertNotNull(again.state.value.error)
    }

    @Test fun resourceViewModelRefetchesWhenAWriteInvalidatesIt() {
        server.enqueue(ok("""[{"id":1,"status":"todo"}]"""))
        val vm = ResourceViewModel(repo, Fn.Tasks, null, "Tasks")
        vm.start(true)
        settle { vm.state.value.data != null && !vm.state.value.loading }
        server.enqueue(ok("{}"))
        server.enqueue(ok("""[{"id":1,"status":"done"}]"""))
        runBlocking { repo.submit(Fn.UpdateTaskStatus, TaskStatusRequest(JsonPrimitive(1), "done").toJson()) }
        settle { vm.state.value.data.toString().contains("done") && !vm.state.value.loading }
        assertTrue(vm.state.value.data.toString().contains("\"done\""))
    }

    @Test fun resourceViewModelReportsSessionExpiry() {
        server.enqueue(fail(401, "UNAUTHORIZED", "expired"))
        val vm = ResourceViewModel(repo, Fn.Tasks, null, "Tasks")
        vm.start(true)
        settle { vm.state.value.sessionExpired }
        assertTrue(vm.state.value.sessionExpired)
        vm.expiryHandled(); assertFalse(vm.state.value.sessionExpired)
    }

    @Test fun appViewModelSignsOutOnceAndHandsOutAFreshSession() {
        server.enqueue(ok("""{"user":{"id":"1","name":"Varad","role":"member"}}"""))
        server.enqueue(ok("""{"permissions":[]}"""))
        val vm = AppViewModel(repo)
        settle { vm.auth.value is Auth.SignedIn }
        assertTrue(vm.auth.value is Auth.SignedIn)
        val first = vm.session
        server.enqueue(ok("{}")); server.enqueue(ok("{}")) // device-token/remove, auth/logout
        vm.signOut("bye"); vm.signOut("twice")
        settle { vm.auth.value is Auth.SignedOut }
        assertEquals(Auth.SignedOut("bye"), vm.auth.value)
        assertNotSame(first, vm.session)
        assertFalse(api.hasSession())
    }
}
