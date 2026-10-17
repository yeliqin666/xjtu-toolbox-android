package com.xjtu.toolbox.platform

import kotlin.random.Random

/** Web：退化成 `Random`。 */
actual fun secureRandomInt(bound: Int): Int {
    require(bound > 0) { "bound 必须为正" }
    return Random.Default.nextInt(bound)
}
