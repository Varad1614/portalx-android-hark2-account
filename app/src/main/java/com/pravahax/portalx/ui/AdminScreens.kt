package com.pravahax.portalx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.data.*
import com.pravahax.portalx.net.Fn
import kotlinx.serialization.json.*

/**
 * Native screens for everything v0.3.0 opened in a browser. Each reads exactly one Mobile Gateway v1 route
 * (see Fn) and is only reachable when the user holds the permission that route's service checks.
 */

/** A searchable list screen over one resource: the shared shell of the admin/workspace lists. */
@Composable
private fun ListScreen(
    res: Resource, items: List<JsonObject>, prefix: String, emptyTitle: String, emptyBody: String, icon: ImageVector,
    searchHint: String? = null, searchKeys: List<String> = emptyList(), header: (LazyListScopeShim.() -> Unit)? = null,
    row: @Composable (JsonObject) -> Unit,
) {
    var q by rememberSaveable { mutableStateOf("") }
    val ql = q.trim().lowercase()
    val shown = remember(items, ql) { if (ql.isEmpty()) items else items.filter { o -> searchKeys.any { (o.str(it) ?: "").lowercase().contains(ql) } } }
    val keys = stableKeys(shown, prefix)
    RefreshList(res.refreshing, { res.refresh() }) {
        res.error?.let { item(key = "err") { ErrorBanner(it, res.stale) { res.refresh() } } }
        header?.invoke(LazyListScopeShim(this))
        if (searchHint != null && items.isNotEmpty()) item(key = "search") { SearchField(q, { q = it }, searchHint) }
        when {
            res.initialLoading -> item(key = "sk") { SkeletonList(4, 72.dp) }
            items.isEmpty() -> { if (res.error == null) item(key = "empty") { EmptyState(emptyTitle, emptyBody, icon) } }
            shown.isEmpty() -> item(key = "none") { EmptyState("No matches", "Nothing matches “${q.trim()}”.", Icons.Outlined.SearchOff, "Clear search") { q = "" } }
            else -> item(key = "count") { Text("${shown.size} of ${items.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(shown.size, key = { keys[it] }) { i -> row(shown[i]) }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }
}

/** Lets callers add items above the list without exposing LazyListScope generics in the signature. */
class LazyListScopeShim(val scope: androidx.compose.foundation.lazy.LazyListScope)

// ======================= NOTIFICATIONS =======================
@Composable
fun NotificationsScreen() {
    val repo = LocalRepo.current
    val res = rememberResource(Fn.Notifications)
    val act = rememberAction()
    val list = res.data.objects()
    val unread = list.count { !it.bool("read") }
    ListScreen(res, list, "ntf", "No notifications", "Approvals, mentions and updates will appear here.", Icons.Outlined.Notifications,
        header = {
            if (unread > 0) scope.item(key = "readall") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$unread unread", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { act("all", "All marked as read.") { repo.act(Fn.MarkAllNotificationsRead) } }, enabled = !act.isRunning("all")) {
                        BusyLabel(act.isRunning("all"), "Mark all read")
                    }
                }
            }
        }) { n ->
        val id = n.idOf()
        val isUnread = !n.bool("read")
        SectionCard(highlighted = isUnread, onClick = if (isUnread && id != null) ({ act("n-${id.content}") { repo.act(Fn.MarkNotificationRead, buildJsonObject { put("id", id) }) } }) else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.str("title") ?: "Notification", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (isUnread) StatusChip("New", MaterialTheme.colorScheme.primary)
            }
            n.str("body")?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(listOfNotNull(pretty(n.str("type")), n.str("createdAt")?.let { fmtDateTime(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ======================= PROJECTS =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen() {
    val res = rememberResource(Fn.Projects)
    var open by remember { mutableStateOf<JsonObject?>(null) }
    ListScreen(res, res.data.objects(), "prj", "No projects yet", "Projects you can see will appear here.", Icons.Outlined.FolderOpen,
        "Search projects", listOf("name", "code", "status")) { p ->
        SectionCard(onClick = { open = p }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.str("name") ?: "Project", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    p.str("code")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                StatusChip(pretty(p.str("status")), statusColor(p.str("status")))
            }
            val total = p.num("taskCount") ?: 0.0; val done = p.num("tasksDone") ?: 0.0
            Spacer(Modifier.height(Space.sm))
            LinearProgressIndicator(progress = { if (total > 0) (done / total).toFloat().coerceIn(0f, 1f) else 0f },
                Modifier.fillMaxWidth().height(6.dp), drawStopIndicator = {})
            Text("${fmtNum(done)}/${fmtNum(total)} tasks done · ${fmtNum(p.num("memberCount"))} members" + (p.str("endDate")?.let { " · due ${fmtShortDate(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
        }
    }
    open?.let { p -> ProjectSheet(p) { open = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSheet(p: JsonObject, onDismiss: () -> Unit) {
    val id = p.idOf()
    val res = rememberResource(Fn.ProjectDetail, buildJsonObject { put("projectId", id ?: JsonNull) }, key = "project-${id?.content}", enabled = id != null)
    val d = res.data.obj() ?: p
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxl).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Kicker(d.str("code") ?: "Project")
            Text(d.str("name") ?: "Project", style = MaterialTheme.typography.headlineMedium)
            res.error?.let { ErrorBanner(it, res.stale) { res.refresh() } }
            d.str("description")?.let { Spacer(Modifier.height(Space.sm)); Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(Space.md))
            listOf("Status" to d.str("status")?.let { pretty(it) }, "Priority" to d.str("priority")?.let { pretty(it) },
                "Start" to d.str("startDate")?.let { fmtDate(it) }, "End" to d.str("endDate")?.let { fmtDate(it) })
                .forEach { (k, v) -> if (v != null) KeyValueRow(k, v) }
            val members = d.list("members").objects()
            if (members.isNotEmpty()) {
                Spacer(Modifier.height(Space.md)); Text("Members (${members.size})", style = MaterialTheme.typography.titleMedium)
                members.forEach { m -> ListRow(m.str("name") ?: "Member", m.str("role")?.let { pretty(it) }, leading = { Avatar(m.str("name") ?: "?", 32.dp) }) }
            }
            val tasks = d.list("tasks").objects()
            if (res.initialLoading) SkeletonList(2, 48.dp)
            else if (tasks.isNotEmpty()) {
                Spacer(Modifier.height(Space.md)); Text("Tasks (${tasks.size})", style = MaterialTheme.typography.titleMedium)
                tasks.take(50).forEach { t -> ListRow(t.str("title") ?: "Task", listOfNotNull(t.str("assignee"), t.str("dueDate")?.let { fmtShortDate(it) }).joinToString(" · ").ifBlank { null },
                    trailing = { StatusChip(pretty(t.str("status")), statusColor(t.str("status"))) }) }
            }
        }
    }
}

// ======================= DOCUMENTS =======================
@Composable
fun DocumentsScreen() {
    val res = rememberResource(Fn.Documents)
    val docs = res.data.obj().list("documents").objects()
    ListScreen(res, docs, "doc", "No documents", "Documents shared with you will appear here.", Icons.Outlined.Description,
        "Search documents", listOf("title", "filename", "state")) { d ->
        SectionCard {
            ListRow(d.str("title") ?: d.str("filename") ?: "Document",
                listOfNotNull(d.str("filename"), d.num("version")?.let { "v${fmtNum(it)}" }, d.num("size")?.let { fmtBytes(it) }, d.str("owner"), d.str("createdAt")?.let { fmtShortDate(it) }).joinToString(" · "),
                leading = { CircleIcon(Icons.Outlined.Description, MaterialTheme.colorScheme.primary) },
                trailing = { StatusChip(pretty(d.str("state")), statusColor(d.str("state"))) })
        }
    }
}

internal fun fmtBytes(b: Double): String = when {
    b < 1024 -> "${fmtNum(b)} B"
    b < 1024 * 1024 -> "${fmtNum(Math.round(b / 102.4) / 10.0)} KB"
    else -> "${fmtNum(Math.round(b / 104857.6) / 10.0)} MB"
}

// ======================= PERFORMANCE & FEEDBACK =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(user: SessionUser) {
    val repo = LocalRepo.current
    val res = rememberResource(Fn.PerformanceReviews)
    val people = rememberResource(Fn.Directory, enabled = user.canReadPeople)
    val act = rememberAction()
    var giving by rememberSaveable { mutableStateOf(false) }
    ListScreen(res, res.data.objects(), "rev", "No reviews yet", "Your performance reviews will appear here once they're shared.", Icons.Outlined.Insights,
        header = {
            scope.item(key = "give") {
                OutlinedButton(onClick = { giving = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium, enabled = user.canReadPeople) {
                    Icon(Icons.Outlined.RateReview, null); Spacer(Modifier.width(Space.sm)); Text("Give feedback to a colleague")
                }
            }
            scope.item(key = "rh") { SectionHeader("My reviews") }
        }) { r ->
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.str("period") ?: "Review", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                r.num("rating")?.let { StatusChip("${fmtNum(it)} / 5", MaterialTheme.colorScheme.primary, dot = false) }
            }
            Text(listOfNotNull(pretty(r.str("status")), r.str("reviewer")?.let { "by $it" }, r.str("at")?.let { fmtShortDate(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("Strengths" to r.str("strengths"), "To improve" to r.str("improvements"), "Goals" to r.str("goals")).forEach { (k, v) ->
                if (v != null) { Spacer(Modifier.height(Space.sm)); Text(k, style = MaterialTheme.typography.labelLarge); Text(v, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    if (giving) {
        val options = people.data.objects().filter { it.str("id") != user.id }.mapNotNull { o -> o.idOf()?.let { it to (o.str("name") ?: "Member") } }
        var to by remember { mutableStateOf<JsonPrimitive?>(null) }
        var category by rememberSaveable { mutableStateOf("peer") }
        var text by rememberSaveable { mutableStateOf("") }
        var score by rememberSaveable { mutableIntStateOf(5) }
        var err by remember { mutableStateOf<String?>(null) }
        val busy = act.isRunning("fb")
        ModalBottomSheet(onDismissRequest = { giving = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxl).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Kicker("Performance"); Text("Give feedback", style = MaterialTheme.typography.headlineMedium)
                err?.let { ErrorBanner(it) }
                Picker("Colleague", null, options, to) { to = it; err = null }
                FlowRowChips(listOf("peer" to "Peer", "praise" to "Praise", "improvement" to "Improvement"), category) { category = it }
                Text("Score: $score / 5", style = MaterialTheme.typography.labelLarge)
                Slider(score.toFloat(), { score = it.toInt().coerceIn(1, 5) }, valueRange = 1f..5f, steps = 3)
                OutlinedTextField(text, { text = it.take(2000); err = null }, Modifier.fillMaxWidth(), label = { Text("Feedback") }, minLines = 3, shape = MaterialTheme.shapes.medium,
                    supportingText = { Text("${text.length}/2000") })
                Button(enabled = !busy, onClick = {
                    val target = to
                    err = when { target == null -> "Choose a colleague."; text.trim().length < 5 -> "Write at least a sentence."; else -> null }
                    if (err == null && target != null) act("fb", "Feedback sent.", onDone = { giving = false }) {
                        repo.act(Fn.GiveFeedback, buildJsonObject {
                            put("revieweeId", target); put("category", category); put("content", text.trim()); put("score", score)
                        })
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { BusyLabel(busy, "Send feedback") }
            }
        }
    }
}

// ======================= TEAMS =======================
@Composable
fun TeamsScreen() {
    val res = rememberResource(Fn.Teams)
    ListScreen(res, res.data.objects(), "team", "No teams yet", "Teams set up by your admin will appear here.", Icons.Outlined.Diversity3,
        "Search teams", listOf("name", "lead", "manager")) { t ->
        SectionCard {
            ListRow(t.str("name") ?: "Team",
                listOfNotNull(t.str("lead")?.let { "Lead: $it" }, t.str("manager")?.let { "Manager: $it" }, "${fmtNum(t.num("memberCount"))} members").joinToString(" · "),
                leading = { Avatar(t.str("name") ?: "?", 40.dp) })
            t.str("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ======================= USERS =======================
@Composable
fun UsersScreen() {
    val res = rememberResource(Fn.Users)
    ListScreen(res, res.data.objects(), "usr", "No users", "Members of this workspace will appear here.", Icons.Outlined.ManageAccounts,
        "Search name, User ID, email", listOf("name", "userId", "email", "team")) { u ->
        SectionCard {
            val roles = u.list("organizationRoles").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ListRow(u.str("name") ?: "Member",
                listOfNotNull(u.str("userId"), u.str("email"), u.str("team"), roles.joinToString().ifBlank { null }).joinToString(" · "),
                leading = { Avatar(u.str("name") ?: "?", 40.dp) },
                trailing = { StatusChip(pretty(u.str("status")), statusColor(u.str("status"))) })
            if (u.bool("mustChangePassword")) Text("Must change password at next sign-in", style = MaterialTheme.typography.bodySmall, color = PortalTheme.status.warning)
        }
    }
}

// ======================= ACCESS CONTROL =======================
@Composable
fun AccessScreen(user: SessionUser) {
    val canRoles = user.can("organization.roles.manage") && user.can("organization.members.read")
    val res = rememberResource(Fn.AccessRoles, enabled = canRoles)
    val roles = res.data.obj().list("roles").objects()
    val mine = user.permissions.orEmpty().sorted()
    RefreshList(res.refreshing, { res.refresh() }) {
        res.error?.let { item(key = "err") { ErrorBanner(it, res.stale) { res.refresh() } } }
        item(key = "mh") { SectionHeader("My access (${mine.size})") }
        item(key = "mine") {
            SectionCard {
                if (mine.isEmpty()) Text("No permissions loaded yet. Pull to refresh on More.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else mine.groupBy { it.substringBefore('.') }.forEach { (area, codes) ->
                    KeyValueRow(pretty(area), codes.joinToString { pretty(it.substringAfter('.')) })
                }
            }
        }
        if (canRoles) {
            item(key = "rh") { SectionHeader("Roles (${roles.size})") }
            if (res.initialLoading) item(key = "sk") { SkeletonList(3, 72.dp) }
            val keys = stableKeys(roles, "role")
            items(roles.size, key = { keys[it] }) { i ->
                val r = roles[i]
                val perms = r.list("permissionCodes").ifEmpty { r.list("permissions") }.size
                val assigned = r.list("assignedUserIds").size
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.str("name") ?: "Role", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (r.bool("system")) StatusChip("System", PortalTheme.status.neutral, dot = false)
                    }
                    r.str("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("$perms permissions · $assigned assigned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }
}

// ======================= AUDIT LOGS =======================
@Composable
fun AuditScreen() {
    val res = rememberResource(Fn.AuditLogs, buildJsonObject { put("limit", 100) })
    ListScreen(res, res.data.objects(), "aud", "No audit events", "Security-relevant actions will be listed here.", Icons.Outlined.Policy,
        "Search action or person", listOf("action", "actor", "actorUid", "entityType")) { a ->
        SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.sm)) {
            ListRow(a.str("action") ?: "event",
                listOfNotNull(a.str("actor"), a.str("actorUid"), a.str("entityType")?.let { e -> e + (a.str("entityId")?.let { " #$it" } ?: "") }, a.str("createdAt")?.let { fmtDateTime(it) }).joinToString(" · "))
        }
    }
}

// ======================= COMPANY SETTINGS =======================
@Composable
fun SettingsScreen() {
    val res = rememberResource(Fn.Settings)
    val s = res.data.obj()
    RefreshList(res.refreshing, { res.refresh() }) {
        res.error?.let { item(key = "err") { ErrorBanner(it, res.stale) { res.refresh() } } }
        item(key = "org") {
            if (res.initialLoading) Skeleton(200.dp)
            else if (s != null) SectionCard {
                listOf("Company" to s.str("name"), "Legal name" to s.str("legalName"), "Workspace" to s.str("workspaceSlug"), "Timezone" to s.str("timezone"),
                    "Primary colour" to s.str("primaryColor"), "Secondary colour" to s.str("secondaryColor"))
                    .forEach { (k, v) -> if (v != null) KeyValueRow(k, v) }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }
}
