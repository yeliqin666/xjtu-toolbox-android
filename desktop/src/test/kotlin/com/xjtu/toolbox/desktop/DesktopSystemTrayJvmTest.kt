package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.desktop.notify.DesktopReminders
import com.xjtu.toolbox.desktop.notify.DesktopSystemTray
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 系统通知那一层的降级验收（B-7）。
 *
 * 这条测试**不能**假设“一定能弹出去”（有显示环境的开发机上真会弹一个气泡），也不能假设
 * 一定能**静默**（`DISPLAY` 为空时一定会退回 false）。能跨机器断言的只有两件事：
 *
 * 1. 任何环境下都不抛（这是那条链的主要职责：提醒弹不出去不该把课表刷新变成崩溃点）；
 * 2. 返回值自洽（弹出去了就一定 `canNotify`，无头就一定 `false`）。
 */
class DesktopSystemTrayJvmTest {

    @Test
    fun `拿不到托盘就静默跳过：不抛，退回 false`() {
        val headless = GraphicsEnvironment.isHeadless()
        val sent = runCatching { DesktopSystemTray.notify("课表有变动", "测试通知") }
            .getOrElse { fail("拿不到托盘时不该抛：$it") }
        val canNotify = runCatching { DesktopSystemTray.canNotify }
            .getOrElse { fail("问“能不能弹”也不该抛：$it") }

        if (headless) {
            assertFalse(canNotify, "无头环境（`DISPLAY` 为空）不该有系统托盘")
            assertFalse(sent, "无头环境不可能真弹出去")
        } else {
            assertTrue(!sent || canNotify, "弹成功了就得是“能弹”，不能说弹了又没托盘")
        }
    }

    @Test
    fun `课表变更那条提醒走的是同一个降级口`() {
        val headless = GraphicsEnvironment.isHeadless()
        val sent = runCatching { DesktopReminders.scheduleChanged("夹具：3-201 换到 3-305") }
            .getOrElse { fail("提醒送达不该抛：$it") }
        if (headless) assertFalse(sent, "无头环境上送达必然是 false（收纳那条路不受影响）")

        // 第二次走的是缓存下来的「这一端没有系统通知」，同样不许抛。
        runCatching { DesktopReminders.scheduleChanged("夹具：再一条") }
            .getOrElse { fail("失败是粘性的，但粘住之后更不该抛：$it") }
    }
}
