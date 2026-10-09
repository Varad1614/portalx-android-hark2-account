package com.pravahax.portalx

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.data.SessionUser
import com.pravahax.portalx.data.Validate
import com.pravahax.portalx.data.obj
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalException
import com.pravahax.portalx.net.RefreshResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pravahax.portalx.net.Connectivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.pravahax.portalx.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var repo: Repo
    @Inject lateinit var connectivity: Connectivity

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Light system bars ALWAYS (dark icons on parchment), even when the phone is in dark mode.
        // enableEdgeToEdge()'s default "auto" style flips to light icons in system dark mode, which is what made v0.2.0 look dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        // Privacy: the recents/overview thumbnail is blanked (attendance, directory phone numbers, etc. stay private).
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        com.pravahax.portalx.push.DeepLink.offer(intent)
        val reduceMotion = reduceMotion()
        setContent {
            PortalTheme {
                CompositionLocalProvider(LocalRepo provides repo, LocalReduceMotion provides reduceMotion) {
                    val online by connectivity.online.collectAsState()
                    PortalApp(repo, online)
                }
            }
        }
        // Tapjacking: drop touches that arrive while another app's window covers ours (overlay attacks on
        // Check out / Approve / Sign in). Applied to the root so every button in the window is protected.
        window.decorView.filterTouchesWhenObscured = true
        findViewById<android.view.View>(android.R.id.content)?.filterTouchesWhenObscured = true
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.pravahax.portalx.push.DeepLink.offer(intent)
    }

    // v0.8: the widget shows what this session just loaded.
    override fun onStop() {
        super.onStop()
        com.pravahax.portalx.widget.TodayWidget.refresh(this)
    }
}

@Composable
/** @param startRoute first tab to show (tests/screenshots); users always start on Home. */
fun PortalApp(repo: Repo, online: Boolean = true, startRoute: String = "home") {
    // v0.7: auth lives in a ViewModel (survives rotation). Keyed by repo so a test with a fresh repo gets a fresh one.
    val vm: AppViewModel = viewModel(key = "app:${System.identityHashCode(repo)}", factory = viewModelFactory { initializer { AppViewModel(repo) } })
    val auth by vm.auth.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val reduce = LocalReduceMotion.current

    AnimatedContent(auth, transitionSpec = { if (reduce) EnterTransition.None togetherWith ExitTransition.None else fadeIn(tween(320)) togetherWith fadeOut(tween(200)) },
        contentKey = { it::class }, label = "auth") { a ->
        when (a) {
            Auth.Checking -> BrandSplash()
            is Auth.Unavailable -> UnavailableScreen(a.message, onRetry = { vm.retry() }, onSignOut = { vm.signOut(null) })
            is Auth.SignedOut -> { SecureWindow(); LoginScreen(notice = a.notice) { vm.signedIn(it) } }
            is Auth.SignedIn ->
                if (a.user.mustChangePassword) {
                    SecureWindow()
                    ChangePasswordScreen(onDone = { u -> vm.userUpdated(u) }, onSignOut = { vm.signOut(null) })
                } else {
                    // Screens' ViewModels (and the NavController's) live in this session's store. It is captured once,
                    // so an exit animation never sees the next session's store, and cleared when this shell leaves for
                    // good (sign-out, back out of the app), but kept across rotation.
                    val owner = remember { vm.session }
                    val activity = LocalContext.current as? Activity
                    DisposableEffect(owner) { onDispose { if (activity?.isChangingConfigurations != true) owner.viewModelStore.clear() } }
                    CompositionLocalProvider(LocalSessionExpired provides { vm.signOut(AppViewModel.EXPIRED) }, LocalViewModelStoreOwner provides owner) {
                        MainShell(a.user, online, { vm.refreshMe() }, startRoute) { vm.signOut(null) }
                    }
                }
        }
    }
}

/** FLAG_SECURE while a credential screen is visible: no screenshots, no recents thumbnail of the password. */
@Composable
private fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Light brand splash (matches the system splash: parchment + gold mark). */
@Composable
private fun BrandSplash() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).goldGlow(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(com.pravahax.portalx.core.ui.R.drawable.logo_mark_gold), "PortalX", Modifier.width(132.dp))
            Spacer(Modifier.height(Space.xxl))
            LinearProgressIndicator(Modifier.width(96.dp), color = Web.Primary, trackColor = Web.Slate200)
        }
    }
}

@Composable
private fun UnavailableScreen(message: String, onRetry: () -> Unit, onSignOut: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).goldGlow().systemBarsPadding().verticalScroll(rememberScrollState()).padding(Space.xxl),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Space.xxxl))
        Image(painterResource(com.pravahax.portalx.core.ui.R.drawable.logo_mark_gold), "PortalX", Modifier.width(96.dp))
        Spacer(Modifier.height(Space.xxl))
        CircleIcon(Icons.Outlined.CloudOff, MaterialTheme.colorScheme.primary, 56.dp)
        Spacer(Modifier.height(Space.lg))
        Kicker("Connection")
        Spacer(Modifier.height(Space.sm))
        Text("We can't reach Portal One", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.sm))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.xxl))
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 52.dp).fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Try again") }
        TextButton(onClick = onSignOut, modifier = Modifier.padding(top = Space.sm)) { Text("Sign in with a different account") }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)
private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("attendance", "Attendance", Icons.Outlined.Fingerprint, Icons.Filled.Fingerprint),
    Tab("tasks", "Tasks", Icons.Outlined.TaskAlt, Icons.Filled.TaskAlt),
    Tab("calendar", "Calendar", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Tab("more", "More", Icons.Outlined.GridView, Icons.Filled.GridView),
)
private val titles = mapOf(
    "home" to "", "attendance" to "Attendance", "tasks" to "Tasks", "calendar" to "Calendar", "more" to "More",
    "leave" to "Leave", "meetings" to "Meetings", "announcements" to "Announcements", "directory" to "Directory", "profile" to "Profile", "password" to "Security",
    "notifications" to "Notifications", "projects" to "Projects", "documents" to "Documents", "performance" to "Performance", "teams" to "Teams",
    "users" to "Users", "access" to "Access control", "approvals" to "Approvals", "insights" to "Insights", "search" to "Search", "audit" to "Audit logs", "settings" to "Company settings",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(user: SessionUser, online: Boolean, refreshMe: suspend () -> Unit, startRoute: String, onLogout: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: startRoute
    val isTab = tabs.any { it.route == route }
    val snackbar = remember { SnackbarHostState() }
    val shellScope = rememberCoroutineScope()
    var createTask by remember { mutableStateOf(false) }
    var applyLeave by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<com.pravahax.portalx.data.model.Person?>(null) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    fun go(r: String) {
        if (r == route) return
        if (tabs.any { it.route == r }) nav.navigate(r) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true
        } else nav.navigate(r) { launchSingleTop = true }
    }
    val askLogout = { confirmLogout = true }

    // v0.8: register for push once per signed-in user, ask for the Android 13+ notification permission, and
    // follow a tapped notification's deep link (allowlisted in Push.ROUTES; approvals only for approvers).
    val ctx = LocalContext.current
    val repo = LocalRepo.current
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(user.userId) {
        com.pravahax.portalx.push.Push.register(ctx, repo)
        if (Build.VERSION.SDK_INT >= 33 && com.pravahax.portalx.push.Push.available(ctx) &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    val deepLink by com.pravahax.portalx.push.DeepLink.route.collectAsState()
    LaunchedEffect(deepLink) {
        val r = deepLink ?: return@LaunchedEffect
        com.pravahax.portalx.push.DeepLink.consume()
        go(if (r == "approvals" && !(user.canApproveLeave || user.canApproveCorrections)) "notifications" else r)
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalOnline provides online) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            if (route == "home") Image(painterResource(com.pravahax.portalx.core.ui.R.drawable.logo_mark_gold), "PortalX", Modifier.height(24.dp))
                            else Text(titles[route] ?: "", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { heading() })
                        },
                        navigationIcon = { if (!isTab) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                        actions = {
                            if (route == "home") {
                                IconButton(onClick = { go("search") }) { Icon(Icons.Outlined.Search, "Search") }
                                IconButton(onClick = { go("notifications") }) { Icon(Icons.Outlined.Notifications, "Notifications") }
                                IconButton(onClick = { go("profile") }, modifier = Modifier.semantics { contentDescription = "Your profile" }) { Avatar(user.name, 32.dp) }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.surface),
                    )
                    OfflineBar(!online)
                    OutboxBar()
                }
            },
            bottomBar = {
                AnimatedVisibility(isTab, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // At very large font sizes five labels can't fit: show only the selected one (icons keep labels for TalkBack).
                        val bigFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                            tabs.forEach { t ->
                                val sel = route == t.route
                                NavigationBarItem(
                                    selected = sel, onClick = { haptics.tap(); go(t.route) },
                                    icon = { Icon(if (sel) t.selectedIcon else t.icon, if (bigFont && !sel) t.label else null) },
                                    label = { CappedFontScale(1.3f) { Text(t.label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, letterSpacing = 0.sp), maxLines = 1, softWrap = false) } },
                                    alwaysShowLabel = !bigFont,
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                val fab: @Composable (String, () -> Unit) -> Unit = { label, onClick ->
                    ExtendedFloatingActionButton(onClick = onClick, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(label) },
                        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.medium)
                }
                when {
                    route == "tasks" && user.canManageTasks -> fab("New task") { createTask = true }
                    route == "leave" -> fab("Apply") { applyLeave = true }
                    else -> {}
                }
            },
            snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, shape = MaterialTheme.shapes.medium) } },
            containerColor = MaterialTheme.colorScheme.background,
        ) { pad ->
            val reduce = LocalReduceMotion.current
            NavHost(nav, startRoute, Modifier.padding(pad).consumeWindowInsets(pad),
                enterTransition = { if (reduce) EnterTransition.None else fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 14 } },
                exitTransition = { if (reduce) ExitTransition.None else fadeOut(tween(160)) },
                popEnterTransition = { if (reduce) EnterTransition.None else fadeIn(tween(220)) },
                popExitTransition = { if (reduce) ExitTransition.None else fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 14 } }) {
                composable("home") { HomeScreen(user, ::go) }
                composable("attendance") { AttendanceScreen(user) { go("insights") } }
                composable("tasks") { TasksScreen(user, createTask) { createTask = false } }
                composable("calendar") { CalendarScreen() }
                composable("more") { MoreScreen(user, ::go, askLogout, refreshMe) }
                composable("leave") { LeaveScreen(user, applyLeave) { applyLeave = false } }
                composable("approvals") { ApprovalsScreen(user) }
                composable("insights") { InsightsScreen() }
                composable("search") { SearchScreen(user, ::go) { person = it } }
                composable("meetings") { MeetingsScreen() }
                composable("announcements") { AnnouncementsScreen() }
                composable("directory") { DirectoryScreen { person = it } }
                composable("notifications") { NotificationsScreen() }
                composable("projects") { ProjectsScreen() }
                composable("documents") { DocumentsScreen() }
                composable("performance") { PerformanceScreen(user) }
                composable("teams") { TeamsScreen() }
                composable("users") { UsersScreen() }
                composable("access") { AccessScreen(user) }
                composable("audit") { AuditScreen() }
                composable("settings") { SettingsScreen() }
                composable("profile") { ProfileScreen(user, askLogout, refreshMe) { go("password") } }
                composable("password") {
                    SecureWindow()
                    ChangePasswordScreen(embedded = true, onSignOut = onLogout, onDone = { _ ->
                        nav.popBackStack()
                        shellScope.launch { snackbar.showSnackbar("Password updated.") }
                    })
                }
            }
        }
        person?.let { PersonSheet(it) { person = null } }
        if (confirmLogout) ConfirmDialog(
            "Sign out?", "You'll need your workspace, User ID and password to sign in again. Saved data on this device will be cleared.",
            "Sign out", destructive = true, onConfirm = onLogout, onDismiss = { confirmLogout = false },
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ChangePasswordScreen(onDone: (SessionUser) -> Unit, onSignOut: () -> Unit, embedded: Boolean = false) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = rememberHaptics()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val transform = if (show) VisualTransformation.None else PasswordVisualTransformation()

    fun submit() {
        if (busy) return
        Validate.newPassword(current, next, confirm)?.let { error = it; haptics.error(); return }
        focus.clearFocus(); busy = true; error = null
        scope.launch {
            try {
                val r = repo.act(Fn.ChangePassword, buildJsonObject { put("currentPassword", current); put("newPassword", next) })
                // The web uses the returned user; fall back to re-reading it.
                val u = r.obj()?.takeIf { it.containsKey("userId") || it.containsKey("user_id") || it.containsKey("name") }
                    ?.also { repo.acceptUser(it) }?.let { SessionUser(it) } ?: repo.me()
                current = ""; next = ""; confirm = ""
                if (u == null) { error = "Password changed. Please sign in again."; onSignOut() }
                else if (u.mustChangePassword) error = "Password updated, but the server still asks for a change. Please try again in a moment."
                else { haptics.success(); onDone(u) }
            } catch (e: CancellationException) { throw e
            } catch (e: PortalException) { haptics.error(); if (e.code == 401) onSignOut() else error = e.message ?: "Could not change password."
            } catch (e: Exception) { haptics.error(); error = "Could not change password. Please try again."
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).goldGlow().verticalScroll(rememberScrollState())
            .then(if (embedded) Modifier else Modifier.systemBarsPadding()).imePadding().padding(Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        if (!embedded) {
            Spacer(Modifier.height(Space.lg))
            Image(painterResource(com.pravahax.portalx.core.ui.R.drawable.logo_mark_gold), null, Modifier.height(28.dp))
            Spacer(Modifier.height(Space.sm))
        }
        Kicker("Account security")
        Text(if (embedded) "Change password" else "Set a new password", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text(if (embedded) "You'll stay signed in on this device." else "For your security, choose your own password before continuing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(error != null) { ErrorBanner(error ?: "") }
        val eye: @Composable () -> Unit = {
            IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (show) "Hide passwords" else "Show passwords") }
        }
        fun kb(last: Boolean) = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = if (last) ImeAction.Done else ImeAction.Next)
        val next_ = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { submit() })
        OutlinedTextField(current, { current = it.take(256); error = null }, Modifier.fillMaxWidth().autofill(listOf(AutofillType.Password)) { current = it },
            label = { Text("Current or temporary password") }, visualTransformation = transform, singleLine = true,
            keyboardOptions = kb(false), keyboardActions = next_, trailingIcon = eye, shape = MaterialTheme.shapes.medium)
        OutlinedTextField(next, { next = it.take(256); error = null }, Modifier.fillMaxWidth().autofill(listOf(AutofillType.NewPassword)) { next = it },
            label = { Text("New password") }, visualTransformation = transform, singleLine = true, keyboardOptions = kb(false), keyboardActions = next_,
            shape = MaterialTheme.shapes.medium)
        OutlinedTextField(confirm, { confirm = it.take(256); error = null }, Modifier.fillMaxWidth(),
            label = { Text("Confirm new password") }, visualTransformation = transform, singleLine = true, keyboardOptions = kb(true), keyboardActions = next_,
            shape = MaterialTheme.shapes.medium)
        // Live checklist: users see the rules being met instead of discovering them on submit.
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Requirement("At least 12 characters", next.length >= 12)
            Requirement("Different from your current password", next.isNotEmpty() && next != current)
            Requirement("Both new passwords match", confirm.isNotEmpty() && next == confirm)
        }
        Spacer(Modifier.height(Space.xs))
        Button(enabled = !busy, onClick = { submit() }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = MaterialTheme.shapes.medium) {
            BusyLabel(busy, "Update password")
        }
        if (!embedded) TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Sign out instead") }
    }
}

@Composable
private fun Requirement(text: String, met: Boolean) {
    val c = if (met) PortalTheme.status.success else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(if (met) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (met) "Done" else "Not yet", tint = c, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.sm))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c)
    }
}
