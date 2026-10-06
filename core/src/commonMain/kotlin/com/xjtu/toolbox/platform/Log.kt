package com.xjtu.toolbox.platform

/**
 * 平台能力：**日志**。
 *
 * 为什么要有它：`:app` 里 `android.util.Log` 有 66 处，是「UI 层自己就扎在 Android 里」的头号原因
 * （交接文档 §1 的实测）。日志是纯能力、没有业务语义，所以用**一个 expect 函数 + 一个共享门面**
 * 收口 —— 粒度是「能力」而不是「文件」，这正是交接文档 §4 末尾那条坑说的做法。
 *
 * 注意 expect 只落在 [platformLog] 一个函数上，[Log] 本身是 commonMain 里的普通对象：
 * 加一个日志级别、改一次格式，不需要动三端。
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** 各端把一行日志落到哪里去。 */
expect fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?)

/**
 * 跨端日志门面。
 *
 * 行为口径（与 :app 现在的用法保持一致，避免「搬个位置就把日志刷屏改了」）：
 *  - Android 走 `android.util.Log`（沿用系统 tag 过滤，行为不变）；
 *  - Web 走 `console`（浏览器里唯一能被开发者工具看到的地方）；
 *  - jvm/desktop 走标准输出（本地跑 :core 测试时看得见）。
 */
object Log {
    fun d(tag: String, message: String) = platformLog(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = platformLog(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) =
        platformLog(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        platformLog(LogLevel.ERROR, tag, message, error)
}
