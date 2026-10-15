package com.xjtu.toolbox.platform

import java.security.SecureRandom

/** JVM：与 Android 同一份实现（这个切口是平台能力，不是平台外观）。 */
private val secureRandom by lazy { SecureRandom() }

actual fun secureRandomInt(bound: Int): Int {
    require(bound > 0) { "bound 必须为正" }
    return secureRandom.nextInt(bound)
}
