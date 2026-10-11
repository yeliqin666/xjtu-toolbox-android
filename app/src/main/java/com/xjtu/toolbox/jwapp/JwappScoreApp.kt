package com.xjtu.toolbox.jwapp

import android.content.Context
import com.xjtu.toolbox.data.DataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:app` 侧的成绩屏**缓存**装配 —— `:core` 的 [JwappScoreCache] 在 Android 上的实现。
 *
 * 取数那一半（`AppJwappScoreSource`）已经搬进 `:data`（与 `JwappApi` / `CjcxApi` 同一个包，
 * 桌面端也用同一份），这里只剩缓存：它是唯一依赖 `Context` + `DataCache` 的一档 —— 与
 * `score/ScoreReportApp.kt` 里那个 `appScoreReportCache` 同一条前例。
 *
 * key 与 TTL 都保持迁移前的样子：`score_all_terms` + `DataCache.DEFAULT_TTL_MS`
 * （`JwappScoreCache.DEFAULT_TTL_MS` 就是同一个值），所以老缓存无缝沿用、换账号不串号
 * （`DataCache` 自己按账号分目录）。
 *
 * ⚠️ 两个方法自己包 `Dispatchers.IO`：端口在 `:core`（有 wasm 目标），那边不能出现
 * `Dispatchers.IO`（实测 `:core:compileKotlinWasmJs` 报 `Unresolved reference 'IO'`），
 * 所以搬屏时这个挑调度器的活从 VM 移到了实现方 —— `DataCache.read/write` 是**阻塞式**文件读写，
 * 搬迁前它们就在 `withContext(Dispatchers.IO)` 里（与 `score/ScoreReportApp.kt` 那条前例同型）。
 */
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
        /** 迁移前 `JwappScoreViewModel.CACHE_KEY`（同一个文件名，老缓存直接命中）。 */
        const val CACHE_KEY = "score_all_terms"
    }
}
