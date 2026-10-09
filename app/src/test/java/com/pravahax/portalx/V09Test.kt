package com.pravahax.portalx

import com.pravahax.portalx.data.LivenessResult
import com.pravahax.portalx.data.model.*
import com.pravahax.portalx.media.Challenge
import com.pravahax.portalx.media.FaceFrame
import com.pravahax.portalx.media.LivenessCheck
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** v0.9: liveness state machine, insights, search. */
class V09Test {
    private val face = FaceFrame(1, 0.4f, 0f, 0.9f, 0.9f, 0.1f)
    private fun LivenessCheck.feed(vararg f: FaceFrame, t: Long = 1) = f.fold(state) { _, x -> onFrame(x, t) }
    private fun steady(c: LivenessCheck) = c.feed(*Array(LivenessCheck.STEADY_FRAMES) { face })

    @Test fun livenessPassesBlinkThenTurn() {
        val c = LivenessCheck(listOf(Challenge.Blink, Challenge.Turn), startedAt = 0)
        assertEquals(LivenessCheck.State.Doing(0, Challenge.Blink), steady(c))
        c.feed(face, face.copy(leftEyeOpen = 0.1f, rightEyeOpen = 0.1f))
        assertEquals(LivenessCheck.State.Doing(1, Challenge.Turn), c.feed(face))
        c.feed(face.copy(yaw = 30f))
        assertEquals(LivenessCheck.State.Passed, c.feed(face.copy(yaw = 2f)))
    }

    @Test fun livenessRejectsPhotosCrowdsAndDistance() {
        val c = LivenessCheck(listOf(Challenge.Smile, Challenge.Blink), startedAt = 0)
        assertEquals(LivenessCheck.State.Hint("Move a little closer"), c.feed(face.copy(widthFraction = 0.1f)))
        steady(c)
        // a still photo never blinks or smiles: still on the first challenge
        assertEquals(LivenessCheck.State.Doing(0, Challenge.Smile), c.feed(face, face, face, face))
        // a second face resets progress
        assertEquals(LivenessCheck.State.Hint("Only your face, please"), c.feed(face.copy(faces = 2)))
        assertTrue(c.feed(face) is LivenessCheck.State.Hint)
        assertTrue(c.onFrame(face, 30_000) is LivenessCheck.State.Failed)
        assertTrue(c.onFrame(face, 1) is LivenessCheck.State.Failed) // terminal
    }

    @Test fun livenessChallengesAreTwoDistinct() {
        repeat(20) { val r = LivenessCheck.random(kotlin.random.Random(it)); assertEquals(2, r.toSet().size) }
        assertEquals("X-PortalX-Liveness" to "passed; challenges=blink,smile", LivenessResult(true, listOf("blink", "smile")).header())
        assertEquals("X-PortalX-Liveness" to "unavailable", LivenessResult(false).header())
    }

    @Test fun attendanceInsights() {
        val today = LocalDate.of(2026, 10, 9) // Friday
        fun r(d: String, i: String?, o: String?, s: String? = "present") = AttendanceRecord(d, i, o, 1800, s, d)
        val recs = listOf(
            r("2026-10-09", "2026-10-09T09:30:00+05:30", null),
            r("2026-10-08", "2026-10-08T09:00:00+05:30", "2026-10-08T18:00:00+05:30"),
            r("2026-10-07", "2026-10-07T10:00:00+05:30", "2026-10-07T18:30:00+05:30", "late"),
            r("2026-10-05", "2026-10-05T09:30:00+05:30", "2026-10-05T17:30:00+05:30"), // Mon; Tue 6th missing breaks the streak
            r("2026-08-01", "2026-08-01T09:00:00+05:30", "2026-08-01T17:00:00+05:30"), // outside 30 days
            r("2026-10-04", null, null, "absent"),
        )
        val ins = AttendanceInsights.from(recs, today)
        assertEquals(4, ins.daysPresent); assertEquals(1, ins.lateDays); assertEquals(75, ins.onTimeRate)
        assertEquals(3, ins.streak)
        assertEquals(9 * 60 + 30, ins.avgCheckInMinutes) // 9:30, 9:00, 10:00, 9:30
        assertEquals((8.5 * 3600 + 8 * 3600 + 7.5 * 3600).toLong() / 3, ins.avgWorkedSeconds)
        assertEquals(14, ins.daily.size); assertEquals(today, ins.daily.last().first); assertEquals(0L, ins.daily.last().second)
        assertNull(AttendanceInsights.from(emptyList(), today).onTimeRate)
        val li = LeaveInsights.from(LeaveSummary(listOf(
            LeaveBalance(null, "1", "Casual", 12.0, 3.0, 9.0, true), LeaveBalance(null, "2", "Unpaid", 0.0, 5.0, 0.0, false)), emptyList()))!!
        assertEquals(25, li.usedPercent); assertNull(LeaveInsights.from(null))
    }

    @Test fun searchRanksAndCaps() {
        fun task(t: String, p: String? = null) = Task(JsonPrimitive(t), t, t, null, "todo", null, null, p, null, null)
        val people = listOf(Person(null, "p1", "U1", "Asha Patil", "asha@x.com", null, "Designer", null, "Mobile", null, null, null, emptyList()))
        val hits = SearchHit.search("mobile", listOf(task("Fix login", "Mobile app"), task("Mobile release")), people)
        assertEquals(listOf("Mobile release", "Fix login", "Asha Patil"), hits.map { it.title })
        assertEquals("directory", hits.last().route); assertNotNull(hits.last().person)
        assertTrue(SearchHit.search("m", listOf(task("Mobile"))).isEmpty())
        assertEquals(listOf("Fix login"), SearchHit.search("LOGIN fix", listOf(task("Fix login"), task("Login page"))).map { it.title })
        assertEquals(SearchHit.PER_KIND, SearchHit.search("task", (1..20).map { task("Task $it") }).size)
    }
}
