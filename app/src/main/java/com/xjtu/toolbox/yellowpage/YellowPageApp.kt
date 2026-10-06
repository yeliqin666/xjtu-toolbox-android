package com.xjtu.toolbox.yellowpage

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.data.DataCache
import io.ktor.client.HttpClient

/**
 * :app 侧的黄页装配：把 :core 里那两个 Android 专属依赖（传输层与按账号隔离的缓存）
 * 注入到 [YellowPageApi]。
 *
 * 黄页接口是**匿名可读**的，不带任何凭据，所以用一条不带 CookieJar 的 Ktor 客户端即可；
 * 缓存仍走 :app 的 [DataCache]，key 与 TTL 取 [YellowPageApi] 里的常量，行为与搬迁前一致。
 */

/** 进程级一份：与 :app 里 `HttpClients.base` 的用法对齐，别每次新建连接池。 */
private val yellowPageClient: HttpClient by lazy { createToolboxClient() }

/**
 * @param accountId 传 null 时取 [AccountContext.activeAccountId]（与搬迁前 `DataCache` 的默认一致）。
 */
fun appYellowPageApi(context: Context, accountId: String? = AccountContext.activeAccountId): YellowPageApi =
    YellowPageApi(
        client = yellowPageClient,
        cache = DataCacheYellowPageCache(context.applicationContext, accountId),
    )

/** 把 :app 的 `DataCache`（按账号隔离的文件缓存）适配成 :core 的 [YellowPageCache]。 */
private class DataCacheYellowPageCache(context: Context, accountId: String?) : YellowPageCache {
    private val cache = DataCache(context, accountId)

    override fun read(): YellowPageData? =
        cache.read<YellowPageData>(YellowPageApi.CACHE_KEY, YellowPageApi.CACHE_TTL_MS)

    override fun readStale(): YellowPageData? =
        cache.readStale<YellowPageData>(YellowPageApi.CACHE_KEY)

    override fun write(data: YellowPageData) =
        cache.write(YellowPageApi.CACHE_KEY, data)
}
