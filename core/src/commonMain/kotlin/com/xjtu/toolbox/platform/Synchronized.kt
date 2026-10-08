package com.xjtu.toolbox.platform

/**
 * 进程内互斥：Android / JVM 用 `synchronized`（与 `@Synchronized` 注解**逐字同义**），
 * Web 直接执行（浏览器是单线程事件循环，没有并发写）。
 *
 * 为什么做成切口：`@Synchronized` 与 `synchronized` 都是 `kotlin.jvm` 的东西（在 JVM 上是
 * **默认导入**，import 判据抓不到），而 :core 里有「读-改-写」型的状态存储
 * （`InboxStore` 的 SharedPreferences 读写）需要它。搬进 :core 时**不能**把锁丢掉 ——
 * 那是行为变化（丢更新）。
 */
expect fun <T> synchronizedBlock(lock: Any, block: () -> T): T
