package com.pravahax.portalx.media

import kotlin.random.Random

/** One analysed camera frame, reduced to what the liveness check needs (no ML Kit types, so it is unit-tested). */
data class FaceFrame(
    val faces: Int,
    /** Face box width / image width (after rotation). */
    val widthFraction: Float = 0f,
    /** Head yaw in degrees (ML Kit headEulerAngleY). */
    val yaw: Float = 0f,
    val leftEyeOpen: Float? = null,
    val rightEyeOpen: Float? = null,
    val smiling: Float? = null,
)

enum class Challenge(val prompt: String) {
    Blink("Blink slowly"),
    Turn("Turn your head to one side, then look back"),
    Smile("Give us a smile"),
}

/**
 * v0.9 active liveness: the user first holds their face steady in the frame, then completes randomly chosen
 * challenges a printed photo can't (blink, head turn, smile). One face only; too far away doesn't count.
 * Not proof against a replayed video — the server still owns the decision; this raises the bar on-device.
 */
class LivenessCheck(val challenges: List<Challenge> = random(), private val timeoutMs: Long = 25_000, private val startedAt: Long) {
    sealed interface State {
        data class Hint(val text: String) : State
        data class Doing(val index: Int, val challenge: Challenge) : State
        data object Passed : State
        data class Failed(val reason: String) : State
    }

    private var steady = 0
    private var index = -1 // -1: waiting for a steady face
    private var phase = 0
    var state: State = State.Hint("Fit your face in the circle"); private set

    /** Wall-clock check, called on a timer too: the timeout must fire even when no face is ever detected. */
    fun tick(now: Long): State {
        if (state !is State.Passed && state !is State.Failed && now - startedAt > timeoutMs) state = State.Failed("That took too long. Let's try again.")
        return state
    }

    fun onFrame(f: FaceFrame, now: Long): State {
        if (tick(now) is State.Passed || state is State.Failed) return state
        state = when {
            f.faces > 1 -> { steady = 0; if (index >= 0) { index = -1; phase = 0 }; State.Hint("Only your face, please") }
            f.faces == 0 -> { steady = 0; State.Hint("Fit your face in the circle") }
            f.widthFraction < MIN_FACE -> { steady = 0; State.Hint("Move a little closer") }
            index < 0 -> if (kotlin.math.abs(f.yaw) < 12 && ++steady >= STEADY_FRAMES) { index = 0; phase = 0; State.Doing(0, challenges[0]) } else State.Hint("Hold still")
            else -> {
                if (step(challenges[index], f)) { index++; phase = 0 }
                if (index >= challenges.size) State.Passed else State.Doing(index, challenges[index])
            }
        }
        return state
    }

    /** True when the challenge is complete. */
    private fun step(c: Challenge, f: FaceFrame): Boolean {
        val l = f.leftEyeOpen; val r = f.rightEyeOpen
        return when (c) {
            Challenge.Blink -> {
                if (l == null || r == null) return false
                when (phase) {
                    // Thresholds kept loose enough for glasses and dim offices (ML Kit scores drop for both).
                    0 -> if (l > OPEN && r > OPEN) phase = 1
                    1 -> if (l < CLOSED && r < CLOSED) phase = 2
                    2 -> if (l > OPEN && r > OPEN) return true
                }
                false
            }
            Challenge.Turn -> {
                when (phase) {
                    0 -> if (kotlin.math.abs(f.yaw) > 22f) phase = 1
                    1 -> if (kotlin.math.abs(f.yaw) < 10f) return true
                }
                false
            }
            Challenge.Smile -> {
                if ((f.smiling ?: 0f) > 0.7f) phase++ else phase = 0
                phase >= 2
            }
        }
    }

    companion object {
        const val MIN_FACE = 0.25f
        const val STEADY_FRAMES = 5
        const val OPEN = 0.55f
        const val CLOSED = 0.35f
        fun random(rng: Random = Random.Default): List<Challenge> = Challenge.entries.shuffled(rng).take(2)
    }
}
