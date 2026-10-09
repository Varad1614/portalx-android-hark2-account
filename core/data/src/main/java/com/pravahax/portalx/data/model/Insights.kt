package com.pravahax.portalx.data.model

import com.pravahax.portalx.data.Dates
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * v0.9: personal attendance insights over the last [days] days, computed on-device from attendance history.
 * Times are IST minutes since midnight. A day counts as worked when it has a check-in.
 */
data class AttendanceInsights(
    val daysPresent: Int,
    val lateDays: Int,
    val avgCheckInMinutes: Int?,
    val avgWorkedSeconds: Long?,
    val streak: Int,
    /** Worked seconds per day, oldest first, for the last [chartDays] days (0 when absent or still open). */
    val daily: List<Pair<LocalDate, Long>>,
) {
    val onTimeRate: Int? get() = if (daysPresent == 0) null else ((daysPresent - lateDays) * 100 + daysPresent / 2) / daysPresent

    companion object {
        fun from(records: List<AttendanceRecord>, today: LocalDate = Dates.today(), days: Int = 30, chartDays: Int = 14): AttendanceInsights {
            val from = today.minusDays(days - 1L)
            val byDay = records.mapNotNull { r -> Dates.day(r.date)?.let { it to r } }
                .filter { (d, r) -> !d.isBefore(from) && !d.isAfter(today) && r.checkIn != null }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.first() }
            fun worked(r: AttendanceRecord): Long? {
                val i = Dates.instant(r.checkIn) ?: return null
                val o = Dates.instant(r.checkOut) ?: return null
                return (java.time.Duration.between(i, o).seconds - r.breakSeconds).takeIf { it > 0 }
            }
            val ins = byDay.values.mapNotNull { Dates.instant(it.checkIn)?.let { t -> t.hour * 60 + t.minute } }
            val worked = byDay.values.mapNotNull(::worked)
            // Streak: consecutive working days back from today (weekends never break it; today only counts once checked in).
            var streak = 0
            var d = today
            while (!d.isBefore(from)) {
                val weekend = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
                when {
                    byDay.containsKey(d) -> streak++
                    weekend || d == today -> {}
                    else -> break
                }
                d = d.minusDays(1)
            }
            val daily = (chartDays - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { day -> day to (byDay[day]?.let(::worked) ?: 0L) }
            return AttendanceInsights(
                byDay.size, byDay.values.count { it.status.equals("late", true) },
                ins.takeIf { it.isNotEmpty() }?.average()?.toInt(), worked.takeIf { it.isNotEmpty() }?.average()?.toLong(), streak, daily,
            )
        }
    }
}

/** v0.9: leave used vs allocated across every paid leave type. */
data class LeaveInsights(val used: Double, val allocated: Double, val remaining: Double) {
    val usedPercent: Int get() = if (allocated <= 0) 0 else ((used / allocated) * 100).toInt().coerceIn(0, 100)
    companion object {
        fun from(s: LeaveSummary?): LeaveInsights? {
            val b = s?.balances.orEmpty().filter { it.paid != false }
            if (b.isEmpty()) return null
            return LeaveInsights(b.sumOf { it.used ?: 0.0 }, b.sumOf { it.allocated ?: 0.0 }, b.sumOf { it.remaining ?: 0.0 })
        }
    }
}
