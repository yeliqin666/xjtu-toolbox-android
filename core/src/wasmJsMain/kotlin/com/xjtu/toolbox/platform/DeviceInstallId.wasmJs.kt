package com.xjtu.toolbox.platform

/**
 * Web 侧：**没有等价物** ⇒ 恒 null（调用方退回本机随机 ID，见 expect 的 KDoc）。
 *
 * 浏览器能拿到的指纹（UA、canvas、时区…）都不是「这台机器上这个应用」这一档，而且拿它们冒充
 * 匿名标识反而更糟。真要有稳定的匿名 ID，应写在 `localStorage` 里 —— 那正是调用方的兜底路径。
 */
actual fun deviceInstallId(salt: String): String? = null
