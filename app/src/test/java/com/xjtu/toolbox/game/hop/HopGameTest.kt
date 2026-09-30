package com.xjtu.toolbox.game.hop

import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HopGameTest {

    /** 按住 [seconds] 秒再松手，等这一跳落地。 */
    private fun HopGame.jump(seconds: Float, side: Int = 1) {
        press(side)
        update(seconds)
        release()
        update(FLIGHT_TIME)
    }

    /** 正好跳到 [pad] 中心要蓄多久。 */
    private fun HopGame.chargeFor(pad: Pad, boost: Float = 1f) =
        hypot(pad.x - px, pad.z - pz) / (JUMP_SPEED * boost)

    private fun pad(x: Float, z: Float, kind: PadKind = PadKind.NORMAL, half: Float = 0.5f, axis: Int = 0, bonus: Int = 0) =
        Pad(x, z, half, round = false, kind = kind, axis = axis, style = 0, bonus = bonus)

    @Test
    fun `连续落正中分数按 2 4 递增`() {
        val g = HopGame(Random(1))
        g.jump(g.chargeFor(g.targets[0]))
        assertEquals(2, g.score)
        g.jump(g.chargeFor(g.targets[0]))
        assertEquals(6, g.score)
        assertEquals(2, g.streak)
    }

    @Test
    fun `蓄力太短落回原地不加分`() {
        val g = HopGame(Random(1))
        g.jump(0.01f)
        assertEquals(HopPhase.IDLE, g.phase)
        assertEquals(0, g.score)
    }

    @Test
    fun `跳过头掉下去就结束`() {
        val g = HopGame(Random(1))
        g.jump(MAX_CHARGE)
        assertEquals(HopPhase.FALLING, g.phase)
        g.update(FALL_TIME)
        assertEquals(HopPhase.OVER, g.phase)
    }

    @Test
    fun `分叉时按左半屏跳沿 z 那块并拿额外分`() {
        val g = HopGame(Random(1))
        val right = pad(2f, 0f, axis = 0)
        val left = pad(0f, 2f, half = 0.3f, axis = 1, bonus = FORK_BONUS)
        g.targets = listOf(right, left)
        g.jump(g.chargeFor(left), side = -1)
        assertEquals(left, g.current)
        assertEquals(2 + FORK_BONUS, g.score)
    }

    @Test
    fun `蓄力中手指挪到另一边就改瞄另一块`() {
        val g = HopGame(Random(1))
        val right = pad(2f, 0f, axis = 0)
        val left = pad(0f, 2f, axis = 1)
        g.targets = listOf(right, left)
        g.press(-1)
        assertEquals(left, g.aimTarget)
        g.aim(1)
        assertEquals(right, g.aimTarget)
    }

    @Test
    fun `落在台边外不到半个底座会往外倒`() {
        val g = HopGame(Random(1))
        g.targets = listOf(pad(2f, 0f))
        g.jump((2f + 0.5f + PLAYER_RADIUS / 2) / JUMP_SPEED)
        assertEquals(HopPhase.FALLING, g.phase)
        assertTrue(g.tipping)
        assertTrue(g.tiltX > 0.9f)
    }

    @Test
    fun `在黑胶上停够两秒拿 10 分`() {
        val g = HopGame(Random(1))
        val vinyl = Pad(2f, 0f, 0.5f, round = true, kind = PadKind.NORMAL, axis = 0, style = 1)
        g.targets = listOf(vinyl)
        g.jump(2f / JUMP_SPEED)
        val before = g.score
        g.update(STAY_TIME + 0.01f)
        assertEquals(before + 10, g.score)
        g.update(STAY_TIME)
        assertEquals("只给一次", before + 10, g.score)
    }

    @Test
    fun `落进传送门被送到前面并加分`() {
        val g = HopGame(Random(1))
        val portal = Pad(2f, 0f, 0.5f, round = true, kind = PadKind.PORTAL, axis = 0, style = 0)
        g.targets = listOf(portal)
        g.jump(2f / JUMP_SPEED)
        assertEquals(HopPhase.WARPING, g.phase)
        val before = g.score
        g.update(WARP_TIME)
        assertEquals(HopPhase.IDLE, g.phase)
        assertTrue(g.px > 5f)
        assertEquals(before + PORTAL_BONUS, g.score)
        assertTrue(g.targets.isNotEmpty())
    }

    @Test
    fun `幽灵台隐身时落上去会掉下`() {
        val g = HopGame(Random(1))
        val ghost = pad(2f, 0f, PadKind.GHOST)
        // phase 让 sin 落在最低处，此刻完全透明
        ghost.phase = (3 * Math.PI / 2 / 2.4).toFloat()
        g.targets = listOf(ghost)
        g.press(1)
        g.update(2f / JUMP_SPEED)
        ghost.phase = (3 * Math.PI / 2 / 2.4).toFloat() - FLIGHT_TIME
        g.release()
        g.update(FLIGHT_TIME)
        assertEquals(HopPhase.FALLING, g.phase)
    }

    @Test
    fun `蹦床落稳后自动跳到下一块正中`() {
        val g = HopGame(Random(1))
        g.targets = listOf(Pad(2f, 0f, 0.5f, round = true, kind = PadKind.TRAMPOLINE, axis = 0, style = 0))
        g.jump(2f / JUMP_SPEED)
        val next = g.targets.single()
        assertEquals(PadKind.NORMAL, next.kind)
        g.update(BOUNCE_DELAY + 0.01f)
        assertEquals(HopPhase.FLYING, g.phase)
        g.update(FLIGHT_TIME)
        assertEquals(next, g.current)
        assertTrue(g.lastWasCenter)
    }

    @Test
    fun `站在旋转台上会被带着转`() {
        val g = HopGame(Random(1))
        g.targets = listOf(Pad(2f, 0f, 0.5f, round = true, kind = PadKind.SPIN, axis = 0, style = 0))
        g.jump((2f + 0.3f) / JUMP_SPEED)
        val r = hypot(g.px - 2f, g.pz)
        val z0 = g.pz
        g.update(0.5f)
        assertTrue(g.pz != z0)
        assertEquals(r, hypot(g.px - 2f, g.pz), 1e-3f)
    }

    @Test
    fun `冰面落得太靠前会滑下去`() {
        val g = HopGame(Random(1))
        val ice = pad(2f, 0f, PadKind.ICE)
        g.targets = listOf(ice)
        g.jump((2f + 0.2f) / JUMP_SPEED)
        assertEquals(HopPhase.SLIDING, g.phase)
        g.update(SLIDE_TIME)
        assertEquals(HopPhase.FALLING, g.phase)
    }

    @Test
    fun `冰面落在正中滑完还站得住`() {
        val g = HopGame(Random(1))
        g.targets = listOf(pad(2f, 0f, PadKind.ICE))
        g.jump(2f / JUMP_SPEED)
        g.update(SLIDE_TIME)
        assertEquals(HopPhase.IDLE, g.phase)
    }

    @Test
    fun `易碎台站太久会塌`() {
        val g = HopGame(Random(1))
        g.targets = listOf(pad(2f, 0f, PadKind.CRUMBLE))
        g.jump(2f / JUMP_SPEED)
        assertEquals(HopPhase.IDLE, g.phase)
        g.update(CRUMBLE_TIME + 0.01f)
        assertEquals(HopPhase.FALLING, g.phase)
    }

    @Test
    fun `易碎台之后只出够得着又不用等时机的一块`() {
        repeat(300) { seed ->
            val g = HopGame(Random(seed))
            g.score = 120
            g.targets = listOf(pad(2f, 0f, PadKind.CRUMBLE))
            g.jump(2f / JUMP_SPEED)
            val next = g.targets.single()
            assertTrue(next.kind != PadKind.MOVING && next.kind != PadKind.GHOST)
            assertTrue(hypot(next.baseX - 2f, next.baseZ) <= RUSHED_GAP + 1e-4f)
        }
    }

    @Test
    fun `高分时站在台子后沿也够得着每一块`() {
        repeat(300) { seed ->
            val g = HopGame(Random(seed))
            g.score = 120
            g.targets = listOf(pad(2f, 0f, half = 0.6f))
            g.jump(2f / JUMP_SPEED)
            for (t in g.targets) {
                assertTrue(hypot(t.baseX - 2f, t.baseZ) <= JUMP_SPEED * MAX_CHARGE - 0.6f + 1e-4f)
            }
        }
    }

    @Test
    fun `移动台沿轨道中线起跳，落点不随台子此刻的位置歪`() {
        val g = HopGame(Random(1))
        val moving = pad(2f, 0f, PadKind.MOVING, axis = 0)
        moving.phase = (Math.PI / 2).toFloat()
        g.targets = listOf(moving)
        g.jump(2f / JUMP_SPEED)
        assertEquals(0f, g.pz, 1e-4f)
    }

    @Test
    fun `弹簧台上起跳力度放大`() {
        val g = HopGame(Random(1))
        g.targets = listOf(pad(2f, 0f, PadKind.SPRING))
        g.jump(2f / JUMP_SPEED)
        assertTrue(g.springActive)
        val next = pad(5f, 0f)
        g.targets = listOf(next)
        g.jump(g.chargeFor(next, SPRING_BOOST))
        assertEquals(next, g.current)
        assertTrue(g.lastWasCenter)
    }
}
