package com.xjtu.toolbox.jwapp

import android.content.Context
import com.xjtu.toolbox.data.DataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `:app` 侧的成绩屏缓存装配 —— `:core` 的 [JwappScoreCache] 在 Android 上的实现。 */
fun appJwappScoreCache(context: Context, accountId: String?): JwappScoreCache =
    DataCacheJwappScoreCache(context.applicationContext, accountId)

private class DataCacheJwappScoreCache(
    context: Context,
    accountId: String?,
) : JwappScoreCache {
    private val cache = DataCache(context, accountId)

    override suspend fun read(maxAgeMs: Long): List<TermScore>? =
        withContext(Dispatchers.IO) { cache.read(CACHE_KEY, maxAgeMs) }

    override suspend fun write(scores: List<TermScore>) =
        withContext(Dispatchers.IO) { cache.write(CACHE_KEY, scores) }

    private companion object {
        /** 缓存文件名，不能改（老缓存靠它命中）。 */
        const val CACHE_KEY = "score_all_terms"
    }
}
