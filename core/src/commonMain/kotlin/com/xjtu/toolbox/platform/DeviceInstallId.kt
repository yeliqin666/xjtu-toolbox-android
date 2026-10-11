package com.xjtu.toolbox.platform

/**
 * 平台能力：**本机稳定匿名标识**（哈希过，不含学号/姓名/IMEI）。
 *
 * 用途只有一处：反馈（工单）要把「同一个人的多条反馈」串起来，而用户不注册账号，所以需要一个
 * 换安装包、清数据之后仍能认出来的本机标识（见 `:core` 的 `feedback/FeedbackStore.anonId`）。
 *
 * 交付的是**哈希后的短串**，不是设备标识本身 —— 调用方拿到的永远是一串十六进制，拿去对不上
 * 任何设备。`salt` 让不同用途各拿一份互不相干的串（现在的调用点只有反馈，传来的是 `"feedback"`）。
 *
 * 各端：
 *  - **Android** = `Settings.Secure.ANDROID_ID` 的 `SHA-256("$salt|$androidId")` 前 8 位十六进制
 *    —— 与搬迁前 `:app` 的 `FeedbackStore.androidIdHash` **逐字同构**（同一个 salt、
 *    同一段截断、连那个「旧模拟器固定值」的判据都照搬）⇒ 老用户手里那个匿名 ID 不变。
 *    读不到（没有 Context、字段为空、撞上那个无效值）返回 null；
 *  - **桌面 / Web** = null：这两端没有等价物（Android 的 ANDROID_ID 按「应用签名 + 用户 + 设备」
 *    隔离，桌面/浏览器都没有这一档）⇒ 调用方退回本机随机 ID 并**存在自己的键值存储里**，
 *    所以同一份数据下仍然稳定。这是如实的差异，不是漏做。
 */
expect fun deviceInstallId(salt: String): String?
