package com.xjtu.toolbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动提醒三档（关 / 少 / 标准）的判断逻辑。
 *
 * [ProactiveRules.alertsAllowed]、[ProactiveRules.chatterAllowed]、
 * [ProactiveRules.globalCooldownMs] 都是不碰 Context、不碰时间的纯函数，直接对着
 * [ProactiveLevel] 断言即可，不用伪造 SharedPreferences。
 */
class ProactiveRulesTest {

    @Test
    fun `关档完全不允许生成气泡`() {
        assertFalse(ProactiveRules.alertsAllowed(ProactiveLevel.OFF))
        assertFalse(ProactiveRules.chatterAllowed(ProactiveLevel.OFF))
    }

    @Test
    fun `少档允许正事提醒但不允许闲聊`() {
        assertTrue(ProactiveRules.alertsAllowed(ProactiveLevel.LOW))
        assertFalse(ProactiveRules.chatterAllowed(ProactiveLevel.LOW))
    }

    @Test
    fun `标准档正事和闲聊都允许`() {
        assertTrue(ProactiveRules.alertsAllowed(ProactiveLevel.STANDARD))
        assertTrue(ProactiveRules.chatterAllowed(ProactiveLevel.STANDARD))
    }

    @Test
    fun `少档把全局冷却拉长到一小时`() {
        assertEquals(60 * 60 * 1000L, ProactiveRules.globalCooldownMs(ProactiveLevel.LOW))
    }

    @Test
    fun `标准档全局冷却维持十五分钟不变`() {
        assertEquals(15 * 60 * 1000L, ProactiveRules.globalCooldownMs(ProactiveLevel.STANDARD))
    }

    @Test
    fun `关档下的全局冷却值不影响结果——alertsAllowed 已经在更前面拦掉`() {
        // OFF 档不会走到 globalCooldownMs 这一步（pick() 里先判 alertsAllowed），
        // 但函数本身对 OFF 也要有确定的返回值，不能抛异常或返回垃圾值。
        assertEquals(15 * 60 * 1000L, ProactiveRules.globalCooldownMs(ProactiveLevel.OFF))
    }
}
