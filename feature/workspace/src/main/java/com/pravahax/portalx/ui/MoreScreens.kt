package com.pravahax.portalx.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.net.AppInfo
import com.pravahax.portalx.data.*
import com.pravahax.portalx.data.model.*
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

fun appVersionLabel(context: android.content.Context): String = "PortalX for Android · v${AppInfo.versionName(context)}"

// ======================= MORE HUB =======================
@Composable
fun MoreScreen(user: SessionUser, navigate: (String) -> Unit, onLogout: () -> Unit, refreshMe: suspend () -> Unit = {}) {
    val ctx = LocalContext.current
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val (refreshing, refresh) = rememberUserRefresh(refreshMe)
    RefreshList(refreshing, refresh) {
        item(key = "me") {
            SectionCard(onClick = { navigate("profile") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user.name, 56.dp); Spacer(Modifier.width(Space.lg))
                    Column(Modifier.weight(1f)) {
                        Text(user.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(user.userId, user.roleLabel).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "wsh") { SectionHeader("Workspace") }
        item(key = "ws") {
            SectionCard(padding = PaddingValues(Space.sm)) {
                HubRow(Icons.Outlined.BeachAccess, "Leave", "Balances, requests and approvals") { navigate("leave") }
                HubRow(Icons.Outlined.Groups, "Meetings", "Upcoming and past") { navigate("meetings") }
                HubRow(Icons.Outlined.Campaign, "Announcements", "Company updates") { navigate("announcements") }
                if (user.canReadPeople) HubRow(Icons.Outlined.Badge, "Directory", "Find a colleague") { navigate("directory") }
                if (user.can("projects.read")) HubRow(Icons.Outlined.FolderOpen, "Projects", "Progress, members and tasks") { navigate("projects") }
                if (user.can("documents.read")) HubRow(Icons.Outlined.Description, "Documents", "Files shared with you") { navigate("documents") }
                if (user.can("performance.read")) HubRow(Icons.Outlined.Insights, "Performance & feedback", "Reviews and peer feedback") { navigate("performance") }
                if (user.can("teams.read")) HubRow(Icons.Outlined.Diversity3, "Teams", "Leads, managers and members") { navigate("teams") }
                HubRow(Icons.Outlined.Notifications, "Notifications", "Approvals and updates") { navigate("notifications") }
            }
        }
        val admin = listOf(
            Triple(user.can("people.read") && user.can("organization.members.read"), "users", Triple(Icons.Outlined.ManageAccounts, "Users", "Everyone in this workspace")),
            Triple(true, "access", Triple(Icons.Outlined.AdminPanelSettings, "Access control", "Your permissions and roles")),
            Triple(user.can("audit.read"), "audit", Triple(Icons.Outlined.Policy, "Audit logs", "Security-relevant activity")),
            Triple(user.can("organization.settings.manage"), "settings", Triple(Icons.Outlined.Settings, "Company settings", "Organisation profile")),
        ).filter { it.first }
        item(key = "adh") { SectionHeader("Administration") }
        item(key = "ad") {
            SectionCard(padding = PaddingValues(Space.sm)) {
                admin.forEach { (_, route, v) -> HubRow(v.first, v.second, v.third) { navigate(route) } }
            }
        }
        item(key = "out") {
            OutlinedButton(onClick = onLogout, Modifier.fillMaxWidth().padding(top = Space.sm).heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null); Spacer(Modifier.width(Space.sm)); Text("Sign out")
            }
        }
        item(key = "ver") {
            val c = LocalContext.current
            Text("${appVersionLabel(c)} (${AppInfo.versionCode(c)})", Modifier.fillMaxWidth().padding(Space.lg), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HubRow(icon: ImageVector, label: String, subtitle: String? = null, external: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClickLabel = if (external) "Open in browser" else "Open", onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.Outlined.ChevronRight, if (external) "Opens in browser" else null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

// ======================= ANNOUNCEMENTS =======================
@Composable
fun AnnouncementsScreen() {
    val repo = LocalRepo.current
    val ann = rememberResource(Endpoint.Announcements)
    val act = rememberAction()
    val published = ann.data.orEmpty()
    val keys = stableKeysOf(published, "ann")
    RefreshList(ann.refreshing, { ann.refresh() }) {
        ann.error?.let { item(key = "err") { ErrorBanner(it, ann.stale) { ann.refresh() } } }
        if (ann.initialLoading) item(key = "sk") { SkeletonList(3, 120.dp) }
        else if (published.isEmpty()) item(key = "empty") { EmptyState("No announcements yet", "Company news and policy updates from your admins will appear here.", Icons.Outlined.Campaign) }
        else {
            val unreadN = published.count { !it.readByMe }
            item(key = "count") { Text(if (unreadN > 0) "$unreadN unread of ${published.size}" else "All caught up · ${published.size} announcement${if (published.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(published.size, key = { keys[it] }) { i ->
            val a = published[i]
            val unread = !a.readByMe
            val id = a.id
            SectionCard(highlighted = unread) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.title ?: "Announcement", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)) // web: font-display text-lg font-semibold
                    if (unread) StatusChip("New", MaterialTheme.colorScheme.primary)
                }
                Text(listOfNotNull(a.author, a.publishedAt?.let { fmtDateTime(it) }, a.teamName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.sm))
                val body = a.body ?: ""
                var expanded by rememberSaveable(keys[i]) { mutableStateOf(false) }
                val long = body.length > 280 || body.count { it == '\n' } > 5
                Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded || !long) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                    modifier = if (LocalReduceMotion.current) Modifier else Modifier.animateContentSize())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (long) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Read more") }
                    Spacer(Modifier.weight(1f))
                    if (unread && id != null) {
                        val k = "read-${id.content}"
                        TextButton(onClick = { act(k, "Marked as read.") { repo.act(Fn.MarkAnnouncementRead, buildJsonObject { put("id", id) }) } }, enabled = !act.isRunning(k)) {
                            Icon(Icons.Outlined.DoneAll, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs)); BusyLabel(act.isRunning(k), "Mark as read")
                        }
                    }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }
}

// ======================= MEETINGS =======================
@Composable
fun MeetingsScreen() {
    val meetings = rememberResource(Endpoint.Meetings)
    val ctx = LocalContext.current
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val list = meetings.data.orEmpty().sortedBy { parseInstant(it.startAt)?.toEpochSecond() ?: Long.MAX_VALUE }
    val now = java.time.ZonedDateTime.now(AppZone)
    val (upcoming, past) = list.partition { (parseInstant(it.endAt ?: it.startAt)?.isAfter(now)) ?: true }
    val pastShown = past.reversed().take(20)
    val uKeys = stableKeysOf(upcoming, "up"); val pKeys = stableKeysOf(pastShown, "past")
    val join: (String) -> Unit = { link -> if (!ctx.openWeb(link)) scope.launch { snack.showSnackbar("This meeting link can't be opened.") } }
    RefreshList(meetings.refreshing, { meetings.refresh() }) {
        meetings.error?.let { item(key = "err") { ErrorBanner(it, meetings.stale) { meetings.refresh() } } }
        if (meetings.initialLoading) item(key = "sk") { SkeletonList(3, 96.dp) }
        else if (list.isEmpty()) item(key = "empty") { EmptyState("No meetings scheduled", "Meetings you're invited to will appear here, with a Join button for video links.", Icons.Outlined.Groups) }
        else if (upcoming.isEmpty()) item(key = "noup") { SectionCard { Text("Nothing upcoming. Your calendar is clear.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (upcoming.isNotEmpty()) item(key = "uh") { SectionHeader("Upcoming") }
        items(upcoming.size, key = { uKeys[it] }) { i -> MeetingCard(upcoming[i], onJoin = join) }
        if (pastShown.isNotEmpty()) item(key = "ph") { SectionHeader("Past") }
        items(pastShown.size, key = { pKeys[it] }) { i -> MeetingCard(pastShown[i], dim = true) {} }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }
}

@Composable
private fun MeetingCard(m: Meeting, dim: Boolean = false, onJoin: (String) -> Unit) {
    val start = parseInstant(m.startAt)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(58.dp).clip(MaterialTheme.shapes.medium)
                .background(if (dim) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer).padding(vertical = Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(start?.month?.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)?.uppercase() ?: "—", style = MaterialTheme.typography.labelSmall,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
                Text("${start?.dayOfMonth ?: ""}", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(m.title ?: "Meeting", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val now = java.time.ZonedDateTime.now(AppZone)
                val end = parseInstant(m.endAt)
                val live = !dim && start != null && !start.isAfter(now) && (end == null || end.isAfter(now))
                val dayLabel = when (start?.toLocalDate()) { null -> null; Dates.today() -> "Today"; Dates.today().plusDays(1) -> "Tomorrow"; else -> null }
                if (live) { StatusChip("Live now", PortalTheme.status.success); Spacer(Modifier.height(Space.xxs)) }
                Text(listOfNotNull(dayLabel, "${fmtTime(m.startAt)}${m.endAt?.let { " – " + fmtTime(it) } ?: ""}", m.location).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                m.organizer?.let { Text("By $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val link = Validate.safeWebUrl(m.link)
            if (!dim && link != null) FilledTonalButton(onClick = { onJoin(link) }, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.semantics { contentDescription = "Join ${m.title ?: "meeting"}" }) { Text("Join") }
        }
        m.description?.let { Spacer(Modifier.height(Space.sm)); Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
    }
}

// ======================= DIRECTORY =======================
@Composable
fun DirectoryScreen(openPerson: (Person) -> Unit) {
    val dir = rememberResource(Endpoint.Directory)
    var q by rememberSaveable { mutableStateOf("") }
    var team by rememberSaveable { mutableStateOf<String?>(null) }
    val all = dir.data.orEmpty()
    val teams = remember(all) { all.mapNotNull { it.team }.distinct().sorted() }
    val ql = q.trim().lowercase()
    val shown = remember(all, ql, team) {
        all.filter { p -> team == null || p.team == team }
            .filter { p -> ql.isEmpty() || p.matches(ql) }
            .sortedBy { (it.name ?: "").lowercase() }
    }
    val keys = stableKeysOf(shown, "p")
    RefreshList(dir.refreshing, { dir.refresh() }) {
        item(key = "search") { SearchField(q, { q = it }, "Search name, User ID, team, role…") }
        if (teams.size > 1) item(key = "teams") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                item(key = "all") { FilterChip(team == null, { team = null }, { Text("Everyone · ${all.size}") }) }
                items(teams, key = { it }) { t -> FilterChip(team == t, { team = if (team == t) null else t }, { Text(t) }) }
            }
        }
        dir.error?.let { item(key = "err") { ErrorBanner(it, dir.stale) { dir.refresh() } } }
        if (dir.initialLoading) item(key = "sk") { SkeletonList(5, 72.dp) }
        else if (shown.isEmpty()) item(key = "empty") {
            if (all.isEmpty()) EmptyState("No one to show yet", "Colleagues appear here once your admin adds them.", Icons.Outlined.PersonSearch)
            else EmptyState("No matches", "Try a name, a User ID like PSE-00007, or a team.", Icons.Outlined.PersonSearch, "Clear filters") { q = ""; team = null }
        }
        else item(key = "count") { Text("${shown.size} ${if (shown.size == 1) "person" else "people"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown.size, key = { keys[it] }) { i ->
            val p = shown[i]
            SectionCard(onClick = { openPerson(p) }, padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
                ListRow(p.name ?: "Member", listOfNotNull(p.designation, p.team, p.userId).joinToString(" · "),
                    leading = { Avatar(p.name ?: "?") },
                    trailing = {
                        val role = p.organizationRoles.firstOrNull() ?: p.roleLabel ?: pretty(p.role)
                        if (role != "—") StatusChip(role, MaterialTheme.colorScheme.primary, dot = false)
                    })
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }
}

private val emailRe = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonSheet(p: Person, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(bottom = Space.xxl).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(p.name ?: "?", 88.dp)
            Spacer(Modifier.height(Space.md))
            Text(p.name ?: "Member", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(listOfNotNull(p.designation, p.userId).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.lg))
            listOf("Team" to p.team, "Manager" to p.manager, "Role" to (p.roleLabel ?: p.role?.let { pretty(it) }),
                "Status" to p.status?.let { pretty(it) }, "Email" to p.email, "Phone" to p.phone)
                .forEach { (k, v) -> if (v != null) KeyValueRow(k, v) }
            val email = p.email?.trim()?.takeIf { emailRe.matches(it) }
            val phone = p.phone?.filter { it.isDigit() || it == '+' }?.takeIf { it.length in 5..16 }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md), modifier = Modifier.padding(top = Space.md)) {
                email?.let { e -> FilledTonalButton(onClick = { ctx.startSafely(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", e, null))) }) { Icon(Icons.Outlined.Mail, null); Spacer(Modifier.width(6.dp)); Text("Email") } }
                phone?.let { ph -> FilledTonalButton(onClick = { ctx.startSafely(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", ph, null))) }) { Icon(Icons.Outlined.Call, null); Spacer(Modifier.width(6.dp)); Text("Call") } }
            }
        }
    }
}

// ======================= CALENDAR =======================
private data class CalEvent(val date: LocalDate, val title: String, val kind: String, val color: Color)

@Composable
fun CalendarScreen() {
    // Saved as strings so they survive rotation and process death.
    var monthStr by rememberSaveable { mutableStateOf(YearMonth.from(Dates.today()).toString()) }
    var selectedStr by rememberSaveable { mutableStateOf(Dates.today().toString()) }
    var filter by rememberSaveable { mutableStateOf("all") }
    val month = runCatching { YearMonth.parse(monthStr) }.getOrDefault(YearMonth.from(Dates.today()))
    val selected = runCatching { LocalDate.parse(selectedStr) }.getOrDefault(Dates.today())
    val today = Dates.today()
    val res = rememberResource(Endpoint.CalendarMonth, buildJsonObject { put("year", month.year); put("month", month.monthValue) }, key = "cal-$month")
    val d = res.data
    val p = PortalTheme.status
    val events = remember(d, filter, p) {
        buildList {
            d?.holidays.orEmpty().forEach { h -> Dates.day(h.date)?.let { add(CalEvent(it, h.title ?: "Holiday", "holidays", p.accent)) } }
            d?.meetings.orEmpty().forEach { m -> Dates.day(m.date ?: m.startAt)?.let { add(CalEvent(it, (m.title ?: "Meeting") + " · " + fmtTime(m.startAt), "meetings", p.success)) } }
            d?.tasks.orEmpty().forEach { t -> Dates.day(t.date)?.let { add(CalEvent(it, t.title ?: "Task", "tasks", p.info)) } }
            d?.deadlines.orEmpty().forEach { pd -> Dates.day(pd.date)?.let { add(CalEvent(it, (pd.title ?: "Project") + " deadline", "deadlines", p.danger)) } }
            d?.leave.orEmpty().forEach { l ->
                val s = Dates.day(l.startDate); val e = Dates.day(l.endDate) ?: s
                if (s != null && e != null && !e.isBefore(s) && java.time.temporal.ChronoUnit.DAYS.between(s, e) <= 62) {
                    var x: LocalDate = s
                    while (!x.isAfter(e)) { add(CalEvent(x, (l.name ?: "Someone") + " on leave", "leave", p.warning)); x = x.plusDays(1) }
                }
            }
        }.filter { filter == "all" || it.kind == filter }
    }
    val byDay = remember(events) { events.groupBy { it.date } }

    RefreshList(res.refreshing, { res.refresh() }) {
        res.error?.let { item(key = "err") { ErrorBanner(it, res.stale) { res.refresh() } } }
        item(key = "grid") {
            SectionCard(padding = PaddingValues(Space.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { monthStr = month.minusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronLeft, "Previous month") }
                    Text("${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = { monthStr = month.plusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronRight, "Next month") }
                }
                if (YearMonth.from(today) != month) TextButton(onClick = { monthStr = YearMonth.from(today).toString(); selectedStr = today.toString() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Today") }
                Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                if (res.initialLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Space.xs).height(2.dp), color = MaterialTheme.colorScheme.primary)
                CappedFontScale(1.3f) {
                val first = month.atDay(1); val offset = first.dayOfWeek.value - 1
                val cells = offset + month.lengthOfMonth(); val rows = (cells + 6) / 7
                for (r in 0 until rows) Row {
                    for (c in 0 until 7) {
                        val idx = r * 7 + c - offset
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (idx in 0 until month.lengthOfMonth()) {
                                val date = month.atDay(idx + 1)
                                val isSel = date == selected; val isToday = date == today
                                val n = byDay[date].orEmpty().size
                                Column(Modifier.fillMaxSize().clip(CircleShape)
                                    .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .then(if (isToday && !isSel) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                    .clickable(role = Role.Button) { selectedStr = date.toString() }
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)) +
                                            (if (isToday) ", today" else "") + (if (n > 0) ", $n event${if (n == 1) "" else "s"}" else ", nothing scheduled")
                                        this.selected = isSel
                                    },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text("${idx + 1}", color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        byDay[date].orEmpty().map { it.color }.distinct().take(3).forEach { col ->
                                            Box(Modifier.size(5.dp).clip(CircleShape).background(if (isSel) MaterialTheme.colorScheme.onPrimary else col))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        item(key = "filters") { FlowRowChips(listOf("all" to "All", "holidays" to "Holidays", "meetings" to "Meetings", "tasks" to "Tasks", "leave" to "Leave", "deadlines" to "Deadlines"), filter) { filter = it } }
        item(key = "dayh") { SectionHeader(selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))) }
        val dayEvents = byDay[selected].orEmpty()
        item(key = "day") {
            if (dayEvents.isEmpty()) SectionCard {
                if (res.initialLoading) SkeletonList(1, 40.dp)
                else Text(if (filter == "all") "Nothing scheduled. Tap a day with dots to see its holidays, meetings, tasks and leave." else "No ${pretty(filter).lowercase()} on this day.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.sm)) {
                dayEvents.forEach { e ->
                    Row(Modifier.padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(e.color)); Spacer(Modifier.width(Space.md))
                        Column { Text(e.title, style = MaterialTheme.typography.titleSmall); Text(pretty(e.kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }
}

// ======================= PROFILE =======================
/** Pull-to-refresh state for screens that only show the signed-in user. */
@Composable
fun rememberUserRefresh(refreshMe: suspend () -> Unit): Pair<Boolean, () -> Unit> {
    val scope = rememberCoroutineScope()
    val snack = LocalSnackbar.current
    var refreshing by remember { mutableStateOf(false) }
    return refreshing to {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                try { refreshMe() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { launch { snack.showSnackbar("Couldn't refresh right now. Showing saved details.") } }
                finally { refreshing = false }
            }
        }
    }
}

@Composable
fun ProfileScreen(user: SessionUser, onLogout: () -> Unit, refreshMe: suspend () -> Unit = {}, onChangePassword: () -> Unit = {}) {
    val repo = LocalRepo.current
    val (refreshing, refresh) = rememberUserRefresh(refreshMe)
    RefreshList(refreshing, refresh) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(vertical = Space.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(user.name, 96.dp); Spacer(Modifier.height(Space.md))
                Text(user.name, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Space.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    if (user.userId.isNotBlank()) StatusChip(user.userId, MaterialTheme.colorScheme.primary, dot = false)
                    StatusChip(user.roleLabel, PortalTheme.status.neutral, dot = false)
                }
            }
        }
        item(key = "info") {
            SectionCard {
                listOf("Email" to user.email.ifBlank { null }, "Team" to user.raw.str("team", "teamName"), "Designation" to user.raw.str("designation"),
                    "Workspace" to repo.lastWorkspace().ifBlank { null }, "Org roles" to user.orgRoles.joinToString().ifBlank { null })
                    .forEach { (k, v) -> if (v != null) KeyValueRow(k, v) }
            }
        }
        item(key = "secure") {
            SectionCard(padding = PaddingValues(Space.sm)) {
                HubRow(Icons.Outlined.Password, "Change password", "Use at least 12 characters") { onChangePassword() }
            }
        }
        item(key = "sec") { InfoBanner("Your session is stored encrypted on this device and is cleared when you sign out.") }
        item(key = "out") {
            OutlinedButton(onClick = onLogout, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null); Spacer(Modifier.width(Space.sm)); Text("Sign out")
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }
}
