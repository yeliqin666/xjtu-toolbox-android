package com.xjtu.toolbox.dormpower

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.SsnLogin
import com.xjtu.toolbox.auth.SsnSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** 宿舍电费（`ssn.xjtu.edu.cn/cems`，公寓用电管理系统的手机页）取数。 */
class DormPowerApi(private val site: SiteSession) : DormPowerSource {

    private fun cid(): String = site.localToken["cid"] ?: throw AuthExpiredException("宿舍电费")

    private fun builder(url: String) = Request.Builder().url(url)
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .header("Referer", SsnLogin.PAY_PAGE_URL)
        .header("X-Requested-With", "XMLHttpRequest")

    private suspend fun send(request: Request): String {
        val response = site.executeWithReAuth(request)
        return withContext(Dispatchers.IO) {
            response.use {
                val bytes = it.body.bytes()
                if (!it.isSuccessful) throw RuntimeException("宿舍电费请求失败（HTTP ${it.code}）")
                DormPowerParsers.decode(bytes)
            }
        }
    }

    private suspend fun get(path: String, vararg query: Pair<String, String>) = DormPowerParsers.data(
        send(
            builder(
                "${SsnLogin.BASE_URL}$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString(),
            ).get().build(),
        ),
    )

    override suspend fun rooms(): List<DormRoom> = DormPowerParsers.rooms(get("/mobile/addr/list", "cid" to cid()))

    /** 房间剩余电量（度）；查不到返回 null。 */
    override suspend fun kwh(roomId: String): Double? = DormPowerParsers.kwh(get("/mobile/meterAccount/electricity", "roomId" to roomId))

    /** 缴费页入口地址（给浏览器用，见 [DormPowerSource.freshPayUrl]）。 */
    override suspend fun freshPayUrl(): String {
        val url = (site as SsnSession).freshPayUrl()
        DormPowerStore.markUsed()
        return url
    }
}
