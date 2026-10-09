package com.pravahax.portalx

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ApplicationProvider
import com.pravahax.portalx.data.*
import com.pravahax.portalx.data.model.AttendanceToday
import com.pravahax.portalx.location.BestEffortLocation
import com.pravahax.portalx.media.FaceFrame
import com.pravahax.portalx.media.LivenessCheck
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.security.SecureStore
import com.pravahax.portalx.ui.*
import com.pravahax.portalx.widget.WidgetSummary
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** v0.9.1 audit fixes, including the whole check-in flow on a simulated Android 14 device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class V091Test {
    private lateinit var server: MockWebServer
    private lateinit var api: PortalApi
    private lateinit var repo: Repo
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val tag = System.nanoTime()
        api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "v091-$tag"))
        repo = Repo(ctx, api, "cache-v091-$tag")
        api.token = "pravahax.abc"
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun livenessTimesOutEvenWhenNoFaceIsEverSeen() {
        val c = LivenessCheck(startedAt = 0)
        assertTrue(c.tick(1_000) is LivenessCheck.State.Hint)
        assertTrue(c.tick(26_000) is LivenessCheck.State.Failed)
        assertTrue(c.onFrame(FaceFrame(1, 0.5f), 1) is LivenessCheck.State.Failed)
    }

    @Test fun livenessTravelsAsHeaderAndTheMultipartContractIsUnchanged() = runBlocking {
        server.enqueue(envelope("""{"id":1}"""))
        repo.punch(Fn.CheckIn, jpegFile(ctx.cacheDir), null, LivenessResult(true, listOf("blink", "turn")))
        val req = server.takeRequest()
        assertEquals("passed; challenges=blink,turn", req.getHeader(LivenessResult.HEADER))
        assertEquals(setOf("selfie", "deviceId"), parts(req).map { it.name }.toSet())
        // no selfie (check-out without photo): nothing to vouch for
        server.enqueue(envelope("""{"id":2}"""))
        repo.punch(Fn.CheckOut, null, null, LivenessResult(true))
        assertNull(server.takeRequest().getHeader(LivenessResult.HEADER))
    }

    @Test fun tlsFailuresAreNotOfflineSoWritesDontQueueForever() {
        val e = PortalApi.friendly(javax.net.ssl.SSLPeerUnverifiedException("Pin verification failed"))
        assertFalse(e.offline)
    }

    @Test fun oneUnreadableSecureEntryDoesNotWipeTheStore() {
        val s = SecureStore(ctx, "corrupt-${System.nanoTime()}")
        // Robolectric has no AndroidKeyStore; the store then keeps values in memory and this path can't occur.
        org.junit.Assume.assumeTrue(s.javaClass.getDeclaredField("key").apply { isAccessible = true }.get(s) != null)
        s.put("deviceId", "dev-1"); s.put("token", "t")
        // simulate a corrupted ciphertext for one key
        val prefsField = s.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
        (prefsField.get(s) as android.content.SharedPreferences).edit().putString("token", "garbage:###").commit()
        assertNull(s.get("token"))
        assertEquals("dev-1", s.get("deviceId"))
    }

    /** The v0.9.1 field bug: `data: null` (not checked in yet) rendered "couldn't be loaded" and hid Check in. */
    @Test fun notCheckedInYetIsNotStartedNotUnknown() {
        val j = kotlinx.serialization.json.Json
        assertEquals(AttendanceToday.NotStarted, AttendanceToday.from(kotlinx.serialization.json.JsonNull))
        assertNull(AttendanceToday.from(null)) // nothing loaded yet stays "unknown"
        val d = LocalDate.of(2026, 10, 9)
        val row = """{"attendance_date":"%s","check_in":"2026-10-09T05:45:00.000Z","check_out":null}"""
        assertEquals("2026-10-09T05:45:00.000Z", AttendanceToday.from(j.parseToJsonElement(row.format("2026-10-09")), d)!!.checkIn)
        // pg DATE serialised as UTC midnight of the IST day must still count as today
        assertNotNull(AttendanceToday.from(j.parseToJsonElement(row.format("2026-10-08T18:30:00.000Z")), d)!!.checkIn)
        assertEquals(AttendanceToday.NotStarted, AttendanceToday.from(j.parseToJsonElement(row.format("2026-10-08")), d))
    }

    @Test fun widgetDoesNotShowYesterdaysPunchAfterMidnight() {
        val y = AttendanceToday.from(kotlinx.serialization.json.Json.parseToJsonElement("""{"check_in":"2026-10-08T09:30:00+05:30","check_out":"2026-10-08T18:00:00+05:30"}"""))
        assertEquals("Open PortalX to sync", WidgetSummary.of(y, 0, 0, LocalDate.of(2026, 10, 9)).headline)
        assertTrue(WidgetSummary.of(y, 0, 0, LocalDate.of(2026, 10, 8)).headline.startsWith("Checked out at"))
    }

    /**
     * Simulated device: tap Check in → camera permission granted → no Play services/front camera, so the system
     * camera opens → photo returned → selfie prepared → multipart check-in with `X-PortalX-Liveness: unavailable`
     * → success snackbar. Fails on any crash, hang or missing request.
     */
    @Test fun checkInEndToEndOnSimulatedDevice() {
        BestEffortLocation.markAsked(ctx)
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(Manifest.permission.CAMERA)
        server.enqueue(envelope("""{"id":7}""", """{"version":"v1","locationRecorded":false}"""))
        val snack = SnackbarHostState()
        var controller: AttendanceController? = null
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        act.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalSnackbar provides snack, LocalAppScope provides rememberCoroutineScope()) {
                controller = rememberAttendanceController()
            }
        }
        ShadowLooper.idleMainLooper()
        controller!!.checkIn()
        ShadowLooper.idleMainLooper()
        val started = shadowOf(act).nextStartedActivityForResult ?: error("camera was not opened")
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started.intent.action)
        // The camera app writes the photo to the file we handed it.
        @Suppress("DEPRECATION") val uri = started.intent.getParcelableExtra<android.net.Uri>(MediaStore.EXTRA_OUTPUT) ?: error("no EXTRA_OUTPUT")
        val out = File(File(ctx.cacheDir, "selfies"), uri.lastPathSegment!!)
        out.writeBytes(jpegFile(ctx.cacheDir).readBytes())
        shadowOf(act).receiveResult(started.intent, Activity.RESULT_OK, Intent())
        var req: okhttp3.mockwebserver.RecordedRequest? = null
        val deadline = System.currentTimeMillis() + 15_000
        // Advance the main-thread clock too: the ≤5 s location fix and coroutine delays run on it.
        while (req == null && System.currentTimeMillis() < deadline) { ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS); req = server.takeRequest(100, TimeUnit.MILLISECONDS) }
        assertNotNull("check-in was never sent; snackbar=" + snack.currentSnackbarData?.visuals?.message, req)
        assertEquals("/api/v1/attendance/check-in", req!!.path)
        assertEquals("unavailable", req.getHeader(LivenessResult.HEADER))
        assertTrue(parts(req).any { it.name == "selfie" && it.body.size > 100 })
        val until = System.currentTimeMillis() + 5_000
        while (snack.currentSnackbarData == null && System.currentTimeMillis() < until) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS); Thread.sleep(20) }
        assertTrue(snack.currentSnackbarData?.visuals?.message.orEmpty(), snack.currentSnackbarData?.visuals?.message.orEmpty().startsWith("Checked in at"))
    }
}
