package com.xjtu.toolbox.platform

import java.security.SecureRandom

/** Android：与搬迁前逐字一致 —— `OneTimeToken.generate()` 原先就是每次 new 一个 SecureRandom。 */
private val secureRandom by lazy { SecureRandom() }

actual fun secureRandomInt(bound: Int): Int {
    require(bound > 0) { "bound 必须为正" }
    return secureRandom.nextInt(bound)
}
