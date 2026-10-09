package com.pravahax.portalx.ui

import com.pravahax.portalx.data.ApplyLeaveRequest
import com.pravahax.portalx.data.TaskCommentRequest
import com.pravahax.portalx.data.TaskStatusRequest
import com.pravahax.portalx.data.toJson
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.data.*
import com.pravahax.portalx.net.Fn
import kotlinx.serialization.json.*
import java.time.LocalDate

private val taskStatuses = listOf("todo" to "To Do", "in_progress" to "In Progress", "review" to "Review", "done" to "Done")

// ======================= TASKS =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(user: SessionUser, createRequested: Boolean, onCreateHandled: () -> Unit) {
    val repo = LocalRepo.current
    val tasks = rememberResource(Fn.Tasks)
    var filter by rememberSaveable { mutableStateOf("open") }
    var q by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<JsonObject?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val act = rememberAction()
    val haptics = rememberHaptics()
    LaunchedEffect(createRequested) { if (createRequested) { creating = true; onCreateHandled() } }
    val all = tasks.data.objects()
    val ql = q.trim().lowercase()
    fun overdue(t: JsonObject) = Dates.isOverdue(t.str("dueDate", "due_date"), t.str("status") == "done")
    val shown = remember(all, filter, ql) {
        all.filter { t ->
            when (filter) { "all" -> true; "open" -> t.str("status") != "done"; "overdue" -> overdue(t); else -> t.str("status") == filter }
        }.filter { t -> ql.isEmpty() || listOf("title", "projectName", "assignee").any { (t.str(it) ?: "").lowercase().contains(ql) } }
            // Done last, then most urgent due date first; undated after dated.
            .sortedWith(compareBy<JsonObject>({ it.str("status") == "done" }, { Dates.day(it.str("dueDate", "due_date"))?.toEpochDay() ?: Long.MAX_VALUE }))
    }
    val keys = stableKeys(shown, "task")
    val label = taskStatuses.toMap()

    fun setStatus(t: JsonObject, s: String, msg: String) {
        val id = t.idOf() ?: return
        val prev = t.str("status")
        act("task-${id.content}", msg, undo = prev?.takeIf { it != s }?.let { p -> {
            act("task-undo-${id.content}", "Moved back to ${label[p] ?: pretty(p)}.") { repo.submit(Fn.UpdateTaskStatus, TaskStatusRequest(id, p).toJson()) }
        } }) { repo.submit(Fn.UpdateTaskStatus, TaskStatusRequest(id, s).toJson()) }
    }

    RefreshList(tasks.refreshing, { tasks.refresh() }) {
        tasks.error?.let { item(key = "err") { ErrorBanner(it, tasks.stale) { tasks.refresh() } } }
        item(key = "search") { SearchField(q, { q = it }, "Search tasks, projects, people") }
        item(key = "filters") {
            val chips = listOf("open" to "Open", "overdue" to "Overdue") + taskStatuses + ("all" to "All")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(chips, key = { it.first }) { (v, l) ->
                    val n = when (v) { "all" -> all.size; "open" -> all.count { it.str("status") != "done" }; "overdue" -> all.count { overdue(it) }; else -> all.count { it.str("status") == v } }
                    FilterChip(filter == v, { filter = v }, { Text("$l · $n") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
                }
            }
        }
        if (tasks.initialLoading) item(key = "sk") { SkeletonList(4, 104.dp) }
        else if (shown.isEmpty()) item(key = "empty") {
            when {
                all.isEmpty() -> EmptyState("No tasks yet", if (user.canManageTasks) "Tap New task to create the first one." else "Tasks assigned to you will appear here.", Icons.Outlined.TaskAlt)
                ql.isNotEmpty() -> EmptyState("No matches", "Nothing matches “${q.trim()}”. Try another word or clear the search.", Icons.Outlined.SearchOff, "Clear search") { q = "" }
                filter == "open" || filter == "overdue" -> EmptyState("You're all caught up", "No ${if (filter == "overdue") "overdue" else "open"} tasks. Nice work.", Icons.Outlined.TaskAlt, "Show all") { filter = "all" }
                else -> EmptyState("Nothing in this view", "No tasks are in this status right now.", Icons.Outlined.TaskAlt, "Show all") { filter = "all" }
            }
        }
        else item(key = "hint") { Text("${shown.size} task${if (shown.size == 1) "" else "s"} · swipe right to mark done", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown.size, key = { keys[it] }) { i ->
            val t = shown[i]
            val done = t.str("status") == "done"
            val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                if (v == SwipeToDismissBoxValue.StartToEnd && !done) { haptics.tap(); setStatus(t, "done", "“${(t.str("title") ?: "Task").take(40)}” marked done.") }
                false // never remove the row; the refreshed list reflects the new status
            }, positionalThreshold = { it * 0.4f })
            SwipeToDismissBox(dismiss, enableDismissFromEndToStart = false, enableDismissFromStartToEnd = !done, backgroundContent = {
                Box(Modifier.fillMaxSize().padding(horizontal = Space.xxl), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Done, null, tint = PortalTheme.status.success); Spacer(Modifier.width(6.dp)); Text("Done", color = PortalTheme.status.success)
                    }
                }
            }) {
                TaskCard(t, busy = t.idOf()?.let { act.isRunning("task-${it.content}") } == true, onStatus = { s -> setStatus(t, s, "Moved to ${label[s] ?: pretty(s)}.") }) { open = t }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(96.dp)) }
    }

    open?.let { TaskDetailSheet(it, user) { open = null } }
    if (creating) CreateTaskSheet(user, busy = act.isRunning("create"), onDismiss = { creating = false }) { body ->
        // work-api requires the acting user and an initial status; the gateway forwards both from the body.
        val full = JsonObject(body + mapOf("actorUserId" to (user.id.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonNull), "status" to JsonPrimitive("todo")))
        act("create", "Task created.", onDone = { creating = false }) { repo.act(Fn.CreateTask, full) }
    }
}

@Composable
private fun TaskCard(t: JsonObject, busy: Boolean, onStatus: (String) -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val due = t.str("dueDate", "due_date")
    val status = t.str("status")
    val overdue = Dates.isOverdue(due, status == "done")
    SectionCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t.str("title") ?: "Untitled", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(t.str("projectName"), t.str("assignee")?.let { "→ $it" }).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Space.sm))
            StatusChip(pretty(t.str("priority")), statusColor(t.str("priority")))
        }
        Spacer(Modifier.height(Space.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (due != null) {
                val c = if (overdue) PortalTheme.status.danger else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(Icons.Outlined.Event, null, Modifier.size(16.dp), tint = c)
                Spacer(Modifier.width(Space.xs))
                Text(Dates.dueLabel(due, status == "done"), style = MaterialTheme.typography.bodySmall, color = c)
            }
            Spacer(Modifier.weight(1f))
            Box {
                AssistChip(onClick = { menu = true }, enabled = !busy,
                    label = { Text(taskStatuses.firstOrNull { it.first == status }?.second ?: pretty(status)) },
                    leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(status))) },
                    trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp)) })
                DropdownMenu(menu, { menu = false }) {
                    taskStatuses.forEach { (v, l) ->
                        DropdownMenuItem({ Text(l) }, { menu = false; if (v != status) onStatus(v) },
                            trailingIcon = if (v == status) ({ Icon(Icons.Outlined.Check, null) }) else null)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailSheet(task: JsonObject, user: SessionUser, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val id = task.idOf()
    val detail = rememberResource(Fn.TaskDetail, buildJsonObject { put("taskId", id ?: JsonNull) }, key = "task-${id?.content}", enabled = id != null)
    var comment by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val act = rememberAction()
    val d = detail.data.obj() ?: task
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxl).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
            Kicker(d.str("projectName") ?: "Task")
            Spacer(Modifier.height(Space.xs))
            Text(d.str("title") ?: "Untitled", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                StatusChip(pretty(d.str("status")), statusColor(d.str("status")))
                StatusChip(pretty(d.str("priority")), statusColor(d.str("priority")))
            }
            detail.error?.let { Spacer(Modifier.height(Space.sm)); ErrorBanner(it, detail.stale) { detail.refresh() } }
            d.str("description")?.let {
                Spacer(Modifier.height(Space.md))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(Space.md).fillMaxWidth())
                }
            }
            Spacer(Modifier.height(Space.md))
            listOf("Assignee" to d.str("assignee"), "Due" to d.str("dueDate", "due_date")?.let { fmtDate(it) }, "Created by" to d.str("creator", "createdBy"))
                .forEach { (k, v) -> if (v != null) KeyValueRow(k, v) }
            Spacer(Modifier.height(Space.lg))
            val comments = d.list("comments").objects()
            Text("Comments (${comments.size})", style = MaterialTheme.typography.titleMedium)
            if (detail.initialLoading) SkeletonList(2, 52.dp)
            else if (comments.isEmpty()) Text("No comments yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Space.sm))
            comments.forEach { c ->
                val who = c.str("author", "authorName", "name") ?: "Someone"
                ListRow(who, c.str("body"), leading = { Avatar(who, 32.dp) },
                    trailing = { Text(fmtShortDate(c.str("at", "createdAt", "created_at")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val sending = act.isRunning("comment")
            OutlinedTextField(comment, { comment = it.take(2000); err = null }, Modifier.fillMaxWidth().padding(top = Space.sm), placeholder = { Text("Add a comment…") },
                shape = MaterialTheme.shapes.medium, enabled = id != null,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                trailingIcon = {
                    IconButton(enabled = comment.isNotBlank() && !sending && id != null, onClick = {
                        val body = comment.trim()
                        Validate.comment(body)?.let { err = it; return@IconButton }
                        val taskId = id ?: return@IconButton
                        act("comment", "Comment posted.", onDone = { comment = "" }) { repo.submit(Fn.AddTaskComment, TaskCommentRequest(taskId, body).toJson()) }
                    }) { if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
                })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTaskSheet(user: SessionUser, busy: Boolean, onDismiss: () -> Unit, onCreate: (JsonObject) -> Unit) {
    val people = rememberResource(Fn.Directory, enabled = user.canReadPeople)
    val projects = rememberResource(Fn.Projects, enabled = user.can("projects.read"))
    var title by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf("medium") }
    var due by rememberSaveable { mutableStateOf<String?>(null) }
    var projectId by remember { mutableStateOf<JsonPrimitive?>(null) }
    var assigneeId by remember { mutableStateOf<JsonPrimitive?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxl).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Kicker("Work")
            Text("New task", style = MaterialTheme.typography.headlineMedium)
            err?.let { ErrorBanner(it) }
            val cap = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = androidx.compose.ui.text.input.ImeAction.Next)
            OutlinedTextField(title, { title = it.take(200); err = null }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                keyboardOptions = cap, isError = err != null && title.isBlank())
            OutlinedTextField(desc, { desc = it.take(4000) }, Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2, shape = MaterialTheme.shapes.medium,
                keyboardOptions = cap.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Default))
            Text("Priority", style = MaterialTheme.typography.labelLarge)
            FlowRowChips(listOf("low" to "Low", "medium" to "Medium", "high" to "High"), priority) { priority = it }
            Picker("Project", "No project", projects.data.objects().mapNotNull { o -> o.idOf()?.let { it to (o.str("name") ?: "Project") } }, projectId) { projectId = it }
            val meOpt = user.id.toLongOrNull()?.let { listOf(JsonPrimitive(it) to "Me (${user.name})") }.orEmpty()
            val ppl = people.data.objects().mapNotNull { o -> o.idOf()?.let { it to (o.str("name") ?: "Member") } }
            Picker("Assignee", "Unassigned", ppl.ifEmpty { meOpt }, assigneeId) { assigneeId = it }
            OutlinedButton(onClick = { pickDate = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Outlined.Event, null); Spacer(Modifier.width(Space.sm)); Text(due?.let { "Due ${fmtDate(it)}" } ?: "Set due date")
            }
            Button(enabled = !busy, onClick = {
                err = Validate.task(title)
                if (err == null) onCreate(buildJsonObject {
                    put("title", title.trim()); desc.trim().takeIf { it.isNotEmpty() }?.let { put("description", it) }; put("priority", priority); put("dueDate", due?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("projectId", projectId ?: JsonNull); put("assigneeId", assigneeId ?: JsonNull)
                })
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { BusyLabel(busy, "Create task") }
        }
    }
    if (pickDate) DateDialog(due, { due = it; pickDate = false }, { pickDate = false }) { d -> !d.isBefore(Dates.today()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(label: String, none: String?, options: List<Pair<T, String>>, selected: T?, onSelect: (T?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(options.firstOrNull { it.first == selected }?.second ?: none ?: "Select…", {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded, { expanded = false }) {
            if (none != null) DropdownMenuItem({ Text(none) }, { onSelect(null); expanded = false })
            options.forEach { (id, name) -> DropdownMenuItem({ Text(name) }, { onSelect(id); expanded = false }) }
        }
    }
}

// ======================= LEAVE =======================
@Composable
fun LeaveScreen(user: SessionUser, applyRequested: Boolean, onApplyHandled: () -> Unit) {
    val repo = LocalRepo.current
    val leave = rememberResource(Fn.MyLeave)
    val approvals = rememberResource(Fn.PendingLeaveApprovals, enabled = user.canApproveLeave)
    var applying by rememberSaveable { mutableStateOf(false) }
    var rejectTarget by remember { mutableStateOf<JsonObject?>(null) }
    val act = rememberAction()
    val online = LocalOnline.current
    LaunchedEffect(applyRequested) { if (applyRequested) { applying = true; onApplyHandled() } }
    val balances = leave.data.obj().list("balances").objects()
    val requests = leave.data.obj().list("requests").objects()
    val pending = approvals.data.objects()
    var reqFilter by rememberSaveable { mutableStateOf("all") }

    fun decide(r: JsonObject, decision: String) {
        val id = r.idOf() ?: return
        act("leave-${id.content}", "${r.str("name") ?: "Request"}: leave $decision.") { repo.act(Fn.DecideLeave, buildJsonObject { put("id", id); put("decision", decision) }) }
    }

    RefreshList(leave.refreshing || approvals.refreshing, { leave.refresh(); approvals.refresh() }) {
        listOf(leave, approvals).firstOrNull { it.error != null }?.let { r -> item(key = "err") { ErrorBanner(r.error ?: "", r.stale) { leave.refresh(); approvals.refresh() } } }
        item(key = "bal") {
            when {
                leave.initialLoading -> Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) { repeat(2) { Skeleton(120.dp, Modifier.weight(1f)) } }
                balances.isEmpty() -> SectionCard { Text("No leave types configured yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    items(balances.size, key = { "bal:" + (balances[it].str("typeId", "type") ?: "") + ":" + it }) { i ->
                        val b = balances[i]
                        SectionCard(Modifier.width(172.dp)) {
                            Text(b.str("type") ?: "Leave", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(fmtNum(b.num("remaining")), style = MaterialTheme.typography.displaySmall)
                                Text(" / ${fmtNum(b.num("allocated"))} days", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            val unpaid = (b["paid"] as? JsonPrimitive)?.booleanOrNull == false
                            Text("${fmtNum(b.num("used"))} used" + if (unpaid) " · unpaid" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (user.canApproveLeave && pending.isNotEmpty()) {
            item(key = "ph") { SectionHeader("Pending approvals (${pending.size})") }
            val keys = stableKeys(pending, "appr")
            items(pending.size, key = { keys[it] }) { i ->
                val r = pending[i]
                val id = r.idOf()
                val k = "leave-${id?.content}"
                val running = act.isRunning(k)
                SectionCard {
                    val name = r.str("name") ?: "Member"
                    val days = r.num("days")?.let { "${fmtNum(it)} day${if (it == 1.0) "" else "s"}" }
                    ListRow(name,
                        listOfNotNull(r.str("type"), "${fmtShortDate(r.str("startDate"))} → ${fmtShortDate(r.str("endDate"))}", days).joinToString(" · ") +
                            (r.str("reason")?.let { "\n“$it”" } ?: ""),
                        leading = { Avatar(name, 40.dp) })
                    Spacer(Modifier.height(Space.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        OutlinedButton(onClick = { rejectTarget = r }, Modifier.weight(1f), enabled = id != null && !running && online, shape = MaterialTheme.shapes.medium) { Text("Reject") }
                        Button(onClick = { decide(r, "approved") }, Modifier.weight(1f), enabled = id != null && !running && online, shape = MaterialTheme.shapes.medium) { BusyLabel(running, "Approve") }
                    }
                }
            }
        }
        item(key = "rh") { SectionHeader("My requests") }
        if (requests.isNotEmpty()) item(key = "rfilter") {
            val opts = listOf("all", "pending", "approved", "rejected", "cancelled").filter { v -> v == "all" || requests.any { it.str("status")?.lowercase() == v } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(opts, key = { it }) { v ->
                    val n = if (v == "all") requests.size else requests.count { it.str("status")?.lowercase() == v }
                    FilterChip(reqFilter == v, { reqFilter = v }, { Text("${pretty(v)} · $n") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
                }
            }
        }
        val shownReq = if (reqFilter == "all") requests else requests.filter { it.str("status")?.lowercase() == reqFilter }
        when {
            leave.initialLoading -> item(key = "rsk") { SkeletonList(2, 64.dp) }
            requests.isEmpty() -> item(key = "rempty") { EmptyState("No leave requests yet", "Planning time off? Tap Apply — dates and type are pre-filled, you just add a reason.", Icons.Outlined.BeachAccess) }
            shownReq.isEmpty() -> item(key = "rnone") { EmptyState("Nothing here", "No ${reqFilter} requests.", Icons.Outlined.BeachAccess, "Show all") { reqFilter = "all" } }
            else -> item(key = "rlist") {
                SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
                    shownReq.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val days = r.num("days")?.let { "${fmtNum(it)} day${if (it == 1.0) "" else "s"}" }
                        ListRow(r.str("type") ?: "Leave",
                            listOfNotNull("${fmtShortDate(r.str("startDate"))} → ${fmtShortDate(r.str("endDate"))}", days, r.str("reviewer")?.let { "by $it" }).joinToString(" · "),
                            trailing = { StatusChip(pretty(r.str("status")), statusColor(r.str("status"))) })
                    }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(96.dp)) }
    }

    rejectTarget?.let { r ->
        ConfirmDialog("Reject this leave request?", "${r.str("name") ?: "The member"}'s ${r.str("type") ?: "leave"} request will be declined and they'll be notified. This can't be undone from the app.",
            "Reject", destructive = true, onConfirm = { decide(r, "rejected") }, onDismiss = { rejectTarget = null })
    }
    if (applying) ApplyLeaveSheet(balances, busy = act.isRunning("apply"), onDismiss = { applying = false }) { body ->
        act("apply", "Leave request submitted.", onDone = { applying = false }) { repo.submit(Fn.ApplyLeave, body.toJson()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplyLeaveSheet(balances: List<JsonObject>, busy: Boolean, onDismiss: () -> Unit, onSubmit: (ApplyLeaveRequest) -> Unit) {
    val types = balances.mapNotNull { b -> b.idOf("typeId", "leaveTypeId", "id")?.let { it to b } }
    // Sensible defaults: the first type that still has balance, starting tomorrow for one day.
    var typeId by remember { mutableStateOf((types.firstOrNull { (it.second.num("remaining") ?: 0.0) > 0 } ?: types.firstOrNull())?.first) }
    var start by rememberSaveable { mutableStateOf<String?>(Dates.today().plusDays(1).toString()) }
    var end by rememberSaveable { mutableStateOf<String?>(Dates.today().plusDays(1).toString()) }
    var half by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    var showErr by remember { mutableStateOf(false) }
    val s = start?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val e = end?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val remaining = types.firstOrNull { it.first == typeId }?.second?.num("remaining")
    val check = Validate.leave(typeId, s, e, half, reason, remaining)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxl).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Kicker("Time off")
            Text("Apply for leave", style = MaterialTheme.typography.headlineMedium)
            if (types.isEmpty()) InfoBanner("No leave types are configured for you yet. Ask your administrator.")
            Picker("Leave type", null, types.map { (id, b) -> id to "${b.str("type") ?: "Leave"} · ${fmtNum(b.num("remaining"))} left" }, typeId) { typeId = it }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedButton(onClick = { picking = "start" }, Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) { Text(start?.let { "From ${fmtShortDate(it)}" } ?: "Start date") }
                OutlinedButton(onClick = { picking = "end" }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !half, shape = MaterialTheme.shapes.medium) { Text(end?.let { "To ${fmtShortDate(it)}" } ?: "End date") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(half, { half = it; if (it) end = start }); Spacer(Modifier.width(Space.md))
                Column { Text("Half day", style = MaterialTheme.typography.titleSmall); Text("Start and end on the same date.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            OutlinedTextField(reason, { reason = it.take(500); showErr = false }, Modifier.fillMaxWidth(), label = { Text("Reason") }, placeholder = { Text("e.g. Family function in Pune") }, minLines = 2,
                shape = MaterialTheme.shapes.medium, supportingText = { Text("${reason.length}/500") }, isError = showErr && reason.isBlank(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences))
            if (check.days > 0) Text("${fmtNum(check.days)} calendar day${if (check.days == 1.0) "" else "s"} requested", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            check.warning?.let { InfoBanner(it) }
            if (showErr) check.error?.let { ErrorBanner(it) }
            Button(enabled = !busy, onClick = {
                if (check.error != null) { showErr = true; return@Button }
                val sd = s ?: return@Button; val ed = e ?: return@Button; val tid = typeId ?: return@Button
                onSubmit(ApplyLeaveRequest(leaveTypeId = tid, startDate = sd.toString(), endDate = ed.toString(), halfDay = half, reason = reason.trim()))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { BusyLabel(busy, "Submit request") }
        }
    }
    picking?.let { which ->
        DateDialog(if (which == "start") start else end ?: start, { picked ->
            val d = LocalDate.parse(picked)
            if (which == "start") {
                start = picked
                val curEnd = end?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (half || curEnd == null || curEnd.isBefore(d)) end = picked
            } else end = picked
            picking = null
        }, { picking = null }) { d ->
            if (which == "end") s == null || !d.isBefore(s) else !d.isAfter(Dates.today().plusYears(1))
        }
    }
}
