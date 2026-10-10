package com.pravahax.portalx.now

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/*
 * PortalX NOW (v0.10): a deterministic "what should I do next?" engine.
 *
 *   WorkdayContext → rules (catalogue) → candidates → freshness gate → snooze filter → resolver → stability → UI
 *
 * Pure Kotlin + java.time, no Android, no network, no AI: the same context always gives the same answer, and every
 * answer names the rule that produced it. The engine never performs an action; it only routes into existing flows.
 */

// ---------------- configuration ----------------

/** The user's shift. Until the gateway exposes shift hours this is the org default (9:30 to 18:30, Mon to Fri, IST). */
data class ShiftWindow(
    val start: LocalTime = LocalTime.of(9, 30),
    val end: LocalTime = LocalTime.of(18, 30),
    val workDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
)

/** Every tunable number in one place, so rules can later be driven by the gateway without an app update. */
object NowConfig {
    var shift = ShiftWindow()
    /** Check-in becomes relevant this long before the shift starts. */
    val checkInLead: Duration = Duration.ofMinutes(60)
    /** After shift end, "check out" is a gentle Due; past this grace it becomes "forgot to check out". */
    val checkOutGrace: Duration = Duration.ofMinutes(30)
    /** A meeting surfaces this long before it starts: card, widget and notification all use this one number. */
    val meetingLead: Duration = Duration.ofMinutes(10)
    /** v0.10.3: inside this window a meeting outranks "Check in" (join first, punch right after). */
    val meetingUrgentLead: Duration = Duration.ofMinutes(3)
    /** v0.10.3: a second check-in reminder this long after shift start. */
    val checkInRepeat: Duration = Duration.ofMinutes(15)
    /** v0.10.4: attendance or meetings older than this are not advised on as if live; Home refetches them. */
    val maxDataAge: Duration = Duration.ofMinutes(30)
    /** v0.10.4: how far back an unclosed day still gets a correction prompt. */
    const val OPEN_DAY_LOOKBACK = 3L
    /** v0.10.4: a submitted correction hides its prompt this long (the gateway has no "my corrections" list to check). */
    val correctionHold: Duration = Duration.ofDays(7)
    /** v0.10.3: break reminders at these marks. */
    val breakAlerts: List<Duration> = listOf(Duration.ofMinutes(45), Duration.ofMinutes(60))
    /** "Not now" hides a candidate for this long. */
    val snooze: Duration = Duration.ofMinutes(30)
    /** A shown recommendation is kept at least this long unless a higher band arrives or it becomes invalid. */
    const val MIN_HOLD_MS = 60_000L
}

// ---------------- context ----------------

/** How much we trust a piece of data right now. */
enum class Freshness { Live, Updating, Cached, Unknown }

data class NowAttendance(
    val checkIn: ZonedDateTime?, val checkOut: ZonedDateTime?, val onBreak: Boolean,
    val breakStartedAt: ZonedDateTime?, val onLeave: Boolean,
)

data class NowMeeting(val key: String, val title: String, val start: ZonedDateTime, val end: ZonedDateTime?)
data class NowTask(val key: String, val title: String, val due: LocalDate?, val priority: String?, val done: Boolean)

/** Everything the rules may look at, already normalized. Rules never see API payloads. */
data class WorkdayContext(
    val now: ZonedDateTime,
    val shift: ShiftWindow = NowConfig.shift,
    /** null = today's attendance is not known (never loaded / failed). */
    val attendance: NowAttendance?,
    val attendanceFreshness: Freshness,
    val holidayToday: String? = null,
    val onApprovedLeaveToday: Boolean = false,
    val meetings: List<NowMeeting> = emptyList(),
    val tasks: List<NowTask> = emptyList(),
    val pendingApprovals: Int = 0,
    val canApprove: Boolean = false,
    /** v0.10.4: the latest recent work day that was checked in but never checked out (from live history), or null. */
    val openDay: LocalDate? = null,
) {
    val today: LocalDate get() = now.toLocalDate()
    val isWorkDay get() = now.dayOfWeek in shift.workDays && holidayToday == null && !onApprovedLeaveToday && attendance?.onLeave != true
    fun at(t: LocalTime): ZonedDateTime = now.with(t)
}

// ---------------- candidates ----------------

/** Bands decide first; urgency (0..99) only orders candidates inside a band. */
enum class Band(val base: Int, val label: String) {
    Blocking(400, "Blocking"), TimeCritical(300, "Time-critical"), Due(200, "Due"), Ambient(100, "Ambient")
}

/** Where a NOW action goes. Routes are the app's existing screens; NOW never punches, approves or completes anything itself. */
sealed interface NowAction {
    data class Open(val route: String) : NowAction
    data object Refresh : NowAction
    data object None : NowAction
}

data class Candidate(
    val ruleId: String,
    /** What the candidate is about (meeting id, task id, "today"): snoozes and stability key on rule + subject. */
    val subject: String,
    val band: Band,
    val urgency: Int,
    val title: String,
    val detail: String,
    /** Plain-language "why", shown in the Why this? sheet. */
    val reason: String,
    val actionLabel: String?,
    val action: NowAction,
    /** Acting on stale data could do harm (punching twice): gated by [Freshness]. */
    val requiresLiveAttendance: Boolean = false,
    /** v0.10.3: a detail that never goes stale (clock times, no countdowns), for the widget. */
    val glance: String? = null,
) {
    val key get() = "$ruleId:$subject"
    val score get() = band.base + urgency.coerceIn(0, 99)
}

fun interface Rule {
    fun evaluate(ctx: WorkdayContext): Candidate?
}

private fun mins(d: Duration) = d.toMinutes()
private fun fmt(t: ZonedDateTime) = t.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))
private fun inMinutes(m: Long) = when { m <= 0 -> "now"; m == 1L -> "in 1 min"; else -> "in $m min" }

// ---------------- rule catalogue ----------------

object Rules {
    /** A break is running: the only thing that blocks the rest of the day. */
    val breakRunning = Rule { c ->
        val a = c.attendance ?: return@Rule null
        if (!a.onBreak || a.checkIn == null || a.checkOut != null) return@Rule null
        val m = a.breakStartedAt?.let { mins(Duration.between(it, c.now)) }?.coerceAtLeast(0)
        Candidate("attendance.break-running", "today", Band.Blocking, (m ?: 0).toInt().coerceAtMost(99),
            "End your break", m?.let { "On break for $it min" } ?: "You're on a break",
            "A break is running, so worked time isn't counting.", "End break", NowAction.Open("attendance"), requiresLiveAttendance = true,
            glance = a.breakStartedAt?.let { "On break since ${fmt(it)}" })
    }

    val checkInRequired = Rule { c ->
        if (!c.isWorkDay) return@Rule null
        val a = c.attendance ?: return@Rule null
        if (a.checkIn != null) return@Rule null
        val start = c.at(c.shift.start); val end = c.at(c.shift.end)
        if (c.now.isBefore(start.minus(NowConfig.checkInLead)) || !c.now.isBefore(end)) return@Rule null
        val late = mins(Duration.between(start, c.now))
        // v0.10.3: one card for both when a meeting is about to start: "check in, then join".
        val then = c.meetings.filter { it.start.isAfter(c.now) && !it.start.isAfter(c.now.plus(NowConfig.meetingLead)) }
            .minByOrNull { it.start }?.let { " · then ${it.title} at ${fmt(it.start)}" } ?: ""
        if (late >= 0) Candidate("attendance.check-in-required", "today", Band.TimeCritical, 85,
            "Check in", (if (late == 0L) "Your shift starts now" else "Your shift started at ${fmt(start)}") + then,
            "No check-in yet today, and your shift has started.", "Check in", NowAction.Open("attendance"), requiresLiveAttendance = true)
        else Candidate("attendance.check-in-required", "today", Band.Due, 60,
            "Check in", "Your shift starts at ${fmt(start)}" + then,
            "No check-in yet today, and your shift starts within the hour.", "Check in", NowAction.Open("attendance"), requiresLiveAttendance = true)
    }

    /** v0.10.4: deep link that opens Attendance with the correction form filled in. */
    fun correctionRoute(day: LocalDate, reason: String) = "attendance?correct=$day&reason=$reason"
    private val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM", java.util.Locale.ENGLISH)

    /** v0.10.4: an earlier day was left checked in (forgot to check out, or punched out on a broken device). */
    val previousDayOpen = Rule { c ->
        val d = c.openDay ?: return@Rule null
        if (!d.isBefore(c.today)) return@Rule null
        Candidate("attendance.previous-day-open", d.toString(), Band.Due, 57, "Check-out missing for ${d.format(dayFmt)}",
            "Request a correction so the day counts in full",
            "You checked in on ${d.format(dayFmt)} but there's no check-out, so that day's hours are incomplete.",
            "Request correction", NowAction.Open(correctionRoute(d, "forgot_check_out")))
    }

    /** v0.10.3: the shift ended with no check-in. Never go silent: offer a correction for the day. */
    val checkInMissed = Rule { c ->
        if (!c.isWorkDay) return@Rule null
        val a = c.attendance ?: return@Rule null
        if (a.checkIn != null || c.now.isBefore(c.at(c.shift.end))) return@Rule null
        val d = c.today.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH))
        Candidate("attendance.check-in-missed", "today", Band.Due, 58, "No check-in today",
            "Request a correction for $d", "Your shift ended and there's no check-in for today, so the day would count as absent.",
            "Request correction", NowAction.Open(correctionRoute(c.today, "forgot_check_in")), requiresLiveAttendance = true)
    }

    val checkOut = Rule { c ->
        val a = c.attendance ?: return@Rule null
        if (a.checkIn == null || a.checkOut != null || a.onBreak) return@Rule null
        // Only for a check-in made today (a stale row from yesterday is already NotStarted upstream).
        val end = c.at(c.shift.end)
        if (c.now.isBefore(end)) return@Rule null
        val past = mins(Duration.between(end, c.now))
        if (past >= mins(NowConfig.checkOutGrace)) Candidate("attendance.check-out-forgotten", "today", Band.TimeCritical,
            (60 + past / 10).toInt().coerceAtMost(84), "Check out", "Your shift ended at ${fmt(end)}",
            "You're still checked in ${past} min after your shift ended.", "Check out", NowAction.Open("attendance"), requiresLiveAttendance = true)
        else Candidate("attendance.check-out-required", "today", Band.Due, 55, "Check out when you leave",
            "Your shift ended at ${fmt(end)}", "Your shift is over and you're still checked in.", "Check out",
            NowAction.Open("attendance"), requiresLiveAttendance = true)
    }

    val meetingLive = Rule { c ->
        val m = c.meetings.filter { !c.now.isBefore(it.start) && (it.end?.let { e -> c.now.isBefore(e) } ?: (mins(Duration.between(it.start, c.now)) < 30)) }
            .minByOrNull { it.start } ?: return@Rule null
        Candidate("meeting.current", m.key, Band.TimeCritical, 95, m.title, "Started at ${fmt(m.start)}",
            "This meeting is happening now.", "Open meeting", NowAction.Open("meetings"), glance = "Started at ${fmt(m.start)}")
    }

    val meetingSoon = Rule { c ->
        val m = c.meetings.filter { it.start.isAfter(c.now) && !it.start.isAfter(c.now.plus(NowConfig.meetingLead)) }
            .minByOrNull { it.start } ?: return@Rule null
        val left = mins(Duration.between(c.now, m.start)).coerceAtLeast(0)
        // v0.10.3: below 70..84 "Check in" (85) wins; inside the last 3 minutes the meeting wins (88..91), never above a live one (95).
        val urgency = if (left <= mins(NowConfig.meetingUrgentLead)) (88 + (mins(NowConfig.meetingUrgentLead) - left)).toInt()
            else (70 + (mins(NowConfig.meetingLead) - left) * 2).toInt().coerceIn(70, 84)
        val notIn = c.isWorkDay && c.attendance != null && c.attendance.checkIn == null
        Candidate("meeting.starting-soon", m.key, Band.TimeCritical, urgency,
            m.title, "Starts ${inMinutes(left)}, at ${fmt(m.start)}" + if (notIn) " · check in right after" else "",
            "A meeting starts within ${mins(NowConfig.meetingLead)} minutes.",
            "Open meeting", NowAction.Open("meetings"), glance = "Starts at ${fmt(m.start)}")
    }

    private fun high(p: String?) = p?.lowercase() in setOf("high", "urgent", "critical")

    val taskOverdue = Rule { c ->
        val t = c.tasks.filter { !it.done && it.due != null && it.due.isBefore(c.today) }
            .sortedWith(compareBy<NowTask> { it.due }.thenBy { !high(it.priority) }).firstOrNull() ?: return@Rule null
        val days = java.time.temporal.ChronoUnit.DAYS.between(t.due, c.today)
        val n = c.tasks.count { !it.done && it.due != null && it.due.isBefore(c.today) }
        Candidate("task.overdue", t.key, Band.Due, (60 + days.coerceAtMost(25) + if (high(t.priority)) 5 else 0).toInt().coerceAtMost(99),
            t.title, "Overdue by $days day${if (days == 1L) "" else "s"}" + if (n > 1) " · ${n - 1} more overdue" else "",
            "This is your oldest overdue open task.", "Open task", NowAction.Open("tasks"))
    }

    val taskDueToday = Rule { c ->
        val due = c.tasks.filter { !it.done && it.due == c.today }
        val t = due.sortedBy { !high(it.priority) }.firstOrNull() ?: return@Rule null
        Candidate("task.due-today", t.key, Band.Due, if (high(t.priority)) 45 else 40, t.title,
            "Due today" + if (due.size > 1) " · ${due.size - 1} more" else "", "An open task is due today.", "Open task", NowAction.Open("tasks"))
    }

    val approvalPending = Rule { c ->
        if (!c.canApprove || c.pendingApprovals <= 0) return@Rule null
        val n = c.pendingApprovals
        Candidate("approval.pending", "inbox", Band.Due, (50 + n).coerceAtMost(59),
            "Review $n approval${if (n > 1) "s" else ""}", "Leave and corrections waiting on you",
            "Teammates are waiting on your decision.", "Review", NowAction.Open("approvals"))
    }

    val dayOff = Rule { c ->
        when {
            c.holidayToday != null -> Candidate("workday.holiday", "today", Band.Ambient, 20, "Holiday: ${c.holidayToday}",
                "No attendance needed today", "Today is an organization holiday.", null, NowAction.None)
            c.onApprovedLeaveToday || c.attendance?.onLeave == true -> Candidate("workday.on-leave", "today", Band.Ambient, 20,
                "You're on leave today", "Attendance is paused", "Your leave for today is approved.", "View leave", NowAction.Open("leave"))
            else -> null
        }
    }

    val dayComplete = Rule { c ->
        val a = c.attendance ?: return@Rule null
        val i = a.checkIn ?: return@Rule null; val o = a.checkOut ?: return@Rule null
        val worked = Duration.between(i, o)
        Candidate("workday.complete", "today", Band.Ambient, 10, "Day wrapped up",
            "Checked out at ${fmt(o)} · ${worked.toHours()}h ${worked.toMinutes() % 60}m", "You've checked out for today.",
            "See insights", NowAction.Open("insights"))
    }

    /** Ordered as documented; evaluation order never affects the result (the resolver sorts). */
    val catalogue: List<Rule> = listOf(breakRunning, checkInRequired, checkInMissed, previousDayOpen, checkOut, meetingLive, meetingSoon,
        taskOverdue, taskDueToday, approvalPending, dayOff, dayComplete)
}

// ---------------- engine ----------------

data class NowResult(
    val chosen: Candidate,
    /** All valid candidates after gating, best first: the Why this? sheet and QA read these. */
    val ranked: List<Candidate>,
    val freshness: Freshness,
)

object NowEngine {
    val Clear = Candidate("workday.clear", "today", Band.Ambient, 0, "Nothing urgent right now",
        "You're on top of things", "No rule matched your workday right now.", null, NowAction.None)

    /**
     * Freshness gate: an attendance action on data we can't trust becomes "Refresh" in the same band, so the user is
     * never told to check in when they already have (or vice versa). Unknown attendance on a work day asks for a refresh too.
     */
    internal fun gate(c: Candidate, f: Freshness): Candidate =
        if (c.requiresLiveAttendance && (f == Freshness.Cached || f == Freshness.Unknown))
            c.copy(ruleId = "attendance.refresh-needed", title = "Refresh attendance",
                detail = "Today's status is ${if (f == Freshness.Cached) "from earlier" else "not loaded"}",
                reason = "\"${c.title}\" needs today's live status, and we only have ${f.name.lowercase()} data.",
                actionLabel = "Refresh", action = NowAction.Refresh, requiresLiveAttendance = false)
        else c

    fun evaluate(ctx: WorkdayContext, rules: List<Rule> = Rules.catalogue, snoozedUntil: Map<String, Long> = emptyMap()): NowResult {
        val nowMs = ctx.now.toInstant().toEpochMilli()
        val raw = rules.mapNotNull { r -> runCatching { r.evaluate(ctx) }.getOrNull() }.toMutableList()
        if (ctx.attendance == null && ctx.isWorkDay && ctx.now.toLocalTime().let { it >= ctx.shift.start.minus(NowConfig.checkInLead) && it < ctx.shift.end })
            raw += gate(Candidate("attendance.check-in-required", "today", Band.Due, 60, "Check in", "", "", "Check in",
                NowAction.Open("attendance"), requiresLiveAttendance = true), Freshness.Unknown)
        val ranked = raw.map { gate(it, ctx.attendanceFreshness) }
            .filter { (snoozedUntil[it.key] ?: 0L) <= nowMs }
            .distinctBy { it.key }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.key }) // total order: deterministic ties
        return NowResult(ranked.firstOrNull() ?: Clear, ranked, ctx.attendanceFreshness)
    }
}

/**
 * Anti-flicker: keep what's on screen unless it's no longer valid, it has been shown for [NowConfig.MIN_HOLD_MS],
 * or something in a higher band arrived. The same candidate refreshes its text in place without resetting the clock.
 */
class StabilityPolicy(private val minHoldMs: Long = NowConfig.MIN_HOLD_MS) {
    data class Shown(val candidate: Candidate, val since: Long)
    var current: Shown? = null
        private set

    fun choose(result: NowResult, nowMs: Long): Candidate {
        val best = result.chosen
        val cur = current
        val still = cur?.let { c -> result.ranked.firstOrNull { it.key == c.candidate.key } }
        val keep = cur != null && still != null && best.key != cur.candidate.key &&
            nowMs - cur.since < minHoldMs && best.band.ordinal >= still.band.ordinal
        current = when {
            keep -> Shown(still!!, cur!!.since)
            cur != null && best.key == cur.candidate.key -> Shown(best, cur.since)
            else -> Shown(best, nowMs)
        }
        return current!!.candidate
    }
}

/** "Not now": per rule + subject. v0.10.3: saved to a small file so it survives restarts and works from a notification. */
object NowSnoozes {
    private val until = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var file: java.io.File? = null

    @Synchronized fun init(f: java.io.File, nowMs: Long = System.currentTimeMillis()) {
        file = f; until.clear()
        runCatching { if (f.exists()) f.readLines().forEach { l -> l.split('\t').takeIf { it.size == 2 }?.let { (k, v) -> v.toLongOrNull()?.takeIf { it > nowMs }?.let { until[k] = it } } } }
    }
    fun snooze(c: Candidate, nowMs: Long = System.currentTimeMillis()) = snooze(c.key, nowMs)
    @Synchronized fun snooze(key: String, nowMs: Long = System.currentTimeMillis(), forMs: Long = NowConfig.snooze.toMillis()) {
        until[key] = maxOf(until[key] ?: 0L, nowMs + forMs)
        until.entries.removeIf { it.value <= nowMs }
        runCatching { file?.writeText(until.entries.joinToString("") { "${it.key}\t${it.value}\n" }) }
    }
    /**
     * v0.10.4: a correction was submitted for [date]: stop prompting for that day (open day for a week; a missed
     * check-in for the rest of today).
     */
    fun correctionRequested(date: LocalDate, today: LocalDate, zone: java.time.ZoneId, nowMs: Long = System.currentTimeMillis()) {
        snooze("attendance.previous-day-open:$date", nowMs, NowConfig.correctionHold.toMillis())
        if (date == today) snooze("attendance.check-in-missed:today", nowMs,
            today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - nowMs)
    }
    fun isSnoozed(key: String, nowMs: Long = System.currentTimeMillis()) = (until[key] ?: 0L) > nowMs
    fun snapshot(): Map<String, Long> = HashMap(until)
    @Synchronized fun clear() { until.clear(); file = null }
}
