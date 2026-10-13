package com.xjtu.toolbox.agent

import com.xjtu.toolbox.schedule.ScheduleCacheStore

/** 把毫秒年龄转成人话，供回退缓存时如实标注新鲜度。逐字来自搬迁前的 `AgentToolRegistry.humanAge`。 */
fun agentCacheAgeText(ms: Long): String = when {
    ms < 60_000L      -> "刚刚"
    ms < 3_600_000L   -> "约${ms / 60_000L}分钟前"
    ms < 86_400_000L  -> "约${ms / 3_600_000L}小时前"
    else              -> "约${ms / 86_400_000L}天前"
}

/**
 * 实时获取失败时的兜底：若有缓存则返回带「约 X 前」时间戳的缓存内容，否则返回实时错误文案。
 * 让 Agent 能如实告诉用户「这是几点的缓存」，而不是干脆报错。
 *
 * 逐字来自搬迁前的 `AgentToolRegistry.staleOr`。缓存那一格收成 `:core` 既有的
 * [ScheduleCacheStore]（**读一段 JSON + 它有多老**两个动作，`readJsonStale` / `ageMsOfJson`），
 * `:app` 传的就是同一个 `DataCache` —— 键名、目录、TTL 判定一行未动。
 */
fun agentStaleFallback(cache: ScheduleCacheStore, cacheKey: String, liveError: String): String {
    val cached = cache.readJsonStale(cacheKey)
    if (cached == null) {
        return ToolReply.noCache(liveError)
    }
    val age = cache.ageMsOfJson(cacheKey)?.let { agentCacheAgeText(it) } ?: "较早"
    return ToolReply.stale(age, cached)
}
