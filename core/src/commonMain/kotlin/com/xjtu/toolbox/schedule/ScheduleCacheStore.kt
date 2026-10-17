package com.xjtu.toolbox.schedule

/** 「课表族落盘缓存」这条缝：原始 JSON 字符串 + TTL 两个动作，别的一律不管。 */
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
