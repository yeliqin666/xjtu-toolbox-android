package com.xjtu.toolbox.platform

/**
 * jvm / 桌面端：打到标准输出。
 *
 * 这一端没有系统短提示气泡（连窗口都可能没有），但共享逻辑的单测跑在 JVM 上 —— 打到 stdout
 * 至少能在测试日志里看见"这句提示该出现"。与 [rememberShareLink] 的 jvm 空实现不同：
 * 提示是纯文本、没有副作用，打一行反而更有信息量。
 */
actual fun showBriefMessage(message: String) {
    println("(提示) $message")
}
