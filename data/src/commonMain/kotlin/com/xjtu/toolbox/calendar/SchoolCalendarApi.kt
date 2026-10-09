package com.xjtu.toolbox.calendar

import com.xjtu.toolbox.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val CALENDAR_URL = "https://workflow.xjtu.edu.cn/selectpage/site/calendar/getData"

/**
 * 校历取数：直连 `workflow.xjtu.edu.cn` 的工作流门户首页小组件接口。
 *
 * ## 为什么它在 `:data`（Stage A 第二步之后）
 *
 * 它原来在 `:app`，但它**一行 Android 都没碰**——`HttpClients.base` 本来就在 `:data`，
 * 解析（[parseUpstreamSchoolCalendar]）与模型早就搬进了 `:core` ⇒ 搬迁 = 换个目录，类名与包名不变，
 * `:app` 侧三处调用点（`AppNavHost` / `AgentTool` / `SchoolCalendarImageApi`）一个字都不用改。
 *
 * 搬到 `:data` 的意义：**桌面端因此白拿一屏**。这个接口**免登录**，所以桌面不需要任何站点会话
 * 适配器，直接 `SchoolCalendarScreen(source = SchoolCalendarApi())` 就有真数据 —— 这就是「屏在
 * `:core` + 取数在 `:data`」的直接报偿（继图书馆之后第二条真能用的桌面路由）。
 *
 * 分工（搬迁前就是这个形状）—— 这个文件**只剩 IO**：
 *  - 模型（[SchoolTerm] / [CalendarEvent]）、解析（[parseUpstreamSchoolCalendar]）、
 *    屏幕（[SchoolCalendarScreen]）全在 `:core`；
 *  - 这里只负责「用 okhttp 客户端把响应体拿回来」，以及把阻塞调用放进
 *    [Dispatchers.IO]（客户端是 okhttp，`HttpClients.base` 与搬迁前是同一个实例）。
 *
 * ⚠️ 接口免登录，所以这里**不带任何凭据**：原来那套走 EIP 门户（`one2020.xjtu.edu.cn`）的方案，
 * 实测证明登录态没法 SSO 过去，逼用户重登完全没用（详见这个文件的历史）。
 */
class SchoolCalendarApi(
    /**
     * 上游地址。默认就是学校那条（行为与搬迁前逐字一致）。
     *
     * 给一个参数是 **`:core` 里那批 `Campus*Api(client, base)` 同一种形状**：基址是「本端取哪一路
     * 上游」的参数，不是行为开关。这里唯一的消费者是离屏证据与 `:desktop:test` ——
     * `workflow.xjtu.edu.cn` 是 **https**，而本地假上游是一个纯 HTTP 代理，给不了 CONNECT+TLS 隧道
     *（图书馆那条能跑通是因为它从 http 的 `/seat/` 开始、一路 302 都在 http 上）。
     *
     * 与图书馆那条的区别：校历这里**没有按 host 判断的判据**（解析只看 JSON 形状），
     * 所以把基址指到假上游不影响「测的是不是真解析链路」；图书馆那条就必须走代理、不能改 URL。
     */
    private val url: String = CALENDAR_URL,
) : SchoolCalendarSource {

    /** 默认配置即可，直接用共享基础客户端，见 [HttpClients]。 */
    private val client: OkHttpClient = HttpClients.base

    override suspend fun terms(): List<SchoolTerm> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val body = client.newCall(request).execute().use { resp ->
            resp.body?.string() ?: throw RuntimeException("校历接口无响应")
        }
        parseUpstreamSchoolCalendar(body)
    }
}
