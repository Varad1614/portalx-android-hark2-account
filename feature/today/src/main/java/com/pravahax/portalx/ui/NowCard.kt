package com.pravahax.portalx.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.data.AppZone
import com.pravahax.portalx.now.*

private fun iconFor(c: Candidate): ImageVector = when {
    c.ruleId.startsWith("attendance.break") -> Icons.Outlined.FreeBreakfast
    c.ruleId.startsWith("attendance.refresh") -> Icons.Outlined.Refresh
    c.ruleId.startsWith("attendance") -> Icons.Outlined.Fingerprint
    c.ruleId.startsWith("meeting") -> Icons.Outlined.VideoCall
    c.ruleId.startsWith("task") -> Icons.Outlined.TaskAlt
    c.ruleId.startsWith("approval") -> Icons.Outlined.Inbox
    c.ruleId == "workday.complete" -> Icons.Outlined.Insights
    c.ruleId.startsWith("workday.holiday") || c.ruleId.startsWith("workday.on-leave") -> Icons.Outlined.BeachAccess
    else -> Icons.Outlined.CheckCircle
}

/**
 * v0.10 PortalX NOW: one action, one reason, a "Why this?" sheet and "Not now". The resolver picks; this only renders.
 * [result] is recomputed by the caller; the [StabilityPolicy] here keeps it from flickering across refreshes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowCard(result: NowResult?, loading: Boolean, navigate: (String) -> Unit, refresh: () -> Unit, onSnooze: (Candidate) -> Unit) {
    val policy = remember { StabilityPolicy() }
    var why by remember { mutableStateOf(false) }
    val p = PortalTheme.status
    SectionCard(Modifier.testTag("now-card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker("Now")
            Spacer(Modifier.weight(1f))
            if (result != null && result.freshness != Freshness.Live)
                StatusChip(when (result.freshness) { Freshness.Updating -> "Updating"; Freshness.Cached -> "Offline data"; else -> "Not loaded" }, p.warning)
        }
        Spacer(Modifier.height(Space.sm))
        if (result == null || (loading && result.ranked.isEmpty() && result.chosen.ruleId == "workday.clear")) {
            Skeleton(24.dp, Modifier.fillMaxWidth(0.6f), MaterialTheme.shapes.small); Spacer(Modifier.height(Space.sm)); Skeleton(44.dp)
            return@SectionCard
        }
        val c = policy.choose(result, System.currentTimeMillis())
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(iconFor(c), if (c.band <= Band.TimeCritical) p.warning else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = "Now: ${c.title}. ${c.detail}" })
                if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Space.md))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            val label = c.actionLabel
            when (val a = c.action) {
                is NowAction.Open -> if (label != null) Button(onClick = { navigate(a.route) }, shape = MaterialTheme.shapes.medium) { Text(label) }
                NowAction.Refresh -> Button(onClick = refresh, shape = MaterialTheme.shapes.medium) { Text(label ?: "Refresh") }
                NowAction.None -> {}
            }
            Spacer(Modifier.weight(1f))
            if (c.ruleId != "workday.clear") TextButton(onClick = { onSnooze(c) }) { Text("Not now") }
            TextButton(onClick = { why = true }) { Text("Why this?") }
        }
    }
    if (why) ModalBottomSheet(onDismissRequest = { why = false }, containerColor = MaterialTheme.colorScheme.surface) {
        val c = policy.current?.candidate ?: result?.chosen
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxxl).navigationBarsPadding()) {
            Text("Why this?", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Space.md))
            if (c != null) {
                Text(c.reason, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Space.md))
                KeyValueRow("Rule", c.ruleId)
                KeyValueRow("Priority", "${c.band.label} · ${c.score}")
                KeyValueRow("Data", result?.freshness?.name ?: "Unknown")
            }
            val others = result?.ranked.orEmpty().filter { it.key != c?.key }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(Space.lg)); Kicker("Also on your plate")
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    others.take(5).forEach { o -> ListRow(o.title, "${o.band.label} · ${o.detail}".trimEnd(' ', '·')) }
                }
            }
        }
    }
}

/** Current IST time, ticking every 30 s so time-based rules (shift start, meeting soon) move on their own. */
@Composable
fun rememberNowZoned(): java.time.ZonedDateTime {
    val ms by rememberNow(30_000)
    return remember(ms) { java.time.Instant.ofEpochMilli(ms).atZone(AppZone) }
}
