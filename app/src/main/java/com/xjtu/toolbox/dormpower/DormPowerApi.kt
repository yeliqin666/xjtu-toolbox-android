package com.xjtu.toolbox.dormpower

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.SsnLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class DormPowerApi(private val site: SiteSession) {

    private fun cid(): String = site.localToken["cid"] ?: throw AuthExpiredException("宿舍电费")

    private fun builder(url: String) = Request.Builder().url(url)
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .header("User-Agent", SsnLogin.BROWSER_UA)
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

    suspend fun rooms(): List<DormRoom> = DormPowerParsers.rooms(get("/mobile/addr/list", "cid" to cid()))

    /** 房间剩余电量（度）；查不到返回 null。 */
    suspend fun kwh(roomId: String): Double? = DormPowerParsers.kwh(get("/mobile/meterAccount/electricity", "roomId" to roomId))
}
