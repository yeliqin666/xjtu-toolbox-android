package com.xjtu.toolbox.platform

import java.security.SecureRandom

private val secureRandom by lazy { SecureRandom() }

actual fun secureRandomInt(bound: Int): Int {
    require(bound > 0) { "bound 必须为正" }
    return secureRandom.nextInt(bound)
}
