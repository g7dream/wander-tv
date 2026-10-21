package tv.wander.browse

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A 2D amount in dp (a step, a per-frame displacement, or a speed in dp/s, depending on the call). */
internal data class Vec(val x: Float, val y: Float) {
    val isZero: Boolean get() = x == 0f && y == 0f

    companion object {
        val ZERO = Vec(0f, 0f)
    }
}

/**
 * The cursor's physics, free of Android so it can be unit tested. Distances are dp, times are ms.
 *
 * Remote: a short D-pad press moves exactly [stepDp]. Holding a key waits [holdDelayMs] (so a quick
 * tap stays precise), then glides from [startSpeed] and eases up to [maxSpeed] by [rampEndMs] after
 * the press. Two perpendicular keys give a diagonal at the faster key's speed. While a key pushes the
 * cursor into an edge band, the page scrolls that way, faster the deeper the cursor sits in the band.
 *
 * Phones: touchpad deltas (phone CSS px) go through a pointer-acceleration curve: slow fingers are
 * precise, quick flicks cross the room.
 */
internal class CursorMotion(
    val stepDp: Float = 14f,
    val startSpeed: Float = 160f,
    val maxSpeed: Float = 900f,
    val holdDelayMs: Long = 150L,
    val rampEndMs: Long = 700L,
    val staleMs: Long = 1500L,
    val edgeBandX: Float = 48f,
    val edgeBandY: Float = 64f,
    val maxEdgeSpeed: Float = 1100f,
) {
    enum class Dir(val sx: Int, val sy: Int) { LEFT(-1, 0), RIGHT(1, 0), UP(0, -1), DOWN(0, 1) }

    private val downAt = LongArray(4) { NONE }
    private val lastSeen = LongArray(4) { NONE }

    private var phoneSpeed = 0f
    private var phoneLastAt = NONE

    val active: Boolean get() = downAt.any { it != NONE }

    fun isHeld(dir: Dir): Boolean = downAt[dir.ordinal] != NONE

    /** A fresh key press: [dir] starts being held, and the precise step to apply right away is returned. */
    fun press(dir: Dir, now: Long): Vec {
        downAt[dir.ordinal] = now
        lastSeen[dir.ordinal] = now
        return Vec(dir.sx * stepDp, dir.sy * stepDp)
    }

    /**
     * A key repeat. Keeps [dir] alive; if we never saw its first press (it went to another screen),
     * it starts gliding at once instead of taking a precise step.
     */
    fun keepAlive(dir: Dir, now: Long) {
        if (downAt[dir.ordinal] == NONE) downAt[dir.ordinal] = now - holdDelayMs
        lastSeen[dir.ordinal] = now
    }

    fun release(dir: Dir) {
        downAt[dir.ordinal] = NONE
        lastSeen[dir.ordinal] = NONE
    }

    fun releaseAll() {
        for (d in Dir.values()) release(d)
    }

    /** How long [dir] has been held at [now], or -1 when it isn't. */
    fun heldFor(dir: Dir, now: Long): Long {
        val t = downAt[dir.ordinal]
        return if (t == NONE) -1L else max(0L, now - t)
    }

    /** Glide speed in dp/s for a key held [heldMs]: 0 inside the precise window, then an ease-in ramp. */
    fun speedFor(heldMs: Long): Float {
        if (heldMs < holdDelayMs) return 0f
        val span = (rampEndMs - holdDelayMs).coerceAtLeast(1L).toFloat()
        val t = ((heldMs - holdDelayMs) / span).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t) // smoothstep: gentle start, no jerk at the top
        return startSpeed + (maxSpeed - startSpeed) * eased
    }

    /**
     * Velocity in dp/s at [now] from the held keys. Keys not refreshed for [staleMs] are dropped, so a
     * lost key-up can never leave the cursor sliding forever.
     */
    fun velocity(now: Long): Vec {
        dropStale(now)
        val vx = axisSpeed(Dir.RIGHT, now) - axisSpeed(Dir.LEFT, now)
        val vy = axisSpeed(Dir.DOWN, now) - axisSpeed(Dir.UP, now)
        if (vx == 0f || vy == 0f) return Vec(vx, vy)
        // Diagonal: point where the keys point, but move at the faster key's speed (not sqrt(2) faster).
        val mag = max(abs(vx), abs(vy))
        val len = hypot(vx, vy)
        return Vec(vx / len * mag, vy / len * mag)
    }

    /** Displacement in dp for one frame of [dtMs] at [now]. */
    fun frame(now: Long, dtMs: Float): Vec {
        val v = velocity(now)
        if (v.isZero) return Vec.ZERO
        val s = dtMs.coerceIn(0f, MAX_FRAME_MS) / 1000f
        return Vec(v.x * s, v.y * s)
    }

    /**
     * Page scroll velocity in dp/s for a cursor at ([x], [y]) on a [width] x [height] dp screen. Only
     * the edge a held key is pushing toward scrolls; speed grows with the cursor's depth into the band
     * and with how long the key has been held.
     */
    fun edgeScroll(now: Long, x: Float, y: Float, width: Float, height: Float): Vec {
        dropStale(now)
        val sx = edgeAxis(Dir.LEFT, Dir.RIGHT, x, width, edgeBandX, now)
        val sy = edgeAxis(Dir.UP, Dir.DOWN, y, height, edgeBandY, now)
        return Vec(sx, sy)
    }

    /**
     * A phone touchpad delta (phone CSS px) turned into a TV cursor delta (dp). [now] is the arrival
     * time; the phone sends about 60 batches a second, so each batch covers at least one 16 ms frame.
     */
    fun phoneMove(dx: Float, dy: Float, now: Long): Vec {
        val gap = if (phoneLastAt == NONE) Long.MAX_VALUE else now - phoneLastAt
        phoneLastAt = now
        val dt = if (gap > PHONE_STROKE_GAP_MS) PHONE_FRAME_MS else gap.toFloat().coerceIn(PHONE_FRAME_MS, PHONE_STROKE_GAP_MS.toFloat())
        val instant = hypot(dx, dy) / dt * 1000f
        phoneSpeed = if (gap > PHONE_STROKE_GAP_MS) instant else phoneSpeed * 0.55f + instant * 0.45f
        val g = phoneGain(phoneSpeed)
        return Vec(dx * g, dy * g)
    }

    private fun axisSpeed(dir: Dir, now: Long): Float {
        val held = heldFor(dir, now)
        return if (held < 0) 0f else speedFor(held)
    }

    private fun edgeAxis(neg: Dir, pos: Dir, at: Float, size: Float, band: Float, now: Long): Float {
        val pushNeg = isHeld(neg)
        val pushPos = isHeld(pos)
        if (pushNeg == pushPos) return 0f // neither, or both cancelling out
        val depth = edgeDepth(at, size, band)
        return if (pushPos && depth > 0f) {
            maxEdgeSpeed * depth * rampFactor(heldFor(pos, now))
        } else if (pushNeg && depth < 0f) {
            maxEdgeSpeed * depth * rampFactor(heldFor(neg, now))
        } else {
            0f
        }
    }

    private fun rampFactor(heldMs: Long): Float = if (heldMs < 0) 0f else speedFor(heldMs) / maxSpeed

    private fun dropStale(now: Long) {
        for (i in 0 until 4) {
            if (downAt[i] != NONE && now - lastSeen[i] > staleMs) {
                downAt[i] = NONE
                lastSeen[i] = NONE
            }
        }
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        private const val MAX_FRAME_MS = 50f
        private const val PHONE_FRAME_MS = 16f
        private const val PHONE_STROKE_GAP_MS = 120L

        /** Phone px -> dp gain at the slowest and fastest finger speeds (phone px/s). */
        const val PHONE_MIN_GAIN = 1.3f
        const val PHONE_MAX_GAIN = 3.6f
        private const val PHONE_SLOW = 120f
        private const val PHONE_FAST = 1400f

        /**
         * How deep [pos] sits in the edge band of a [size]-long axis, from -1 (pressed against the start
         * edge) through 0 (outside both bands) to 1 (pressed against the end edge).
         */
        fun edgeDepth(pos: Float, size: Float, band: Float): Float {
            if (band <= 0f || size <= 0f) return 0f
            val b = minOf(band, size / 2f)
            return when {
                pos < b -> -((b - pos) / b).coerceIn(0f, 1f)
                pos > size - b -> ((pos - (size - b)) / b).coerceIn(0f, 1f)
                else -> 0f
            }
        }

        /** Pointer acceleration: [PHONE_MIN_GAIN] for slow, careful fingers up to [PHONE_MAX_GAIN] for flicks. */
        fun phoneGain(speedPxPerSec: Float): Float {
            val t = ((speedPxPerSec - PHONE_SLOW) / (PHONE_FAST - PHONE_SLOW)).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            return PHONE_MIN_GAIN + (PHONE_MAX_GAIN - PHONE_MIN_GAIN) * eased
        }
    }
}
