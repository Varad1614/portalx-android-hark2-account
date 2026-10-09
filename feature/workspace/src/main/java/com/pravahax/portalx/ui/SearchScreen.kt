package com.pravahax.portalx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.data.*
import com.pravahax.portalx.data.model.*

/** v0.9: one search across tasks, people, projects, meetings, announcements and documents the user can see. */
@Composable
fun SearchScreen(user: SessionUser, navigate: (String) -> Unit, onPerson: (Person) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val tasks = rememberResource(Endpoint.Tasks)
    val people = rememberResource(Endpoint.Directory, enabled = user.canReadPeople)
    val projects = rememberResource(Endpoint.Projects, enabled = user.can("projects.read"))
    val meetings = rememberResource(Endpoint.Meetings)
    val ann = rememberResource(Endpoint.Announcements, enabled = user.can("comms.read"))
    val docs = rememberResource(Endpoint.Documents, enabled = user.can("documents.read"))
    val all = listOf(tasks, people, projects, meetings, ann, docs)
    val hits = remember(q, tasks.data, people.data, projects.data, meetings.data, ann.data, docs.data) {
        SearchHit.search(q, tasks.data.orEmpty(), people.data.orEmpty(), projects.data.orEmpty(), meetings.data.orEmpty(), ann.data.orEmpty(), docs.data.orEmpty())
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = Space.lg, vertical = Space.sm).focusRequester(focus)) { SearchField(q, { q = it }, "Search tasks, people, projects…") }
        RefreshList(all.any { it.refreshing }, { all.forEach { it.refresh() } }) {
            when {
                q.trim().length < SearchHit.MIN_QUERY -> item(key = "hint") { EmptyState("Search PortalX", "Type at least two letters. Results come from what's already on your device, so search works offline too.", Icons.Outlined.Search) }
                hits.isEmpty() && all.any { it.initialLoading } -> item(key = "sk") { SkeletonList(3, 64.dp) }
                hits.isEmpty() -> item(key = "none") { EmptyState("No matches", "Nothing matches “${q.trim()}”.", Icons.Outlined.SearchOff, "Clear") { q = "" } }
                else -> hits.groupBy { it.kind }.forEach { (kind, list) ->
                    item(key = "h:${kind.name}") { SectionHeader("${kind.label} (${list.size})") }
                    item(key = "l:${kind.name}") {
                        SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
                            list.forEachIndexed { i, h ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                ListRow(h.title, h.subtitle, onClick = { h.person?.let(onPerson) ?: navigate(h.route) },
                                    leading = { CircleIcon(iconFor(kind), MaterialTheme.colorScheme.primary, 36.dp) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun iconFor(k: SearchHit.Kind) = when (k) {
    SearchHit.Kind.Task -> Icons.Outlined.TaskAlt
    SearchHit.Kind.Person -> Icons.Outlined.Badge
    SearchHit.Kind.Project -> Icons.Outlined.FolderOpen
    SearchHit.Kind.Meeting -> Icons.Outlined.Groups
    SearchHit.Kind.Announcement -> Icons.Outlined.Campaign
    SearchHit.Kind.Document -> Icons.Outlined.Description
}
