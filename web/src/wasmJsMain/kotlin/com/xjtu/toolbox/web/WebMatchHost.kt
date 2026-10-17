package com.xjtu.toolbox.web

import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.social.MatchHost
import com.xjtu.toolbox.social.MatchIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 浏览器端的匹配交友宿主面（见 [MatchHost]）。 */
internal class WebMatchHost : MatchHost {

    /** 与 [WebScheduleHost.cache] **同一个实现**（同一份 `localStorage`、同一个键前缀）。 */
    override fun cache(accountId: String?): ScheduleCacheStore =
        KeyValueScheduleCacheStore(keyValueStore(WebScheduleHost.SCHEDULE_CACHE_STORE))

    override fun identity(): MatchIdentity? = null

    override fun transactions(): List<Transaction> = emptyList()

    /** `:core` 的 commonMain 里没有 `Dispatchers.IO`（wasm 上更没有）⇒ 这一端就落在计算池上。 */
    override val io: CoroutineDispatcher = Dispatchers.Default
}
