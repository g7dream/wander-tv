package tv.wander.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.wander.browse.CursorMotion.Dir
import kotlin.math.abs
import kotlin.math.hypot

class CursorMotionTest {
    private val eps = 0.01f

    @Test
    fun shortPressIsOnePreciseStep() {
        val m = CursorMotion()
        val step = m.press(Dir.RIGHT, 1000)
        assertEquals(Vec(14f, 0f), step)
        // Released before the hold delay: no glide at all.
        assertTrue(m.frame(1100, 16f).isZero)
        m.release(Dir.RIGHT)
        assertFalse(m.active)
        assertTrue(m.frame(1200, 16f).isZero)
    }

    @Test
    fun stepsPointTheRightWay() {
        val m = CursorMotion()
        assertEquals(Vec(-14f, 0f), m.press(Dir.LEFT, 0))
        assertEquals(Vec(0f, -14f), m.press(Dir.UP, 0))
        assertEquals(Vec(0f, 14f), m.press(Dir.DOWN, 0))
    }

    @Test
    fun holdRampsUpToMaxSpeedBy700ms() {
        val m = CursorMotion()
        assertEquals(0f, m.speedFor(0), eps)
        assertEquals(0f, m.speedFor(149), eps)
        assertEquals(m.startSpeed, m.speedFor(150), eps)
        assertEquals(900f, m.speedFor(700), eps)
        assertEquals(900f, m.speedFor(5000), eps)
        var last = 0f
        for (t in 150L..700L step 10) {
            val s = m.speedFor(t)
            assertTrue("speed must not drop at $t", s >= last)
            last = s
        }
    }

    @Test
    fun frameMovesAtHeldSpeed() {
        val m = CursorMotion()
        m.press(Dir.DOWN, 0)
        m.keepAlive(Dir.DOWN, 900)
        val d = m.frame(1000, 16f)
        assertEquals(0f, d.x, eps)
        assertEquals(900f * 0.016f, d.y, eps)
    }

    @Test
    fun hugeFrameGapsAreCapped() {
        val m = CursorMotion()
        m.press(Dir.RIGHT, 0)
        m.keepAlive(Dir.RIGHT, 1000)
        val d = m.frame(1000, 2000f)
        assertEquals(900f * 0.05f, d.x, eps) // 50 ms cap: a hiccup never teleports the cursor
    }

    @Test
    fun diagonalsMoveAtTheFasterKeysSpeed() {
        val m = CursorMotion()
        m.press(Dir.RIGHT, 0)
        m.press(Dir.DOWN, 0)
        m.keepAlive(Dir.RIGHT, 900)
        m.keepAlive(Dir.DOWN, 900)
        val v = m.velocity(1000)
        assertEquals(900f, hypot(v.x, v.y), 0.5f)
        assertEquals(v.x, v.y, eps)
        assertTrue(v.x > 0 && v.y > 0)
    }

    @Test
    fun diagonalBendsSmoothlyWhenSecondKeyJoins() {
        val m = CursorMotion()
        m.press(Dir.RIGHT, 0)
        m.keepAlive(Dir.RIGHT, 1000)
        m.press(Dir.UP, 1000)
        val v0 = m.velocity(1000)
        assertEquals(0f, v0.y, eps) // second key still in its precise window
        val v1 = m.velocity(1400)
        assertTrue(v1.y < 0f)
        assertEquals(900f, hypot(v1.x, v1.y), 0.5f) // speed doesn't jump, direction turns
    }

    @Test
    fun oppositeKeysCancel() {
        val m = CursorMotion()
        m.press(Dir.LEFT, 0)
        m.press(Dir.RIGHT, 0)
        assertTrue(m.velocity(800).isZero)
    }

    @Test
    fun repeatWithoutPressStartsGlidingAtOnce() {
        val m = CursorMotion()
        m.keepAlive(Dir.LEFT, 500)
        assertTrue(m.isHeld(Dir.LEFT))
        assertEquals(-m.startSpeed, m.velocity(500).x, eps)
    }

    @Test
    fun lostKeyUpCannotLeaveTheCursorSliding() {
        val m = CursorMotion()
        m.press(Dir.UP, 0)
        assertTrue(m.velocity(1000).y < 0f)
        assertTrue(m.velocity(1600).isZero) // no repeat for > 1.5 s: dropped
        assertFalse(m.active)
    }

    @Test
    fun releaseAllStopsEverything() {
        val m = CursorMotion()
        m.press(Dir.UP, 0)
        m.press(Dir.LEFT, 0)
        m.releaseAll()
        assertFalse(m.active)
        assertTrue(m.velocity(900).isZero)
    }

    @Test
    fun edgeDepthMeasuresHowFarIntoTheBand() {
        assertEquals(0f, CursorMotion.edgeDepth(270f, 540f, 64f), eps)
        assertEquals(0f, CursorMotion.edgeDepth(64f, 540f, 64f), eps)
        assertEquals(-0.5f, CursorMotion.edgeDepth(32f, 540f, 64f), eps)
        assertEquals(-1f, CursorMotion.edgeDepth(0f, 540f, 64f), eps)
        assertEquals(0.5f, CursorMotion.edgeDepth(508f, 540f, 64f), eps)
        assertEquals(1f, CursorMotion.edgeDepth(540f, 540f, 64f), eps)
        assertEquals(1f, CursorMotion.edgeDepth(900f, 540f, 64f), eps)
        assertEquals(0f, CursorMotion.edgeDepth(10f, 0f, 64f), eps)
    }

    @Test
    fun edgeScrollOnlyTowardThePushedEdge() {
        val m = CursorMotion()
        m.press(Dir.DOWN, 0)
        m.keepAlive(Dir.DOWN, 900)
        // Deep in the bottom band, held long: full speed down.
        val bottom = m.edgeScroll(1000, 480f, 540f, 960f, 540f)
        assertEquals(0f, bottom.x, eps)
        assertEquals(m.maxEdgeSpeed, bottom.y, 0.5f)
        // Same key, but the cursor is in the top band: pushing away from it, so nothing.
        assertTrue(m.edgeScroll(1000, 480f, 10f, 960f, 540f).isZero)
        // Middle of the screen: nothing.
        assertTrue(m.edgeScroll(1000, 480f, 270f, 960f, 540f).isZero)
    }

    @Test
    fun edgeScrollGrowsWithDepthAndHoldTime() {
        val m = CursorMotion()
        m.press(Dir.UP, 0)
        m.keepAlive(Dir.UP, 900)
        val shallow = m.edgeScroll(1000, 480f, 48f, 960f, 540f).y
        val deep = m.edgeScroll(1000, 480f, 8f, 960f, 540f).y
        assertTrue(shallow < 0f && deep < shallow)
        assertEquals(-m.maxEdgeSpeed * 0.25f, shallow, 1f)

        val fresh = CursorMotion()
        fresh.press(Dir.UP, 0)
        assertTrue("no edge glide during the precise window", fresh.edgeScroll(100, 480f, 0f, 960f, 540f).isZero)
        val early = abs(fresh.edgeScroll(200, 480f, 0f, 960f, 540f).y)
        val later = abs(fresh.edgeScroll(600, 480f, 0f, 960f, 540f).y)
        assertTrue(early > 0f && later > early)
    }

    @Test
    fun horizontalEdgesScrollSideways() {
        val m = CursorMotion()
        m.press(Dir.RIGHT, 0)
        m.keepAlive(Dir.RIGHT, 900)
        val v = m.edgeScroll(1000, 960f, 270f, 960f, 540f)
        assertEquals(m.maxEdgeSpeed, v.x, 0.5f)
        assertEquals(0f, v.y, eps)
    }

    @Test
    fun phoneGainIsPreciseWhenSlowAndFastWhenFlicked() {
        assertEquals(CursorMotion.PHONE_MIN_GAIN, CursorMotion.phoneGain(0f), eps)
        assertEquals(CursorMotion.PHONE_MIN_GAIN, CursorMotion.phoneGain(100f), eps)
        assertEquals(CursorMotion.PHONE_MAX_GAIN, CursorMotion.phoneGain(1400f), eps)
        assertEquals(CursorMotion.PHONE_MAX_GAIN, CursorMotion.phoneGain(9000f), eps)
        var last = 0f
        for (s in 0..2000 step 50) {
            val g = CursorMotion.phoneGain(s.toFloat())
            assertTrue(g >= last)
            last = g
        }
    }

    @Test
    fun phoneMovesAccelerateWithFingerSpeed() {
        val slow = CursorMotion()
        var t = 0L
        var slowTotal = 0f
        repeat(30) {
            t += 16
            slowTotal += slow.phoneMove(1f, 0f, t).x // ~60 px/s
        }
        assertEquals(30f * CursorMotion.PHONE_MIN_GAIN, slowTotal, 0.5f)

        val fast = CursorMotion()
        t = 0L
        var fastTotal = 0f
        repeat(30) {
            t += 16
            fastTotal += fast.phoneMove(40f, 0f, t).x // ~2500 px/s
        }
        assertTrue(fastTotal > 30f * 40f * (CursorMotion.PHONE_MAX_GAIN - 0.2f))
    }

    @Test
    fun burstyPhoneBatchesDontSpikeTheGain() {
        val m = CursorMotion()
        m.phoneMove(4f, 0f, 0)
        // Two batches arriving 1 ms apart (Wi-Fi jitter) are treated as 16 ms each, not 1 ms.
        val d = m.phoneMove(4f, 0f, 1)
        assertTrue(d.x < 4f * CursorMotion.PHONE_MAX_GAIN * 0.6f)
    }

    @Test
    fun phoneMoveKeepsDirection() {
        val m = CursorMotion()
        val d = m.phoneMove(-3f, 4f, 0)
        assertTrue(d.x < 0f && d.y > 0f)
        assertEquals(-3f / 4f, d.x / d.y, eps)
    }
}
