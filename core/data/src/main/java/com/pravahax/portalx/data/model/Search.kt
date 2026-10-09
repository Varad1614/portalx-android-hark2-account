package com.pravahax.portalx.data.model

/** v0.9: one result of the app-wide search. [route] is the screen it opens; [person] opens the contact sheet instead. */
data class SearchHit(val kind: Kind, override val key: String?, val title: String, val subtitle: String?, val route: String, val person: Person? = null) : Keyed {
    enum class Kind(val label: String) { Task("Tasks"), Person("People"), Project("Projects"), Meeting("Meetings"), Announcement("Announcements"), Document("Documents") }

    companion object {
        const val MIN_QUERY = 2
        const val PER_KIND = 8

        /**
         * Case-insensitive search over what the user can already see. Every word must match some field. Title matches
         * rank before matches elsewhere; each kind is capped at [PER_KIND]. A query shorter than [MIN_QUERY] finds nothing.
         */
        fun search(
            query: String, tasks: List<Task> = emptyList(), people: List<Person> = emptyList(), projects: List<Project> = emptyList(),
            meetings: List<Meeting> = emptyList(), announcements: List<Announcement> = emptyList(), documents: List<Document> = emptyList(),
        ): List<SearchHit> {
            val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.joinToString("").length < MIN_QUERY) return emptyList()
            fun <T> pick(items: List<T>, title: (T) -> String?, fields: (T) -> List<String?>, hit: (T) -> SearchHit): List<SearchHit> =
                items.mapNotNull { t ->
                    val all = (fields(t) + title(t)).joinToString(" \u0000 ") { (it ?: "").lowercase() }
                    if (words.all { it in all }) t to words.all { it in (title(t) ?: "").lowercase() } else null
                }.sortedByDescending { it.second }.take(PER_KIND).map { hit(it.first) }
            return pick(tasks, { it.title }, { listOf(it.description, it.projectName, it.assignee, it.status) }) {
                SearchHit(Kind.Task, "task:${it.key}", it.title ?: "Task", listOfNotNull(it.projectName, it.assignee).joinToString(" · ").ifEmpty { null }, "tasks")
            } + pick(people, { it.name }, { listOf(it.userId, it.team, it.roleLabel, it.designation, it.email) }) {
                SearchHit(Kind.Person, "person:${it.key}", it.name ?: "Member", listOfNotNull(it.designation ?: it.roleLabel, it.team).joinToString(" · ").ifEmpty { null }, "directory", it)
            } + pick(projects, { it.name }, { listOf(it.code, it.description, it.status) }) {
                SearchHit(Kind.Project, "project:${it.key}", it.name ?: "Project", it.code, "projects")
            } + pick(meetings, { it.title }, { listOf(it.description, it.location, it.organizer) }) {
                SearchHit(Kind.Meeting, "meeting:${it.key}", it.title ?: "Meeting", com.pravahax.portalx.data.Dates.dateTime(it.startAt), "meetings")
            } + pick(announcements, { it.title }, { listOf(it.body, it.author, it.teamName) }) {
                SearchHit(Kind.Announcement, "ann:${it.key}", it.title ?: "Announcement", it.author, "announcements")
            } + pick(documents, { it.title }, { listOf(it.filename, it.owner) }) {
                SearchHit(Kind.Document, "doc:${it.key}", it.title ?: it.filename ?: "Document", it.filename, "documents")
            }
        }
    }
}
