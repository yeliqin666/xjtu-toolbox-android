package com.xjtu.toolbox.schedule

/**
 * 「课表族落盘缓存」这条缝：**原始 JSON 字符串 + TTL** 两个动作，别的一律不管。
 *
 * ## 为什么是这两个动作
 *
 * 搬迁前这些键都由 `:app` 的 `DataCache` 存取：一个 `cacheDir/data_cache<账号后缀>/<key>.json`
 * 文件，`get` 时按文件最后修改时间判 TTL、过期就删掉返回 null；`put` 先写 `.tmp` 再 rename。
 * 键名（`schedule_<学期>` / `exams_<学期>` / `schedule_optimized_<学期>` …）与**存进去的 JSON**
 * 是用户数据：换端、换实现都不能让它们读不出来。所以这条缝的口径就是**逐字节**的
 * 「读一段 JSON、写一段 JSON」，序列化一律在 [ScheduleCache] 里走 `:core` 的 `AppJson`
 * （与 `DataCache.read/write` 用的同一个 `Json` 实例）—— 缝上不留任何模型。
 *
 * ## 三端各是谁
 *
 * - `:app` —— `com.xjtu.toolbox.data.DataCache` **直接实现**它（五个一行转发），
 *   所以 :app 侧所有 `ScheduleCache.xxx(cache, …)` 调用点一行未改：目录名、文件名、
 *   账号后缀、原子写入全是原来那一份。
 * - `:desktop` —— 落在 `secureKeyValueStore` 的键值文件里（这一端没有 `cacheDir`）。
 * - `:web` —— 尚未接入（课表屏还没进浏览器那条链）。
 *
 * ⚠️ 命名带 `Json` 是刻意的：它存的是字符串，不是对象。谁想要对象用 [ScheduleCache] 里
 * 那几个带类型的读法（它们内部才解 JSON）。
 */
interface ScheduleCacheStore {

    /** 读一段 JSON；不存在、读写错误、或**已过 [ttlMs]**（实现应当顺手把它删掉）返回 null。 */
    fun readJson(key: String, ttlMs: Long): String?

    /** 写一段 JSON。实现要保证「写一半崩掉」不会留下一份读不出来的值。 */
    fun writeJson(key: String, json: String)

    /** 不看过期时间读一段 JSON（联网失败时兜底用）。 */
    fun readJsonStale(key: String): String?

    /** 删掉一个键。 */
    fun dropJson(key: String)

    /** 距上次写入过了多少毫秒；没写过返回 null。不受 TTL 影响。 */
    fun ageMsOfJson(key: String): Long?
}
