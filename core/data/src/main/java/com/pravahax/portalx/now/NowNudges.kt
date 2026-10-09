package com.pravahax.portalx.now

import java.time.Duration

/**
 * v0.10.1: which NOW candidates deserve a local notification. Only time-critical, costly-to-miss moments:
 * a forgotten check-out and a meeting about to start. Everything else stays on Home and the widget.
 *
 * Pure: the worker supplies the context and the keys already sent today; this returns what to show.
 */
object NowNudges {
    /** A meeting is nudged once when it starts within this window (the worker runs about every 15 minutes). */
    val meetingLead: Duration = Duration.ofMinutes(15)

    data class Nudge(val key: String, val title: String, val body: String, val route: String)

    fun due(ctx: WorkdayContext, alreadySent: Set<String>): List<Nudge> {
        val day = ctx.today.toString()
        val out = mutableListOf<Nudge>()
        // Never nudge a punch from data we can't trust: a check-out made on the web would make this wrong.
        if (ctx.attendanceFreshness == Freshness.Live) {
            Rules.checkOut.evaluate(ctx)?.takeIf { it.ruleId == "attendance.check-out-forgotten" }?.let { c ->
                out += Nudge("$day|checkout", "Still checked in?", "${c.detail}. Check out in PortalX.", "attendance")
            }
        }
        ctx.meetings
            .filter { it.start.isAfter(ctx.now) && !it.start.isAfter(ctx.now.plus(meetingLead)) }
            .sortedBy { it.start }
            .forEach { m ->
                val left = Duration.between(ctx.now, m.start).toMinutes().coerceAtLeast(1)
                val t = m.start.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))
                out += Nudge("$day|meeting:${m.key}", m.title, "Starts in $left min, at $t", "meetings")
            }
        return out.filter { it.key !in alreadySent }
    }
}
