package com.pravahax.portalx

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLooper

/**
 * Boots every signed-in screen on a simulated Android 14 device against a fake gateway that only knows the
 * routes in GatewayContractTest's route table, and fails on any crash, any request to an unknown route, and
 * any request a user without the permission should never have sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class UiSmokeTest {
    private lateinit var server: MockWebServer
    private val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val unknown = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val forbidden = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** realistic | hostile | member (only basic employee permissions; services 500 on anything else). */
    private var mode = "realistic"
    private val memberPerms = listOf("attendance.read", "leave.read", "tasks.read", "comms.read")

    /** Permission each read needs on the server side (from the services' authorize() calls). */
    private val needs = mapOf(
        Fn.Directory to "people.read", Fn.Corrections to "attendance.manage", Fn.PendingLeaveApprovals to "leave.approve",
        Fn.Projects to "projects.read", Fn.ProjectDetail to "projects.read", Fn.Teams to "teams.read", Fn.Users to "people.read",
        Fn.AccessRoles to "organization.roles.manage", Fn.AuditLogs to "audit.read", Fn.Settings to "organization.settings.manage",
        Fn.Documents to "documents.read", Fn.PerformanceReviews to "performance.read",
    )

    /** Wrong types everywhere: strings for arrays, nulls, numbers for objects, nested junk. */
    private fun hostile(fn: Fn?): String = when (fn) {
        Fn.Login, Fn.CurrentUser, Fn.MyAccess -> GatewayFixtures.real(fn)
        Fn.AttendanceToday -> """{"check_in":"garbage","check_out":5,"is_on_break":"yes","total_break_seconds":"NaN","onLeave":"maybe"}"""
        Fn.AttendanceHistory, Fn.Corrections, Fn.Tasks, Fn.Meetings, Fn.Directory, Fn.Projects, Fn.Teams, Fn.Users, Fn.AuditLogs,
        Fn.Notifications, Fn.PerformanceReviews, Fn.PendingLeaveApprovals, Fn.Holidays ->
            """[null, 1, "x", {"id":null,"title":{"weird":true},"start_at":"not-a-date","status":7,"user_id":"abc","due_date":"31-31-2026","member_count":"many"}]"""
        Fn.MyLeave -> """{"balances":"none","requests":[{"days":"two","start_date":"31-31-2026"}]}"""
        Fn.Announcements -> """[1,2,3]"""
        Fn.CalendarMonth -> """{"leave":[{"startDate":"2026-10-30","endDate":"2026-01-01","userId":"x"},{"startDate":"2000-01-01","endDate":"2099-12-31"}],"holidays":{"a":1},"projectDeadlines":[{"deadline":5}]}"""
        Fn.TaskDetail, Fn.ProjectDetail -> """{"task":"nope","project":[1],"comments":{"a":1}}"""
        Fn.Documents -> """{"documents":[{"id":1,"byte_size":"huge","current_version":null}],"hasMore":"yes"}"""
        Fn.AccessRoles -> """{"roles":[{"name":null,"permissionCodes":"all"}]}"""
        Fn.Settings -> """[]"""
        else -> "\"unexpected\""
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val fn = fnFor(request)
                if (fn == null || !GatewayRoutes.matches(request.method!!, request.path!!.substringBefore("?"))) {
                    unknown += "${request.method} ${request.path}"; return v1NotFound(request)
                }
                hits.merge(fn.name, 1, Int::plus)
                val perms = if (mode == "member") memberPerms else GatewayFixtures.ownerPerms
                needs[fn]?.let { p -> if (p !in perms) { forbidden += fn.name; return v1Forbidden() } }
                val body = when (mode) {
                    "hostile" -> hostile(fn)
                    else -> GatewayFixtures.real(fn, perms = perms)
                }
                return v1(fn, Json.parseToJsonElement(body))
            }
        }
        server.start()
    }
    @After fun tearDown() { server.shutdown() }

    private fun renderAll(tag: String) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "ui-$tag"))
        val repo = Repo(ctx, api, "cache-$tag")
        val user = runBlocking { repo.login("pravahax", "PSE-00002", "x") }
        val screens: List<Pair<String, @androidx.compose.runtime.Composable () -> Unit>> = listOf(
            "home" to { HomeScreen(user) {} },
            "attendance" to { AttendanceScreen(user) },
            "tasks" to { TasksScreen(user, false) {} },
            "tasks-create" to { TasksScreen(user, true) {} },
            "calendar" to { CalendarScreen() },
            "more" to { MoreScreen(user, {}, {}) },
            "leave" to { LeaveScreen(user, false) {} },
            "leave-apply" to { LeaveScreen(user, true) {} },
            "meetings" to { MeetingsScreen() },
            "announcements" to { AnnouncementsScreen() },
            "directory" to { DirectoryScreen {} },
            "notifications" to { NotificationsScreen() },
            "projects" to { ProjectsScreen() },
            "documents" to { DocumentsScreen() },
            "performance" to { PerformanceScreen(user) },
            "teams" to { TeamsScreen() },
            "users" to { UsersScreen() },
            "access" to { AccessScreen(user) },
            "audit" to { AuditScreen() },
            "settings" to { SettingsScreen() },
            "profile" to { ProfileScreen(user, {}) },
            "login" to { LoginScreen("Notice") {} },
        )
        for ((name, screen) in screens) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            controller.get().setContent {
                PortalTheme {
                    CompositionLocalProvider(LocalRepo provides repo, LocalSnackbar provides SnackbarHostState()) { screen() }
                }
            }
            // Let network (real IO threads) complete and recompose with the results.
            repeat(12) { ShadowLooper.idleMainLooper(); Thread.sleep(40) }
            ShadowLooper.idleMainLooper()
            println("RENDER_OK $tag/$name")
            controller.pause().stop().destroy()
        }
    }

    @Test fun rendersAllScreensWithRealGatewayData() {
        mode = "realistic"; renderAll("real")
        assertEquals("requests to routes the gateway doesn't have", emptyList<String>(), unknown.toList())
        assertEquals("owner was refused something", emptyList<String>(), forbidden.toList())
        for (fn in listOf(Fn.MyAccess, Fn.AttendanceToday, Fn.Tasks, Fn.MyLeave, Fn.Directory, Fn.Projects, Fn.CalendarMonth, Fn.Notifications, Fn.Users, Fn.AuditLogs, Fn.Settings))
            assertTrue("$fn never requested", (hits[fn.name] ?: 0) > 0)
    }

    @Test fun rendersAllScreensWithHostileData() {
        mode = "hostile"; renderAll("hostile")
        assertEquals(emptyList<String>(), unknown.toList())
    }

    /** A plain employee: Home/Attendance/Tasks/Leave must not fire reads they're not allowed to make. */
    @Test fun memberNeverCallsAdminRoutesFromEverydayScreens() {
        mode = "member"
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "ui-member"))
        val repo = Repo(ctx, api, "cache-member")
        val user = runBlocking { repo.login("pravahax", "PSE-00009", "x") }
        assertTrue(!user.canApproveLeave && !user.canReadPeople && !user.canManageTasks)
        val screens: List<@androidx.compose.runtime.Composable () -> Unit> = listOf(
            { HomeScreen(user) {} }, { AttendanceScreen(user) }, { TasksScreen(user, false) {} }, { LeaveScreen(user, false) {} },
            { CalendarScreen() }, { MoreScreen(user, {}, {}) }, { AnnouncementsScreen() }, { MeetingsScreen() }, { NotificationsScreen() }, { AccessScreen(user) },
        )
        for (screen in screens) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            controller.get().setContent { PortalTheme { CompositionLocalProvider(LocalRepo provides repo, LocalSnackbar provides SnackbarHostState()) { screen() } } }
            repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(40) }
            controller.pause().stop().destroy()
        }
        assertEquals("forbidden reads fired", emptyList<String>(), forbidden.toList())
        assertEquals(emptyList<String>(), unknown.toList())
    }
}
