package com.pravahax.portalx

import androidx.test.core.app.ApplicationProvider
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.net.PortalException
import com.pravahax.portalx.security.SecureStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PortalApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PortalApi
    private fun ok(data: String) = MockResponse().setBody("""{"success":true,"data":$data,"meta":{"version":"v1"}}""")
    private fun fail(http: Int, code: String, msg: String = "x", retry: Boolean = false) =
        MockResponse().setResponseCode(http).setBody("""{"success":false,"error":{"code":"$code","message":"$msg","retryable":$retry,"requestId":"r1"}}""")

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "test-${System.nanoTime()}"))
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun sendsBearerAndUnwrapsData() = runBlocking {
        api.token = "pravahax.abc"
        server.enqueue(ok("""[{"id":1}]"""))
        val r = api.call(Fn.Tasks)
        val req = server.takeRequest()
        assertEquals("/api/v1/tasks", req.path)
        assertEquals("Bearer pravahax.abc", req.getHeader("Authorization"))
        assertEquals(1, r.jsonArray.size)
    }

    @Test fun fillsPathParamsAndSendsRestAsBody() = runBlocking {
        server.enqueue(ok("null"))
        api.call(Fn.UpdateTaskStatus, buildJsonObject { put("taskId", 7); put("status", "done") })
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/v1/tasks/7", req.path)
        assertEquals("""{"status":"done"}""", req.body.readUtf8())
        assertNotNull(req.getHeader("Idempotency-Key"))
    }

    @Test fun unauthorizedClearsToken() = runBlocking {
        api.token = "pravahax.abc"
        server.enqueue(fail(401, "UNAUTHORIZED"))
        val e = runCatching { api.call(Fn.Tasks) }.exceptionOrNull() as PortalException
        assertEquals(401, e.code); assertFalse(api.hasSession())
    }

    @Test fun wrongPasswordIsNotSessionLoss() = runBlocking {
        server.enqueue(fail(401, "INVALID_CREDENTIALS"))
        val e = runCatching { api.call(Fn.Login, buildJsonObject { put("workspace", "pravahax") }) }.exceptionOrNull() as PortalException
        assertNotEquals(401, e.code); assertEquals("INVALID_CREDENTIALS", e.errorCode); assertEquals("r1", e.requestId)
    }

    @Test fun readsRetryOnceWritesNever() = runBlocking {
        server.enqueue(fail(503, "UNAVAILABLE", retry = true)); server.enqueue(ok("{}"))
        api.call(Fn.Tasks); assertEquals(2, server.requestCount)
        server.enqueue(fail(503, "UNAVAILABLE", retry = true))
        assertTrue(runCatching { api.call(Fn.CheckOut) }.isFailure); assertEquals(3, server.requestCount)
    }
}
