package com.pravahax.portalx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.data.*
import com.pravahax.portalx.data.model.*

/** v0.8: one inbox for everything waiting on this manager — pending leave and attendance corrections. */
@Composable
fun ApprovalsScreen(user: SessionUser) {
    val repo = LocalRepo.current
    val online = LocalOnline.current
    val leave = rememberResource(Endpoint.PendingLeaveApprovals, enabled = user.canApproveLeave)
    val corr = rememberResource(Endpoint.Corrections, enabled = user.canApproveCorrections)
    val act = rememberAction()
    var filter by rememberSaveable { mutableStateOf("all") }
    var rejectTarget by remember { mutableStateOf<ApprovalItem?>(null) }
    val all = ApprovalItem.inbox(leave.data.orEmpty(), corr.data.orEmpty())
    val shown = when (filter) { "leave" -> all.filter { it.kind == ApprovalItem.Kind.Leave }; "corr" -> all.filter { it.kind == ApprovalItem.Kind.Correction }; else -> all }
    val refresh = { leave.refresh(); corr.refresh() }

    fun decide(a: ApprovalItem, approve: Boolean) {
        val body = a.decision(approve) ?: return
        val verb = if (approve) "approved" else "rejected"
        act(a.actionKey, "${a.name}: ${a.kind.label.lowercase()} $verb.") { repo.act(a.kind.fn, body.toJson()) }
    }

    RefreshList(leave.refreshing || corr.refreshing, refresh) {
        listOf(leave, corr).firstOrNull { it.error != null }?.let { r -> item(key = "err") { ErrorBanner(r.error ?: "", r.stale) { refresh() } } }
        if (user.canApproveLeave && user.canApproveCorrections && all.isNotEmpty()) item(key = "filters") {
            val nl = all.count { it.kind == ApprovalItem.Kind.Leave }
            FlowRowChips(listOf("all" to "All · ${all.size}", "leave" to "Leave · $nl", "corr" to "Corrections · ${all.size - nl}"), filter) { filter = it }
        }
        when {
            leave.initialLoading || corr.initialLoading -> item(key = "sk") { SkeletonList(3, 112.dp) }
            all.isEmpty() -> item(key = "empty") { EmptyState("You're all caught up", "Leave and attendance correction requests from your team will appear here.", Icons.Outlined.Inbox) }
            shown.isEmpty() -> item(key = "none") { EmptyState("Nothing here", "No requests of this kind right now.", Icons.Outlined.Inbox, "Show all") { filter = "all" } }
            else -> {
                val keys = stableKeysOf(shown, "appr")
                items(shown.size, key = { keys[it] }) { i -> ApprovalCard(shown[i], enabled = online && !act.isRunning(shown[i].actionKey), running = act.isRunning(shown[i].actionKey),
                    onReject = { rejectTarget = shown[i] }, onApprove = { decide(shown[i], true) }) }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }

    rejectTarget?.let { a ->
        ConfirmDialog("Reject this ${a.kind.label.lowercase()}?", "${a.name} will be notified. This can't be undone from the app.", "Reject", destructive = true,
            onConfirm = { decide(a, false) }, onDismiss = { rejectTarget = null })
    }
}

@Composable
private fun ApprovalCard(a: ApprovalItem, enabled: Boolean, running: Boolean, onReject: () -> Unit, onApprove: () -> Unit) {
    val detail = when (a.kind) {
        ApprovalItem.Kind.Leave -> listOfNotNull(a.type, "${fmtShortDate(a.startDate)} → ${fmtShortDate(a.endDate)}",
            a.days?.let { "${fmtNum(it)} day${if (it == 1.0) "" else "s"}" }).joinToString(" · ")
        ApprovalItem.Kind.Correction -> "Attendance · ${fmtDate(a.startDate)}" +
            if (a.checkIn != null || a.checkOut != null) "\nProposed ${fmtTime(a.checkIn)} → ${fmtTime(a.checkOut)}" else ""
    }
    SectionCard {
        ListRow(a.name, detail + (a.note?.let { "\n“$it”" } ?: ""), leading = { Avatar(a.name, 40.dp) },
            trailing = { StatusChip(a.kind.label, if (a.kind == ApprovalItem.Kind.Leave) PortalTheme.status.warning else PortalTheme.status.info, dot = false) })
        Spacer(Modifier.height(Space.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedButton(onClick = onReject, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled && a.id != null, shape = MaterialTheme.shapes.medium) { Text("Reject") }
            Button(onClick = onApprove, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled && a.id != null, shape = MaterialTheme.shapes.medium) { BusyLabel(running, "Approve") }
        }
    }
}
