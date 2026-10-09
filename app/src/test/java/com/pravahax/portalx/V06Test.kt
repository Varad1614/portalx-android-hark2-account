package com.pravahax.portalx

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.location.BestEffortLocation
import com.pravahax.portalx.location.Fix
import com.pravahax.portalx.location.LocationSource
import com.pravahax.portalx.location.PlatformLocationSource
import com.pravahax.portalx.media.Selfie
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.net.PortalException
import com.pravahax.portalx.net.RefreshResult
import com.pravahax.portalx.net.SessionPolicy
import com.pravahax.portalx.security.SecureStore
import com.pravahax.portalx.ui.locationHint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartReader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

internal data class Part(val name: String, val filename: String?, val contentType: String?, val body: ByteArray)

/**
 * v0.9.3: check-in/out is JSON like the web (`selfie` = JPEG data URL). Parses the recorded body into the same
 * [Part] view the v0.6 multipart tests used: the selfie as decoded JPEG bytes, every other field as its text.
 */
internal fun parts(req: RecordedRequest): List<Part> {
    val ct = req.getHeader("Content-Type")!!
    assertTrue("not JSON: $ct", ct.startsWith("application/json"))
    val o = kotlinx.serialization.json.Json.parseToJsonElement(req.body.readUtf8()) as kotlinx.serialization.json.JsonObject
    return o.map { (k, v) ->
        val text = (v as kotlinx.serialization.json.JsonPrimitive).content
        if (k == "selfie") {
            assertTrue("selfie must be a JPEG data URL", text.startsWith("data:image/jpeg;base64,"))
            Part(k, "selfie.jpg", "image/jpeg", java.util.Base64.getDecoder().decode(text.substringAfter(",")))
        } else Part(k, null, null, text.toByteArray())
    }
}

/** Copies a real JPEG from test resources (capture-small.jpg 40×30, capture-4000x3000.jpg) to a temp file. */
internal fun jpegFile(dir: File, big: Boolean = false): File {
    val res = if (big) "capture-4000x3000.jpg" else "capture-small.jpg"
    val bytes = object {}.javaClass.classLoader!!.getResourceAsStream(res)!!.use { it.readBytes() }
    return File(dir, "cap-${System.nanoTime()}.jpg").also { it.writeBytes(bytes) }
}

/** Width/height from the JPEG's SOF header (independent of Robolectric's graphics mode). */
private fun jpegSize(b: ByteArray): Pair<Int, Int> {
    var i = 2
    while (i + 9 < b.size) {
        val marker = b[i + 1].toInt() and 0xFF
        val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
            val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
            return w to h
        }
        i += 2 + len
    }
    error("no SOF")
}

internal fun envelope(data: String, meta: String = """{"version":"v1"}""") =
    MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":$data,"meta":$meta}""")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MultipartAttendanceTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PortalApi
    private lateinit var repo: Repo
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val tag = System.nanoTime()
        api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "mp-$tag"))
        repo = Repo(ctx, api, "cache-mp-$tag")
        api.token = "pravahax.abc"
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun checkInIsMultipartWithSelfieLocationAndDeviceId() = runBlocking {
        val selfie = jpegFile(ctx.cacheDir)
        server.enqueue(envelope("""{"id":41}""", """{"version":"v1","locationRecorded":true}"""))
        val recorded = repo.punch(Fn.CheckIn, selfie, Fix(19.997454, 73.789803, 12.5f))
        assertTrue(recorded)
        assertFalse("temp selfie must be deleted after upload", selfie.exists())
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/api/v1/attendance/check-in", req.path)
        assertEquals("Bearer pravahax.abc", req.getHeader("Authorization"))
        assertEquals(api.deviceId, req.getHeader("X-Device-Id"))
        assertNotNull(req.getHeader("Idempotency-Key"))
        val p = parts(req).associateBy { it.name }
        assertEquals(setOf("selfie", "latitude", "longitude", "accuracyMeters", "deviceId"), p.keys)
        val s = p.getValue("selfie")
        assertEquals("image/jpeg", s.contentType); assertEquals("selfie.jpg", s.filename)
        assertEquals(0xFF, s.body[0].toInt() and 0xFF); assertEquals(0xD8, s.body[1].toInt() and 0xFF)
        assertEquals("19.997454", String(p.getValue("latitude").body)); assertEquals("73.789803", String(p.getValue("longitude").body))
        assertEquals("12.5", String(p.getValue("accuracyMeters").body)); assertEquals(api.deviceId, String(p.getValue("deviceId").body))
        listOf("latitude", "longitude", "accuracyMeters", "deviceId").forEach { assertNull("$it is a text part", p.getValue(it).filename) }
    }

    @Test fun checkOutSelfieIsOptional() = runBlocking {
        server.enqueue(envelope("""{"id":41}""", """{"locationRecorded":false}"""))
        assertFalse(repo.punch(Fn.CheckOut, null, null))
        val p = parts(server.takeRequest())
        assertEquals(listOf("deviceId"), p.map { it.name })
    }

    @Test fun checkInWithoutSelfieNeverSends() = runBlocking {
        val e = runCatching { repo.punch(Fn.CheckIn, null, null) }.exceptionOrNull()
        assertTrue(e is PortalException); assertEquals(0, server.requestCount)
    }

    @Test fun redirectNeverLeaksAuthorizationOrSelfie() = runBlocking {
        val elsewhere = MockWebServer(); elsewhere.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/steal").toString()))
            val selfie = jpegFile(ctx.cacheDir)
            val e = runCatching { repo.punch(Fn.CheckIn, selfie, null) }.exceptionOrNull() as PortalException
            assertEquals(302, e.code)
            assertEquals("redirect followed", 0, elsewhere.requestCount)
            assertFalse(selfie.exists())
        } finally { elsewhere.shutdown() }
    }

    /** The live gateway answers a body it can't take with 400: retry once with the selfie alone, like the web. */
    @Test fun rejectedExtraFieldsFallBackToSelfieOnly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
            .setBody("""{"success":false,"error":{"code":"BAD_REQUEST","message":"Invalid JSON request payload."}}"""))
        server.enqueue(envelope("""{"id":41}"""))
        repo.punch(Fn.CheckIn, jpegFile(ctx.cacheDir), Fix(19.997454, 73.789803, 12.5f))
        assertEquals(setOf("selfie", "latitude", "longitude", "accuracyMeters", "deviceId"), parts(server.takeRequest()).map { it.name }.toSet())
        assertEquals(listOf("selfie"), parts(server.takeRequest()).map { it.name })
    }

    @Test fun multipartWritesAreNeverRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"success":false,"error":{"code":"UNAVAILABLE","message":"x","retryable":true}}"""))
        val selfie = jpegFile(ctx.cacheDir)
        assertTrue(runCatching { repo.punch(Fn.CheckIn, selfie, null) }.isFailure)
        assertEquals(1, server.requestCount); assertFalse(selfie.exists())
    }

    @Test fun deviceIdIsAStableUuidOnEveryRequest() = runBlocking {
        val store = SecureStore(ctx, "dev-${System.nanoTime()}")
        val a = PortalApi(ctx, server.url("/").toString().trimEnd('/'), store)
        val id = a.deviceId
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id))
        assertEquals(id, PortalApi(ctx, a.baseUrl, store).deviceId)
        a.clearSession(); assertEquals("sign-out keeps the device id", id, a.deviceId)
        server.enqueue(envelope("[]")); a.call(Fn.Tasks)
        assertEquals(id, server.takeRequest().getHeader("X-Device-Id"))
        assertEquals(id, a.buildRequest(Fn.Login, null).header("X-Device-Id"))
    }

    @Test fun logoutRemovesDeviceTokenThenLogsOutWithDeviceId() = runBlocking {
        server.enqueue(envelope("""{"ok":true}""")); server.enqueue(envelope("""{"message":"ok"}"""))
        repo.logout()
        val r1 = server.takeRequest(); val r2 = server.takeRequest()
        assertEquals("/api/v1/notifications/device-token/remove", r1.path)
        assertEquals("""{"deviceId":"${api.deviceId}"}""", r1.body.readUtf8())
        assertEquals("/api/v1/auth/logout", r2.path)
        assertEquals(api.deviceId, r1.getHeader("X-Device-Id")); assertEquals(api.deviceId, r2.getHeader("X-Device-Id"))
        assertFalse(api.hasSession()); assertNull(api.expiresAt)
    }

    @Test fun deviceTokenPlumbingBody() = runBlocking {
        server.enqueue(envelope("""{"ok":true}"""))
        repo.registerDeviceToken("fcm-token-from-v0.8")
        val r = server.takeRequest()
        assertEquals("/api/v1/notifications/device-token", r.path)
        assertEquals("""{"token":"fcm-token-from-v0.8","platform":"android","deviceId":"${api.deviceId}"}""", r.body.readUtf8())
    }

    @Test fun loginStoresExpiry() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = v1(fnFor(request), kotlinx.serialization.json.Json.parseToJsonElement(GatewayFixtures.real(fnFor(request))))
        }
        val before = System.currentTimeMillis()
        repo.login("pravahax", "PSE-00002", "x")
        val exp = api.expiresAt!!
        assertTrue(exp >= before + 604_800_000L && exp <= System.currentTimeMillis() + 604_800_000L)
    }
}

class SessionPolicyTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L

    @Test fun refreshesInsideTheLast48Hours() {
        assertTrue(SessionPolicy.needsRefresh(now + 47 * h, now))
        assertTrue(SessionPolicy.needsRefresh(now + 48 * h - 1, now))
        assertFalse(SessionPolicy.needsRefresh(now + 48 * h, now))
        assertFalse(SessionPolicy.needsRefresh(now + 7 * 24 * h, now))
        assertTrue("already expired", SessionPolicy.needsRefresh(now - 1, now))
        assertTrue("unknown expiry (pre-v0.6 session)", SessionPolicy.needsRefresh(null, now))
    }

    @Test fun expiryFromExpiresIn() {
        assertEquals(now + 604_800_000L, SessionPolicy.expiryFrom(now, 604_800))
        assertNull(SessionPolicy.expiryFrom(now, null)); assertNull(SessionPolicy.expiryFrom(now, 0))
    }

    @Test fun locationHintWording() {
        assertEquals("Location recorded", locationHint("in", true))
        assertEquals("Checked in without location", locationHint("in", false))
        assertEquals("Checked out without location", locationHint("out", false))
    }

    @Test fun fixFormFieldsUseFixedDecimals() {
        val f = Fix(0.00001, -73.5, null).formFields()
        assertEquals(mapOf("latitude" to "0.000010", "longitude" to "-73.500000"), f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionRefreshTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PortalApi
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private var refreshResponse: () -> MockResponse = { envelope(GatewayFixtures.real(Fn.AuthRefresh)).setBodyDelay(300, TimeUnit.MILLISECONDS) }
    private var writeDelayMs = 0L

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return when (request.path) {
                    "/api/v1/auth/refresh" -> refreshResponse()
                    "/api/v1/attendance/break/start" -> envelope("""{"id":1}""").setBodyDelay(writeDelayMs, TimeUnit.MILLISECONDS)
                    else -> envelope("[]")
                }
            }
        }
        server.start()
        api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "rf-${System.nanoTime()}"))
        api.startSession("pravahax.old", 3600) // 1 h left → inside the 48 h window
    }
    @After fun tearDown() { server.shutdown() }
    private fun refreshes() = seen.count { it.path == "/api/v1/auth/refresh" }

    @Test fun parallelCallersRefreshExactlyOnce() = runBlocking {
        val results = (1..8).map { async(kotlinx.coroutines.Dispatchers.IO) { api.refreshIfNeeded() } }.awaitAll()
        assertEquals(1, refreshes()); assertEquals(1, api.refreshCount.get())
        assertEquals(1, results.count { it == RefreshResult.Refreshed })
        assertTrue(results.all { it == RefreshResult.Refreshed || it == RefreshResult.NotNeeded })
        assertEquals("pravahax." + "b".repeat(64), api.token)
        val left = api.expiresAt!! - System.currentTimeMillis()
        assertTrue(left in (604_800_000L - 60_000)..604_800_000L)
        assertEquals("Bearer pravahax.old", seen.first { it.path == "/api/v1/auth/refresh" }.getHeader("Authorization"))
        assertEquals(api.deviceId, seen.first().getHeader("X-Device-Id"))
    }

    @Test fun requestsStartedDuringRotationUseTheNewToken() = runBlocking {
        val r = async(kotlinx.coroutines.Dispatchers.IO) { api.refreshIfNeeded() }
        while (refreshes() == 0) delay(5)
        api.call(Fn.Tasks)
        assertEquals(RefreshResult.Refreshed, r.await())
        assertEquals("Bearer pravahax." + "b".repeat(64), seen.last { it.path == "/api/v1/tasks" }.getHeader("Authorization"))
    }

    @Test fun refresh401SignsOutCleanly() = runBlocking {
        refreshResponse = { MockResponse().setResponseCode(401).setBody("""{"success":false,"error":{"code":"UNAUTHORIZED","message":"revoked"}}""") }
        assertEquals(RefreshResult.SignedOut, api.refreshIfNeeded())
        assertFalse(api.hasSession()); assertNull(api.expiresAt)
        assertEquals(RefreshResult.NoSession, api.refreshIfNeeded()); assertEquals(1, refreshes())
    }

    @Test fun otherFailuresKeepTheSession() = runBlocking {
        refreshResponse = { MockResponse().setResponseCode(503).setBody("""{"success":false,"error":{"code":"UNAVAILABLE","message":"x"}}""") }
        assertEquals(RefreshResult.Failed, api.refreshIfNeeded())
        assertEquals("pravahax.old", api.token); assertEquals(1, refreshes()) // never auto-retried
    }

    @Test fun neverRefreshesWhileAWriteIsInFlight() = runBlocking {
        writeDelayMs = 600
        val w = launch(kotlinx.coroutines.Dispatchers.IO) { api.call(Fn.StartBreak) }
        while (!api.writeInFlight) delay(5)
        assertEquals(RefreshResult.Deferred, api.refreshIfNeeded())
        w.join()
        assertEquals(0, refreshes()); assertEquals("pravahax.old", api.token)
        assertEquals(RefreshResult.Refreshed, api.refreshIfNeeded()) // next resume does it
    }

    @Test fun notNeededFarFromExpiry() = runBlocking {
        api.startSession("pravahax.old", 72 * 3600)
        assertEquals(RefreshResult.NotNeeded, api.refreshIfNeeded())
        assertEquals(0, server.requestCount)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SelfieImageTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun sizing() {
        assertEquals(2, Selfie.sampleSize(4032, 3024)); assertEquals(1, Selfie.sampleSize(1280, 720)); assertEquals(4, Selfie.sampleSize(8000, 6000))
        assertEquals(1280 to 960, Selfie.targetSize(4032, 3024)); assertEquals(960 to 1280, Selfie.targetSize(3024, 4032))
        assertEquals(800 to 600, Selfie.targetSize(800, 600))
        assertTrue(Selfie.swapsAxes(ExifInterface.ORIENTATION_ROTATE_90) && Selfie.swapsAxes(ExifInterface.ORIENTATION_TRANSVERSE))
        assertFalse(Selfie.swapsAxes(ExifInterface.ORIENTATION_ROTATE_180) || Selfie.swapsAxes(ExifInterface.ORIENTATION_NORMAL))
    }

    @Test fun stripMetadataRemovesExifXmpAndComments() {
        fun seg(marker: Int, payload: ByteArray) = byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        val jfif = seg(0xE0, "JFIF\u0000\u0001\u0001".toByteArray())
        val exif = seg(0xE1, "Exif\u0000\u0000GPS-SECRET-19.99N".toByteArray())
        val xmp = seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray())
        val icc = seg(0xE2, "ICC_PROFILE\u0000\u0001\u0001".toByteArray())
        val com = seg(0xFE, "Serial 1234".toByteArray())
        val dqt = seg(0xDB, ByteArray(65) { 1 })
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
        val src = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + jfif + exif + xmp + icc + com + dqt + sos
        val out = Selfie.stripMetadata(src)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + jfif + icc + dqt + sos, out)
        assertArrayEquals("not a JPEG → unchanged", byteArrayOf(1, 2, 3), Selfie.stripMetadata(byteArrayOf(1, 2, 3)))
    }

    /** A rotated 12 MP-class capture with GPS EXIF comes out upright, ≤ 1280 px, as a JPEG with no EXIF at all. */
    @Test fun processDownscalesHonoursOrientationAndStripsExif() {
        val src = jpegFile(ctx.cacheDir, big = true)
        ExifInterface(src.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "19/1,59/1,5000/100"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_MAKE, "SecretPhoneCo")
            saveAttributes()
        }
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, Selfie.readOrientation(src))
        val out = Selfie.process(src, File(ctx.cacheDir, "out-${System.nanoTime()}.jpg"))
        val bytes = out.readBytes()
        assertEquals(0xFF, bytes[0].toInt() and 0xFF); assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        val (w, h) = jpegSize(bytes)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(out.path, b)
        println("SELFIE_OUT sof=${w}x$h bitmapFactory=${b.outWidth}x${b.outHeight} bytes=${bytes.size}")
        assertTrue("long edge ${maxOf(w, h)} must be ≤ 1280", maxOf(w, h) <= 1280)
        assertEquals("rotated 90° → portrait", 960 to 1280, w to h)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("Exif")); assertFalse(text.contains("SecretPhoneCo"))
        val exif = ExifInterface(out.path)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)); assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        src.delete(); out.delete()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocationDeniedTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun deniedOrTimedOutLocationStillChecksIn() = runBlocking {
        // No location permission granted in this test app.
        assertFalse(BestEffortLocation.granted(ctx))
        assertNull(PlatformLocationSource(ctx).currentFix())
        assertNull(BestEffortLocation.fix(PlatformLocationSource(ctx)))
        // A provider that never answers is cut off by the timeout, and a throwing one is "no location".
        val t0 = System.currentTimeMillis()
        assertNull(BestEffortLocation.fix(LocationSource { awaitCancellation() }, timeoutMs = 300))
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
        assertNull(BestEffortLocation.fix(LocationSource { error("provider crashed") }))

        val server = MockWebServer(); server.start()
        try {
            val api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "loc-${System.nanoTime()}"))
            api.token = "pravahax.abc"
            val repo = Repo(ctx, api, "cache-loc-${System.nanoTime()}")
            server.enqueue(envelope("""{"id":41}""", """{"locationRecorded":false}"""))
            val selfie = jpegFile(ctx.cacheDir)
            val recorded = repo.punch(Fn.CheckIn, selfie, BestEffortLocation.fix(PlatformLocationSource(ctx)))
            assertFalse(recorded)
            assertEquals("Checked in without location", locationHint("in", recorded))
            val p = parts(server.takeRequest()).map { it.name }
            assertEquals(listOf("deviceId", "selfie"), p.sorted())
        } finally { server.shutdown() }
    }

    @Test fun rationaleIsShownOnlyOnce() {
        ctx.getSharedPreferences(BestEffortLocation.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(BestEffortLocation.shouldAsk(ctx))
        BestEffortLocation.markAsked(ctx)
        assertFalse(BestEffortLocation.shouldAsk(ctx))
    }
}
