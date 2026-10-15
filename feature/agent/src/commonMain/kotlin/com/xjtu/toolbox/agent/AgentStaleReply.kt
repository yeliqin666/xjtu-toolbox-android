package com.xjtu.toolbox.agent

import com.xjtu.toolbox.schedule.ScheduleCacheStore

/** 把毫秒年龄转成人话，供回退缓存时标注新鲜度。 */
fun agentCacheAgeText(ms: Long): String = when {
    ms < 60_000L      -> "刚刚"
    ms < 3_600_000L   -> "约${ms / 60_000L}分钟前"
    ms < 86_400_000L  -> "约${ms / 3_600_000L}小时前"
    else              -> "约${ms / 86_400_000L}天前"
}

/** 实时获取失败时的兜底：若有缓存则返回带「约 X 前」时间戳的缓存内容，否则返回实时错误文案。 */
fun agentStaleFallback(cache: ScheduleCacheStore, cacheKey: String, liveError: String): String {
    val cached = cache.readJsonStale(cacheKey)
    if (cached == null) {
        return ToolReply.noCache(liveError)
    }
    val age = cache.ageMsOfJson(cacheKey)?.let { agentCacheAgeText(it) } ?: "较早"
    return ToolReply.stale(age, cached)
}
