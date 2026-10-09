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
/** v0.9.2: not-checked-in-yet must show Check in. */
class V092Test {
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

    /** Attendance screen against a gateway that answers `data: null`: the Check in button must be on screen. */
    @Test fun attendanceScreenShowsCheckInWhenGatewaySaysNull() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = also { seen += request.path!! }.let { when {
                request.path!!.startsWith("/api/v1/attendance/today") -> envelope("null")
                else -> envelope("[]")
            } }
        }
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup(); val act = ctl.get()
        val user = SessionUser(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"2","userId":"PSE-00002","name":"Varad","role":"super_admin","permissions":["attendance.read"]}""") as kotlinx.serialization.json.JsonObject)
        act.setContent { CompositionLocalProvider(LocalRepo provides repo, LocalSnackbar provides SnackbarHostState(), LocalOnline provides true) { AttendanceScreen(user) } }
        repeat(30) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS); Thread.sleep(40) }
        val texts = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.forEach { s -> texts += s.text }
            n.children.forEach(::walk)
        }
        val root = act.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as androidx.compose.ui.platform.ComposeView
        walk(((root.getChildAt(0)) as androidx.compose.ui.node.RootForTest).semanticsOwner.unmergedRootSemanticsNode)
        assertFalse("$texts requests=$seen", texts.any { it.contains("couldn't be loaded") })
        assertTrue(texts.toString(), texts.any { it.equals("Check in", true) })
        ctl.pause().stop().destroy()
    }

}
