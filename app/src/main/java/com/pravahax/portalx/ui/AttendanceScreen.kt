package com.pravahax.portalx.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
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
import com.pravahax.portalx.net.Fn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- selfie handling ----------
private const val SELFIE_MAX_EDGE = 960

internal fun newSelfieFile(ctx: Context): File {
    val dir = File(ctx.cacheDir, "selfies").apply { mkdirs() }
    dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() } // sweep leftovers
    return File(dir, "selfie-${System.currentTimeMillis()}.jpg")
}

/**
 * Decodes the full-resolution capture with subsampling (never loads a 12 MP bitmap), applies EXIF rotation,
 * scales to ≤ 960 px on the long edge and returns a JPEG data URL like the web (canvas.toDataURL("image/jpeg", .8)).
 * Runs off the main thread; always deletes the file.
 */
internal suspend fun selfieDataUrl(file: File): String = withContext(Dispatchers.Default) {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "empty capture" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SELFIE_MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("decode failed")
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        val scale = minOf(1f, SELFIE_MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rotation != 0f) postRotate(rotation) }
        val out = if (scale < 1f || rotation != 0f) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true) else decoded
        val bytes = ByteArrayOutputStream().use { s -> out.compress(Bitmap.CompressFormat.JPEG, 80, s); s.toByteArray() }
        if (out !== decoded) out.recycle()
        decoded.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    } finally {
        file.delete()
    }
}

val LocalOnline = compositionLocalOf { true }

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
fun rememberAttendanceController(onAlreadyDone: () -> Unit = {}): AttendanceController {
    val ctx = LocalContext.current
    val repo = LocalRepo.current
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val act = rememberAction()
    // Saveable: the camera app often causes our process to be killed; the result must still be applied.
    var pendingMode by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var confirmCheckout by rememberSaveable { mutableStateOf(false) }
    var launchedAt by remember { mutableLongStateOf(0L) }
    val busy = processing || act.isRunning("attendance")

    val camera = rememberLauncherForActivityResult(TakeSelfie()) { ok ->
        val mode = pendingMode; val path = pendingPath
        pendingMode = null; pendingPath = null
        val file = path?.let { File(it) }
        if (!ok || mode == null || file == null || !file.exists()) {
            file?.delete()
            if (!ok && mode != null) scope.launch { snack.showSnackbar(if (mode == "in") "Check-in cancelled — no selfie taken." else "Check-out cancelled — no selfie taken.") }
            return@rememberLauncherForActivityResult
        }
        processing = true
        scope.launch {
            val selfie = try { selfieDataUrl(file) } catch (e: Throwable) { null } finally { processing = false }
            if (selfie == null) { snack.showSnackbar("Couldn't read the photo. Please try again."); return@launch }
            val now = ZonedDateTime.now(AppZone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
            act("attendance", if (mode == "in") "Checked in at $now. Have a great day!" else "Checked out at $now. See you tomorrow!",
                onError = { e ->
                    // Already done on another device / a retried request: just show the real state.
                    if (e.message?.contains("already", true) == true) { onAlreadyDone(); scope.launch { snack.showSnackbar(e.message ?: "") }; true } else false
                }) {
                repo.act(if (mode == "in") Fn.CheckIn else Fn.CheckOut, buildJsonObject { put("selfie", selfie) })
            }
        }
    }

    fun capture(mode: String) {
        if (busy) return
        // A double tap must not open the camera twice (the second launch would orphan the first file).
        if (System.currentTimeMillis() - launchedAt < 1500L) return
        launchedAt = System.currentTimeMillis()
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
internal fun weekWorkedSeconds(rows: List<JsonObject>, today: LocalDate = Dates.today()): Long {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    return rows.sumOf { r ->
        val d = Dates.day(r.str("attendance_date", "date")) ?: Dates.day(r.str("check_in", "checkIn"))
        val a = parseInstant(r.str("check_in", "checkIn")); val b = parseInstant(r.str("check_out", "checkOut"))
        if (d == null || d.isBefore(monday) || d.isAfter(today) || a == null || b == null || !b.isAfter(a)) 0L
        else (java.time.Duration.between(a, b).seconds - (r.num("total_break_seconds") ?: 0.0).toLong()).coerceIn(0L, 24 * 3600L)
    }
}

@Composable
fun AttendanceScreen(user: SessionUser) {
    val repo = LocalRepo.current
    val online = LocalOnline.current
    val today = rememberResource(Fn.AttendanceToday)
    val history = rememberResource(Fn.AttendanceHistory)
    val corrections = rememberResource(Fn.Corrections, enabled = user.canApproveCorrections)
    val act = rememberAction()
    var showCorrection by rememberSaveable { mutableStateOf(false) }
    var rejectTarget by remember { mutableStateOf<JsonObject?>(null) }
    val refreshAll = { today.refresh(); history.refresh(); corrections.refresh() }
    val ctl = rememberAttendanceController(onAlreadyDone = refreshAll)
    val busy = ctl.busy

    val t = today.data.obj()
    val checkIn = t.str("check_in", "checkIn"); val checkOut = t.str("check_out", "checkOut")
    val onBreak = t.bool("is_on_break", "isOnBreak")
    val breakSec = (t.num("total_break_seconds") ?: 0.0).toLong()

    RefreshList(today.refreshing || history.refreshing || corrections.refreshing, refreshAll) {
        today.error?.let { item(key = "err") { ErrorBanner(it, today.stale, refreshAll) } }
        item(key = "status") {
            SectionCard(padding = PaddingValues(Space.xl)) {
                Kicker("Today's status")
                Spacer(Modifier.height(Space.md))
                val state = when {
                    today.initialLoading -> "loading"
                    t == null -> "unknown"
                    t.bool("onLeave", "on_leave") -> "leave"
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
                                Text("You're on ${t.str("leaveType") ?: "leave"} today. Check-in is disabled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            WorkedTimer(checkIn, breakSec, s == "break", t.str("last_break_start", "lastBreakStart"))
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

        val pendingCorr = corrections.data.objects().filter { (it.str("status") ?: "pending") == "pending" }
        if (user.canApproveCorrections && pendingCorr.isNotEmpty()) {
            item(key = "corr-h") { SectionHeader("Correction requests (${pendingCorr.size})") }
            val keys = stableKeys(pendingCorr, "corr")
            pendingCorr.forEachIndexed { i, c ->
                item(key = keys[i]) {
                    val id = c.idOf()
                    val name = c.str("name", "full_name", "memberName") ?: "Member"
                    // One key per correction for BOTH decisions, so Approve and Reject can't race each other.
                    val k = "corr-${id?.content ?: keys[i]}"
                    SectionCard {
                        ListRow(name,
                            listOfNotNull(c.str("memberUserId"), fmtDate(c.str("date", "attendance_date"))).joinToString(" · ") +
                                (if (c.str("checkIn") != null || c.str("checkOut") != null) "\nProposed ${fmtTime(c.str("checkIn"))} → ${fmtTime(c.str("checkOut"))}" else "") +
                                (c.str("note")?.let { "\n“$it”" } ?: ""),
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

        val rows = history.data.objects()
        item(key = "hist-h") {
            val week = weekWorkedSeconds(rows)
            SectionHeader("History")
            if (week > 0) Text("This week: ${fmtDuration(week)} worked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            history.initialLoading -> item(key = "hist-sk") { SkeletonList(3, 64.dp) }
            rows.isEmpty() -> item(key = "hist-empty") { EmptyState("No attendance yet", "Once you check in, each day shows up here with hours and breaks.", Icons.Outlined.EventAvailable) }
            else -> item(key = "hist") {
                SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
                    rows.take(60).forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val inT = r.str("check_in", "checkIn"); val outT = r.str("check_out", "checkOut")
                        val b = (r.num("total_break_seconds") ?: 0.0).toLong()
                        val status = r.str("status") ?: if (outT != null) "present" else if (inT != null) "open" else "absent"
                        val day = Dates.day(r.str("attendance_date", "date"))
                        ListRow(
                            if (day == Dates.today()) "Today" else if (day != null) day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)) else fmtDate(r.str("attendance_date", "date")),
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
        val id = c.idOf()
        val name = c.str("name", "full_name", "memberName") ?: "The member"
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
internal fun correctionReason(category: String, note: String): String =
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        options.forEach { (v, l) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(l) },
                leadingIcon = if (v == selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer))
        }
    }
}

/** Date picker in UTC millis (as Material requires) converted to/from ISO dates; [selectable] limits the range. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(initial: String?, onPick: (String) -> Unit, onDismiss: () -> Unit, selectable: (LocalDate) -> Boolean = { true }) {
    val init = runCatching { LocalDate.parse(initial).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = init ?: Dates.today().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = selectable(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate())
        },
    )
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton(onClick = {
            state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) } ?: onDismiss()
        }) { Text("OK") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }) { DatePicker(state) }
}
