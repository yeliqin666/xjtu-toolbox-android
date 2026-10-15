package com.xjtu.toolbox.platform

/** Web 侧：没有等价物 ⇒ 恒 null（调用方退回本机随机 ID，见 expect 的 KDoc）。 */
actual fun deviceInstallId(salt: String): String? = null
