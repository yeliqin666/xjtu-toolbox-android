package com.xjtu.toolbox.platform

import kotlin.random.Random

/**
 * Web：退化成 `Random`。见 commonMain 的说明 —— 浏览器没有 BLE，Web 端不会是联机房主，
 * 这条路径实际走不到；真要走，也只有「防第三台设备顶替」这一层会变弱。
 */
actual fun secureRandomInt(bound: Int): Int {
    require(bound > 0) { "bound 必须为正" }
    return Random.Default.nextInt(bound)
}
