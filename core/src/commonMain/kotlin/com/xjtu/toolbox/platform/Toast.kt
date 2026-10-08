package com.xjtu.toolbox.platform

/**
 * 平台能力：**一句短提示**（`:app` 里 `android.widget.Toast` 那一族）。
 *
 * 为什么要有它：共享屏里有「长按复制教室名 → 弹一句『已复制：xxx』」这种即时反馈，
 * 而 Toast 是 Android 专属类型。做成一个 expect 函数，屏只写「告诉用户这一句」，
 * 各端决定这句以什么形式出现。
 *
 * 三端实现与各自的**如实降级**：
 *  - Android = `Toast.makeText(…, LENGTH_SHORT)`（与原实现逐字一致：同样的短提示、同样的位置）；
 *  - jvm/desktop = 打到标准输出（本地跑 :core 时看得见，没有系统气泡可弹）；
 *  - Web = 空实现。**复制本身是成功的**（剪贴板那一半三端都有），只是浏览器里没有系统级短提示；
 *    不做一个假的网页气泡来假装"跟 App 一样"，也不吞掉复制 —— 复制的效果用户粘一下就知道。
 *
 * 为什么是普通函数而不是 `@Composable`：调用点（长按回调）不在 composable 作用域里，
 * 而 Android 的 Toast 自己就能在任意线程拿 Context 弹（本实现用进程级的 [androidPlatformContext]）。
 * 将来若要画网页气泡，改成 `@Composable` 的 `rememberBriefMessage()` 也不影响调用点的语义。
 */
expect fun showBriefMessage(message: String)
