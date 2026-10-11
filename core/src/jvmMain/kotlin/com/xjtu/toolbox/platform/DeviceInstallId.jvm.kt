package com.xjtu.toolbox.platform

/**
 * 桌面（jvm）侧：**没有等价物** ⇒ 恒 null（调用方退回本机随机 ID，见 expect 的 KDoc）。
 *
 * 不拿 `user.name` / 主机名 / MAC 这类东西凑一个「稳定标识」：那不是 Android 那个口径
 * （按应用签名 + 用户 + 设备隔离），拿它冒充只会让「匿名」变得不匿名。
 */
actual fun deviceInstallId(salt: String): String? = null
