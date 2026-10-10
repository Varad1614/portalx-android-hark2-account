package com.pravahax.portalx.now

import java.time.Duration
import java.time.ZonedDateTime

/**
 * Which NOW moments deserve a local notification: only time-critical, costly-to-miss ones.
 *  - v0.10.1: forgotten check-out, meeting about to start
 *  - v0.10.3: check-in at shift start (+ one repeat), long break (45 and 60 min); meeting lead unified with the card
 *
 * Pure: the caller supplies the context, the keys already sent today and the snoozes; this returns what to show.
 * Every punch-related nudge needs live attendance: a punch made on the web would make it wrong.
 */
object NowNudges {
    /** Same lead as the Home card and widget: one number, one moment. */
    val meetingLead: Duration get() = NowConfig.meetingLead

    /** [snoozeKey] is the NOW candidate the nudge stands for, so "Not now" on the notification hides the card too. */
    data class Nudge(val key: String, val title: String, val body: String, val route: String, val snoozeKey: String? = null)

    private val clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)

    fun due(ctx: WorkdayContext, alreadySent: Set<String>, snoozed: (String) -> Boolean = { false }): List<Nudge> {
        val day = ctx.today.toString()
        val out = mutableListOf<Nudge>()
        if (ctx.attendanceFreshness == Freshness.Live) {
            Rules.checkInRequired.evaluate(ctx)?.takeIf { it.band == Band.TimeCritical }?.let { c ->
                val repeat = !ctx.now.isBefore(ctx.at(ctx.shift.start).plus(NowConfig.checkInRepeat))
                out += Nudge(if (repeat) "$day|checkin2" else "$day|checkin", if (repeat) "You haven't checked in" else "Time to check in",
                    "${c.detail}. Check in with PortalX.", "attendance", c.key)
            }
            Rules.checkOut.evaluate(ctx)?.takeIf { it.ruleId == "attendance.check-out-forgotten" }?.let { c ->
                out += Nudge("$day|checkout", "Still checked in?", "${c.detail}. Check out in PortalX.", "attendance", c.key)
            }
            // v0.10.4: one reminder per open day, from an hour before shift start on a work day.
            val open = ctx.openDay
            if (open != null && ctx.isWorkDay && !ctx.now.isBefore(ctx.at(ctx.shift.start).minus(NowConfig.checkInLead)))
                Rules.previousDayOpen.evaluate(ctx)?.let { c ->
                    out += Nudge("$day|openday:$open", c.title, "${c.detail}.", Rules.correctionRoute(open, "forgot_check_out"), c.key)
                }
            val a = ctx.attendance
            val since = a?.breakStartedAt
            if (a != null && a.onBreak && a.checkOut == null && since != null) {
                val m = Duration.between(since, ctx.now)
                NowConfig.breakAlerts.filter { m >= it }.maxOrNull()?.let { mark ->
                    out += Nudge("$day|break${mark.toMinutes()}:${since.toEpochSecond()}", "Still on a break?",
                        "On break for ${m.toMinutes()} min, since ${since.toLocalTime().format(clock)}. End it when you're back.",
                        "attendance", "attendance.break-running:today")
                }
            }
        }
        ctx.meetings
            .filter { it.start.isAfter(ctx.now) && !it.start.isAfter(ctx.now.plus(meetingLead)) }
            .sortedBy { it.start }
            .forEach { m ->
                val left = Duration.between(ctx.now, m.start).toMinutes().coerceAtLeast(1)
                out += Nudge("$day|meeting:${m.key}", m.title, "Starts in $left min, at ${m.start.toLocalTime().format(clock)}", "meetings",
                    "meeting.starting-soon:${m.key}")
            }
        return out.filter { it.key !in alreadySent && (it.snoozeKey == null || !snoozed(it.snoozeKey)) }
    }
}

/**
 * v0.10.3: the next moment NOW should wake up for, so reminders land on the minute instead of whenever a
 * 15-minute poll happens to run. Pure; the app turns it into one exact alarm and asks again after each run.
 */
object NowSchedule {
    fun moments(ctx: WorkdayContext, snoozes: Map<String, Long> = emptyMap()): List<ZonedDateTime> = buildList {
        val shift = ctx.shift
        if (ctx.now.dayOfWeek in shift.workDays) {
            val start = ctx.at(shift.start); val end = ctx.at(shift.end)
            add(start.minus(NowConfig.checkInLead)); add(start); add(start.plus(NowConfig.checkInRepeat))
            add(end); add(end.plus(NowConfig.checkOutGrace))
        }
        ctx.meetings.forEach { add(it.start.minus(NowConfig.meetingLead)); add(it.start.minus(NowConfig.meetingUrgentLead)); add(it.start); it.end?.let(::add) }
        ctx.attendance?.takeIf { it.onBreak }?.breakStartedAt?.let { s -> NowConfig.breakAlerts.forEach { add(s.plus(it)) } }
        snoozes.values.forEach { add(java.time.Instant.ofEpochMilli(it).atZone(ctx.now.zone)) }
        // Just after midnight: the day rolls over and the widget must stop showing yesterday.
        add(ctx.today.plusDays(1).atStartOfDay(ctx.now.zone).plusMinutes(1))
    }.filter { it.isAfter(ctx.now) }.sorted()

    fun next(ctx: WorkdayContext, snoozes: Map<String, Long> = emptyMap()): ZonedDateTime = moments(ctx, snoozes).first()
}
