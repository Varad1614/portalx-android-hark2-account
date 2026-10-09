package com.pravahax.portalx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.data.*
import com.pravahax.portalx.data.model.*
import java.time.format.TextStyle
import java.util.Locale

/** v0.9: personal attendance and leave insights, computed on-device from data the app already has. */
@Composable
fun InsightsScreen() {
    val history = rememberResource(Endpoint.AttendanceHistory)
    val leave = rememberResource(Endpoint.MyLeave)
    val ins = remember(history.data) { AttendanceInsights.from(history.data.orEmpty()) }
    val li = LeaveInsights.from(leave.data)

    RefreshList(history.refreshing || leave.refreshing, { history.refresh(); leave.refresh() }) {
        history.error?.let { e -> item(key = "err") { ErrorBanner(e, history.stale) { history.refresh() } } }
        item(key = "h30") { SectionHeader("Last 30 days") }
        if (history.initialLoading) { item(key = "sk") { SkeletonList(2, 96.dp) }; return@RefreshList }
        if (ins.daysPresent == 0) { item(key = "empty") { EmptyState("No attendance yet", "Insights appear once you've checked in for a few days.", Icons.Outlined.Insights) }; return@RefreshList }
        item(key = "tiles") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    Tile(Icons.Outlined.EventAvailable, "Days present", "${ins.daysPresent}", Modifier.weight(1f))
                    Tile(Icons.Outlined.LocalFireDepartment, "Current streak", "${ins.streak} day${if (ins.streak == 1) "" else "s"}", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    Tile(Icons.Outlined.Login, "Avg check-in", ins.avgCheckInMinutes?.let { clock(it) } ?: "—", Modifier.weight(1f))
                    Tile(Icons.Outlined.Schedule, "Avg day", ins.avgWorkedSeconds?.let { fmtDuration(it) } ?: "—", Modifier.weight(1f))
                }
                ins.onTimeRate?.let { Tile(Icons.Outlined.TaskAlt, "On time", "$it%" + if (ins.lateDays > 0) " · ${ins.lateDays} late" else "", Modifier.fillMaxWidth()) }
            }
        }
        item(key = "chart-h") { SectionHeader("Hours, last 14 days") }
        item(key = "chart") { SectionCard { HoursChart(ins.daily) } }
        li?.let { l ->
            item(key = "leave-h") { SectionHeader("Leave this year") }
            item(key = "leave") {
                SectionCard {
                    Text("${fmtNum(l.used)} of ${fmtNum(l.allocated)} days used · ${fmtNum(l.remaining)} left", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Space.sm))
                    LinearProgressIndicator({ l.usedPercent / 100f }, Modifier.fillMaxWidth().height(8.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }
}

private fun clock(min: Int): String {
    val h = min / 60; val m = min % 60
    return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}

@Composable
private fun Tile(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    SectionCard(modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Space.sm))
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HoursChart(daily: List<Pair<java.time.LocalDate, Long>>) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val max = maxOf(9 * 3600L, daily.maxOfOrNull { it.second } ?: 0L).toFloat()
    val desc = daily.filter { it.second > 0 }.joinToString { "${it.first.dayOfMonth} ${it.first.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}: ${fmtDuration(it.second)}" }
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Hours worked per day. $desc" }) {
        val n = daily.size.coerceAtLeast(1)
        val slot = size.width / n
        val w = slot * 0.6f
        daily.forEachIndexed { i, (_, s) ->
            val x = i * slot + (slot - w) / 2
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 3))
            val h = size.height * (s / max)
            if (h > 0) drawRoundRect(bar, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 3))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        daily.forEachIndexed { i, (d, _) ->
            Text(if (i % 2 == 0) d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH) else "", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
