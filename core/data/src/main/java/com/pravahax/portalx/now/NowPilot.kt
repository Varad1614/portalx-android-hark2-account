package com.pravahax.portalx.now

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.10.2 pilot instrumentation for PortalX NOW. On-device only: nothing leaves the phone unless the user
 * shares the export. One tab-separated line per event in a small file; [Metrics] is computed from it purely.
 *
 * Events:
 *  - shown / tapped / snoozed / why: a NOW candidate on `home` or `widget` (shown is counted once per key per day)
 *  - nudge / nudge-open: a local notification posted / tapped (subject = checkout or meeting)
 *  - att: the attendance state seen on live data (subject = none | in | out | leave)
 */
object NowPilot {
    data class Event(val atMs: Long, val day: LocalDate, val type: String, val key: String, val source: String) {
        fun line() = "$atMs\t$day\t$type\t${key.clean()}\t${source.clean()}"
        companion object {
            fun parse(l: String): Event? = l.split('\t').takeIf { it.size == 5 }?.let { p ->
                runCatching { Event(p[0].toLong(), LocalDate.parse(p[1]), p[2], p[3], p[4]) }.getOrNull()
            }
        }
    }

    /** Keep at most ~90 days of events so the file stays small across a 4-week pilot plus baseline. */
    private const val MAX_EVENTS = 20_000
    var zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private var file: File? = null
    private val events = mutableListOf<Event>()
    private val seen = mutableSetOf<String>() // "day|type|key|source" for once-per-day events

    @Synchronized fun init(f: File) {
        if (file == f) return
        file = f; events.clear(); seen.clear()
        runCatching { f.takeIf { it.exists() }?.readLines()?.mapNotNullTo(events) { Event.parse(it) } }
        if (events.size > MAX_EVENTS) { events.subList(0, events.size - MAX_EVENTS).clear(); rewrite() }
        events.forEach { seen += it.dedupeKey() }
    }

    /** For tests: forget everything and stop writing to disk. */
    @Synchronized fun reset() { file = null; events.clear(); seen.clear() }

    @Synchronized fun record(type: String, key: String, source: String, nowMs: Long = System.currentTimeMillis()) {
        val e = Event(nowMs, Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), type, key, source)
        val once = type == "shown" || type == "att"
        if (once && e.dedupeKey() in seen) return
        if (type == "att") seen.removeAll { it.startsWith("${e.day}|att|") } // the latest state of the day wins
        seen += e.dedupeKey(); events += e
        runCatching { file?.appendText(e.line() + "\n") }
    }

    fun shown(c: Candidate, source: String) { if (c.action != NowAction.None) record("shown", c.key, source) }
    fun tapped(c: Candidate, source: String) = record("tapped", c.key, source)
    fun snoozed(c: Candidate) = record("snoozed", c.key, "home")
    fun why(c: Candidate) = record("why", c.key, "home")
    fun nudge(key: String) = record("nudge", key, "notification")
    fun nudgeOpened(key: String) = record("nudge-open", key, "notification")

    /** Live attendance only: cached data could miss a check-out made on the web. */
    fun attendance(ctx: WorkdayContext) {
        if (ctx.attendanceFreshness != Freshness.Live) return
        val a = ctx.attendance ?: return
        record("att", when { a.onLeave -> "leave"; a.checkOut != null -> "out"; a.checkIn != null -> "in"; else -> "none" }, "live")
    }

    @Synchronized fun snapshot(): List<Event> = events.toList()

    private fun Event.dedupeKey() = if (type == "att") "$day|att|$key" else "$day|$type|$key|$source"
    private fun String.clean() = replace('\t', ' ').replace('\n', ' ')
    private fun rewrite() { runCatching { file?.writeText(events.joinToString("") { it.line() + "\n" }) } }

    data class Metrics(
        val days: Int, val shown: Int, val tapped: Int, val snoozed: Int, val whyOpened: Int,
        val nudges: Int, val nudgesOpened: Int, val checkedInDays: Int, val missedCheckOutDays: Int,
        val checkOutNudges: Int, val checkOutAfterNudge: Int,
    ) {
        val tapRate get() = if (shown == 0) 0.0 else tapped.toDouble() / shown
        val missedCheckOutRate get() = if (checkedInDays == 0) 0.0 else missedCheckOutDays.toDouble() / checkedInDays

        fun report(version: String): String = buildString {
            fun pct(x: Double) = "${Math.round(x * 100)}%"
            appendLine("PortalX NOW pilot · v$version · $days day${if (days == 1) "" else "s"}")
            appendLine("NOW shown: $shown · tapped: $tapped (${pct(tapRate)}) · Not now: $snoozed · Why this?: $whyOpened")
            appendLine("Nudges sent: $nudges · opened: $nudgesOpened")
            appendLine("Checked-in days: $checkedInDays · missed check-outs: $missedCheckOutDays (${pct(missedCheckOutRate)})")
            append("Check-out nudges: $checkOutNudges · checked out after: $checkOutAfterNudge")
        }
    }

    /**
     * Pure. Tap-through = distinct (day, key, source) tapped / shown. A missed check-out is a finished day
     * (before [today]) whose last live attendance was still "in".
     */
    fun metrics(events: List<Event>, today: LocalDate): Metrics {
        fun distinct(t: String) = events.filter { it.type == t }.map { Triple(it.day, it.key, it.source) }.toSet()
        val shown = distinct("shown")
        val tapped = distinct("tapped").filter { (d, k, _) -> shown.any { it.first == d && it.second == k } }
        val lastAtt = events.filter { it.type == "att" }.groupBy { it.day }.mapValues { (_, v) -> v.maxBy { it.atMs }.key }
        val checkedIn = lastAtt.filter { (d, s) -> d.isBefore(today) && (s == "in" || s == "out") }
        val coNudges = events.filter { it.type == "nudge" && it.key.endsWith("|checkout") }
        val afterNudge = coNudges.count { n -> events.any { it.type == "att" && it.key == "out" && it.day == n.day && it.atMs > n.atMs } }
        return Metrics(
            days = events.map { it.day }.toSet().size,
            shown = shown.size, tapped = tapped.size,
            snoozed = events.count { it.type == "snoozed" }, whyOpened = events.count { it.type == "why" },
            nudges = events.count { it.type == "nudge" }, nudgesOpened = events.count { it.type == "nudge-open" },
            checkedInDays = checkedIn.size, missedCheckOutDays = checkedIn.count { it.value == "in" },
            checkOutNudges = coNudges.size, checkOutAfterNudge = afterNudge,
        )
    }

    /** Raw events as CSV, for the pilot spreadsheet. */
    fun csv(events: List<Event>): String =
        "time_ms,day,type,key,source\n" + events.joinToString("") { "${it.atMs},${it.day},${it.type},\"${it.key.replace("\"", "'")}\",${it.source}\n" }
}
