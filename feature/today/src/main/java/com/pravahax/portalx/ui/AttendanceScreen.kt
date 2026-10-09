package com.pravahax.portalx.ui

import androidx.annotation.VisibleForTesting
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pravahax.portalx.data.*
import com.pravahax.portalx.data.model.*
import com.pravahax.portalx.location.BestEffortLocation
import com.pravahax.portalx.location.LocationSource
import com.pravahax.portalx.location.PlatformLocationSource
import com.pravahax.portalx.media.Selfie
import com.pravahax.portalx.net.Fn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- selfie handling ----------
internal fun selfieDir(ctx: Context): File = File(ctx.cacheDir, "selfies").apply { mkdirs() }

internal fun newSelfieFile(ctx: Context): File {
    val dir = selfieDir(ctx)
    dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() } // sweep leftovers
    return File(dir, "selfie-${System.currentTimeMillis()}.jpg")
}

/**
 * Turns the camera capture into the upload file (≤ 1280 px, EXIF orientation applied, JPEG q80, no EXIF).
 * Runs off the main thread; always deletes the original capture. The returned file is deleted by Repo.punch
 * once the upload finishes.
 */
internal suspend fun prepareSelfie(ctx: Context, capture: File): File = withContext(Dispatchers.Default) {
    val out = File(selfieDir(ctx), "upload-${System.currentTimeMillis()}.jpg")
    try {
        Selfie.process(capture, out, maxEdge = 960) // v0.9.3: sent as a base64 JSON data URL like the web (≤ 960 px)
    } catch (e: Throwable) {
        out.delete(); throw e
    } finally {
        capture.delete()
    }
}

/** The small post-punch hint, driven by the server's meta.locationRecorded. */
@VisibleForTesting
fun locationHint(mode: String, locationRecorded: Boolean): String = when {
    locationRecorded -> "Location recorded"
    mode == "in" -> "Checked in without location"
    else -> "Checked out without location"
}



/** TakePicture that asks camera apps to open the FRONT camera (honoured by most OEM cameras; harmless otherwise). */
private class TakeSelfie : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri) = super.createIntent(context, input).apply {
        putExtra("android.intent.extras.CAMERA_FACING", 1)
        putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
        putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
    }
}

/**
 * Check-in / check-out / break actions, shared by the Attendance tab and the one-tap card on Home.
 * Owns the selfie camera round-trip (process-death safe), the check-out confirmation and in-flight guards.
 */
@Stable
class AttendanceController internal constructor(
    val busy: Boolean,
    val checkIn: () -> Unit,
    val checkOut: () -> Unit,
    val startBreak: () -> Unit,
    val endBreak: () -> Unit,
)

@Composable
fun rememberAttendanceController(
    onAlreadyDone: () -> Unit = {},
    locationSource: LocationSource? = null,
): AttendanceController {
    val ctx = LocalContext.current
    val repo = LocalRepo.current
    val snack = LocalSnackbar.current
    // v0.9.1: the punch (photo prep, location, upload) runs in the activity-wide scope: leaving the tab or scrolling
    // the Home card away used to cancel it silently before it was sent.
    val localScope = rememberCoroutineScope()
    val scope = LocalAppScope.current ?: localScope
    val act = rememberAction(longLived = true)
    val location = remember(locationSource) { locationSource ?: PlatformLocationSource(ctx.applicationContext) }
    // Saveable: the camera app often causes our process to be killed; the result must still be applied.
    var pendingMode by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    // Location rationale / system permission prompt in progress for this mode ("in" | "out").
    var rationaleFor by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionFor by rememberSaveable { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var confirmCheckout by rememberSaveable { mutableStateOf(false) }
    var launchedAt by remember { mutableLongStateOf(0L) }
    val busy = processing || act.isRunning("attendance")

    // v0.9: the in-app liveness camera for this mode ("in" | "out"), and the file it captures to.
    var livenessFor by rememberSaveable { mutableStateOf<String?>(null) }
    var livenessPath by rememberSaveable { mutableStateOf<String?>(null) }
    val cancelled = { mode: String -> scope.launch { snack.showSnackbar(if (mode == "in") "Check-in cancelled — no selfie taken." else "Check-out cancelled — no selfie taken.") } }

    fun submit(mode: String, file: File, liveness: LivenessResult) {
        processing = true
        scope.launch {
            // Location is best-effort metadata: fetched in parallel with the photo, ≤ 5 s, null on denial/timeout.
            val fixJob = async { BestEffortLocation.fix(location) }
            val selfie = try { prepareSelfie(ctx, file) } catch (e: Throwable) { null }
            if (selfie == null && mode == "in") {
                fixJob.cancel(); processing = false
                snack.showSnackbar("Couldn't read the photo. Please try again."); return@launch
            }
            val fix = try { fixJob.await() } catch (e: Exception) { null } finally { processing = false }
            val now = ZonedDateTime.now(AppZone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
            var recorded = false
            val ran = booleanArrayOf(false)
            act("attendance", null,
                onError = { e ->
                    // Already done on another device / a retried request: just show the real state.
                    if (e.message?.contains("already", true) == true) { onAlreadyDone(); scope.launch { snack.showSnackbar(e.message ?: "") }; true } else false
                },
                onDone = {
                    val msg = if (mode == "in") "Checked in at $now. Have a great day!" else "Checked out at $now. See you tomorrow!"
                    scope.launch {
                        snack.currentSnackbarData?.dismiss()
                        snack.showSnackbar("$msg\n${locationHint(mode, recorded)}")
                    }
                }) {
                ran[0] = true
                recorded = repo.punch(if (mode == "in") Fn.CheckIn else Fn.CheckOut, selfie, fix, liveness)
            }
            // The runner ignores a duplicate tap; don't leave the processed photo behind in that case.
            if (!act.isRunning("attendance") && !ran[0]) selfie?.delete()
        }
    }

    // Fallback when liveness can't run here: the system camera app (sent as liveness=unavailable).
    val camera = rememberLauncherForActivityResult(TakeSelfie()) { ok ->
        val mode = pendingMode; val path = pendingPath
        pendingMode = null; pendingPath = null
        val file = path?.let { File(it) }
        if (!ok || mode == null || file == null || !file.exists()) {
            file?.delete()
            if (!ok && mode != null) cancelled(mode)
            return@rememberLauncherForActivityResult
        }
        submit(mode, file, LivenessResult(false))
    }

    fun openSystemCamera(mode: String) {
        try {
            val f = newSelfieFile(ctx)
            val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            pendingMode = mode; pendingPath = f.path
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingMode = null; pendingPath = null
            scope.launch { snack.showSnackbar("No camera app is available on this device.") }
        } catch (e: Exception) {
            pendingMode = null; pendingPath = null
            scope.launch { snack.showSnackbar("Couldn't open the camera. Please try again.") }
        }
    }

    fun startLiveness(mode: String) { livenessPath = newSelfieFile(ctx).path; livenessFor = mode }
    // The app declares CAMERA (for the liveness camera), so Android also requires it for the system camera intent.
    fun proceed(mode: String) = if (LivenessSupport.available(ctx)) startLiveness(mode) else openSystemCamera(mode)
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = permissionFor; permissionFor = null
        if (mode != null) {
            if (granted) proceed(mode)
            else scope.launch {
                // After two denials Android stops showing the prompt, so the way out must be a button, not advice.
                val r = snack.showSnackbar("PortalX needs the camera for your ${if (mode == "in") "check-in" else "check-out"} selfie.", actionLabel = "Settings", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    /** v0.9: liveness check in-app when the device supports it, otherwise the system camera. */
    fun openCamera(mode: String) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) proceed(mode)
        else { permissionFor = mode; try { cameraPermission.launch(android.Manifest.permission.CAMERA) } catch (e: Exception) { permissionFor = null; proceed(mode) } }
    }

    livenessFor?.let { mode ->
        val out = File(livenessPath ?: newSelfieFile(ctx).path.also { livenessPath = it })
        LivenessDialog(out) { r ->
            livenessFor = null; livenessPath = null
            when (r) {
                is LivenessOutcome.Passed -> submit(mode, r.file, LivenessResult(true, r.challenges))
                LivenessOutcome.Cancelled -> { out.delete(); cancelled(mode) }
                LivenessOutcome.Unavailable -> { out.delete(); openSystemCamera(mode) }
            }
        }
    }

    // Whatever the user answers, the punch continues to the camera: location never blocks check-in.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val mode = permissionFor; permissionFor = null
        if (mode != null) openCamera(mode)
    }

    fun capture(mode: String) {
        if (busy) return
        // A double tap must not open the camera twice (the second launch would orphan the first file).
        if (System.currentTimeMillis() - launchedAt < 1500L) return
        launchedAt = System.currentTimeMillis()
        if (BestEffortLocation.shouldAsk(ctx)) { rationaleFor = mode; return }
        openCamera(mode)
    }

    rationaleFor?.let { mode ->
        val skip = { BestEffortLocation.markAsked(ctx); rationaleFor = null; openCamera(mode) }
        AlertDialog(
            onDismissRequest = skip,
            icon = { Icon(Icons.Outlined.LocationOn, null) },
            title = { Text("Add your location to attendance?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text("PortalX can attach your location to each check-in and check-out so your attendance record shows where you clocked in. " +
                    "It's read once, only when you tap Check in or Check out — never in the background. " +
                    "If you'd rather not, you can still check in; it just won't include a location.", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Button(onClick = {
                    BestEffortLocation.markAsked(ctx); rationaleFor = null; permissionFor = mode
                    try { permissions.launch(BestEffortLocation.PERMISSIONS) } catch (e: Exception) { permissionFor = null; openCamera(mode) }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = skip) { Text("Not now") } },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
    if (confirmCheckout) ConfirmDialog(
        "Check out for today?", "This ends your working day at ${ZonedDateTime.now(AppZone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))} IST and can't be undone from the app. You'll take a quick selfie next.",
        "Take selfie & check out", onConfirm = { capture("out") }, onDismiss = { confirmCheckout = false },
    )
    return AttendanceController(
        busy = busy,
        checkIn = { capture("in") },
        checkOut = { if (!busy) confirmCheckout = true },
        startBreak = { act("attendance", "Break started. Enjoy!") { repo.act(Fn.StartBreak, buildJsonObject { put("breakType", "lunch") }) } },
        endBreak = { act("attendance", "Welcome back!") { repo.act(Fn.EndBreak) } },
    )
}

/** Hours worked per row (check-out − check-in − breaks), summed for the current Mon–Sun week in IST. */
@VisibleForTesting
fun weekWorkedSeconds(rows: List<AttendanceRecord>, today: LocalDate = Dates.today()): Long {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    return rows.sumOf { r ->
        val d = Dates.day(r.date) ?: Dates.day(r.checkIn)
        val a = parseInstant(r.checkIn); val b = parseInstant(r.checkOut)
        if (d == null || d.isBefore(monday) || d.isAfter(today) || a == null || b == null || !b.isAfter(a)) 0L
        else (java.time.Duration.between(a, b).seconds - r.breakSeconds).coerceIn(0L, 24 * 3600L)
    }
}

@Composable
fun AttendanceScreen(user: SessionUser, onInsights: () -> Unit = {}) {
    val repo = LocalRepo.current
    val online = LocalOnline.current
    val today = rememberResource(Endpoint.AttendanceToday)
    val history = rememberResource(Endpoint.AttendanceHistory)
    val corrections = rememberResource(Endpoint.Corrections, enabled = user.canApproveCorrections)
    val act = rememberAction()
    var showCorrection by rememberSaveable { mutableStateOf(false) }
    var rejectTarget by remember { mutableStateOf<Correction?>(null) }
    val refreshAll = { today.refresh(); history.refresh(); corrections.refresh() }
    val ctl = rememberAttendanceController(onAlreadyDone = refreshAll)
    val busy = ctl.busy

    val t = today.data
    val checkIn = t?.checkIn; val checkOut = t?.checkOut
    val onBreak = t?.onBreak == true
    val breakSec = t?.breakSeconds ?: 0L

    RefreshList(today.refreshing || history.refreshing || corrections.refreshing, refreshAll) {
        today.error?.let { item(key = "err") { ErrorBanner(it, today.stale, refreshAll) } }
        item(key = "status") {
            SectionCard(padding = PaddingValues(Space.xl)) {
                Kicker("Today's status")
                Spacer(Modifier.height(Space.md))
                val state = when {
                    today.initialLoading -> "loading"
                    t == null -> "unknown"
                    t.onLeave -> "leave"
                    checkIn == null -> "out"
                    checkOut == null -> if (onBreak) "break" else "in"
                    else -> "done"
                }
                val reduce = LocalReduceMotion.current
                AnimatedContent(state, transitionSpec = { if (reduce) EnterTransition.None togetherWith ExitTransition.None else fadeIn() togetherWith fadeOut() }, label = "att") { s ->
                    when (s) {
                        "loading" -> Column(verticalArrangement = Arrangement.spacedBy(Space.md)) { Skeleton(40.dp); Skeleton(150.dp, Modifier.fillMaxWidth(0.5f).align(Alignment.CenterHorizontally), CircleShape) }
                        "unknown" -> Text("Today's attendance couldn't be loaded. Pull down to retry.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        "leave" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircleIcon(Icons.Outlined.BeachAccess, PortalTheme.status.warning, 48.dp); Spacer(Modifier.width(Space.md))
                            Column {
                                Text("On approved leave", style = MaterialTheme.typography.titleLarge)
                                Text("You're on ${t?.leaveType ?: "leave"} today. Check-in is disabled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        "out" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            LiveClock()
                            Spacer(Modifier.height(Space.xl))
                            BigRoundButton(if (online) "Check in" else "Offline", Icons.Outlined.Fingerprint, busy, enabled = online) { ctl.checkIn() }
                            Spacer(Modifier.height(Space.md))
                            Text(if (online) "Tap, then take a quick selfie to confirm." else "Reconnect to check in.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        "in", "break" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            StatusChip(if (s == "break") "On break" else "Checked in at ${fmtTime(checkIn)}", if (s == "break") PortalTheme.status.warning else PortalTheme.status.success)
                            Spacer(Modifier.height(Space.md))
                            WorkedTimer(checkIn, breakSec, s == "break", t?.lastBreakStart)
                            Spacer(Modifier.height(Space.xl))
                            if (s == "break") {
                                Button(onClick = ctl.endBreak, enabled = !busy && online, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(Space.sm)); BusyLabel(busy, "Resume work")
                                }
                            } else Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                                OutlinedButton(onClick = ctl.startBreak, enabled = !busy && online, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                                    Icon(Icons.Outlined.Coffee, null); Spacer(Modifier.width(6.dp)); Text("Break")
                                }
                                Button(onClick = ctl.checkOut, enabled = !busy && online, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                                    Icon(Icons.AutoMirrored.Outlined.Logout, null); Spacer(Modifier.width(6.dp)); BusyLabel(busy, "Check out")
                                }
                            }
                            if (!online) { Spacer(Modifier.height(Space.sm)); Text("Reconnect to update your attendance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircleIcon(Icons.Outlined.TaskAlt, PortalTheme.status.success, 48.dp); Spacer(Modifier.width(Space.md))
                            Column {
                                Text("Day complete", style = MaterialTheme.typography.titleLarge)
                                Text("${fmtTime(checkIn)} – ${fmtTime(checkOut)}" + (if (breakSec > 0) " · break ${fmtDuration(breakSec)}" else ""),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item(key = "corr-btn") {
            OutlinedButton(onClick = { showCorrection = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Outlined.EditCalendar, null); Spacer(Modifier.width(Space.sm)); Text("Missed a punch? Request a correction")
            }
        }

        val pendingCorr = corrections.data.orEmpty().filter { it.status == "pending" }
        if (user.canApproveCorrections && pendingCorr.isNotEmpty()) {
            item(key = "corr-h") { SectionHeader("Correction requests (${pendingCorr.size})") }
            val keys = stableKeysOf(pendingCorr, "corr")
            pendingCorr.forEachIndexed { i, c ->
                item(key = keys[i]) {
                    val id = c.id
                    val name = c.name ?: "Member"
                    // One key per correction for BOTH decisions, so Approve and Reject can't race each other.
                    val k = "corr-${id?.content ?: keys[i]}"
                    SectionCard {
                        ListRow(name,
                            listOfNotNull(c.memberUserId, fmtDate(c.date)).joinToString(" · ") +
                                (if (c.checkIn != null || c.checkOut != null) "\nProposed ${fmtTime(c.checkIn)} → ${fmtTime(c.checkOut)}" else "") +
                                (c.note?.let { "\n“$it”" } ?: ""),
                            leading = { Avatar(name, 38.dp) })
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            OutlinedButton(onClick = { rejectTarget = c }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = id != null && !act.isRunning(k) && online, shape = MaterialTheme.shapes.medium) { Text("Reject") }
                            Button(onClick = { act(k, "Correction approved for $name.") { repo.act(Fn.DecideCorrection, buildJsonObject { put("id", id ?: JsonNull); put("decision", "approved") }) } },
                                Modifier.weight(1f).heightIn(min = 48.dp), enabled = id != null && !act.isRunning(k) && online, shape = MaterialTheme.shapes.medium) { BusyLabel(act.isRunning(k), "Approve") }
                        }
                    }
                }
            }
        }

        val rows = history.data.orEmpty()
        item(key = "hist-h") {
            val week = weekWorkedSeconds(rows)
            SectionHeader("History", "Insights", onInsights)
            if (week > 0) Text("This week: ${fmtDuration(week)} worked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            history.initialLoading -> item(key = "hist-sk") { SkeletonList(3, 64.dp) }
            rows.isEmpty() -> item(key = "hist-empty") { EmptyState("No attendance yet", "Once you check in, each day shows up here with hours and breaks.", Icons.Outlined.EventAvailable) }
            else -> item(key = "hist") {
                SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
                    rows.take(60).forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val inT = r.checkIn; val outT = r.checkOut
                        val b = r.breakSeconds
                        val status = r.status ?: if (outT != null) "present" else if (inT != null) "open" else "absent"
                        val day = Dates.day(r.date)
                        ListRow(
                            if (day == Dates.today()) "Today" else if (day != null) day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)) else fmtDate(r.date),
                            "${fmtTime(inT)} – ${if (outT != null) fmtTime(outT) else "…"}" + (if (b > 0) " · break ${fmtDuration(b)}" else ""),
                            trailing = { StatusChip(pretty(status), statusColor(status)) },
                        )
                    }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }

    rejectTarget?.let { c ->
        val id = c.id
        val name = c.name ?: "The member"
        ConfirmDialog("Reject this correction?", "$name will be notified. This can't be undone from the app.", "Reject", destructive = true,
            onConfirm = { if (id != null) act("corr-${id.content}", "Correction rejected.") { repo.act(Fn.DecideCorrection, buildJsonObject { put("id", id); put("decision", "rejected") }) } },
            onDismiss = { rejectTarget = null })
    }
    if (showCorrection) CorrectionSheet(busy = act.isRunning("correction"), onDismiss = { showCorrection = false }) { date, reason, note ->
        act("correction", "Correction submitted. Your manager has been notified.", onDone = { showCorrection = false }) {
            repo.act(Fn.RequestCorrection, buildJsonObject { put("date", date); put("reason", correctionReason(reason, note)) })
        }
    }
}

/** The gateway stores one free-text reason: "<category>" or "<category>: <note>". */
@VisibleForTesting
fun correctionReason(category: String, note: String): String =
    pretty(category).let { c -> if (note.isBlank()) c else "$c: ${note.trim()}" }.take(600)

@Composable
private fun LiveClock() {
    val now by rememberNow(1000)
    val z = Instant.ofEpochMilli(now).atZone(AppZone)
    Text(z.format(DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH)), style = MaterialTheme.typography.displaySmall)
    Text(z.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)) + " · IST", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WorkedTimer(checkIn: String?, breakSec: Long, onBreak: Boolean, breakStart: String?) {
    val now by rememberNow(1000)
    val start = parseInstant(checkIn)?.toInstant()?.toEpochMilli() ?: now
    val bStart = if (onBreak) parseInstant(breakStart)?.toInstant()?.toEpochMilli() else null
    val worked = ((now - start) / 1000 - breakSec - (if (bStart != null) (now - bStart) / 1000 else 0)).coerceAtLeast(0)
    val h = worked / 3600; val m = (worked % 3600) / 60; val s = worked % 60
    Text("%02d:%02d:%02d".format(Locale.US, h, m, s), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
    Text(if (bStart != null) "On break · ${fmtDuration((now - bStart) / 1000)}" else "Time worked today", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BigRoundButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    CappedFontScale(1.4f) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(184.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)))
        Surface(onClick = onClick, enabled = !busy && enabled, shape = CircleShape, color = Color.Transparent,
            modifier = Modifier.size(152.dp).shadow(16.dp, CircleShape, ambientColor = Web.Gold700, spotColor = Web.Gold700)) {
            Box(Modifier.background(if (enabled) GoldBrush else androidx.compose.ui.graphics.SolidColor(Web.Slate500)), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(Space.xs))
                    // 20sp bold = WCAG "large text": white on the indigo-700→600 gradient passes AA (≥ 3:1).
                    Text(label, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), maxLines = 1)
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectionSheet(busy: Boolean, onDismiss: () -> Unit, onSubmit: (String, String, String) -> Unit) {
    var date by rememberSaveable { mutableStateOf(Dates.today().toString()) }
    var reason by rememberSaveable { mutableStateOf("forgot_check_in") }
    var note by rememberSaveable { mutableStateOf("") }
    var pickDate by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val reasons = listOf("forgot_check_in" to "Forgot check in", "forgot_check_out" to "Forgot check out", "late_arrival" to "Late arrival", "early_departure" to "Early departure", "other" to "Other")
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxl).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Kicker("Attendance")
            Text("Request a correction", style = MaterialTheme.typography.headlineMedium)
            err?.let { ErrorBanner(it) }
            OutlinedButton(onClick = { pickDate = true }, Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Outlined.CalendarMonth, null); Spacer(Modifier.width(Space.sm)); Text(fmtDate(date))
            }
            FlowRowChips(reasons, reason) { reason = it }
            OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, placeholder = { Text("e.g. Reached at 9:10, forgot to punch in") },
                shape = MaterialTheme.shapes.medium, supportingText = { Text("${note.length}/500") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences))
            Button(onClick = {
                err = Validate.correction(runCatching { LocalDate.parse(date) }.getOrNull(), note)
                if (err == null) onSubmit(date, reason, note.trim())
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) { BusyLabel(busy, "Submit request") }
        }
    }
    if (pickDate) DateDialog(date, { date = it; pickDate = false }, { pickDate = false }) { d -> !d.isAfter(Dates.today()) }
}

