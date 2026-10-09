package com.pravahax.portalx.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.pravahax.portalx.data.Dates
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pravahax.portalx.data.Submit

// ======================= data loading =======================
@Stable
class Resource(
    val data: JsonElement?,
    /** Any fetch in flight (initial, background or user-triggered). */
    val loading: Boolean,
    /** Only true while a user pull-to-refresh is in flight (drives the pull indicator, not skeletons). */
    val refreshing: Boolean,
    val error: String?,
    /** True when [data] came from the on-device cache and the last network attempt failed. */
    val stale: Boolean,
    val refresh: () -> Unit,
) {
    /** First load with nothing to show yet: render skeletons. */
    val initialLoading get() = data == null && loading
}

val LocalRepo = staticCompositionLocalOf<Repo> { error("no repo") }
val LocalSessionExpired = staticCompositionLocalOf<() -> Unit> { {} }
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Loads [fn] through a [ResourceViewModel] (v0.7): cached data from Room shows immediately, and the state
 * survives rotation. Refetches when the key first appears, the user pulls to refresh, a write invalidates [fn]
 * (see Invalidation), or the app returns to the foreground after 60 s.
 */
@Composable
fun rememberResource(fn: Fn, data: JsonElement? = null, key: String = fn.name, enabled: Boolean = true): Resource {
    val repo = LocalRepo.current
    val expired = LocalSessionExpired.current
    // Keyed by the repo too, so a new session (or a test with a fresh repo) never sees another one's ViewModel.
    val vm: ResourceViewModel = viewModel(key = "res:${System.identityHashCode(repo)}:$key",
        factory = viewModelFactory { initializer { ResourceViewModel(repo, fn, data, key) } })
    val s by vm.state.collectAsState()
    LaunchedEffect(vm, enabled) { vm.start(enabled) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.onResume() } }
    LaunchedEffect(s.sessionExpired) { if (s.sessionExpired) { vm.expiryHandled(); expired() } }
    return Resource(s.data, s.loading, s.userRefresh && s.loading, s.error, s.stale) { vm.refresh() }
}

// ======================= actions =======================
/** Device haptics with graceful fallbacks (CONFIRM/REJECT need API 30). */
class Haptics(private val view: View) {
    fun tap() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun success() = view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    fun error() = view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
}

@Composable
fun rememberHaptics(): Haptics { val v = LocalView.current; return remember(v) { Haptics(v) } }

/**
 * Runs writes with: an in-flight guard per key (no double submits), friendly snackbar errors, haptics,
 * session-expiry handling, and a re-read when the outcome is uncertain (timeout after sending).
 */
@Stable
class ActionRunner internal constructor(
    private val running: SnapshotStateList<String>,
    private val launch: (String, String?, suspend () -> Any?, () -> Unit, (PortalException) -> Boolean, (() -> Unit)?) -> Unit,
) {
    fun isRunning(key: String = DEFAULT) = key in running
    val anyRunning get() = running.isNotEmpty()

    /**
     * @param onError return true if you handled the error yourself (suppresses the default snackbar).
     * @param undo when set, the success snackbar offers "Undo" and calls this if tapped (only for reversible writes).
     */
    operator fun invoke(
        key: String = DEFAULT, success: String? = null, onError: (PortalException) -> Boolean = { false },
        onDone: () -> Unit = {}, undo: (() -> Unit)? = null, block: suspend () -> Any?,
    ) = launch(key, success, block, onDone, onError, undo)

    companion object { const val DEFAULT = "default" }
}

@Composable
fun rememberAction(): ActionRunner {
    val scope = rememberCoroutineScope()
    val snack = LocalSnackbar.current
    val expired = LocalSessionExpired.current
    val haptics = rememberHaptics()
    val repo = LocalRepo.current
    val running = remember { mutableStateListOf<String>() }
    val finishedAt = remember { HashMap<String, Long>() }
    return remember(scope, snack, expired, haptics, repo) {
        ActionRunner(running) launcher@{ key, success, block, onDone, onError, undo ->
            if (key in running) return@launcher // already in flight: ignore the second tap
            // Rate limit: a repeat of the same action within 800 ms of it finishing is treated as an accidental double tap.
            if (System.currentTimeMillis() - (finishedAt[key] ?: 0L) < 800L) return@launcher
            running.add(key)
            scope.launch {
                try {
                    val outcome = block()
                    haptics.success()
                    onDone()
                    // v0.7: a write saved to the outbox says so instead of claiming it was sent (and offers no undo).
                    val queued = outcome == Submit.Queued
                    (if (queued) "Saved on this device. It'll send when you're back online." else success)?.let { msg ->
                        launch {
                            snack.currentSnackbarData?.dismiss() // newest feedback first, never a queue of stale toasts
                            val r = snack.showSnackbar(msg, actionLabel = if (undo != null && !queued) "Undo" else null, duration = SnackbarDuration.Short)
                            if (r == SnackbarResult.ActionPerformed) undo?.invoke()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PortalException) {
                    haptics.error()
                    when {
                        e.code == 401 -> expired()
                        onError(e) -> {}
                        e.uncertain -> {
                            repo.invalidate(Fn.entries.filter { it.get }) // re-read everything visible
                            launch { snack.showSnackbar("We couldn't confirm that went through. Refreshing to check…") }
                        }
                        else -> launch { snack.showSnackbar(e.message ?: "Something went wrong.") }
                    }
                } catch (e: Exception) {
                    haptics.error()
                    launch { snack.showSnackbar("Something went wrong. Please try again.") }
                } finally {
                    running.remove(key)
                    finishedAt[key] = System.currentTimeMillis()
                }
            }
        }
    }
}

// ======================= layout =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshList(
    refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Space.lg, vertical = Space.md), content: LazyListScope.() -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(Space.md), content = content)
    }
}

/** The web's `.portal-panel`: white card, slate-200 hairline, rounded-2xl, shadow-soft. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, highlighted: Boolean = false,
    padding: PaddingValues = PaddingValues(Space.lg), content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant)
    val m = modifier.fillMaxWidth().then(
        Modifier.shadow(10.dp, shape, clip = false, ambientColor = Web.ShadowInk.copy(alpha = 0.10f), spotColor = Web.ShadowInk.copy(alpha = 0.14f))
    )
    if (onClick != null) {
        Surface(onClick = onClick, modifier = m, shape = shape, color = MaterialTheme.colorScheme.surface, border = border) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Surface(modifier = m, shape = shape, color = MaterialTheme.colorScheme.surface, border = border) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Space.md), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() }, color = MaterialTheme.colorScheme.onBackground)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun Kicker(text: String, color: Color = PortalTheme.status.eyebrow) =
    Text(text.uppercase(), style = Eyebrow, color = color)

/** Rounded status pill with a leading dot, tinted like the web's badge component. */
@Composable
fun StatusChip(text: String, color: Color, dot: Boolean = true) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) { Box(Modifier.size(6.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Status → tone, matching the web's badge colours (pending amber, approved green, rejected red, cancelled slate). */
@Composable
fun statusColor(s: String?): Color {
    val p = PortalTheme.status
    return when (s?.lowercase()?.replace(' ', '_')) {
        "approved", "done", "present", "active", "completed", "checked_out" -> p.success
        "pending", "review", "late", "medium", "half_day", "on_break" -> p.warning
        "rejected", "high", "urgent", "absent", "disabled", "overdue" -> p.danger
        "in_progress", "on_leave", "checked_in", "open" -> p.info
        "holiday" -> p.accent
        else -> p.neutral
    }
}

fun pretty(s: String?): String = s?.trim()?.takeIf { it.isNotEmpty() }?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "—"

/** Empty state with a layered "halo" illustration around the icon. */
@Composable
fun EmptyState(title: String, subtitle: String, icon: ImageVector = Icons.Outlined.Inbox, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xxxl, horizontal = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)))
            Box(Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
            Box(Modifier.size(52.dp).clip(CircleShape).background(GoldBrush), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(Space.lg))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.xs))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(Space.md)); FilledTonalButton(onClick = onAction) { Text(action) } }
    }
}

@Composable
fun ErrorBanner(message: String, stale: Boolean = false, onRetry: (() -> Unit)? = null) {
    val bg = if (stale) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
    val fg = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Space.md, top = Space.xs, bottom = Space.xs, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (stale) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.sm))
            Text(message, Modifier.weight(1f).padding(vertical = Space.sm), color = fg, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun InfoBanner(message: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.sm))
            Text(message, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Thin app-wide banner shown while the device has no network. */
@Composable
fun OfflineBar(visible: Boolean) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = Space.lg, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Space.sm))
            Text("You're offline — showing saved data. Leave requests and task updates send when you're back.", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * v0.7: the offline outbox, under the top bar. Shows how many writes are waiting, and the oldest one the server
 * refused or didn't confirm, with "Send again" (same Idempotency-Key) and "Discard".
 */
@Composable
fun OutboxBar() {
    val repo = LocalRepo.current
    val items by repo.outbox.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val pending = items.count { it.state == com.pravahax.portalx.data.db.OutboxItem.STATE_PENDING }
    val problem = items.firstOrNull { it.state != com.pravahax.portalx.data.db.OutboxItem.STATE_PENDING }
    AnimatedVisibility(pending > 0 || problem != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (pending > 0) InfoBanner("$pending change${if (pending == 1) "" else "s"} waiting to send. ${if (pending == 1) "It goes" else "They go"} out automatically once you're online.")
            problem?.let { p ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(start = Space.md, end = Space.xs, top = Space.sm)) {
                        Text("${com.pravahax.portalx.data.Outbox.label(p.fn)} didn't send", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(p.lastError ?: "Portal One refused it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row(Modifier.align(Alignment.End)) {
                            TextButton(onClick = { scope.launch { repo.discard(p.id) } }) { Text("Discard") }
                            TextButton(onClick = { scope.launch { repo.resend(p.id) } }) { Text("Send again") }
                        }
                    }
                }
            }
        }
    }
}

/** Confirmation for irreversible actions. */
@Composable
fun ConfirmDialog(
    title: String, text: String, confirm: String, destructive: Boolean = false,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = { onDismiss(); onConfirm() },
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                else ButtonDefaults.buttonColors(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** Shimmering placeholder (replaces spinners for first loads). */
@Composable
fun Skeleton(height: Dp = 72.dp, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.large) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val m = modifier.fillMaxWidth().height(height).clip(shape).semantics { contentDescription = "Loading" }
    if (LocalReduceMotion.current) { Box(m.background(base)); return } // static placeholder when animations are off
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-600f, 1400f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    val brush = Brush.linearGradient(listOf(base, Color.White.copy(alpha = 0.75f), base), start = Offset(x, 0f), end = Offset(x + 500f, 160f))
    Box(m.background(brush))
}

@Composable
fun SkeletonList(count: Int = 3, height: Dp = 84.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) { repeat(count) { Skeleton(height) } }
}

private val avatarTones = listOf(
    listOf(Web.Gold600, Web.Gold400), listOf(Web.Gold800, Web.Gold500), listOf(Web.Slate700, Web.Slate500), listOf(Web.HeroTop, Web.Gold700),
)

/** Initials avatar on a warm gradient picked deterministically from the name. */
@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { "?" }
    val tone = avatarTones[(name.hashCode() and Int.MAX_VALUE) % avatarTones.size]
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(tone)), contentAlignment = Alignment.Center) {
        // Initials live in a fixed-size circle: they must not grow with the system font size.
        CappedFontScale(1.0f) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold,
            style = if (size >= 64.dp) MaterialTheme.typography.headlineSmall.copy(fontFamily = Playfair) else MaterialTheme.typography.labelLarge) }
    }
}

@Composable
fun ListRow(
    title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.md, horizontal = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(Space.md)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(Space.sm)); trailing() }
    }
}

@Composable
fun KeyValueRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.sm)) {
        Text(k, Modifier.width(112.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Button content that swaps to a small progress ring while busy (keeps the button size stable). */
@Composable
fun BusyLabel(busy: Boolean, text: String, color: Color = LocalContentColor.current) {
    Box(contentAlignment = Alignment.Center) {
        Text(text, modifier = Modifier.alpha(if (busy) 0f else 1f))
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
    }
}


// ======================= safe external intents =======================
/** Opens an http(s) link in the browser; never crashes if no app can handle it. */
fun Context.openWeb(url: String?): Boolean {
    val safe = com.pravahax.portalx.data.Validate.safeWebUrl(url) ?: return false
    return startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE))
}

fun Context.startSafely(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

// ======================= time =======================
/** Wall-clock ticker that pauses while the app is in the background. */
@Composable
fun rememberNow(periodMs: Long = 1000L): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, periodMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { state.longValue = System.currentTimeMillis(); delay(periodMs - System.currentTimeMillis() % periodMs) }
        }
    }
    return state
}

fun parseInstant(s: String?) = Dates.instant(s)
fun fmtTime(s: String?) = Dates.time(s)
fun fmtDate(s: String?) = Dates.date(s)
fun fmtShortDate(s: String?) = Dates.shortDate(s)
fun fmtDateTime(s: String?) = Dates.dateTime(s)
fun fmtDuration(seconds: Long) = Dates.duration(seconds)
fun greeting() = Dates.greeting()


// ======================= accessibility & motion =======================
/** True when the user turned animations off (Settings › Accessibility › Remove animations / animator scale 0). */
val LocalReduceMotion = staticCompositionLocalOf { false }

fun Context.reduceMotion(): Boolean = runCatching {
    android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/**
 * Caps font scaling for content inside a FIXED-size container (round check-in button, calendar day cells) so 200 %
 * system font size can't clip it. Everything else scales freely.
 */
@Composable
fun CappedFontScale(max: Float = 1.5f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (d.fontScale <= max) content()
    else CompositionLocalProvider(LocalDensity provides Density(d.density, max), content = content)
}

/** The web's light auth backdrop: radial-gradient(circle at 100% 0, #be882e1f, transparent 42%) over parchment. */
fun Modifier.goldGlow(): Modifier = drawBehind {
    val r = kotlin.math.hypot(size.width, size.height) * 0.42f
    if (r > 0f) drawRect(Brush.radialGradient(listOf(GlowGold, Color.Transparent), center = Offset(size.width, 0f), radius = r))
}

/**
 * Password-manager / Google autofill for a Compose text field (Compose 1.7 API): registers an AutofillNode,
 * keeps its bounds current and asks the framework to fill when the field gains focus.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.autofill(types: List<androidx.compose.ui.autofill.AutofillType>, onFill: (String) -> Unit): Modifier = composed {
    val autofill = androidx.compose.ui.platform.LocalAutofill.current
    val tree = androidx.compose.ui.platform.LocalAutofillTree.current
    val node = remember(types) { androidx.compose.ui.autofill.AutofillNode(autofillTypes = types, onFill = onFill) }
    DisposableEffect(node) { tree += node; onDispose { tree.children.remove(node.id) } }
    this.onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { f ->
            if (autofill != null && node.boundingBox != null) {
                if (f.isFocused) autofill.requestAutofillForNode(node) else autofill.cancelAutofillForNode(node)
            }
        }
}

/** Pill search box with clear button; the keyboard's Search key just hides the keyboard (results filter live). */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    OutlinedTextField(value, { onChange(it.take(80)) }, Modifier.fillMaxWidth(), placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(50),
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(androidx.compose.material.icons.Icons.Outlined.Close, "Clear search") } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
}
