package com.xjtu.toolbox.dormpower

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.SsnLogin
import com.xjtu.toolbox.auth.SsnSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * 宿舍电费（`ssn.xjtu.edu.cn/cems`，公寓用电管理系统的手机页）取数。
 *
 * 从 `:app` 的 `dormpower/DormPowerApi.kt` 整份搬进 `:data`（同包、同类名，桌面端第 17 条真数据
 * 路由要用同一个上游）：`rooms()` / `kwh()` 两枪与搬迁前逐字相同，只是按 `:core` 的
 * [DormPowerSource] 端口补上 `override`。
 *
 * 新添的第三个方法是 [freshPayUrl]：它原来写在屏上（`DormPowerScreen` 里那句
 * `(site as SsnSession).freshPayUrl()` 与紧随其后的 `DormPowerStore.markUsed(context)`），
 * 屏搬进 `:core` 之后 `:core` 不认识 `:data` 的 `SsnSession` ⇒ 收进本实现，**时机与顺序未改**
 *（拿到地址之后才记「进过这一页」，失败不记）。
 *
 * 站点会话仍是 `:data` 的 [SsnSession]（`ssn`）：`cid` 由它的 `onLoginSuccess` 从缴费页 HTML 里
 * 解析后写进 `localToken`，每个业务请求都要带；`401001` 由会话层的
 * `isAuthFailureResponse` 与 [DormPowerParsers.data] 两处认领为「会话失效」。
 */
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

    /**
     * 缴费页入口地址（给浏览器用，见 [DormPowerSource.freshPayUrl]）。
     *
     * `ssn` 站没有可搬运的会话 cookie：每次进页面都要凭 OAuth 回调里一次性的 code 换会话，
     * [SsnSession.freshPayUrl] 因此每次重走一遍 OAuth、拦下带 code 的那一跳把地址交出来。
     * 拿到地址之后记下「用户进过这一页」—— 与搬迁前屏上那两行的先后顺序逐字一致。
     */
    override suspend fun freshPayUrl(): String {
        val url = (site as SsnSession).freshPayUrl()
        DormPowerStore.markUsed()
        return url
    }
}
