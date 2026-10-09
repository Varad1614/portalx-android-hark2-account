package com.pravahax.portalx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.security.SecureStore
import com.pravahax.portalx.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders key screens to PNG at phone size (393×852 dp @ 3x) in the light theme.
 * Needs Robolectric NATIVE graphics, which only ships for x86_64 Linux/macOS, so it is skipped in the normal
 * suite and run explicitly with: -Dportalx.screenshots=<out dir> -Drobolectric.graphicsMode=NATIVE
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ScreenshotTest {
    private val out: String? = System.getProperty("portalx.screenshots")

    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
    var attendanceState = "out"

    /** Real gateway payloads; "out" = not checked in yet today (attendance/today returns null). */
    private fun fixture(fn: Fn?): String =
        if (fn == Fn.AttendanceToday && attendanceState == "out") "null" else GatewayFixtures.real(fn, today)

    private fun shoot(name: String, signedIn: Boolean, route: String, state: String = "out") {
        attendanceState = state
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                                val fn = fnFor(request)
                val r = v1(fn, Json.parseToJsonElement(fixture(fn)))
                return r
            }
        }
        server.start()
        try {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "shot-$name"))
            val repo = Repo(ctx, api, "cache-shot-$name")
            if (signedIn) runBlocking { repo.login("pravahax", "PSE-00002", "x") }
            org.robolectric.RuntimeEnvironment.setFontScale(if (name.endsWith("200pct")) 2.0f else 1.0f)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java)
            controller.get().setTheme(R.style.Theme_PortalX) // the app's real (light) window theme, no action bar
            controller.setup()
            val activity = controller.get()
            activity.enableEdgeToEdge(
                statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
            activity.setContent {
                PortalTheme { CompositionLocalProvider(LocalRepo provides repo, LocalReduceMotion provides true) { PortalApp(repo, true, startRoute = route) } }
            }
            // Real network threads + advance the paused looper clock so enter/size animations finish before capture.
            repeat(30) { ShadowLooper.idleMainLooper(); Thread.sleep(60) }
            repeat(10) { ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
            val v = activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(out!!).mkdirs()
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SHOT_OK $name ${v.width}x${v.height}")
            controller.pause().stop().destroy()
        } finally { runCatching { server.shutdown() } }
    }

    @Test fun screens() {
        assumeTrue("screenshots run only with -Dportalx.screenshots", out != null)
        val only = System.getProperty("portalx.only")?.takeIf { it.isNotBlank() && it != "all" }?.split(",")?.toSet()
        listOf(
            Triple("01-login", false, "home"), Triple("02-home", true, "home"), Triple("03-home-checked-in", true, "home"),
            Triple("04-attendance", true, "attendance"), Triple("05-tasks", true, "tasks"), Triple("06-leave", true, "leave"),
            Triple("07-calendar", true, "calendar"), Triple("08-directory", true, "directory"), Triple("09-more", true, "more"),
            Triple("10-announcements", true, "announcements"), Triple("11-home-200pct", true, "home"), Triple("12-attendance-200pct", true, "attendance"),
        ).filter { only == null || it.first in only }.forEach { (n, s, r) -> shoot(n, s, r, if (n == "03-home-checked-in") "in" else "out") }
    }
}
