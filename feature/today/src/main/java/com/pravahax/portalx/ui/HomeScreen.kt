package com.pravahax.portalx.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.data.*
import com.pravahax.portalx.data.model.*
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One overview tile: its count and the rows behind it (shown in the detail sheet). count < 0 = unknown. */
private data class Stat(val type: String, val label: String, val count: Int, val icon: ImageVector, val alert: Boolean = false,
                        val rows: List<Pair<String, String>> = emptyList(), val nav: String? = null)

/** Projects still in flight (web statuses: planned, active, on_hold, completed, cancelled). */
internal fun isActiveProject(status: String?) = status?.lowercase() !in setOf("completed", "cancelled", "canceled", "archived", "done", "closed")

@Composable
fun HomeScreen(user: SessionUser, navigate: (String) -> Unit) {
    val today = rememberResource(Endpoint.AttendanceToday)
    val tasks = rememberResource(Endpoint.Tasks)
    val leave = rememberResource(Endpoint.MyLeave)
    val ann = rememberResource(Endpoint.Announcements, enabled = user.can("comms.read"))
    // The overview is composed from the gateway's real endpoints, each only when the user holds its permission.
    val people = rememberResource(Endpoint.Directory, enabled = user.canReadPeople)
    val pendLeave = rememberResource(Endpoint.PendingLeaveApprovals, enabled = user.canApproveLeave)
    val pendCorr = rememberResource(Endpoint.Corrections, enabled = user.canApproveCorrections)
    val projects = rememberResource(Endpoint.Projects, enabled = user.can("projects.read"))
    val ym = java.time.YearMonth.from(Dates.today())
    val cal = rememberResource(Endpoint.CalendarMonth, buildJsonObject { put("year", ym.year); put("month", ym.monthValue) }, key = "cal-$ym", enabled = user.can("comms.read"))
    val all = listOf(today, tasks, leave, ann, people, pendLeave, pendCorr, projects, cal)
    val refreshing = all.any { it.refreshing }
    var detail by remember { mutableStateOf<Stat?>(null) }

    RefreshList(refreshing, { all.forEach { it.refresh() } }) {
        item(key = "greet") {
            Column(Modifier.padding(top = Space.xs, bottom = Space.xs)) {
                Kicker(Dates.today().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale.ENGLISH)))
                Spacer(Modifier.height(Space.xs))
                Text("${greeting()}, ${user.firstName}", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (user.userId.isNotBlank()) StatusChip(user.userId, MaterialTheme.colorScheme.primary, dot = false)
                    Text(user.roleLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val firstErr = listOf(today, tasks, leave).firstOrNull { it.error != null }
        firstErr?.let { r -> item(key = "err") { ErrorBanner(r.error ?: "", stale = r.stale) { today.refresh(); tasks.refresh(); leave.refresh() } } }

        item(key = "hero") { AttendanceHero(today, navigate) }

        if (user.seesStats) {
            val list = buildList {
                fun n(r: Resource<*>, size: Int) = if (r.data == null) -1 else size
                if (user.canReadPeople) {
                    val ps = people.data.orEmpty()
                    add(Stat("people", "Team members", n(people, ps.size), Icons.Outlined.Groups,
                        rows = ps.map { (it.name ?: "Member") to listOfNotNull(it.userId, it.team).joinToString(" · ") }, nav = "directory"))
                }
                if (user.can("comms.read")) {
                    val t = Dates.today()
                    val away = cal.data?.leave.orEmpty().filter { l ->
                        val s0 = Dates.day(l.startDate); val e0 = Dates.day(l.endDate) ?: s0
                        l.status == "approved" && s0 != null && e0 != null && !t.isBefore(s0) && !t.isAfter(e0)
                    }
                    add(Stat("on_leave", "On leave today", n(cal, away.size), Icons.Outlined.BeachAccess,
                        rows = away.map { (it.name ?: "Someone") to "${fmtShortDate(it.startDate)} → ${fmtShortDate(it.endDate)}" }, nav = "calendar"))
                }
                if (user.canApproveLeave) {
                    val pl = pendLeave.data.orEmpty()
                    add(Stat("pending_leaves", "Pending leaves", n(pendLeave, pl.size), Icons.Outlined.PendingActions, pl.isNotEmpty(),
                        rows = pl.map { (it.name ?: "Member") to listOfNotNull(it.type, fmtShortDate(it.startDate)).joinToString(" · ") }, nav = "approvals"))
                }
                if (user.canApproveCorrections) {
                    val pc = pendCorr.data.orEmpty()
                    add(Stat("pending_corrections", "Pending corrections", n(pendCorr, pc.size), Icons.Outlined.EditCalendar, pc.isNotEmpty(),
                        rows = pc.map { (it.name ?: "Member") to fmtShortDate(it.date) }, nav = "approvals"))
                }
                if (user.can("projects.read")) {
                    val ap = projects.data.orEmpty().filter { isActiveProject(it.status) }
                    add(Stat("active_projects", "Active projects", n(projects, ap.size), Icons.Outlined.FolderOpen,
                        rows = ap.map { (it.name ?: "Project") to pretty(it.status) }, nav = "projects"))
                }
            }
            if (list.isNotEmpty()) {
                item(key = "ovh") { SectionHeader("Overview") }
                item(key = "ov") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.md), contentPadding = PaddingValues(end = Space.sm)) {
                        items(list, key = { it.type }) { st -> StatTile(st) { detail = st } }
                    }
                }
            }
            // v0.8: one button into the approvals inbox (leave + corrections).
            val n = com.pravahax.portalx.data.model.ApprovalItem.inbox(pendLeave.data.orEmpty(), pendCorr.data.orEmpty()).size
            if (n > 0) item(key = "appr") {
                FilledTonalButton(onClick = { navigate("approvals") }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Outlined.Inbox, null); Spacer(Modifier.width(Space.sm)); Text("Review $n approval${if (n > 1) "s" else ""}")
                }
            }
        }

        item(key = "th") { SectionHeader("Open tasks", "View all") { navigate("tasks") } }
        // Most urgent first: overdue/soonest due date, undated last.
        val open = tasks.data.orEmpty().filter { !it.done }
            .sortedBy { Dates.day(it.dueDate)?.toEpochDay() ?: Long.MAX_VALUE }.take(5)
        when {
            tasks.initialLoading -> item(key = "tsk") { Skeleton(160.dp) }
            open.isEmpty() -> item(key = "tempty") {
                SectionCard { ListRow("You're all caught up", "No open tasks right now. New assignments will appear here.", leading = { CircleIcon(Icons.Outlined.TaskAlt, PortalTheme.status.success) }) }
            }
            else -> item(key = "tlist") {
                SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
                    open.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val overdue = Dates.isOverdue(t.dueDate, false)
                        ListRow(
                            t.title ?: "Untitled",
                            listOfNotNull(t.projectName, t.dueDate?.let { Dates.dueLabel(it, false) }).joinToString(" · "),
                            trailing = { StatusChip(pretty(t.priority), statusColor(t.priority)) },
                            onClick = { navigate("tasks") },
                        )
                    }
                }
            }
        }

        item(key = "lh") { SectionHeader("Leave balance", "Manage") { navigate("leave") } }
        item(key = "lb") {
            val balances = leave.data?.balances.orEmpty()
            when {
                leave.initialLoading -> Skeleton(110.dp)
                balances.isEmpty() -> SectionCard { Text("No leave types configured yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    items(balances.size, key = { "bal:" + (balances[it].key ?: it.toString()) + ":" + it }) { i -> BalanceTile(balances[i]) }
                }
            }
        }

        if (user.can("comms.read")) item(key = "ah") { SectionHeader("Announcements", "View all") { navigate("announcements") } }
        val published = ann.data.orEmpty().take(3)
        if (user.can("comms.read")) item(key = "al") {
            when {
                ann.initialLoading -> Skeleton(120.dp)
                published.isEmpty() -> SectionCard { Text("No announcements yet. Company updates will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
                    published.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow(a.title ?: "Announcement", a.body, onClick = { navigate("announcements") },
                            trailing = if (!a.readByMe) ({ Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }) else null)
                    }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }

    detail?.let { st -> StatDetailSheet(st, { detail = null }) { r -> detail = null; navigate(r) } }
}


/**
 * Today's attendance at a glance, with the day's ONE next action right on Home:
 * not checked in → "Check in" (opens the selfie camera directly), working → Break / Check out, on break → Resume.
 * Light brand panel: primary-soft → white with the web's gold corner glow.
 */
@Composable
private fun AttendanceHero(today: Resource<AttendanceToday?>, navigate: (String) -> Unit) {
    val t = today.data
    val checkIn = t?.checkIn; val checkOut = t?.checkOut
    val onLeave = t?.onLeave == true
    val onBreak = t?.onBreak == true
    val active = checkIn != null && checkOut == null
    val online = LocalOnline.current
    val ctl = rememberAttendanceController(onAlreadyDone = { today.refresh() })
    val now by rememberNow(30_000)
    val p = PortalTheme.status
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        onClick = { navigate("attendance") }, shape = shape, color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().shadow(10.dp, shape, clip = false, ambientColor = Web.ShadowInk.copy(alpha = 0.10f), spotColor = Web.ShadowInk.copy(alpha = 0.14f))
            .semantics { contentDescription = "Today's attendance. Opens Attendance." },
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(Web.PrimarySoft, Color.White))).goldGlow().padding(Space.xl)
                .then(if (LocalReduceMotion.current) Modifier else Modifier.animateContentSize())
        ) {
            if (today.initialLoading) {
                Kicker("Today"); Spacer(Modifier.height(Space.sm)); Skeleton(28.dp, Modifier.fillMaxWidth(0.6f), MaterialTheme.shapes.small)
                Spacer(Modifier.height(Space.md)); Skeleton(52.dp, shape = MaterialTheme.shapes.medium)
                return@Column
            }
            val (dot, title) = when {
                t == null -> p.neutral to "Attendance"
                onLeave -> p.warning to "On approved leave"
                checkIn == null -> p.warning to "Not checked in yet"
                checkOut == null -> if (onBreak) p.warning to "On a break" else p.success to "Checked in · ${fmtTime(checkIn)}"
                else -> p.success to "Day complete"
            }
            Kicker("Today's attendance")
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(Space.sm))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val sub = when {
                checkIn != null && checkOut != null -> "${fmtTime(checkIn)} – ${fmtTime(checkOut)}" + ((t?.breakSeconds ?: 0L).takeIf { it > 0 }?.let { " · break ${fmtDuration(it)}" } ?: "")
                active -> {
                    val start = parseInstant(checkIn)?.toInstant()?.toEpochMilli()
                    val brk = t?.breakSeconds ?: 0L
                    start?.let { "${fmtDuration(((now - it) / 1000 - brk).coerceAtLeast(0))} worked so far" }
                }
                onLeave -> t?.leaveType?.let { "$it · check-in is disabled today" } ?: "Check-in is disabled today"
                t == null -> today.error ?: "Pull down to refresh."
                else -> if (online) "One tap, a quick selfie, and you're in." else "You're offline. Reconnect to check in."
            }
            sub?.let { Spacer(Modifier.height(Space.xs)); Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            if (t != null && !onLeave && checkOut == null) {
                Spacer(Modifier.height(Space.lg))
                val enabled = online && !ctl.busy
                when {
                    checkIn == null -> Button(onClick = ctl.checkIn, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(Space.sm)); BusyLabel(ctl.busy, "Check in")
                    }
                    onBreak -> Button(onClick = ctl.endBreak, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(Space.sm)); BusyLabel(ctl.busy, "Resume work")
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        OutlinedButton(onClick = ctl.startBreak, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)) {
                            Icon(Icons.Outlined.Coffee, null); Spacer(Modifier.width(6.dp)); Text("Break")
                        }
                        Button(onClick = ctl.checkOut, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, null); Spacer(Modifier.width(6.dp)); BusyLabel(ctl.busy, "Check out")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(s: Stat, onClick: () -> Unit) {
    SectionCard(Modifier.width(152.dp), onClick = onClick, padding = PaddingValues(Space.md + Space.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(s.icon, MaterialTheme.colorScheme.primary, 34.dp)
            Spacer(Modifier.weight(1f))
            if (s.alert) Box(Modifier.size(8.dp).clip(CircleShape).background(PortalTheme.status.danger))
        }
        Spacer(Modifier.height(Space.md))
        Text(if (s.count < 0) "—" else "${s.count}", style = MaterialTheme.typography.headlineLarge)
        Text(s.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BalanceTile(b: LeaveBalance) {
    SectionCard(Modifier.width(164.dp)) {
        Text(b.type ?: "Leave", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtNum(b.remaining), style = MaterialTheme.typography.headlineLarge)
            Text(" / ${fmtNum(b.allocated)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        val alloc = b.allocated ?: 0.0; val rem = b.remaining ?: 0.0
        LinearProgressIndicator(
            progress = { if (alloc > 0) (rem / alloc).toFloat().coerceIn(0f, 1f) else 0f },
            Modifier.fillMaxWidth().padding(top = Space.sm).height(6.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatDetailSheet(st: Stat, onDismiss: () -> Unit, open: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxxl).navigationBarsPadding()) {
            Text(st.label, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Space.md))
            when {
                st.count < 0 -> Text("Couldn't load this yet. Pull down on Home to retry.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                st.rows.isEmpty() -> Text("Nothing here right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    st.rows.take(100).forEach { (name, sub) -> ListRow(name, sub.ifBlank { null }, leading = { Avatar(name, 36.dp) }) }
                }
            }
            st.nav?.let { r -> TextButton(onClick = { open(r) }, modifier = Modifier.align(Alignment.End)) { Text("Open") } }
        }
    }
}
