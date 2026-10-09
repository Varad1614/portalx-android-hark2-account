package com.pravahax.portalx.data

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The company operates on India Standard Time; all "today", day-bucketing and display use it. */
val AppZone: ZoneId = ZoneId.of("Asia/Kolkata")

object Dates {
    fun today(clock: Clock = Clock.system(AppZone)): LocalDate = LocalDate.now(clock.withZone(AppZone))

    /** Parses any timestamp the server sends (ISO instant, offset, naive local, or date-only) into IST. */
    fun instant(s: String?): ZonedDateTime? {
        val v = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(v).atZone(AppZone) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v).atZoneSameInstant(AppZone) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v.replace(' ', 'T')).atZoneSameInstant(AppZone) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(v.replace(' ', 'T')).atZone(AppZone) }.getOrNull() // naive = IST, like the browser
            ?: runCatching { LocalDate.parse(v.take(10)).atStartOfDay(AppZone) }.getOrNull()
            ?: v.toLongOrNull()?.let { ms -> runCatching { Instant.ofEpochMilli(ms).atZone(AppZone) }.getOrNull() }
    }

    /**
     * Calendar day for a date-like field. "2026-10-07" stays as-is. A timestamp is converted to IST first, so a
     * DATE column serialised as midnight UTC ("…T00:00:00Z") or as IST midnight ("2026-10-06T18:30:00Z") both
     * land on 7 Oct. v0.1.x took the first 10 chars, which put IST-midnight dates on the previous day.
     */
    fun day(s: String?): LocalDate? {
        val v = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.length == 10) runCatching { LocalDate.parse(v) }.getOrNull()?.let { return it }
        return instant(v)?.toLocalDate()
    }

    private fun f(p: String) = DateTimeFormatter.ofPattern(p, Locale.ENGLISH)
    fun time(s: String?) = instant(s)?.format(f("h:mm a")) ?: "—"
    fun date(s: String?) = day(s)?.format(f("d MMM yyyy")) ?: (s ?: "—")
    fun shortDate(s: String?) = day(s)?.format(f("d MMM")) ?: (s ?: "—")
    fun dateTime(s: String?) = instant(s)?.format(f("EEE d MMM, h:mm a")) ?: (s ?: "—")

    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0m"; if (seconds < 60) return "< 1m"
        val m = Math.round(seconds / 60.0); if (m < 60) return "${m}m"
        val h = m / 60; val r = m % 60; return if (r > 0) "${h}h ${r}m" else "${h}h"
    }

    fun greeting(now: ZonedDateTime = ZonedDateTime.now(AppZone)): String = when (now.hour) {
        in 0..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening"
    }

    /** "Overdue · 7 Oct", "Due today", "Due tomorrow", "Due 12 Oct" (IST). */
    fun dueLabel(due: String?, done: Boolean, today: LocalDate = today()): String {
        val d = day(due) ?: return "Due " + (due ?: "—")
        return when {
            !done && d.isBefore(today) -> "Overdue · " + shortDate(due)
            d == today -> "Due today"
            d == today.plusDays(1) -> "Due tomorrow"
            else -> "Due " + shortDate(due)
        }
    }

    fun isOverdue(due: String?, done: Boolean, today: LocalDate = today()): Boolean =
        !done && (day(due)?.isBefore(today) ?: false)
}

/** Form validation, shared by the screens and unit tests. Returns a user-facing message or null when valid. */
object Validate {
    private val workspaceRe = Regex("^[a-z0-9][a-z0-9-]{0,62}$")

    fun login(workspace: String, userId: String, password: String): String? = when {
        workspace.isBlank() || userId.isBlank() || password.isEmpty() -> "Fill in all three fields."
        !workspaceRe.matches(workspace.trim().lowercase()) -> "Workspace names use letters, numbers and dashes only."
        userId.trim().length > 120 -> "That User ID looks too long."
        else -> null
    }

    /** The web requires at least 12 characters (v0.1.x checked 8, so the server rejected 8–11 char passwords). */
    fun newPassword(current: String, next: String, confirm: String): String? = when {
        current.isEmpty() -> "Enter your current (or temporary) password."
        next.length < 12 -> "Password must be at least 12 characters."
        next == current -> "Choose a password different from your current one."
        next != confirm -> "Passwords don't match."
        else -> null
    }

    data class LeaveCheck(val error: String?, val days: Double, val warning: String?)

    /**
     * Leave request rules: type, start, end required; end on/after start; half day only for a single day;
     * reason required (≤ 500 chars). Requests more than a year out are rejected client-side.
     * Exceeding the remaining balance is a warning, not a block (the server owns the policy).
     */
    fun leave(typeId: Any?, start: LocalDate?, end: LocalDate?, halfDay: Boolean, reason: String, remaining: Double?, today: LocalDate = Dates.today()): LeaveCheck {
        val days = if (start != null && end != null && !end.isBefore(start))
            if (halfDay) 0.5 else (ChronoUnit.DAYS.between(start, end) + 1).toDouble() else 0.0
        val err = when {
            typeId == null -> "Choose a leave type."
            start == null -> "Choose a start date."
            end == null -> "Choose an end date."
            end.isBefore(start) -> "End date can't be before the start date."
            halfDay && start != end -> "A half day must start and end on the same date."
            start.isAfter(today.plusYears(1)) -> "Leave can be requested up to a year ahead."
            reason.isBlank() -> "Add a short reason."
            reason.length > 500 -> "Keep the reason under 500 characters."
            else -> null
        }
        val warn = when {
            err != null -> null
            remaining != null && days > remaining -> "This is more than your remaining balance (${fmtNum(remaining)} days). It may be unpaid or declined."
            start != null && start.isBefore(today) -> "This request is for past dates."
            else -> null
        }
        return LeaveCheck(err, days, warn)
    }

    /** Attendance corrections are for today or the past only. */
    fun correction(date: LocalDate?, note: String, today: LocalDate = Dates.today()): String? = when {
        date == null -> "Choose a date."
        date.isAfter(today) -> "Corrections can only be requested for today or earlier."
        note.length > 500 -> "Keep the note under 500 characters."
        else -> null
    }

    fun comment(body: String): String? = when {
        body.isBlank() -> "Write a comment first."
        body.length > 2000 -> "Comments are limited to 2000 characters."
        else -> null
    }

    fun task(title: String): String? = when {
        title.isBlank() -> "Give the task a title."
        title.length > 200 -> "Keep the title under 200 characters."
        else -> null
    }

    /** Only http(s) links are ever opened from server data (blocks javascript:, intent:, file: …). */
    fun safeWebUrl(s: String?): String? {
        val v = s?.trim() ?: return null
        val uri = runCatching { java.net.URI(v) }.getOrNull() ?: return null
        return if ((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank()) v else null
    }
}
